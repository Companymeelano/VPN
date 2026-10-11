package ir.meelano.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeHealthLedgerTest {

    @Test
    fun historySurvivesRoundTripAndFailureStreakAffectsPenalty() {
        val now = 1_800_000_000_000L
        val ledger = NodeHealthLedger()
        ledger.record("abcdef123456", true, 100, now)
        ledger.record("abcdef123456", true, 300, now + 1)
        ledger.record("abcdef123456", false, 0, now + 2)
        ledger.record("abcdef123456", false, 0, now + 3)

        val restored = NodeHealthLedger()
        restored.restore(ledger.encode())
        val entry = restored.get("abcdef123456")!!
        assertEquals(2, entry.successes)
        assertEquals(2, entry.failures)
        assertEquals(2, entry.consecutiveFailures)
        assertTrue(entry.averageLatencyMs in 100..300)
        assertEquals(2, restored.penalty("abcdef123456", now + 4))
    }

    @Test
    fun oneLuckySuccessDoesNotClaimPerfectLongTermReliability() {
        val ledger = NodeHealthLedger()
        ledger.record("abcdef123456", true, 120, 10)
        val score = ledger.get("abcdef123456")!!.score
        assertTrue(score > 0.6f)
        assertTrue(score < 1f)
    }

    @Test
    fun fileIsBoundedAndMalformedRowsAreIgnored() {
        val ledger = NodeHealthLedger(maxEntries = 2)
        ledger.restore(
            "bad row\n" +
                "abcdef123456\t1\t0\t100\t0\t1\n" +
                "abcdef123457\t1\t0\t100\t0\t2\n" +
                "abcdef123458\t1\t0\t100\t0\t3"
        )
        assertEquals(2, ledger.size())
        assertTrue(ledger.get("abcdef123458") != null)
    }
}
