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
        assertTrue(r.internet)
        assertFalse(r.platformBlocked)
        assertEquals("clean", r.reason)
        assertEquals(30, r.latencyMs)
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
        assertFalse(r.internet)
        assertEquals("no_internet_through_tunnel", r.reason)
    }
}
