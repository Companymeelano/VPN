package ir.meelano.vpn.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe budgets, pinned down. These numbers are the difference between "the app found my
 * working servers" and "the app says nothing connects on Irancell": every value here was a
 * fixed constant before 2.6.0 (TCP 1200 ms / TLS 2000 ms), calibrated on good Wi-Fi.
 */
class ProbePolicyTest {

    @Test
    fun unknownNetworkGetsTheMobileSafeMiddle() {
        assertEquals(2_200, ProbePolicy.tcpTimeoutMs())
        assertEquals(2_200, ProbePolicy.tcpTimeoutMs(-1L))
        assertEquals(3_200, ProbePolicy.tlsTimeoutMs())
    }

    @Test
    fun fastNetworksStaySnappy() {
        assertEquals(1_500, ProbePolicy.tcpTimeoutMs(80L))   // floored, not 4*80=320 ms of false negatives
        assertEquals(2_200, ProbePolicy.tlsTimeoutMs(80L))
    }

    @Test
    fun slowCarriersGetTheTimeTheirPhysicsNeeds() {
        assertEquals(2_400, ProbePolicy.tcpTimeoutMs(600L))
        assertEquals(3_500, ProbePolicy.tcpTimeoutMs(900L))
        assertEquals(3_800, ProbePolicy.tlsTimeoutMs(500L))
    }

    @Test
    fun budgetsAreCappedSoADeadNetworkCannotStallARefresh() {
        assertEquals(3_500, ProbePolicy.tcpTimeoutMs(60_000L))
        assertEquals(5_000, ProbePolicy.tlsTimeoutMs(60_000L))
        assertTrue(ProbePolicy.awaitCapMs(2_200) > 2_200L)
    }
}
