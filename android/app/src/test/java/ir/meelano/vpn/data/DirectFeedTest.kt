package ir.meelano.vpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror/fallback plumbing of the on-device free builder, tested with fake networks. `DirectFeed`
 * only sees IO through lambdas, which is exactly what makes a "GitHub is filtered on this path" build
 * reproducible on a JVM in CI.
 *
 * Each test hands `buildFree` a url→body map; an absent key throws (the network answer on a poisoned
 * route) and is turned into `null` inside the builder, the same as a timeout from OkHttp.
 */
class DirectFeedTest {

    private class Net(val bodies: Map<String, String>) {
        val calls = ArrayList<String>()
        val fetch: suspend (DirectFeed.Upstream) -> String? = { u ->
            calls += u.url
            bodies[u.url] ?: throw IllegalStateException("route poisoned")
        }
    }

    private val probe: suspend (List<FeedNode>) -> Map<String, Long> = { nodes ->
        nodes.associate { it.id to 120L }
    }

    private fun vless(host: String, port: Int = 443) =
        "vless://11111111-2222-3333-4444-555555555555@$host:$port?security=tls&type=tcp#$host"

    /* ------------------------------------------------------------------ tiers */

    @Test
    fun healthyPrimaries_neverTouchTheFallbackTier() = runBlocking {
        val net = Net(
            mapOf(
                "https://a.example/x" to vless("203.0.113.11"),
                "https://b.example/y" to vless("203.0.113.12"),
            )
        )
        val boom = DirectFeed.Upstream("fallback", "https://fallback.example/z")
        val res = DirectFeed.buildFree(
            sources = listOf(
                DirectFeed.Upstream("a", "https://a.example/x"),
                DirectFeed.Upstream("b", "https://b.example/y"),
            ),
            lowWatermark = 0,                       // primaries are healthy; second tier must stay shut
            fallbacks = listOf(boom),
            fetch = net.fetch,
            probe = probe,
        )
        assertEquals(2, res.nodes.size)
        assertEquals(2, res.sourcesOk)
        assertFalse("fallback tier was fetched although primaries were fine", net.calls.any { it.contains("fallback") })
    }

    @Test
    fun thinPrimaries_autoEngageTheFallbackTier() = runBlocking {
        val net = Net(
            mapOf(
                "https://fn.example/daily" to vless("198.51.100.7") + "\n" + vless("198.51.100.8"),
            )
        )
        val res = DirectFeed.buildFree(
            sources = listOf(DirectFeed.Upstream("dead-primary", "https://dead.example/wiki")),
            lowWatermark = 2,
            fallbacks = listOf(DirectFeed.Upstream("fn", "https://fn.example/daily")),
            fetch = net.fetch,
            probe = probe,
        )
        assertEquals(2, res.nodes.size)
        assertEquals(1, res.sourcesOk)
        assertEquals(2, res.sourcesTotal)
        assertTrue(res.notes.any { it.contains("منابع جایگزین") })
    }

    /* ------------------------------------------------------------------ mirror chains */

    @Test
    fun mirrorChain_walksToTheNextHostAndSaysSo() = runBlocking {
        val net = Net(mapOf("https://cdn.example/doc" to vless("203.0.113.21")))
        val res = DirectFeed.buildFree(
            sources = listOf(
                DirectFeed.Upstream("m", "https://raw.example/doc", mirrors = listOf("https://cdn.example/doc")),
            ),
            lowWatermark = 0,
            fallbacks = emptyList(),
            fetch = net.fetch,
            probe = probe,
        )
        assertEquals(listOf("https://raw.example/doc", "https://cdn.example/doc"), net.calls)
        assertEquals(1, res.nodes.size)
        assertEquals(1, res.sourcesOk)
        assertTrue("mirror use must be announced in the notes", res.notes.any { it.contains("jsdelivr") })
    }

    /* ------------------------------------------------------------------ protocol honesty */

    @Test
    fun undialableRows_neverBecomeTappableNodes() = runBlocking {
        val body = listOf(
            "hysteria2://secretpass@203.0.113.31:443?sni=example.com#hy2-row",
            "socks5://user:pass@203.0.113.32:1080#socks-row",
            vless("203.0.113.33"),
        ).joinToString("\n")
        val net = Net(mapOf("https://mixed.example/list" to body))
        val res = DirectFeed.buildFree(
            sources = listOf(DirectFeed.Upstream("mixed", "https://mixed.example/list")),
            lowWatermark = 0,
            fallbacks = emptyList(),
            fetch = net.fetch,
            probe = probe,
        )
        assertEquals(2, res.nodes.size)
        assertEquals(setOf("vless", "socks5"), res.nodes.map { it.proto }.toSet())
        assertTrue(res.nodes.all { it.proto in DirectFeed.TUNNEL_PROTOS })
    }

    @Test
    fun tunnelProtos_areExactlyWhatTheCoreCanWrite() {
        // The pinned Xray writer also supports raw HTTP and SOCKS5; SOCKS4/hy2 stay out.
        assertEquals(setOf("vless", "vmess", "trojan", "ss", "shadowsocks", "http", "socks5", "socks"), DirectFeed.TUNNEL_PROTOS)
    }

    @Test
    fun bareProxySourcesUseTheirDeclaredProtocol() = runBlocking {
        val body = "176.9.10.11:8080\n176.9.10.12:8080"
        val net = Net(mapOf("https://proxy.example/list" to body))
        val res = DirectFeed.buildFree(
            sources = listOf(DirectFeed.Upstream("s", "https://proxy.example/list", kind = DirectFeed.Kind.PROXY, protocolHint = "socks5")),
            lowWatermark = 0,
            fallbacks = emptyList(),
            fetch = net.fetch,
            probe = probe,
        )
        assertEquals(2, res.nodes.size)
        assertTrue(res.nodes.all { it.proto == "socks5" && it.network == "tcp" && !it.supportsUdp })
    }

    /* ------------------------------------------------------------------ total failure */

    @Test
    fun everythingDead_explainsItselfAndPointsAtVip() = runBlocking {
        val net = Net(emptyMap())
        val res = DirectFeed.buildFree(
            sources = listOf(DirectFeed.Upstream("p", "https://dead-primary.example/x")),
            lowWatermark = 1,
            fallbacks = listOf(DirectFeed.Upstream("f", "https://dead-fallback.example/y")),
            fetch = net.fetch,
            probe = probe,
        )
        assertTrue(res.nodes.isEmpty())
        assertEquals(0, res.sourcesOk)
        assertEquals(2, res.sourcesTotal)
        assertTrue(res.notes.any { it.contains("منابع جایگزین") })
        assertTrue(res.notes.any { it.contains("اشتراک VIP") })
    }
}
