package ir.meelano.vpn.data

/**
 * Real, per-stage progress of one feed sync — the numbers the 3D progress dials in the UI show.
 *
 * A spinner proves only that the app did not die; "۳/۸ منبع دریافت شد" is what makes a slow
 * mobile network feel like work is happening. Every stage carries (done, total) taken from the
 * code that is actually doing the work (sources pulled, nodes probed, proxies scanned) — never a
 * simulated ramp. A stage with total == 0 is skipped work and renders as instantly complete,
 * not as a stuck 0 %.
 */
data class SyncProgress(
    val stage: Stage = Stage.IDLE,
    val done: Int = 0,
    val total: Int = 0,
) {
    enum class Stage { IDLE, FETCH, PARSE, PROBE, SCAN, RANK }

    val active: Boolean get() = stage != Stage.IDLE

    /** Fraction of the *current* stage, clamped; empty stages read as done. */
    val stageFraction: Float
        get() = when {
            stage == Stage.IDLE -> 0f
            total <= 0 -> 1f
            else -> (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * Overall fraction across the five real stages, weighted by how long each one actually costs on
     * a phone: fetching is one HTTP round per source, parsing is free, probing dials every tunnel
     * candidate, scanning runs three full HTTPS requests per proxy. The weights are what stop the
     * bar from sitting at 90 % while the slowest stage is still at its start.
     */
    val overallFraction: Float
        get() {
            if (stage == Stage.IDLE) return 0f
            var acc = 0f
            var weight = 0f
            for (s in STAGES) {
                val w = WEIGHTS[s] ?: 0f
                if (s.ordinal < stage.ordinal) {
                    acc += w
                } else if (s == stage) {
                    acc += w * stageFraction
                }
                weight += w
            }
            return if (weight <= 0f) 0f else (acc / weight).coerceIn(0f, 1f)
        }

    val percentText: String
        get() = "${(overallFraction * 100).toInt()}%"

    companion object {
        /** Display and accumulation order. */
        val STAGES = listOf(Stage.FETCH, Stage.PARSE, Stage.PROBE, Stage.SCAN, Stage.RANK)

        private val WEIGHTS = mapOf(
            Stage.FETCH to 0.25f,
            Stage.PARSE to 0.05f,
            Stage.PROBE to 0.30f,
            Stage.SCAN to 0.35f,
            Stage.RANK to 0.05f,
        )

        val IDLE = SyncProgress()

        val DONE = SyncProgress(Stage.RANK, 1, 1)
    }
}
