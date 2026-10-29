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

    /**
     * Resolvers reachable from inside Iran without a VPN, with the address we dial them by.
     *
     * 2.8.1: the set grew from three to five and the *order* stopped mattering (every provider is
     * still asked in parallel). On MCI and some Irancell regions the two Western classics
     * (8.8.8.8, 1.1.1.1) are throttled or blackholed at the IP level — a resolver that answers
     * from Shanghai (AliDNS) or from an ad-blocker anycast (AdGuard) or from Quad9 is the
     * difference between "DoH works here" and "every lookup silently falls back to the poisoned
     * system resolver", which is exactly the bug this file exists to remove. A provider whose JSON
     * flavour we mis-parse simply contributes nothing (parseAnswers is total); it can never hurt.
     */
    private val DOH = listOf(
        Tria("dns.google", "8.8.8.8", "/resolve?name=%s&type=A&sd=1"),
        Tria("cloudflare-dns.com", "1.1.1.1", "/dns-query?name=%s&type=A"),
        // AliDNS: Chinese, aggressively cached, and historically alive when Google/Cloudflare are throttled.
        Tria("dns.alidns.com", "223.5.5.5", "/resolve?name=%s&type=A"),
        // AdGuard: anycast incl. Middle-East PoPs; Google-style JSON API; alive on most Iranian carriers.
        Tria("dns.adguard-dns.com", "94.140.14.14", "/resolve?name=%s&type=A"),
        // Quad9: security-focused anycast, RFC-8484 JSON mapping on the same path shape as Cloudflare.
        Tria("dns.quad9.net", "9.9.9.9", "/dns-query?name=%s&type=A"),
    )

    private data class Tria(val host: String, val ip: String, val path: String)

    /**
     * The resolver that answered fastest on the most recent full lookup. Purely a tie-breaker for
     * list order next time — every call still asks everyone (agreement is what ranks answers) —
     * but it keeps the "who is alive from here" learning instead of throwing it away.
     */
    @Volatile
    private var preferred: Tria? = null

    /**
     * Socket guard for every resolver/client this file builds. Default is a no-op for tests; the
     * app installs `MeelanoVpnService.protectFeedSocket` so a lookup never enters a live (or worse,
     * kill-switched) TUN — the VpnOrchestrator trap: during failover the old TUN is intentionally
     * dead, and an unprotected socket would vanish into it and "prove" the network is down.
     */
    @Volatile
    var socketProtect: (java.net.Socket) -> Boolean = { true }

    private fun protectedSocketFactory(): javax.net.SocketFactory = object : javax.net.SocketFactory() {
        override fun createSocket(): java.net.Socket = java.net.Socket().also { socketProtect(it) }
        override fun createSocket(host: String, port: Int): java.net.Socket =
            java.net.Socket().also { socketProtect(it) }.also { it.connect(java.net.InetSocketAddress(host, port), 5_000) }
        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket =
            createSocket(host, port)
        override fun createSocket(address: java.net.InetAddress, port: Int): java.net.Socket =
            createSocket(address.hostAddress ?: "", port)
        override fun createSocket(
            address: java.net.InetAddress, port: Int,
            localAddress: java.net.InetAddress, localPort: Int,
        ): java.net.Socket = createSocket(address, port)
    }

    /**
     * Cache of DoH answers with per-entry expiry: 4 minutes for an answer (a poisoned record is a
     * moving target, and so are CDN ones), 30 seconds for *silence*. Negative caching is the
     * connect-speed side of the same coin: without it, one dead hostname re-pays the full DoH
     * budget on every candidate attempt of every reconnect (orderCandidates used to serialise
     * exactly that).
     */
    private class Cached(val expiresAt: Long, val ips: List<String>)
    private val dnsCache = Collections.synchronizedMap(HashMap<String, Cached>())
    private const val DNS_TTL_MS = 4L * 60_000L
    private const val DNS_NEG_TTL_MS = 30_000L

    /**
     * Every DoH request is bounded TWICE: [DOH_CALL_MS] per HTTP call (OkHttp callTimeout — covers
     * connect+read+redirect no matter what the network does) and [DOH_BUDGET_MS] for the whole
     * parallel batch (whoever answered by then counts; the laggards are dropped). The old client
     * (4 s connect + 7 s read, wait-for-all-three) could park a single resolve at 11 s — and
     * orderCandidates resolved nodes one after another, so the pre-connect tax reached a minute.
     * The DoH payload is a few hundred bytes; a resolver that needs more than these budgets is,
     * from here, indistinguishable from a blocked one.
     */
    private const val DOH_CALL_MS = 1_600L
    private const val DOH_BUDGET_MS = 2_000L

    private val dohClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(DOH_CALL_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .socketFactory(protectedSocketFactory())
            .build()
    }

    /** An OkHttp client whose resolver is a fixed list we chose - SNI, Host and verification all stay correct. */
    fun pinnedClient(ips: List<String>): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .socketFactory(protectedSocketFactory())
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val list = ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                return list.ifEmpty { Dns.SYSTEM.lookup(hostname) }
            }
        })
        .build()

    /**
     * A-records for [host], via every DoH endpoint we know, in parallel, each dialed by IP literal.
     * Returns empty when all fail - in which case the caller must not conclude "the host is
     * dead", only "we cannot lie to ourselves right now".
     *
     * Latency contract (2.8.1): the batch as a whole never outlives [DOH_BUDGET_MS], because this
     * sits on the *connect* hot path (SmartResolver asks it before every dial). Answers are ranked
     * by how many resolvers agree (a sinkhole answer usually appears in exactly one); the fastest
     * non-empty responder is remembered and asked first next time.
     */
    suspend fun doh(host: String): List<String> {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { c ->
            if (now < c.expiresAt) return c.ips
        }
        // Preferred resolver first, rest after — a pure ordering hint for the deadline loop below.
        val order = listOfNotNull(preferred) + DOH.filterNot { it == preferred }
        val started = System.currentTimeMillis()
        val answers: List<Pair<Tria, List<String>>> = coroutineScope {
            val jobs = order.map { t ->
                async {
                    t to runCatching {
                        val url = "https://" + t.host + t.path.format(host)
                        // pin the *resolver* by IP: the whole point is to bypass the local resolver
                        val client = dohClient.newBuilder().dns(singletonDns(t.ip)).build()
                        val body = client.newCall(
                            Request.Builder().url(url)
                                .header("Accept", "application/dns-json")
                                .build()
                        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                        parseAnswers(body)
                    }.getOrDefault(emptyList())
                }
            }
            // Await in list order but against ONE shared deadline: a straggler beyond the budget is
            // left to its OkHttp callTimeout and simply not counted. An already-finished job costs
            // zero, so ordering cannot slow the fast path down. Cancellation is rethrown — a
            // cancelled doh() must never "return empty" and fake a resolution miss.
            jobs.mapNotNull { job ->
                val left = DOH_BUDGET_MS - (System.currentTimeMillis() - started)
                if (left <= 0) return@mapNotNull null
                try {
                    withTimeoutOrNull(left) { job.await() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            }
        }
        // union, but a resolver that returns nothing contributes nothing: we want *candidates*,
        // ranked by how many resolvers agree (a sinkhole answer usually appears in exactly one).
        val ranked = answers.flatMap { it.second }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        answers.firstOrNull { it.second.isNotEmpty() }?.let { preferred = it.first }
        // Positive entries live DNS_TTL_MS; a lookup that found nothing is remembered only briefly
        // (DNS_NEG_TTL_MS) so a resolver outage cannot pin a good hostname down.
        val ttl = if (ranked.isEmpty()) DNS_NEG_TTL_MS else DNS_TTL_MS
        dnsCache[host] = Cached(System.currentTimeMillis() + ttl, ranked)
        return ranked
    }

    /**
     * Drop every cached DoH answer — called on network change together with SmartResolver's
     * clearCache(); the two caches feed each other and both must forget the old network.
     */
    fun clearDnsCache() {
        dnsCache.clear()
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
     *
     * One retry per target: on a lossy mobile link a single dropped SYN is common and says
     * nothing about the node; two consecutive failures inside [ProbePolicy] budgets is a signal.
     * (The timeout defaults come from ProbePolicy - the old fixed 1200 ms mislabelled healthy
     * Iranian-carrier paths as dead, which is the "connects only sometimes" bug in probe form.)
     */
    suspend fun tcpProbe(
        targets: List<Pair<String, Int>>,
        timeoutMs: Int = ProbePolicy.tcpTimeoutMs(),
        limit: Int = 12,
    ): List<Long> =
        coroutineScope {
            targets.take(limit).map { (h, p) ->
                async { probeOneWithRetry(h, p, timeoutMs) }
            }.awaitAllSafe(ProbePolicy.awaitCapMs(timeoutMs) * 2)
        }

    private suspend fun List<kotlinx.coroutines.Deferred<Long>>.awaitAllSafe(capMs: Long): List<Long> =
        map { runCatching { withTimeoutOrNull(capMs) { it.await() } ?: -1L }.getOrDefault(-1L) }

    private fun probeOneWithRetry(host: String, port: Int, timeoutMs: Int): Long {
        val first = probeOne(host, port, timeoutMs)
        return if (first > 0L) first else probeOne(host, port, timeoutMs)
    }

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
    suspend fun tlsProbe(
        host: String, port: Int, sni: String, alpn: String?,
        timeoutMs: Int = ProbePolicy.tlsTimeoutMs(),
    ): Long {
        if (host.isBlank() || port !in 1..65535) return -1L
        val t0 = System.nanoTime()
        return runCatching {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                // SSLSocketFactory.createSocket is declared to return Socket (inherited from
                // SocketFactory); the TLS knobs we need live on SSLSocket, so the cast is the point.
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
                    raw, sni.ifBlank { host }, port, true
                ) as javax.net.ssl.SSLSocket
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
    suspend fun measure(nodes: List<ProbeTarget>, previousMedianRttMs: Long = -1L): BlockReport {
        if (nodes.isEmpty()) return BlockReport()
        val tcpTimeout = ProbePolicy.tcpTimeoutMs(previousMedianRttMs)
        val tlsTimeout = ProbePolicy.tlsTimeoutMs(previousMedianRttMs)
        val rtts = Collections.synchronizedList(ArrayList<Long>())
        val tls = Collections.synchronizedList(ArrayList<Long>())
        val out = coroutineScope {
            val jobs = nodes.take(12).map { n ->
                async {
                    val r = probeOneWithRetry(n.host, n.port, tcpTimeout)
                    rtts += r
                    if (r > 0 && n.tls) tls += tlsProbe(n.host, n.port, n.sni, n.alpn, tlsTimeout)
                    r
                }
            }
            val cap = ProbePolicy.awaitCapMs(tcpTimeout) * 2 + tlsTimeout
            jobs.map { runCatching { withTimeoutOrNull(cap) { it.await() } }.getOrNull() }
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
