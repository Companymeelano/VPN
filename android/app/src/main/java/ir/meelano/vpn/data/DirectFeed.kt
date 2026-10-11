package ir.meelano.vpn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Builds the public feed entirely on the phone. Built-in documents live on GitHub and each repository
 * file has a jsDelivr/raw mirror chain where that is technically possible; no request in this builder
 * goes to the product's private feed host.
 *
 * Tunnel configs and raw proxies are intentionally separate pools:
 *
 *  - vless/vmess/trojan/ss candidates receive a bounded, fair TCP reachability pass. They still have
 *    to pass the VPN service's exact-204 test after Xray establishes the complete tunnel;
 *  - HTTP/SOCKS5 candidates never inherit a tunnel's grade. Every one shown in the proxy section is
 *    connected through directly by [ProxyScanner] and classified as clean, robot-blocked,
 *    platform-blocked, or dead.
 *
 * IO is injected, so mirror behavior, pool quotas and every rejection state run in JVM tests without
 * requiring GitHub or a particular national network to be reachable from CI.
 */
object DirectFeed {

    enum class Kind { CONFIG, PROXY, JSON }

    data class Upstream(
        val id: String,
        val url: String,
        val kind: Kind = Kind.CONFIG,
        val maxBytes: Int = 1_400_000,
        /** Required for bare host:port files; a SOCKS list must never silently become HTTP. */
        val protocolHint: String? = null,
        /** Same document through another public host; first non-blank response wins. */
        val mirrors: List<String> = emptyList(),
    )

