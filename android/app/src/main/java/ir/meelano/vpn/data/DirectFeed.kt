package ir.meelano.vpn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Builds the public feed entirely on the phone. Every built-in document is hosted by a public GitHub
 * repository and has independent GitHub API/raw/jsDelivr routes; this builder never needs the product's
 * private feed host.
 *
 * Tunnel configs and raw proxies are deliberately separate pools:
 *
 *  - vless/vmess/trojan/ss candidates receive a bounded, fair TCP *hint*. A failed bare TCP connect is
 *    retained as grade D because DNS filtering, an already-running VPN, Reality and transport details
 *    can all make that pre-flight test lie. The Xray full-transport exact-204 race is the final judge;
 *  - HTTP/SOCKS5 candidates never inherit a tunnel's grade. Every candidate in the proxy section is
 *    connected through directly by [ProxyScanner] and classified as clean, robot-blocked,
 *    platform-blocked, or dead. Only clean proxies can enter automatic selection.
 *
 * IO is injected so mirror behavior, source quotas and every rejection state run in JVM tests without
 * requiring GitHub or a particular national network to be reachable from CI.
 */
object DirectFeed {

    enum class Kind { CONFIG, PROXY, JSON }

    /** A stable, non-sensitive fetch result for diagnostics. [route] never contains a URL or hostname. */
    data class FetchOutcome(
        val sourceId: String,
        val route: String,
        val category: String,
        val bytes: Int = 0,
    ) {
        val successful: Boolean get() = category == "ok"
    }

    /** Thrown by the HTTP boundary so the builder can report HTTP/TLS/DNS/size failures separately. */
    class FetchFailure(val category: String) : RuntimeException(category)

    data class Upstream(
        val id: String,
        val url: String,
        val kind: Kind = Kind.CONFIG,
        val maxBytes: Int = 1_400_000,
        /** Required for bare host:port files; a SOCKS list must never silently become HTTP. */
        val protocolHint: String? = null,
        /** Same document through another public route; first non-blank response wins. */
        val mirrors: List<String> = emptyList(),
    )

    /**
     * GitHub Contents API is intentionally first: it uses api.github.com, which is often reachable when
     * raw.githubusercontent.com is poisoned. The three official jsDelivr front doors and raw GitHub are
     * independent fallbacks. Selected files stay below the Contents API's large-file limit.
     */
    private fun github(
        id: String,
        repo: String,
        ref: String,
        path: String,
        kind: Kind = Kind.CONFIG,
        maxBytes: Int = 600_000,
        protocolHint: String? = null,
    ): Upstream {
        val apiPath = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val assetPath = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val suffix = "$repo@$ref/$assetPath"
        return Upstream(
            id = id,
            url = "https://api.github.com/repos/$repo/contents/$apiPath?ref=$ref",
            kind = kind,
            maxBytes = maxBytes,
            protocolHint = protocolHint,
            mirrors = listOf(
                "https://cdn.jsdelivr.net/gh/$suffix",
                "https://fastly.jsdelivr.net/gh/$suffix",
                "https://gcore.jsdelivr.net/gh/$suffix",
                "https://raw.githubusercontent.com/$repo/$ref/$assetPath",
            ),
        )
    }

    /**
     * Current public config feeds. The first source publishes only configs that independently completed
     * three real Cloudflare-204 requests; the other actively maintained repositories provide diversity
     * when one collector, ASN or protocol family goes stale.
     */
    val TUNNEL_SOURCES: List<Upstream> = listOf(
        // CI snapshots a small, protocol-balanced subset of the verified public list into the APK.
        // It prevents a first install from showing zero when every GitHub/CDN hostname is temporarily
        // blocked; network sources below still refresh normally and replace this build-time safety net.
        Upstream("build-verified-snapshot", "asset://public-bootstrap.txt", maxBytes = 150_000),
        github(
            "radikal-verified", "0xRadikal/Free-v2ray-Configs", "main", "verified/configs.txt",
            maxBytes = 750_000,
        ),
        github("barry-sub1", "barry-far/V2ray-Config", "main", "Sub1.txt", maxBytes = 250_000),
        github(
            "matin-sub1", "MatinGhanbari/v2ray-configs", "main",
            "subscriptions/base64/subs/sub1.txt", maxBytes = 250_000,
        ),
        github("pawdroid-sub", "Pawdroid/Free-servers", "main", "sub", maxBytes = 100_000),
    )

