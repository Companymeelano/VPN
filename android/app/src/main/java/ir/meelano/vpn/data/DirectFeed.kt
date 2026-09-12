package ir.meelano.vpn.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Builds a free list **on the phone**, from the same public sources the /v/ backend reads - so the app
 * works with no host of yours at all (the "I don't want to depend on shared hosting" mode).
 *
 * What it deliberately does NOT do, and why that is not a shortcut:
 *
 *   - no CONNECT-through-proxy gate to Google/Cloudflare: that is 3 round trips per node per build, and
 *     on a phone on LTE it is the difference between a cool pocket and a warm one;
 *   - no multi-day ledger, no ban timers: those only pay off when someone is around tomorrow, and the
 *     server keeps them;
 *   - no raw `ip:port` proxy lists (TheSpeedX, ShiftyTR, …): an HTTP/SOCKS proxy cannot carry a VPN
 *     tunnel, so on a phone they are 400 KB of bytes for nodes the core cannot dial. We keep the
 *     config-grade lists (vless / trojan / ss / vmess / hysteria2), which is what the core can dial.
 *
 * Everything here is pure Kotlin and takes its IO as *lambdas*, so it runs in JVM unit tests with fake
 * fetchers. `ServerFeedRepository` supplies the real ones (OkHttp with the DoH fallback, and the same
 * TCP probe the "چرا وصل نشد" card uses).
 */
object DirectFeed {

    /** CONFIG = a text file of `scheme://` lines. JSON = an array of objects with ip/port/user/pass. */
    enum class Kind { CONFIG, JSON }

    data class Upstream(
        val id: String,
        val url: String,
        val kind: Kind = Kind.CONFIG,
        val maxBytes: Int = 1_400_000,
    )

    /**
     * The shortlist a phone should read. Deliberately only the *config-grade* lists: monosans'
     * `proxies.json` and every raw proxy list are HTTP/SOCKS, and `CoreApi` cannot dial those as an
     * outbound - publishing them here would fill the list with rows that fail on "connect" and get
     * blamed on the app.
     */
    val DEFAULTS: List<Upstream> = listOf(
        Upstream("gfp-vless", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vless.txt"),
        Upstream("gfp-trojan", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/trojan.txt"),
        Upstream("gfp-ss", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/ss.txt"),
        Upstream("gfp-vmess", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vmess.txt"),
        Upstream("gfp-hy2", "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/hy2.txt"),
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
     */
    suspend fun buildFree(
        sources: List<Upstream> = DEFAULTS,
        brand: String = "Free M•A",
        maxNodes: Int = 80,
        parseCap: Int = 160,
        probeLimit: Int = 36,
        extra: Upstream? = null,
        fetch: suspend (Upstream) -> String?,
        probe: suspend (List<FeedNode>) -> Map<String, Long>,
    ): Result = coroutineScope {
        val wanted = if (extra == null) sources else sources + extra
        val bodies = wanted.map { u -> async { u to runCatching { fetch(u) }.getOrNull() } }
            .awaitAll()

        val notes = ArrayList<String>(4)
        val all = ArrayList<FeedNode>(parseCap)
        var ok = 0
        var skippedProxies = 0
        for ((u, body) in bodies) {
            if (body.isNullOrBlank()) {
                continue
            }
            ok++
            // The core dials vless/vmess/trojan/ss/hy2 outbounds only - everything else is a row the
            // user could tap and blame themselves for. Count them, don't list them.
            val parsed = NodeUri.parseBlob(body, tier = "free", brand = brand)
            val dialable = parsed.filter { it.proto in TUNNEL_PROTOS }
            skippedProxies += parsed.size - dialable.size
            if (dialable.isEmpty()) {
                notes += "«${u.id}» پاسخ داد ولی نودِ قابل‌دیال نداشت"
            } else {
                all += dialable
            }
            if (all.size >= parseCap) break        // the head of every list is enough; stop reading intent
        }
        if (skippedProxies > 0) notes += "$skippedProxies ردیف پروکسیِ خام حذف شد (هستهٔ اپ آن‌ها را دیال نمی‌کند)"
        val failed = wanted.size - ok
        if (failed > 0) notes += "$failed منبع از ${wanted.size} جواب نداد (فیلترینگِ GitHub روی این مسیر طبیعی است)"

        val candidates = NodeUri.dedupe(all, max = parseCap)
        if (candidates.isEmpty()) {
            return@coroutineScope Result(emptyList(), ok, wanted.size, 0, 0,
                notes + "هیچ نودی از فهرست‌های عمومی ساخته نشد")
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

        Result(ordered, ok, wanted.size, all.size, head.size, notes)
    }

    /** What `CoreApi` can turn into an outbound. Anything else never reaches the list. */
    val TUNNEL_PROTOS = setOf("vless", "vmess", "trojan", "ss", "hy2")

    private val GRADE_RANK = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3)
}
