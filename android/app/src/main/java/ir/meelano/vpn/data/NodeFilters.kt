package ir.meelano.vpn.data

/**
 * The server-list filter row as pure functions: which rows survive «همه/نزدیک/سریع/کار می‌کند»,
 * a country chip and a ping ceiling — and in what order they return.
 *
 * Extracted from ServerListSheet so the rules are unit-testable and so the sheet stays a
 * renderer. One convention everywhere: a node that was never measured (latencyMs == null) has no
 * ping, so any *ping* filter hides it — "سریع" must never mean "unknown".
 */
object NodeFilters {

    const val KIND_ALL = 0
    const val KIND_NEAR = 1
    const val KIND_FAST = 2
    const val KIND_WORKS = 3

    /** "IR", "DE", … or null = every country. Unknown-cc rows only appear under null. */
    fun apply(
        rows: List<FeedNode>,
        kind: Int,
        country: String?,
        maxPingMs: Long?,
    ): List<FeedNode> {
        val byKind = when (kind) {
            KIND_NEAR -> rows.filter { it.latencyMs != null }
            KIND_FAST -> rows.filter { (it.latencyMs ?: Long.MAX_VALUE) <= FAST_MS }
            KIND_WORKS -> rows.filter { it.grade == "A" || it.grade == "B" }
            else -> rows
        }
        val byCountry = if (country == null) byKind else byKind.filter {
            it.cc != null && it.cc.equals(country, ignoreCase = true)
        }
        return if (maxPingMs == null) byCountry
        else byCountry.filter { (it.latencyMs ?: Long.MAX_VALUE) <= maxPingMs }
    }

    /** Country codes present in a list, biggest group first, then alphabetical — chip row order. */
    fun countries(rows: List<FeedNode>): List<String> = rows
        .mapNotNull { it.cc?.trim()?.uppercase()?.takeIf { c -> c.length == 2 } }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    /** Median of the measured pings; null when nothing is measured. Drives the smart profile. */
    fun medianPingMs(rows: List<FeedNode>): Long? {
        val ms = rows.mapNotNull { it.latencyMs }.sorted()
        return if (ms.isEmpty()) null else ms[ms.size / 2]
    }

    /** Default sort: pinned → usable → grade → reliability → ping. Shared by every tab. */
    fun sorted(rows: List<FeedNode>, pinned: Set<String>): List<FeedNode> = rows.sortedWith(
        compareByDescending<FeedNode> { it.id in pinned }
            .thenBy { if (it.isUsable) 0 else 1 }
            .thenBy { GRADE_RANK[it.grade] ?: 9 }
            .thenByDescending { it.reliability }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE },
    )

    const val FAST_MS = 400L

    private val GRADE_RANK = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3)
}