    /**
     * Frequently regenerated proxy lists. These are candidates, not endorsements: each protocol gets a
     * fair scan quota and must pass real HTTPS requests through that exact proxy on this phone.
     */
    val PROXY_SOURCES: List<Upstream> = listOf(
        github(
            "monosans-http", "monosans/proxy-list", "main", "proxies/http.txt",
            kind = Kind.PROXY, maxBytes = 150_000, protocolHint = "http",
        ),
        github(
            "monosans-socks5", "monosans/proxy-list", "main", "proxies/socks5.txt",
            kind = Kind.PROXY, maxBytes = 200_000, protocolHint = "socks5",
        ),
        github(
            "iplocate-http", "iplocate/free-proxy-list", "main", "protocols/http.txt",
            kind = Kind.PROXY, maxBytes = 200_000, protocolHint = "http",
        ),
        github(
            "iplocate-socks5", "iplocate/free-proxy-list", "main", "protocols/socks5.txt",
            kind = Kind.PROXY, maxBytes = 250_000, protocolHint = "socks5",
        ),
        github(
            "proxyscrape-http", "ProxyScrape/free-proxy-list", "main",
            "proxies/protocols/http/data.txt", kind = Kind.PROXY, maxBytes = 250_000,
            protocolHint = "http",
        ),
        github(
            "proxyscrape-socks5", "ProxyScrape/free-proxy-list", "main",
            "proxies/protocols/socks5/data.txt", kind = Kind.PROXY, maxBytes = 350_000,
            protocolHint = "socks5",
        ),
        github(
            "health-http", "xyzs996/free-proxy-health-list", "main", "http.txt",
            kind = Kind.PROXY, maxBytes = 200_000, protocolHint = "http",
        ),
        github(
            "health-socks5", "xyzs996/free-proxy-health-list", "main", "socks5.txt",
            kind = Kind.PROXY, maxBytes = 200_000, protocolHint = "socks5",
        ),
    )

    /** Public default: tunnel and proxy discovery always run as independent pools in one refresh. */
    val DEFAULTS: List<Upstream> = TUNNEL_SOURCES + PROXY_SOURCES

    /** Additional maintained feeds opened only when the tested primary tunnel pool is thin. */
    val FALLBACKS: List<Upstream> = listOf(
        github("epodonios-sub1", "Epodonios/v2ray-configs", "main", "Sub1.txt", maxBytes = 400_000),
        github(
            "ebrasha-curated", "ebrasha/free-v2ray-public-list", "main",
            "V2Ray-Config-By-EbraSha.txt", maxBytes = 450_000,
        ),
        github(
            "mahdibland-eternity", "mahdibland/V2RayAggregator", "master", "Eternity.txt",
            maxBytes = 200_000,
        ),
    )

    data class Result(
        val nodes: List<FeedNode>,
        val sourcesOk: Int,
        val sourcesTotal: Int,
        val parsed: Int,
        val probed: Int,
        val notes: List<String>,
        val proxyScanned: Int = 0,
        val proxyClean: Int = 0,
        val proxyRobotBlocked: Int = 0,
        val proxyPlatformBlocked: Int = 0,
        val proxyDead: Int = 0,
        val parsedTunnels: Int = 0,
        val parsedProxies: Int = 0,
        val tunnelTcpOk: Int = 0,
        val fetchBytes: Long = 0,
        val fetchOutcomes: List<FetchOutcome> = emptyList(),
    ) {
        fun label(): String = "$sourcesOk/$sourcesTotal منبع · ${nodes.count { it.isUsable }} مسیر قابل‌آزمایش"
    }

