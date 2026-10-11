package ir.meelano.vpn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Builds a free list **on the phone**, from public sources - so the app works with no host of yours at
 * all (the "I don't want to depend on shared hosting" mode). Everything is fetched, parsed, probed and
 * ranked by the app itself; no server, no shared host, nothing in the middle.
 *
 * Survivability on a filtered path (the whole point of this file):
 *
 *   - every upstream carries a `mirrors` chain: the same document on another domain, tried in order
 *     when the first URL answers nothing. `raw.githubusercontent.com` is SNI-blocked on many Iranian
 *     paths while `cdn.jsdelivr.net` usually is not, and jsDelivr can only mirror *repo* files - never
 *     wiki files - so the mirror-ready sources below are real repos, and the wiki lists stay
 *     primary-only on purpose;
 *   - if the primary tier comes home nearly empty, a second tier of daily-crawled repos (each with its
 *     own raw/jsdelivr chain) is fetched automatically, so "GitHub is filtered here" degrades into a
 *     mirror read instead of an empty screen.
 *
 * What it deliberately does NOT do, and why that is not a shortcut:
 *
 *   - every candidate is still verified through the established Android tunnel before it can turn the
 *     UI green; this builder only does the cheap source/TCP stage so a phone does not fan out hundreds
 *     of HTTPS handshakes at once;
 *   - no multi-day ledger, no ban timers: those only pay off when someone is around tomorrow, and the
 *     server keeps them;
 *   - no SOCKS4 rows: the pinned core only supports SOCKS5. HTTP and SOCKS5 lists are accepted and
 *     identified by an explicit per-source protocol hint (never guessed from a random port).
 *
 * Everything here is pure Kotlin and takes its IO as *lambdas*, so it runs in JVM unit tests with fake
 * fetchers. `ServerFeedRepository` supplies the real ones (OkHttp with the DoH fallback, and the same
 * TCP probe the "چرا وصل نشد" card uses).
 */
object DirectFeed {

    /** CONFIG = a text file of `scheme://` lines. JSON = an array of objects with ip/port/user/pass. */
    enum class Kind { CONFIG, PROXY, JSON }

    data class Upstream(
        val id: String,
        val url: String,
        val kind: Kind = Kind.CONFIG,
        val maxBytes: Int = 1_400_000,
        /** Required for bare host:port proxy files; prevents a SOCKS list being mislabeled HTTP. */
        val protocolHint: String? = null,
        /** Same document, other hosts. Tried in order after `url`; the first non-blank body wins. */
        val mirrors: List<String> = emptyList(),
    )

