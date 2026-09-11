package ir.meelano.vpn.net

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The network tools the anti-blocking logic needs: DoH that cannot itself be poisoned, transport
 * probes, and an HTTP client that can be pinned to an address we chose.
 *
 * Why this file exists at all: the feed lives on one shared host, and a shared host is the single
 * point of failure a censor loves. DNS poisoning is how that host dies first (cheap for them,
 * invisible to the app if you only look at sockets). So the client asks a *foreign resolver over
 * encrypted DNS, by IP* - no local DNS in the loop - and if the normal path failed while the pinned
 * path worked, we know with certainty that the resolver lied. That fact is worth more than any
 * number in the feed, because it changes what the app does, not just what it shows.
 */
object NetGuard {

    private const val TAG = "NetGuard"

    /** Resolvers reachable from inside Iran without a VPN, with the address we dial them by. */
    private val DOH = listOf(
        Tria("dns.google", "8.8.8.8", "/resolve?name=%s&type=A&sd=1"),
        Tria("cloudflare-dns.com", "1.1.1.1", "/dns-query?name=%s&type=A"),
        // AliDNS: Chinese, aggressively cached, and historically alive when Google/Cloudflare are throttled.
        Tria("dns.alidns.com", "223.5.5.5", "/resolve?name=%s&type=A"),
    )

    private data class Tria(val host: String, val ip: String, val path: String)

    /** Cache of DoH answers; 4 minutes, because a poisoned record is also a moving target. */
    private val dnsCache = Collections.synchronizedMap(HashMap<String, Pair<Long, List<String>>>())
    private const val DNS_TTL_MS = 4L * 60_000L

