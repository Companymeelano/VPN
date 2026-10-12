package ir.meelano.vpn.data

import java.util.Locale

/**
 * Small on-device reliability history used by smart selection and failover.
 *
 * It records only the stable anonymous node id, counters, an RTT average and a timestamp. There is no
 * address, credential, device id or network identity in the file. Counts decay after 100 observations
 * so a server that recovered is not punished forever, while consecutive recent failures still move it
 * down immediately.
 */
class NodeHealthLedger(private val maxEntries: Int = 256) {

    data class Entry(
        val successes: Int = 0,
        val failures: Int = 0,
        val averageLatencyMs: Long = 0,
        val consecutiveFailures: Int = 0,
        val updatedAtMs: Long = 0,
    ) {
        val samples: Int get() = successes + failures
        /** Beta(1,1) prior: one lucky result never claims 100% long-term reliability. */
        val score: Float get() = ((successes + 1f) / (samples + 2f)).coerceIn(0f, 1f)
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun record(id: String, ok: Boolean, latencyMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (!validId(id)) return
        var old = entries[id] ?: Entry()
        // Bounded rolling history. Halving preserves the ratio and gives recent outcomes more weight.
        if (old.samples >= 100) {
            old = old.copy(
                successes = old.successes / 2,
                failures = old.failures / 2,
                consecutiveFailures = old.consecutiveFailures.coerceAtMost(12),
            )
        }
        val next = if (ok) {
            val latency = latencyMs.coerceAtLeast(0)
            val average = if (old.successes == 0 || old.averageLatencyMs <= 0) latency
            else ((old.averageLatencyMs * 4L) + latency) / 5L // stable EMA, not a last-sample jump
            old.copy(
                successes = old.successes + 1,
                averageLatencyMs = average,
                consecutiveFailures = 0,
                updatedAtMs = nowMs,
            )
        } else {
            old.copy(
                failures = old.failures + 1,
                consecutiveFailures = (old.consecutiveFailures + 1).coerceAtMost(12),
                updatedAtMs = nowMs,
            )
        }
        entries[id] = next
        trim()
    }

    @Synchronized
    fun get(id: String): Entry? = entries[id]

    /** Recent failure streak is a stronger signal than an old aggregate ratio. */
    @Synchronized
    fun penalty(id: String, nowMs: Long = System.currentTimeMillis()): Int {
        val entry = entries[id] ?: return 0
        if (nowMs - entry.updatedAtMs > 24L * 60L * 60L * 1000L) return 0
        return entry.consecutiveFailures.coerceIn(0, 4)
    }

    /** History overrides source-advertised reliability only after a real full-tunnel outcome exists. */
    @Synchronized
    fun enrich(node: FeedNode): FeedNode {
        val entry = entries[node.id] ?: return node
        if (entry.samples <= 0) return node
        return node.copy(
            reliability = entry.score,
            samples = entry.samples,
            // Keep today's scan RTT when present; a historical RTT is only a fallback for a cached row.
            latencyMs = node.latencyMs ?: entry.averageLatencyMs.takeIf { it > 0 },
        )
    }

    /** Compact tab-separated private-file format. Node ids are restricted before import/export. */
    @Synchronized
    fun encode(): String = entries.entries
        .sortedByDescending { it.value.updatedAtMs }
        .take(maxEntries)
        .joinToString("\n") { (id, e) ->
            listOf(id, e.successes, e.failures, e.averageLatencyMs, e.consecutiveFailures, e.updatedAtMs)
                .joinToString("\t")
        }

    @Synchronized
    fun restore(text: String) {
        entries.clear()
        text.lineSequence().take(maxEntries * 2).forEach { line ->
            val p = line.trim().split('\t')
            if (p.size != 6 || !validId(p[0])) return@forEach
            val success = p[1].toIntOrNull()?.coerceIn(0, 100) ?: return@forEach
            val failure = p[2].toIntOrNull()?.coerceIn(0, 100) ?: return@forEach
            val latency = p[3].toLongOrNull()?.coerceIn(0, 120_000) ?: return@forEach
            val streak = p[4].toIntOrNull()?.coerceIn(0, 12) ?: return@forEach
            val updated = p[5].toLongOrNull()?.coerceAtLeast(0) ?: return@forEach
            entries[p[0].lowercase(Locale.ROOT)] = Entry(success, failure, latency, streak, updated)
        }
        trim()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun trim() {
        if (entries.size <= maxEntries) return
        val keep = entries.entries.sortedByDescending { it.value.updatedAtMs }.take(maxEntries)
        entries.clear()
        keep.forEach { entries[it.key] = it.value }
    }

    private fun validId(id: String): Boolean = id.length in 8..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