    /**
     * Primary tier - the highest-quality free config lists. They live in a GitHub *wiki*, which no CDN
     * mirrors, so on a path where raw.githubusercontent is poisoned they simply fail and the fallback
     * tier below takes over. `hy2` is deliberately absent: `CoreProfiles` writes no hysteria2 outbound,
     * so its rows could only ever be tapped into a guaranteed connect failure.
     */
    val DEFAULTS: List<Upstream> = listOf(
        Upstream("gfp-vless", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vless.txt"),
        Upstream("gfp-trojan", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/trojan.txt"),
        Upstream("gfp-ss", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/ss.txt"),
        Upstream("gfp-vmess", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vmess.txt"),
        Upstream(
            "speedx-http", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            kind = Kind.PROXY, maxBytes = 500_000, protocolHint = "http",
            mirrors = listOf("https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/http.txt"),
        ),
        Upstream(
            "speedx-socks5", "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
            kind = Kind.PROXY, maxBytes = 500_000, protocolHint = "socks5",
            mirrors = listOf("https://cdn.jsdelivr.net/gh/TheSpeedX/SOCKS-List@master/socks5.txt"),
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

    /**
     * Fallback tier - daily crawls of the free-node aggregator sites, kept in a *real* repo so jsDelivr
     * can serve them when GitHub itself is filtered. Mirror order is jsdelivr-first: the only situation
     * this tier is consulted in bulk is a path where raw.githubusercontent is already dead. The three
     * files overlap heavily (they crawl the same sites) - `NodeUri.dedupe` collapses the overlap.
     */
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
    ) {
        /** One line for the sheet footer; Persian because a person reads it. */
        fun label(): String = "$sourcesOk/$sourcesTotal منبع · ${nodes.size} نود"
    }

    /**
     * @param fetch   gets one upstream's body (null = it failed; the caller owns timeouts and byte caps)
     * @param probe   dials a batch and returns id -> RTT ms, -1 for "nothing answered"
     * @param probeLimit how many of the head to actually dial (each one is a socket, not a free thing)
     * @param lowWatermark below this many dialable nodes from the primary tier, the fallback tier is
     *        fetched too (0 = never, Int.MAX_VALUE = always)
     * @param fallbacks second-chance list; consulted only when the primary tier comes home thin
     */
    suspend fun buildFree(
        sources: List<Upstream> = DEFAULTS,
        brand: String = "Free M•A",
        maxNodes: Int = 80,
        parseCap: Int = 160,
        probeLimit: Int = 36,
        lowWatermark: Int = 24,
        fallbacks: List<Upstream> = FALLBACKS,
        extra: Upstream? = null,
        fetch: suspend (Upstream) -> String?,
        probe: suspend (List<FeedNode>) -> Map<String, Long>,
    ): Result = coroutineScope {
        val notes = ArrayList<String>(6)
        val all = ArrayList<FeedNode>(parseCap)
        var ok = 0
        var total = 0
        var skippedProxies = 0
        var mirrored = 0

        // One batch of upstreams in parallel; each upstream walks its own url + mirrors chain
        // sequentially and keeps the first body that comes back with anything in it.
        suspend fun CoroutineScope.pull(batch: List<Upstream>) {
            if (batch.isEmpty()) return
            val bodies = batch.map { u ->
                async {
                    var body: String? = null
                    var via = 0
                    for ((i, link) in (listOf(u.url) + u.mirrors).withIndex()) {
                        body = runCatching { fetch(u.copy(url = link)) }.getOrNull()
                        if (!body.isNullOrBlank()) { via = i; break }
                    }
                    Triple(u, body, via)
                }
            }.awaitAll()
            for ((u, body, via) in bodies) {
                total++
                if (body.isNullOrBlank()) continue
                ok++
                if (via > 0) mirrored++
                // The core dials vless/vmess/trojan/ss outbounds only - everything else is a row the
                // user could tap and blame themselves for. Count them, don't list them.
                val parsed = NodeUri.parseBlob(body, tier = "free", brand = brand, proxyHint = u.protocolHint)
                val dialable = parsed.filter { it.proto in TUNNEL_PROTOS && !it.insecure }
                skippedProxies += parsed.size - dialable.size
                if (dialable.isEmpty()) {
                    notes += "«${u.id}» پاسخ داد ولی نودِ قابل‌دیال نداشت"
                } else {
                    // Fair share per source: one huge VLESS file must not starve SOCKS/HTTP or every
                    // other transport before ranking gets a chance to compare them.
                    val share = (parseCap / batch.size.coerceAtLeast(1)).coerceAtLeast(12)
                    all += dialable.take(share)
                }
            }
        }

        pull(if (extra == null) sources else sources + extra)

        if (all.size < lowWatermark && fallbacks.isNotEmpty()) {
            notes += "فهرست‌های اصلی ناکافی بود؛ از منابع جایگزینِ به‌روزشوندهٔ روزانه خوانده شد"
            pull(fallbacks)
        }
        if (mirrored > 0) notes += "$mirrored منبع از مسیرِ جایگزین (آینهٔ jsdelivr) آورده شد"
        if (skippedProxies > 0) notes += "$skippedProxies ردیف با پروتکل ناامن/پشتیبانی‌نشده حذف شد"
        val failed = total - ok
        if (failed > 0) notes += "$failed منبع از $total جواب نداد (فیلترینگِ GitHub روی این مسیر طبیعی است)"

        val candidates = NodeUri.dedupe(all, max = parseCap)
        if (candidates.isEmpty()) {
            return@coroutineScope Result(
                emptyList(), ok, total, 0, 0,
                notes + "هیچ نودی از فهرست‌های عمومی ساخته نشد؛ اگر هاست یا اشتراک VIP دارید همان را وارد کنید",
            )
        }

        // Dial the head, in one batch (the repository runs this on its own dispatcher with a real
        // timeout; here we only decide who gets a grade and in what order).
        val head = candidates.take(probeLimit)
        val rtt = runCatching { probe(head) }.getOrDefault(emptyMap())
        val graded = head.map { n ->
            val ms = rtt[n.id]
            n.copy(latencyMs = if (ms != null && ms >= 0) ms else null, grade = NodeUri.grade(ms))
        } + candidates.drop(probeLimit).map { it.copy(grade = "C") }   // unprobed: honest grey, never a lie

        val ordered = graded.sortedWith(
            compareBy({ GRADE_RANK[it.grade] ?: 9 }, { it.latencyMs ?: Long.MAX_VALUE })
        ).take(maxNodes)
            .mapIndexed { i, n -> n.copy(slot = i + 1) }

        val alive = graded.count { (it.latencyMs ?: -1L) >= 0 }
        if (alive == 0) notes += "هیچ‌کدام از نودها از همین‌جا پاسخ نداد؛ فهرست برای پروکسی‌کردنِ تونل ساخته می‌شود، نه برای تستِ شبکه"

        Result(ordered, ok, total, all.size, head.size, notes)
    }

    /**
     * What `CoreApi`/`CoreProfiles` can turn into a working Xray outbound. Anything else - `hy2`
     * included, until a sing-box-style writer for it exists - never reaches the list, because a row
     * that can only fail at connect time reads as the app's fault, not the source's.
     */
    val TUNNEL_PROTOS = setOf("vless", "vmess", "trojan", "ss", "shadowsocks", "http", "socks5", "socks")

    private val GRADE_RANK = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3)
}
