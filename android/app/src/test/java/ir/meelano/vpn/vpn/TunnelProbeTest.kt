package ir.meelano.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProbeTest {
    private fun result(cf: Int?, google: Int?, gstatic: Int?) = TunnelProbe.classify(
        listOf(
            TunnelProbe.Check("cloudflare", cf, 10),
            TunnelProbe.Check("google", google, 20),
            TunnelProbe.Check("gstatic", gstatic, 30),
        )
    )

    @Test
    fun exact204FromEveryPlatformIsClean() {
        val r = result(204, 204, 204)
        assertTrue(r.clean)
        assertTrue(r.usable)
        assertTrue(r.internet)
        assertFalse(r.platformBlocked)
        assertEquals("clean", r.reason)
        // median, not slowest: one unlucky endpoint must not be the node's reported speed
        assertEquals(20, r.latencyMs)
    }

    @Test
    fun reachableCloudflareWithBlockedGoogleIsSeparated() {
        val r = result(204, 403, null)
        assertFalse(r.clean)
        assertTrue(r.internet)
        assertTrue(r.platformBlocked)
        assertEquals("platform_blocked", r.reason)
    }

    @Test
    fun oneDroppedPlatformProbeDoesNotVetoAWorkingTunnel() {
        // The Irancell case: Cloudflare + gstatic answer, Google's probe dies to mobile
        // jitter. Internet demonstrably flows, so connect/health must accept the tunnel.
        val r = result(204, null, 204)
        assertFalse(r.clean)
        assertTrue(r.usable)
    }

    @Test
    fun cloudflareAloneIsUsableButASingleOtherEndpointIsNot() {
        assertTrue(result(204, null, null).usable)
        assertFalse(result(null, 204, null).usable)
        assertEquals(2, result(204, null, 204).okCount)
    }

    @Test
    fun redirectOrPortalIsNeverCountedAsHealthy() {
        val r = result(302, 200, 200)
        assertFalse(r.clean)
        assertFalse(r.platformBlocked)
        assertEquals("unexpected_status", r.reason)
    }

    @Test
    fun noResponsesMeansNoTunnelInternet() {
        val r = result(null, null, null)
        assertFalse(r.clean)
        assertFalse(r.usable)
        assertFalse(r.internet)
        assertEquals("no_internet_through_tunnel", r.reason)
    }
}
