package ir.meelano.vpn.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery table, every row that decided a real bug. These tests are the contract the service
 * and the health monitor implement; if a row here changes, the corresponding reconnect behaviour
 * on a phone changes with it.
 */
class RecoveryPolicyTest {

    private val un = RecoveryPolicy.Underlay.UNCHANGED
    private val lost = RecoveryPolicy.Underlay.LOST
    private val restored = RecoveryPolicy.Underlay.RESTORED
    private val switched = RecoveryPolicy.Underlay.SWITCHED

    /* ------------------------------------------------------------ classify */

    @Test fun `classify keeps a callback that changed nothing`() {
        assertEquals(un, RecoveryPolicy.classify("wifi", "wifi"))
        assertEquals(un, RecoveryPolicy.classify<String?>(null, null))
        // a removed network we did not track: nothing moved
        assertEquals(un, RecoveryPolicy.classify("cell", "cell"))
    }

    @Test fun `classify detects total loss`() {
        assertEquals(lost, RecoveryPolicy.classify("wifi", null))
    }

    @Test fun `classify detects restoration from zero networks`() {
        // Bug 1 (2.6.0): this event was dropped by an `oldPrimary == null` early return.
        assertEquals(restored, RecoveryPolicy.classify(null, "cell"))
    }

    @Test fun `classify detects a primary switch with a network remaining`() {
        assertEquals(switched, RecoveryPolicy.classify("wifi", "cell"))
    }

    /* ------------------------------------------------------------ onUnderlayEvent */

    @Test fun `unchanged is always a no-op`() {
        for (connected in listOf(true, false)) {
            for (recoverable in listOf(true, false)) {
                for (marked in listOf(true, false)) {
                    assertEquals(
                        RecoveryPolicy.Action.NONE,
                        RecoveryPolicy.onUnderlayEvent(un, connected, recoverable, marked),
                    )
                }
            }
        }
    }

    @Test fun `total loss under a live tunnel marks the outage`() {
        assertEquals(
            RecoveryPolicy.Action.MARK_OUTAGE,
            RecoveryPolicy.onUnderlayEvent(lost, phaseConnected = true, recoverable = false, outageMarked = false),
        )
    }

    @Test fun `total loss while not connected is a no-op`() {
        assertEquals(
            RecoveryPolicy.Action.NONE,
            RecoveryPolicy.onUnderlayEvent(lost, phaseConnected = false, recoverable = false, outageMarked = false),
        )
        assertEquals(
            RecoveryPolicy.Action.NONE,
            RecoveryPolicy.onUnderlayEvent(lost, phaseConnected = false, recoverable = true, outageMarked = true),
        )
    }

    @Test fun `restore while connected is left to the health monitor`() {
        // Re-dialing here would tear down a flap-surviving tunnel; the monitor proves the rest.
        assertEquals(
            RecoveryPolicy.Action.NONE,
            RecoveryPolicy.onUnderlayEvent(restored, phaseConnected = true, recoverable = false, outageMarked = true),
        )
    }

    @Test fun `restore consumes the outage marker`() {
        // Bug 1 (2.6.0): the exact row that was missing - Handshake(0.2f) stall, network returns.
        assertEquals(
            RecoveryPolicy.Action.RECONNECT_RESTORED,
            RecoveryPolicy.onUnderlayEvent(restored, phaseConnected = false, recoverable = false, outageMarked = true),
        )
    }

    @Test fun `restore after a failed burn re-dials`() {
        // all_candidates_failed while the radio was down; the radio is back.
        assertEquals(
            RecoveryPolicy.Action.RECONNECT_RESTORED,
            RecoveryPolicy.onUnderlayEvent(restored, phaseConnected = false, recoverable = true, outageMarked = false),
        )
    }

    @Test fun `restore during an in-flight connect does not supersede it`() {
        assertEquals(
            RecoveryPolicy.Action.NONE,
            RecoveryPolicy.onUnderlayEvent(restored, phaseConnected = false, recoverable = false, outageMarked = false),
        )
    }

    @Test fun `switch under a live tunnel re-dials`() {
        // The old sockets are bound to the dead path; protect() cannot revive them.
        assertEquals(
            RecoveryPolicy.Action.RECONNECT_CHANGED,
            RecoveryPolicy.onUnderlayEvent(switched, phaseConnected = true, recoverable = false, outageMarked = false),
        )
    }

    @Test fun `switch with a stalled or failed tunnel still re-dials`() {
        assertEquals(
            RecoveryPolicy.Action.RECONNECT_RESTORED,
            RecoveryPolicy.onUnderlayEvent(switched, phaseConnected = false, recoverable = true, outageMarked = false),
        )
        assertEquals(
            RecoveryPolicy.Action.RECONNECT_RESTORED,
            RecoveryPolicy.onUnderlayEvent(switched, phaseConnected = false, recoverable = false, outageMarked = true),
        )
    }

    @Test fun `switch during an in-flight connect does not supersede it`() {
        assertEquals(
            RecoveryPolicy.Action.NONE,
            RecoveryPolicy.onUnderlayEvent(switched, phaseConnected = false, recoverable = false, outageMarked = false),
        )
    }

    /* ------------------------------------------------------------ shouldRepublishConnected */

    @Test fun `healthy proof re-publishes a knocked-off phase`() {
        // Bug 2 (2.6.0): Handshake(0.2f) after a blip stayed forever; isRunning() lied.
        assertTrue(RecoveryPolicy.shouldRepublishConnected(generationMatches = true, phaseConnected = false))
    }

    @Test fun `healthy proof never overwrites an already-connected phase`() {
        assertFalse(RecoveryPolicy.shouldRepublishConnected(generationMatches = true, phaseConnected = true))
    }

    @Test fun `a superseded monitor never writes the phase of a newer connect`() {
        assertFalse(RecoveryPolicy.shouldRepublishConnected(generationMatches = false, phaseConnected = false))
        assertFalse(RecoveryPolicy.shouldRepublishConnected(generationMatches = false, phaseConnected = true))
    }

    /* ------------------------------------------------------------ isRetryableFailure */

    @Test fun `weather failures are retryable`() {
        assertTrue(RecoveryPolicy.isRetryableFailure("all_candidates_failed"))
        assertTrue(RecoveryPolicy.isRetryableFailure("all_candidates_failed:no_internet_through_tunnel"))
        assertTrue(RecoveryPolicy.isRetryableFailure("tunnel_error"))
        assertTrue(RecoveryPolicy.isRetryableFailure("establish_returned_null"))
    }

    @Test fun `deterministic failures are not retryable`() {
        assertFalse(RecoveryPolicy.isRetryableFailure("permission_required"))
        assertFalse(RecoveryPolicy.isRetryableFailure("profile_missing"))
        assertFalse(RecoveryPolicy.isRetryableFailure("no_candidates"))
        assertFalse(RecoveryPolicy.isRetryableFailure("no_supported_candidates"))
        assertFalse(RecoveryPolicy.isRetryableFailure("core_not_linked"))
    }
}
