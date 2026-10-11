package ir.meelano.vpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyScannerTest {

    @Test
    fun scannerUsesTheCanonicalAndroidGstatic204Endpoint() {
        assertEquals("connectivitycheck.gstatic.com", ProxyScanner.ENDPOINTS.single { it.id == "gstatic" }.host)
    }

    private fun checks(vararg codes: Int?): List<ProxyScanner.Check> =
        ProxyScanner.ENDPOINTS.mapIndexed { index, endpoint ->
            val code = codes.getOrNull(index)
            ProxyScanner.Check(endpoint.id, code, if (code == null) null else 100L + index)
        }

    @Test
    fun allExact204IsTheOnlyCleanVerdict() {
        val v = ProxyScanner.classify(checks(204, 204, 204))
        assertEquals(ProxyScanner.CLEAN, v.status)
        assertTrue(v.usable)
        assertEquals(102L, v.latencyMs)
        assertEquals(1f, v.reliability)
    }

    @Test
    fun googleChallengeIsRobotBlockedNotHealthy() {
        val forbidden = ProxyScanner.classify(checks(204, 403, 403))
        val limited = ProxyScanner.classify(checks(204, 429, null))
        assertEquals(ProxyScanner.ROBOT_BLOCKED, forbidden.status)
        assertEquals(ProxyScanner.ROBOT_BLOCKED, limited.status)
        assertFalse(forbidden.usable)
    }

    @Test
    fun cloudflareWorksButGoogleDoesNotIsPlatformBlocked() {
        val v = ProxyScanner.classify(checks(204, null, null))
        assertEquals(ProxyScanner.PLATFORM_BLOCKED, v.status)
        assertEquals("google_unreachable_after_cloudflare", v.reason)
    }

    @Test
    fun noReachableEndpointAndProxyAuthAreDead() {
        assertEquals(ProxyScanner.DEAD, ProxyScanner.classify(checks(null, null, null)).status)
        val auth = ProxyScanner.classify(
            listOf(
                ProxyScanner.Check("cloudflare", code = 407),
                ProxyScanner.Check("google", error = "proxy_auth_failed"),
                ProxyScanner.Check("gstatic", error = "proxy_auth_failed"),
            )
        )
        assertEquals(ProxyScanner.DEAD, auth.status)
        assertEquals("proxy_auth_failed", auth.reason)
    }

    @Test
    fun scanUsesTheProxyForEveryEndpointAndHonoursLimit() = runBlocking {
        val nodes = (1..3).map {
            NodeUri.parseProxyLine("176.9.10.${it}:8080", "http")!!
        }
        val calls = ArrayList<Pair<String, String>>()
        val out = ProxyScanner.scan(
            nodes = nodes,
            limit = 2,
            concurrency = 2,
            protect = { true },
            request = { node, endpoint, _, _ ->
                synchronized(calls) { calls += node.id to endpoint.id }
                ProxyScanner.Check(endpoint.id, 204, 50L)
            },
        )
        assertEquals(2, out.size)
        assertEquals(6, calls.size)
        assertTrue(out.values.all { it.status == ProxyScanner.CLEAN })
    }

    @Test
    fun failedSocketProtectionCanNeverBecomeClean() = runBlocking {
        val node = NodeUri.parseProxyLine("176.9.10.11:8080", "http")!!
        val out = ProxyScanner.scan(
            nodes = listOf(node),
            limit = 1,
            concurrency = 1,
            protect = { false },
        )
        assertEquals(ProxyScanner.DEAD, out.getValue(node.id).status)
        assertTrue(out.getValue(node.id).checks.all { it.error == "socket_protect_failed" })
    }
}
