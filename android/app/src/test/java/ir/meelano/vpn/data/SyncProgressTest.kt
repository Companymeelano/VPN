package ir.meelano.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProgressTest {

    @Test fun `idle has no progress and is not active`() {
        val p = SyncProgress.IDLE
        assertFalse(p.active)
        assertEquals(0f, p.overallFraction, 1e-6f)
        assertEquals("0%", p.percentText)
    }

    @Test fun `an empty stage is complete, not stuck at zero`() {
        val p = SyncProgress(SyncProgress.Stage.FETCH, done = 0, total = 0)
        assertEquals(1f, p.stageFraction, 1e-6f)
    }

    @Test fun `stage fraction is clamped`() {
        assertEquals(0.5f, SyncProgress(SyncProgress.Stage.PROBE, 1, 2).stageFraction, 1e-6f)
        assertEquals(1f, SyncProgress(SyncProgress.Stage.PROBE, 9, 2).stageFraction, 1e-6f)
        assertEquals(0f, SyncProgress(SyncProgress.Stage.PROBE, -1, 2).stageFraction, 1e-6f)
    }

    @Test fun `overall grows monotonically through the stages`() {
        var last = -1f
        for (stage in SyncProgress.STAGES) {
            val start = SyncProgress(stage, 0, 10).overallFraction
            val end = SyncProgress(stage, 10, 10).overallFraction
            assertTrue("$stage start $start must be >= $last", start >= last - 1e-6f)
            assertTrue("$stage end must grow", end > start)
            last = end
        }
        assertEquals(1f, SyncProgress(SyncProgress.Stage.RANK, 1, 1).overallFraction, 1e-6f)
    }

    @Test fun `late stages outweigh early ones`() {
        // half of the heaviest stage (SCAN) must read more than half of the lightest (PARSE)
        val halfScan = SyncProgress(SyncProgress.Stage.SCAN, 1, 2).overallFraction
        val halfParse = SyncProgress(SyncProgress.Stage.PARSE, 1, 2).overallFraction
        assertTrue(halfScan > halfParse)
    }

    @Test fun `percent text is whole numbers of the overall run`() {
        // half of SCAN = 0.25 + 0.05 + 0.30 + 0.35*0.5 = 0.775 → 77 %
        assertEquals("77%", SyncProgress(SyncProgress.Stage.SCAN, 5, 10).percentText)
        assertEquals("25%", SyncProgress(SyncProgress.Stage.FETCH, 4, 4).percentText)
    }
}