    /**
     * [probe] is a cheap reachability hint for complete tunnel configs. [deepProbe] performs actual
     * HTTPS requests through raw proxies and returns a verdict for each examined proxy. Missing proxy
     * verdicts are dead, never optimistically usable.
     *
     * Fallback activation is based on direct TCP successes, not URI count. Crucially, a TCP failure no
     * longer removes a parsed tunnel: full Xray transport + exact-204 probing in the service is authoritative.
     */
    suspend fun buildFree(
        sources: List<Upstream> = DEFAULTS,
        brand: String = "Free M•A",
        /** The anti-block regime in force; shapes which transports sort first (NodeRanking). */
        regime: ir.meelano.vpn.net.Regime = ir.meelano.vpn.net.Regime.TIGHT,
        maxNodes: Int = 64,
        parseCap: Int = 192,
        probeLimit: Int = 48,
        proxyParseCap: Int = 144,
        proxyScanLimit: Int = 48,
        lowWatermark: Int = 16,
        fallbacks: List<Upstream> = FALLBACKS,
        extra: Upstream? = null,
        fetch: suspend (Upstream) -> String?,
        probe: suspend (List<FeedNode>) -> Map<String, Long>,
        deepProbe: suspend (List<FeedNode>) -> Map<String, ProxyScanner.Verdict> = { emptyMap() },
        /** Progressive delivery: tunnel candidates can paint while the deeper proxy scan continues. */
        onTunnelsReady: suspend (List<FeedNode>) -> Unit = {},
        /** Real per-stage progress for the 3D dials (data/SyncProgress); never a simulated ramp. */
        onProgress: (SyncProgress) -> Unit = {},
    ): Result = coroutineScope {
        val notes = ArrayList<String>(12)
        val tunnelPool = ArrayList<FeedNode>(parseCap)
        val proxyPool = ArrayList<FeedNode>(proxyParseCap)
        val fetchOutcomes = ArrayList<FetchOutcome>()
        var sourcesOk = 0
        var sourcesTotal = 0
        var skipped = 0
        var mirrored = 0
        val fetchedCount = java.util.concurrent.atomic.AtomicInteger(0)
        var fetchTotal = 0

        fun stage(s: SyncProgress.Stage, done: Int, total: Int) {
            runCatching { onProgress(SyncProgress(s, done, total)) }
        }

        fun failureCategory(error: Throwable): String = when (error) {
            is FetchFailure -> error.category
            is java.net.UnknownHostException -> "dns"
            is java.net.SocketTimeoutException -> "timeout"
            is javax.net.ssl.SSLException -> "tls"
            is java.io.IOException -> "io"
            else -> "exception_${error.javaClass.simpleName.lowercase().take(32)}"
        }

        /** Fetch one tier in parallel; each source's independent public routes are tried sequentially. */
        suspend fun CoroutineScope.pull(batch: List<Upstream>) {
            if (batch.isEmpty()) return
            fetchTotal += batch.size
            val configCount = batch.count { it.kind != Kind.PROXY }.coerceAtLeast(1)
            val proxyCount = batch.count { it.kind == Kind.PROXY }.coerceAtLeast(1)
            data class Pulled(
                val upstream: Upstream,
                val body: String?,
                val via: Int,
                val outcomes: List<FetchOutcome>,
            )
            val bodies = batch.map { upstream ->
                async {
                    var body: String? = null
                    var via = 0
                    val attempts = ArrayList<FetchOutcome>()
                    for ((index, link) in (listOf(upstream.url) + upstream.mirrors).withIndex()) {
                        val route = if (index == 0) "primary" else "mirror_$index"
                        try {
                            val candidate = fetch(upstream.copy(url = link))
                            if (candidate.isNullOrBlank()) {
                                attempts += FetchOutcome(upstream.id, route, "empty")
                            } else {
                                body = candidate
                                via = index
                                attempts += FetchOutcome(
                                    upstream.id, route, "ok", candidate.toByteArray(Charsets.UTF_8).size,
                                )
                                break
                            }
                        } catch (error: Throwable) {
                            attempts += FetchOutcome(upstream.id, route, failureCategory(error))
                        }
                    }
                    stage(SyncProgress.Stage.FETCH, fetchedCount.incrementAndGet(), fetchTotal)
                    Pulled(upstream, body, via, attempts)
                }
            }.awaitAll()
            val tunnelBuckets = ArrayList<List<FeedNode>>()
            val proxyBuckets = ArrayList<List<FeedNode>>()

            stage(SyncProgress.Stage.PARSE, 0, bodies.size)
            for ((parsedIndex, pulled) in bodies.withIndex()) {
                val (upstream, body, via, outcomes) = pulled
                sourcesTotal++
                fetchOutcomes += outcomes
                if (body.isNullOrBlank()) {
                    // the dial must still advance for a dead source, or PARSE never reaches its total
                    stage(SyncProgress.Stage.PARSE, parsedIndex + 1, bodies.size)
                    continue
                }
                sourcesOk++
                if (via > 0) mirrored++
                val sourceLimit = if (upstream.kind == Kind.PROXY) {
                    (proxyParseCap / proxyCount).coerceAtLeast(16)
                } else {
                    (parseCap / configCount).coerceAtLeast(16)
                }
                val parsed = NodeUri.parseBlob(
                    body,
                    tier = "free",
                    brand = brand,
                    proxyHint = upstream.protocolHint,
                    maxNodes = sourceLimit * 2,
                )
                val dialable = parsed.filter { it.proto in TUNNEL_PROTOS && !it.insecure }
                skipped += parsed.size - dialable.size
                val proxies = dialable.filter { it.isRawProxy }.take(sourceLimit)
                val tunnels = dialable.filterNot { it.isRawProxy }.take(sourceLimit)
                if (proxies.isNotEmpty()) proxyBuckets += proxies
                if (tunnels.isNotEmpty()) tunnelBuckets += tunnels
                if (dialable.isEmpty()) notes += "«${upstream.id}» پاسخ داد ولی مسیرِ قابل‌دیال نداشت"
                stage(SyncProgress.Stage.PARSE, parsedIndex + 1, bodies.size)
            }

            // Round-robin by source. Appending whole files lets the first giant repository spend every
            // probe slot and silently starves SOCKS, Shadowsocks and all later repositories.
            fun interleave(buckets: List<List<FeedNode>>, target: MutableList<FeedNode>) {
                var row = 0
                while (buckets.any { row < it.size }) {
                    buckets.forEach { bucket -> if (row < bucket.size) target += bucket[row] }
                    row++
                }
            }
            interleave(tunnelBuckets, tunnelPool)
            interleave(proxyBuckets, proxyPool)
        }

        val primary = if (extra == null) sources else sources + extra
        pull(primary)

        // Probe primary tunnel candidates first. A huge parsed file no longer suppresses fallback when
        // none of its endpoints is directly reachable from this network.
        val rtt = LinkedHashMap<String, Long>()
        suspend fun probeFresh(candidates: List<FeedNode>, limit: Int) {
            val fresh = candidates.filter { it.id !in rtt }.take(limit)
            if (fresh.isEmpty()) return
            // The dial counts "probed out of everything we decided to probe" — honest even when the
            // fallback tier adds candidates halfway through.
            val planned = rtt.size + fresh.size
            stage(SyncProgress.Stage.PROBE, rtt.size, planned)
            val got = runCatching { probe(fresh) }.getOrDefault(emptyMap())
            fresh.forEach { node -> rtt[node.id] = got[node.id] ?: -1L }
            stage(SyncProgress.Stage.PROBE, rtt.size, planned)
        }

        var tunnelCandidates = NodeUri.dedupe(tunnelPool, max = parseCap)
        probeFresh(tunnelCandidates, probeLimit)
        val primaryAlive = rtt.values.count { it >= 0 }
        if (primaryAlive < lowWatermark && fallbacks.isNotEmpty()) {
            notes += "فقط $primaryAlive سرورِ اصلی TCP پاسخ داد؛ منابع جایگزین هم آزمایش شد"
            val primaryIds = tunnelCandidates.mapTo(HashSet()) { it.id }
            val beforeFallback = tunnelPool.size
            pull(fallbacks)
            val fallbackCandidates = NodeUri.dedupe(
                tunnelPool.drop(beforeFallback),
                max = (parseCap / 2).coerceAtLeast(probeLimit),
            )
            tunnelCandidates = (tunnelCandidates + fallbackCandidates).distinctBy { it.id }
            // A separate quota keeps unprobed rows from a giant primary file from starving the recovery tier.
            val fallbackFirst = tunnelCandidates.filter { it.id !in primaryIds } +
                tunnelCandidates.filter { it.id in primaryIds }
            probeFresh(fallbackFirst, probeLimit)
        }

        if (mirrored > 0) notes += "$mirrored منبع از مسیر آینهٔ عمومی جایگزین دریافت شد"
        if (skipped > 0) notes += "$skipped ردیف با پروتکل پشتیبانی‌نشده یا ناامن حذف شد"
        val failedSources = sourcesTotal - sourcesOk
        if (failedSources > 0) notes += "$failedSources منبع از $sourcesTotal پاسخ نداد؛ همهٔ آینه‌ها امتحان شدند"

        // Only candidates that actually received a pre-probe result are published, but -1 is retained.
        // It means "TCP inconclusive", not "Xray tunnel dead". Successful hints sort first.
        stage(SyncProgress.Stage.RANK, 0, 1)
        val tunnelRows = tunnelCandidates.mapNotNull { node ->
            val ms = rtt[node.id] ?: return@mapNotNull null
            if (ms >= 0) {
                node.copy(
                    latencyMs = ms,
                    grade = NodeUri.grade(ms),
                    reliability = 0.5f,
                    samples = 1,
                    platformStatus = "tcp_reachable",
                    platformReason = "full_tunnel_probe_pending",
                )
            } else {
                node.copy(
                    latencyMs = null,
                    grade = "D",
                    reliability = 0f,
                    samples = 1,
                    platformStatus = "tcp_inconclusive",
                    platformReason = "full_tunnel_probe_required",
                )
            }
        }.sortedWith(
            compareBy<FeedNode>(
                { if (it.latencyMs != null) 0 else 1 },
                // Iran-aware bucket before raw RTT: from inside the country, *what* a node is
                // (Reality before plaintext) and *where* it is (Istanbul before Oregon) decides
                // whether it survives the DPI at all; the RTT decides only inside that class.
                // Sorting by one bare-TCP number put doomed-but-fast nodes at the head of every
                // auto-connect attempt - the feed-side half of "connects only sometimes".
                { NodeRanking.bucket(it, regime) },
                { it.latencyMs ?: Long.MAX_VALUE },
                { it.slot },
            )
        ).take(maxNodes).mapIndexed { index, node -> node.copy(slot = index + 1) }

        if (tunnelRows.isNotEmpty()) runCatching { onTunnelsReady(tunnelRows) }

        val proxyCandidates = NodeUri.dedupe(proxyPool, max = proxyParseCap).take(proxyScanLimit)
        val verdicts = if (proxyCandidates.isEmpty()) emptyMap() else {
            runCatching { deepProbe(proxyCandidates) }.getOrDefault(emptyMap())
        }
        val checkedAt = System.currentTimeMillis() / 1000L
        val proxyRows = proxyCandidates.mapIndexed { index, node ->
            val verdict = verdicts[node.id] ?: ProxyScanner.Verdict(
                status = ProxyScanner.DEAD,
                latencyMs = null,
                reliability = 0f,
                reason = "scan_not_completed",
                checks = emptyList(),
            )
            node.copy(
                slot = index + 1,
                latencyMs = verdict.latencyMs,
                grade = if (verdict.usable) NodeUri.grade(verdict.latencyMs) else "D",
                reliability = verdict.reliability,
                samples = verdict.checks.size,
                platformStatus = verdict.status,
                platformCheckedAt = checkedAt,
                platformReason = verdict.reason,
            )
        }.sortedWith(
            compareBy<FeedNode>(
                { PROXY_STATUS_RANK[it.platformStatus] ?: 9 },
                { GRADE_RANK[it.grade] ?: 9 },
                { it.latencyMs ?: Long.MAX_VALUE },
            )
        ).mapIndexed { index, node -> node.copy(slot = index + 1) }

        val clean = proxyRows.count { it.platformStatus == ProxyScanner.CLEAN }
        val robots = proxyRows.count { it.platformStatus == ProxyScanner.ROBOT_BLOCKED }
        val platform = proxyRows.count { it.platformStatus == ProxyScanner.PLATFORM_BLOCKED }
        val dead = proxyRows.count { it.platformStatus == ProxyScanner.DEAD }
        if (proxyRows.isNotEmpty()) {
            notes += "پروکسی: $clean سالم · $robots ربات/کپچا · $platform مسدود برای پلتفرم · $dead مرده"
            notes += "فقط پروکسیِ سالم وارد انتخاب هوشمند و تعویض خودکار می‌شود"
        }

        val tcpOk = rtt.values.count { it >= 0 }
        val tcpFailed = rtt.size - tcpOk
        if (rtt.isNotEmpty()) {
            notes += "TCP اولیه: $tcpOk پاسخ‌گو · $tcpFailed نامطمئن؛ نامطمئن‌ها برای آزمون کامل Xray نگه داشته شدند"
        }
        if (tunnelRows.isEmpty() && proxyRows.none { it.isUsable } && sourcesOk > 0) {
            notes += "منبع دریافت شد ولی هیچ مسیرِ قابل‌آزمایش parse نشد"
        }
        if (sourcesOk == 0) {
            notes += "هیچ منبع عمومی در دسترس نبود؛ جزئیاتِ هر مسیر در گزارش عیب‌یابی ثبت شد"
        }

        stage(SyncProgress.Stage.RANK, 1, 1)
        Result(
            nodes = tunnelRows + proxyRows,
            sourcesOk = sourcesOk,
            sourcesTotal = sourcesTotal,
            parsed = tunnelPool.size + proxyPool.size,
            probed = rtt.size,
            notes = notes,
            proxyScanned = proxyRows.size,
            proxyClean = clean,
            proxyRobotBlocked = robots,
            proxyPlatformBlocked = platform,
            proxyDead = dead,
            parsedTunnels = tunnelPool.size,
            parsedProxies = proxyPool.size,
            tunnelTcpOk = tcpOk,
            fetchBytes = fetchOutcomes.sumOf { it.bytes.toLong() },
            fetchOutcomes = fetchOutcomes.toList(),
        )
    }

    /** Protocols that [CoreProfiles] can turn into a real Xray outbound. */
    val TUNNEL_PROTOS = setOf("vless", "vmess", "trojan", "ss", "shadowsocks", "http", "socks5", "socks")

    private val GRADE_RANK = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3)
    private val PROXY_STATUS_RANK = mapOf(
        ProxyScanner.CLEAN to 0,
        ProxyScanner.ROBOT_BLOCKED to 1,
        ProxyScanner.PLATFORM_BLOCKED to 2,
        ProxyScanner.DEAD to 3,
    )
}