    private val plain by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)          // we do our own failover; don't double the wait
            .build()
    }

    /** An OkHttp client whose resolver is a fixed list we chose - SNI, Host and verification all stay correct. */
    fun pinnedClient(ips: List<String>): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val list = ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                return list.ifEmpty { Dns.SYSTEM.lookup(hostname) }
            }
        })
        .build()

    /**
     * A-records for [host], via every DoH endpoint we know, in parallel, each dialed by IP literal.
     * Returns empty when all three fail - in which case the caller must not conclude "the host is
     * dead", only "we cannot lie to ourselves right now".
     *
     * `sd=1`/no-Cookie/short timeout: this runs on the app's hot path (feed refresh) and must never
     * be the reason a connect feels slow.
     */
    suspend fun doh(host: String): List<String> {
        dnsCache[host]?.let { (at, ips) ->
            if (System.currentTimeMillis() - at < DNS_TTL_MS) return ips
        }
        val answers: List<List<String>> = coroutineScope {
            DOH.map { t ->
                async {
                    runCatching {
                        val url = "https://" + t.host + t.path.format(host)
                        // pin the *resolver* by IP: the whole point is to bypass the local resolver
                        val client = plain.newBuilder().dns(singletonDns(t.ip)).build()
                        val body = client.newCall(
                            Request.Builder().url(url)
                                .header("Accept", "application/dns-json")
                                .build()
                        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                        parseAnswers(body)
                    }.getOrDefault(emptyList())
                }
            }.map { it.await() }
        }
        // union, but a resolver that returns nothing contributes nothing: we want *candidates*,
        // ranked by how many resolvers agree (a sinkhole answer usually appears in exactly one).
        val ranked = answers.flatten().groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        if (ranked.isNotEmpty()) dnsCache[host] = System.currentTimeMillis() to ranked
        return ranked
    }

    /** OkHttp's Dns is a Kotlin fun interface in 4.x and a Java interface in 3.x: write it the long way. */
    private fun singletonDns(ip: String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName(ip))
    }

    private fun parseAnswers(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("data")?.takeIf { it.isNotBlank() && IP.matches(it) }
            }
        }.getOrDefault(emptyList())
    }

    private val IP = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * Which of these addresses is actually answering on this port, right now, from here.
     * -1 = unreachable; otherwise the TCP RTT in ms. [limit] keeps a refresh from turning into a
     * scan: 12 targets is already more than the number of nodes a phone can usefully dial at once.
     */
    suspend fun tcpProbe(targets: List<Pair<String, Int>>, timeoutMs: Int = 1200, limit: Int = 12): List<Long> =
        coroutineScope {
            targets.take(limit).map { (h, p) ->
                async { probeOne(h, p, timeoutMs) }
            }.awaitAllSafe()
        }

    private suspend fun List<kotlinx.coroutines.Deferred<Long>>.awaitAllSafe(): List<Long> =
        map { runCatching { withTimeoutOrNull(3000) { it.await() } ?: -1L }.getOrDefault(-1L) }

    private fun probeOne(host: String, port: Int, timeoutMs: Int): Long {
        if (host.isBlank() || port !in 1..65535) return -1L
        val t0 = System.nanoTime()
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                // RTT of a completed handshake is the honest floor for "this node is near you"
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }
        }.getOrDefault(-1L)
    }

    /**
     * TLS reachability: TCP opening and TLS dying is the signature of the last several years of
     * filtering here, and it is invisible to any latency test that stops at connect().
     *
     * ALPN is set on API 29+ only (SSLParameters.setApplicationProtocols); on older devices we still
     * get the pass/fail, which is the part we act on.
     */
    suspend fun tlsProbe(host: String, port: Int, sni: String, alpn: String?, timeoutMs: Int = 2000): Long {
        if (host.isBlank() || port !in 1..65535) return -1L
        val t0 = System.nanoTime()
        return runCatching {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
                    raw, sni.ifBlank { host }, port, true
                )
                ssl.use { s ->
                    s.soTimeout = timeoutMs
                    val p: SSLParameters = s.sslParameters
                    p.serverNames = mutableListOf(
                        javax.net.ssl.SNIHostName(sni.ifBlank { host })
                    )
                    if (alpn != null && android.os.Build.VERSION.SDK_INT >= 29) {
                        p.applicationProtocols = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
                    }
                    s.sslParameters = p
                    s.startHandshake()
                    ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                }
            }
        }.getOrDefault(-1L)
    }

    /**
     * The one call site the rest of the app uses: probe a batch of nodes and turn the results into a
     * [BlockReport]. Failures are counted, never thrown - a probe that cannot run is not evidence
     * that the network is down.
     */
    suspend fun measure(nodes: List<ProbeTarget>): BlockReport {
        if (nodes.isEmpty()) return BlockReport()
        val rtts = Collections.synchronizedList(ArrayList<Long>())
        val tls = Collections.synchronizedList(ArrayList<Long>())
        val out = coroutineScope {
            val jobs = nodes.take(12).map { n ->
                async {
                    val r = probeOne(n.host, n.port, 1200)
                    rtts += r
                    if (r > 0 && n.tls) tls += tlsProbe(n.host, n.port, n.sni, n.alpn)
                    r
                }
            }
            jobs.map { runCatching { withTimeoutOrNull(3500) { it.await() } }.getOrNull() }
        }
        if (out.isEmpty()) return BlockReport()
        val ok = rtts.count { it > 0 }
        val tlsOk = tls.count { it > 0 }
        val sorted = rtts.filter { it > 0 }.sorted()
        val median = if (sorted.isEmpty()) -1L else sorted[sorted.size / 2]
        val tcpFail = 1f - ok.toFloat() / rtts.size
        val tlsFail = if (tls.isEmpty()) 0f else 1f - tlsOk.toFloat() / tls.size
        Log.d(TAG, "probes=${rtts.size} tcpFail=$tcpFail tlsFail=$tlsFail rtt=$median")
        return BlockReport(
            dnsPoisoned = false,                 // set by the caller, which knows how the feed fetch went
            tcpFailRatio = tcpFail,
            tlsFailRatio = tlsFail,
            medianRttMs = median,
            probes = rtts.size,
            at = System.currentTimeMillis(),
        )
    }

    /** Minimal view of a node for probing: no import of the data layer, so this file stays testable. */
    data class ProbeTarget(val id: String, val host: String, val port: Int, val tls: Boolean, val sni: String, val alpn: String?)

    /** IPv4 only for now: a v6-only ISP with a v4-shaped feed is a real case, and it degrades to "no answer". */
    fun isV4(s: String): Boolean = runCatching { InetAddress.getByName(s) is Inet4Address }.getOrDefault(false)

    /**
     * Wait for a network to settle after a switch (wifi -> cellular rebinds the protect()ed sockets).
     * Used by the reconnect path; a probe fired 200 ms after a radio change is a false "blocked".
     */
    suspend fun settle(graceMs: Long = 800) = delay(graceMs)
}
