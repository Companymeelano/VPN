package ir.meelano.vpn.data

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Deep, on-device verification for raw HTTP/SOCKS5 proxies.
 *
 * A TCP connect to the proxy port proves almost nothing: the proxy may reject CONNECT, require
 * credentials, inject a captive page, be blocked by Google, or return a robot challenge. Every
 * candidate therefore has to carry three independent HTTPS requests *through itself*. TLS hostname
 * verification remains enabled, and only exact 204 responses are considered healthy.
 *
 * This class deliberately uses a small manual CONNECT/SOCKS5 implementation instead of the platform
 * HTTP stack. It lets the VPN service protect the raw socket before connect (so a rescan while already
 * connected measures the phone's direct network, not the old tunnel), supports authenticated SOCKS5,
 * and makes the status classifier fully testable with fake endpoint results.
 */
object ProxyScanner {

    const val CLEAN = "clean"
    const val ROBOT_BLOCKED = "robot_blocked"
    const val PLATFORM_BLOCKED = "platform_blocked"
    const val DEAD = "dead"

    data class Endpoint(
        val id: String,
        val host: String,
        val path: String = "/generate_204",
        val expectedCode: Int = 204,
    )

    data class Check(
        val endpoint: String,
        val code: Int? = null,
        val latencyMs: Long? = null,
        val error: String? = null,
    ) {
        fun exact(endpointSpec: Endpoint): Boolean = code == endpointSpec.expectedCode
    }

    data class Verdict(
        val status: String,
        val latencyMs: Long?,
        val reliability: Float,
        val reason: String,
        val checks: List<Check>,
    ) {
        val usable: Boolean get() = status == CLEAN
    }

    /** HTTPS destinations owned by two independent networks; all three must return exact 204. */
    val ENDPOINTS: List<Endpoint> = listOf(
        Endpoint("cloudflare", "cp.cloudflare.com"),
        Endpoint("google", "www.google.com"),
        Endpoint("gstatic", "www.gstatic.com"),
    )

    /**
     * Scan a bounded set with bounded fan-out. [request] is injectable so CI can reproduce blocked,
     * challenged and dead paths without touching the Internet.
     */
    suspend fun scan(
        nodes: List<FeedNode>,
        limit: Int = 48,
        concurrency: Int = 8,
        timeoutMs: Int = 4_500,
        protect: (Socket) -> Boolean = { true },
        request: suspend (FeedNode, Endpoint, Int, (Socket) -> Boolean) -> Check = ::requestThrough,
    ): Map<String, Verdict> = coroutineScope {
        val candidates = nodes
            .filter { it.isRawProxy && !it.insecure }
            .distinctBy { it.id }
            .take(limit.coerceAtLeast(0))
        if (candidates.isEmpty()) return@coroutineScope emptyMap()
        val gate = Semaphore(concurrency.coerceIn(1, 12))
        candidates.map { node ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val checks = ENDPOINTS.map { endpoint ->
                        async(Dispatchers.IO) {
                            runCatching { request(node, endpoint, timeoutMs, protect) }
                                .getOrElse { Check(endpoint.id, error = errorKey(it)) }
                        }
                    }.awaitAll()
                    node.id to classify(checks)
                }
            }
        }.awaitAll().toMap()
    }

    /** Pure decision table: exact success, robot response, platform block, or unusable/dead. */
    fun classify(checks: List<Check>): Verdict {
        val byId = checks.associateBy { it.endpoint }
        val exact = ENDPOINTS.count { endpoint -> byId[endpoint.id]?.exact(endpoint) == true }
        val successfulLatencies = checks.mapNotNull { c -> c.latencyMs?.takeIf { it >= 0 } }
        val latency = successfulLatencies.maxOrNull() // all destinations matter; do not advertise the fastest one
        val reliability = (exact.toFloat() / ENDPOINTS.size.toFloat()).coerceIn(0f, 1f)

        if (exact == ENDPOINTS.size) {
            return Verdict(CLEAN, latency, 1f, "exact_204_all", checks)
        }

        val codes = checks.mapNotNull { it.code }
        if (codes.any { it == 407 } || checks.any { it.error == "proxy_auth_failed" }) {
            return Verdict(DEAD, latency, reliability, "proxy_auth_failed", checks)
        }

        val googleChecks = listOfNotNull(byId["google"], byId["gstatic"])
        val robot = googleChecks.firstOrNull { it.code == 403 || it.code == 429 }
        if (robot != null) {
            return Verdict(ROBOT_BLOCKED, latency, reliability, "google_robot_${robot.code}", checks)
        }

        val cloudflareOk = byId["cloudflare"]?.exact(ENDPOINTS.first()) == true
        val googleExact = googleChecks.any { c ->
            val spec = ENDPOINTS.firstOrNull { it.id == c.endpoint }
            spec != null && c.exact(spec)
        }
        if (cloudflareOk && !googleExact) {
            return Verdict(PLATFORM_BLOCKED, latency, reliability, "google_unreachable_after_cloudflare", checks)
        }

        val reason = when {
            checks.all { it.code == null } -> "proxy_unreachable"
            codes.any { it == 451 } -> "platform_http_451"
            else -> "no_exact_204"
        }
        return Verdict(DEAD, latency, reliability, reason, checks)
    }

    /** One exact HTTPS request through one raw proxy. */
    private suspend fun requestThrough(
        node: FeedNode,
        endpoint: Endpoint,
        timeoutMs: Int,
        protect: (Socket) -> Boolean,
    ): Check = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var socket: Socket? = null
        try {
            socket = openRaw(node, timeoutMs, protect)
            when (node.proto.lowercase()) {
                "http" -> httpConnect(socket, node, endpoint.host, timeoutMs)
                "socks", "socks5" -> socksConnect(socket, node, endpoint.host, timeoutMs)
                else -> throw IllegalArgumentException("unsupported_proxy_protocol")
            }

            val tls = tls(socket, endpoint.host, 443, timeoutMs)
            socket = tls
            val request = buildString {
                append("GET ").append(endpoint.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(endpoint.host).append("\r\n")
                append("User-Agent: M-A-Proxy-Scanner/1\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            tls.outputStream.write(request)
            tls.outputStream.flush()
            val header = readHeader(tls.inputStream)
            val code = statusCode(header)
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            Check(endpoint.id, code = code, latencyMs = elapsed, error = if (code == null) "bad_http_status" else null)
        } catch (t: Throwable) {
            Check(endpoint.id, latencyMs = null, error = errorKey(t))
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun openRaw(node: FeedNode, timeoutMs: Int, protect: (Socket) -> Boolean): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.soTimeout = timeoutMs
        if (!protect(raw)) {
            raw.close()
            throw IllegalStateException("socket_protect_failed")
        }
        raw.connect(InetSocketAddress(node.host, node.port), timeoutMs)
        raw.soTimeout = timeoutMs
        return if (node.proto == "http" && node.tls.equals("tls", ignoreCase = true)) {
            tls(raw, node.host, node.port, timeoutMs)
        } else raw
    }

    /** RFC 7231 CONNECT; credentials are sent only to the proxy, before end-to-end TLS starts. */
    private fun httpConnect(socket: Socket, node: FeedNode, targetHost: String, timeoutMs: Int) {
        socket.soTimeout = timeoutMs
        val auth = if (!node.username.isNullOrEmpty()) {
            val plain = node.username + ":" + (node.password ?: "")
            "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(plain.toByteArray(StandardCharsets.UTF_8)) + "\r\n"
        } else ""
        val bytes = buildString {
            append("CONNECT ").append(targetHost).append(":443 HTTP/1.1\r\n")
            append("Host: ").append(targetHost).append(":443\r\n")
            append(auth)
            append("Proxy-Connection: keep-alive\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
        val code = statusCode(readHeader(socket.inputStream))
        if (code == 407) throw SecurityException("proxy_auth_failed")
        if (code == null || code !in 200..299) throw IllegalStateException("proxy_connect_http_${code ?: 0}")
    }

    /** SOCKS5 CONNECT with optional RFC 1929 username/password authentication and remote DNS. */
    private fun socksConnect(socket: Socket, node: FeedNode, targetHost: String, timeoutMs: Int) {
        socket.soTimeout = timeoutMs
        val input = socket.inputStream
        val output = socket.outputStream
        val hasAuth = !node.username.isNullOrEmpty()
        output.write(if (hasAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()
        val version = readByte(input)
        val method = readByte(input)
        if (version != 5 || method == 0xFF) throw SecurityException("proxy_auth_failed")
        if (method == 2) {
            val user = (node.username ?: "").toByteArray(StandardCharsets.UTF_8)
            val pass = (node.password ?: "").toByteArray(StandardCharsets.UTF_8)
            if (user.size !in 1..255 || pass.size > 255) throw SecurityException("proxy_auth_failed")
            output.write(byteArrayOf(1, user.size.toByte()))
            output.write(user)
            output.write(byteArrayOf(pass.size.toByte()))
            output.write(pass)
            output.flush()
            if (readByte(input) != 1 || readByte(input) != 0) throw SecurityException("proxy_auth_failed")
        } else if (method != 0) {
            throw SecurityException("proxy_auth_failed")
        }

        val host = targetHost.toByteArray(StandardCharsets.US_ASCII)
        require(host.size in 1..255) { "target_host_too_long" }
        output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
        output.write(host)
        output.write(byteArrayOf(0x01, 0xBB.toByte())) // 443, network byte order
        output.flush()

        if (readByte(input) != 5) throw IllegalStateException("bad_socks_version")
        val reply = readByte(input)
        readByte(input) // reserved
        val addressType = readByte(input)
        if (reply != 0) throw IllegalStateException("socks_reply_$reply")
        when (addressType) {
            1 -> readFully(input, 4)
            3 -> readFully(input, readByte(input))
            4 -> readFully(input, 16)
            else -> throw IllegalStateException("bad_socks_address")
        }
        readFully(input, 2) // bound port
    }

    /** Default trust store + HTTPS endpoint identification. A forged/captive certificate is rejected. */
    private fun tls(socket: Socket, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        ssl.startHandshake()
        return ssl
    }

    private fun readHeader(input: InputStream, maxBytes: Int = 16 * 1024): String {
        val out = ByteArrayOutputStream(512)
        var state = 0
        while (out.size() < maxBytes) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        if (out.size() == 0) throw EOFException("empty_http_response")
        if (state != 4 && out.size() >= maxBytes) throw IllegalStateException("http_header_too_large")
        return out.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun statusCode(header: String): Int? {
        val first = header.lineSequence().firstOrNull()?.trim().orEmpty()
        return Regex("""^HTTP/\d(?:\.\d)?\s+(\d{3})(?:\s|$)""", RegexOption.IGNORE_CASE)
            .find(first)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("unexpected_eof")
        return b
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(out, offset, count - offset)
            if (n < 0) throw EOFException("unexpected_eof")
            offset += n
        }
        return out
    }

    private fun errorKey(t: Throwable): String = when {
        t is SocketTimeoutException -> "timeout"
        t is SecurityException && t.message == "proxy_auth_failed" -> "proxy_auth_failed"
        t.message == "socket_protect_failed" -> "socket_protect_failed"
        t.message?.startsWith("proxy_connect_http_") == true -> t.message!!
        t.message?.startsWith("socks_reply_") == true -> t.message!!
        t is javax.net.ssl.SSLException -> "tls_failed"
        t is java.net.ConnectException -> "connect_failed"
        t is java.net.UnknownHostException -> "dns_failed"
        else -> "io_failed"
    }
}