    /** Config repositories. The gfp wiki is a git repository itself but cannot be mirrored by jsDelivr. */
    val TUNNEL_SOURCES: List<Upstream> = listOf(
        Upstream("gfp-vless", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vless.txt"),
        Upstream("gfp-trojan", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/trojan.txt"),
        Upstream("gfp-ss", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/ss.txt"),
        Upstream("gfp-vmess", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vmess.txt"),
    )

    /** Raw proxy repositories. These are scanned independently; none is published as clean on parse alone. */
    val PROXY_SOURCES: List<Upstream> = listOf(
        Upstream(
            "speedx-http", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            kind = Kind.PROXY, maxBytes = 500_000, protocolHint = "http",
            mirrors = listOf("https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/http.txt"),
        ),
        Upstream(
            "speedx-socks5", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
            kind = Kind.PROXY, maxBytes = 500_000, protocolHint = "socks5",
            mirrors = listOf("https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/socks5.txt"),
        ),
        Upstream(
            "monosans-http", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
            kind = Kind.PROXY, maxBytes = 500_000, protocolHint = "http",
            mirrors = listOf("https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/http.txt"),
        ),
        Upstream(
            "monosans-socks5", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
            kind = Kind.PROXY, maxBytes = 500_000, protocolHint = "socks5",
            mirrors = listOf("https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks5.txt"),
        ),
    )

    /** Kept as the public default API while exposing the two pools explicitly above. */
    val DEFAULTS: List<Upstream> = TUNNEL_SOURCES + PROXY_SOURCES

    /** Small daily repository files used when the *tested* primary tunnel pool is thin. */
    val FALLBACKS: List<Upstream> = listOf(
        Upstream(
            "fn-v2rayshare",
            "https://cdn.jsdelivr.net/gh/jiayou88/FreeNodes-@main/nodes/v2rayshare.txt",
            maxBytes = 900_000,
            mirrors = listOf("https://raw.githubusercontent.com/jiayou88/FreeNodes-/main/nodes/v2rayshare.txt"),
        ),
        Upstream(
            "fn-nodefree",
            "https://cdn.jsdelivr.net/gh/jiayou88/FreeNodes-@main/nodes/nodefree.txt",
            maxBytes = 900_000,
            mirrors = listOf("https://raw.githubusercontent.com/jiayou88/FreeNodes-/main/nodes/nodefree.txt"),
        ),
        Upstream(
            "fn-yudou66",
            "https://cdn.jsdelivr.net/gh/jiayou88/FreeNodes-@main/nodes/yudou66.txt",
            maxBytes = 900_000,
            mirrors = listOf("https://raw.githubusercontent.com/jiayou88/FreeNodes-/main/nodes/yudou66.txt"),
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
    ) {
        fun label(): String = "$sourcesOk/$sourcesTotal منبع · ${nodes.count { it.isUsable }} مسیر سالم"
    }

    /**
     * [probe] is the cheap reachability stage for complete tunnel configs. [deepProbe] performs actual
     * HTTPS requests through raw proxies and must return a verdict for every proxy it examined. Missing
     * verdicts are dead, never optimistically grey/usable.
     *
     * Fallback activation is based on the count that answered the phone's probe, not the count of URI
     * strings parsed from a giant repository. This distinction fixes the common "downloaded thousands,
     * displayed none useful, therefore never opened fallback" failure mode.
     */
    suspend fun buildFree(
        sources: List<Upstream> = DEFAULTS,
        brand: String = "Free M•A",
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
        /** Optional progressive delivery: verified tunnels can paint while the deeper proxy scan runs. */
        onTunnelsReady: suspend (List<FeedNode>) -> Unit = {},
    ): Result = coroutineScope {
        val notes = ArrayList<String>(10)
        val tunnelPool = ArrayList<FeedNode>(parseCap)
        val proxyPool = ArrayList<FeedNode>(proxyParseCap)
        var sourcesOk = 0
        var sourcesTotal = 0
        var skipped = 0
        var mirrored = 0

        /** Fetch one tier in parallel; each source's mirrors are tried sequentially. */
        suspend fun CoroutineScope.pull(batch: List<Upstream>) {
            if (batch.isEmpty()) return
            val configCount = batch.count { it.kind != Kind.PROXY }.coerceAtLeast(1)
            val proxyCount = batch.count { it.kind == Kind.PROXY }.coerceAtLeast(1)
            val bodies = batch.map { upstream ->
                async {
                    var body: String? = null
                    var via = 0
                    for ((index, link) in (listOf(upstream.url) + upstream.mirrors).withIndex()) {
                        body = runCatching { fetch(upstream.copy(url = link)) }.getOrNull()
                        if (!body.isNullOrBlank()) {
                            via = index
                            break
                        }
                    }
                    Triple(upstream, body, via)
                }
            }.awaitAll()
            val tunnelBuckets = ArrayList<List<FeedNode>>()
            val proxyBuckets = ArrayList<List<FeedNode>>()

            for ((upstream, body, via) in bodies) {
                sourcesTotal++
                if (body.isNullOrBlank()) continue
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
            }

            // Round-robin by source. Appending whole files meant the first VLESS/HTTP repository spent
            // the entire probe budget and SOCKS, SS and every mirror source were parsed but never tested.
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

        // Probe primary tunnel candidates first. A huge parsed file no longer suppresses fallbacks when
        // all of its endpoints are dead from this network.
        val rtt = LinkedHashMap<String, Long>()
        suspend fun probeFresh(candidates: List<FeedNode>, limit: Int) {
            val fresh = candidates.filter { it.id !in rtt }.take(limit)
            if (fresh.isEmpty()) return
            val got = runCatching { probe(fresh) }.getOrDefault(emptyMap())
            fresh.forEach { node -> rtt[node.id] = got[node.id] ?: -1L }
        }

        var tunnelCandidates = NodeUri.dedupe(tunnelPool, max = parseCap)
        probeFresh(tunnelCandidates, probeLimit)
        val primaryAlive = rtt.values.count { it >= 0 }
        if (primaryAlive < lowWatermark && fallbacks.isNotEmpty()) {
            notes += "فقط $primaryAlive سرورِ اصلی پاسخ داد؛ منابع جایگزینِ روزانه هم آزمایش شد"
            val primaryIds = tunnelCandidates.mapTo(HashSet()) { it.id }
            val beforeFallback = tunnelPool.size
            pull(fallbacks)
            val fallbackCandidates = NodeUri.dedupe(
                tunnelPool.drop(beforeFallback),
                max = (parseCap / 2).coerceAtLeast(probeLimit),
            )
            tunnelCandidates = (tunnelCandidates + fallbackCandidates).distinctBy { it.id }
            // A separate fallback quota prevents unprobed rows from the first giant source consuming
            // every socket and starving the very tier intended to recover it.
            val fallbackFirst = tunnelCandidates.filter { it.id !in primaryIds } +
                tunnelCandidates.filter { it.id in primaryIds }
            probeFresh(fallbackFirst, probeLimit)
        }

        if (mirrored > 0) notes += "$mirrored منبع از آینهٔ عمومی جایگزین دریافت شد"
        if (skipped > 0) notes += "$skipped ردیف با پروتکل پشتیبانی‌نشده یا ناامن حذف شد"
        val failedSources = sourcesTotal - sourcesOk
        if (failedSources > 0) notes += "$failedSources منبع از $sourcesTotal پاسخ نداد؛ آینه‌ها به‌صورت خودکار امتحان شدند"

        val tunnelRows = tunnelCandidates.mapNotNull { node ->
            val ms = rtt[node.id]
            if (ms == null || ms < 0) null else node.copy(
                latencyMs = ms,
                grade = NodeUri.grade(ms),
                reliability = 0.5f, // port reachable; complete exact-204 proof happens in the service
                samples = 1,
                platformStatus = "unknown",
            )
        }.sortedWith(compareBy({ GRADE_RANK[it.grade] ?: 9 }, { it.latencyMs ?: Long.MAX_VALUE }))
            .take(maxNodes)
            .mapIndexed { index, node -> node.copy(slot = index + 1) }

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

        if (tunnelCandidates.isNotEmpty() && tunnelRows.isEmpty()) {
            notes += "سرورهای دریافت‌شده از همین شبکه به TCP پاسخ ندادند و از خروجیِ قابل اتصال کنار رفتند"
        }
        if (tunnelRows.isEmpty() && proxyRows.none { it.isUsable } && sourcesOk > 0) {
            notes += "هیچ مسیر سالمی در آزمون این گوشی پیدا نشد؛ دوباره‌سازی بعدی منابع تازه را می‌سنجد"
        }
        if (sourcesOk == 0) {
            notes += "هیچ منبع عمومی در دسترس نبود؛ اتصال شبکه یا لینک شخصی را بررسی کنید"
        }

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
