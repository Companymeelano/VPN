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

    private val deepProbe: suspend (List<FeedNode>) -> Map<String, ProxyScanner.Verdict> = { nodes ->
        nodes.associate { node ->
            node.id to ProxyScanner.Verdict(
                ProxyScanner.CLEAN,
                180L,
                1f,
                "exact_204_all",
                ProxyScanner.ENDPOINTS.map { ProxyScanner.Check(it.id, 204, 180L) },
            )
        }
    }

    private fun vless(host: String, port: Int = 443) =
        "vless://11111111-2222-3333-4444-555555555555@$host:$port?security=tls&type=tcp#$host"

    /* ------------------------------------------------------------------ audited defaults */

    @Test
    fun publicFeedUserAgentIsAsciiAndAcceptedByOkHttp() {
        val value = publicFeedUserAgent("2.4.3 • آزمایشی")
        assertEquals("MA-VPN/2.4.3", value)
        assertTrue(value.all { it.code in 0x21..0x7e })
        // Regression for the on-device failure: Request.Builder.header threw before any socket was
        // opened when the branded M•A value was used, making every mirror look broken at once.
        okhttp3.Request.Builder()
            .url("https://api.github.com/repos/example/example/contents/list.txt")
            .header("User-Agent", value)
            .build()
    }

    @Test
    fun builtInDiscoveryUsesOnlyPublicGithubCdnOrBuildSnapshotRoutes() {
        val all = DirectFeed.DEFAULTS + DirectFeed.FALLBACKS
        assertTrue(all.size >= 12)
        assertTrue(all.all { source ->
            source.url.startsWith("https://api.github.com/") || source.url.startsWith("asset://")
        })
        assertTrue(all.flatMap { it.mirrors }.all { route ->
            route.startsWith("https://cdn.jsdelivr.net/") ||
                route.startsWith("https://fastly.jsdelivr.net/") ||
                route.startsWith("https://gcore.jsdelivr.net/") ||
                route.startsWith("https://raw.githubusercontent.com/")
        })
        assertTrue(DirectFeed.TUNNEL_SOURCES.any { it.id == "radikal-verified" })
        assertTrue(DirectFeed.PROXY_SOURCES.mapNotNull { it.protocolHint }.toSet().containsAll(setOf("http", "socks5")))
    }

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
        assertTrue("mirror use must be announced in the notes", res.notes.any { it.contains("آینه") })
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
            deepProbe = deepProbe,
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
            deepProbe = deepProbe,
        )
        assertEquals(2, res.nodes.size)
        assertTrue(res.nodes.all { it.proto == "socks5" && it.network == "tcp" && !it.supportsUdp })
        assertTrue(res.nodes.all { it.platformStatus == ProxyScanner.CLEAN && it.isUsable })
    }

    @Test
    fun proxyScanQuotaIsRoundRobinAcrossSources() = runBlocking {
        val urls = (1..4).map { "https://proxy.example/$it" }
        val sourceList = urls.mapIndexed { index, url ->
            DirectFeed.Upstream("p$index", url, kind = DirectFeed.Kind.PROXY, protocolHint = "http")
        }
        val bodies = urls.mapIndexed { index, url ->
            url to (1..6).joinToString("\n") { row -> "176.9.${index + 1}.${row}:8080" }
        }.toMap()
        var scanned: List<FeedNode> = emptyList()
        DirectFeed.buildFree(
            sources = sourceList,
            lowWatermark = 0,
            fallbacks = emptyList(),
            proxyScanLimit = 4,
            fetch = Net(bodies).fetch,
            probe = probe,
            deepProbe = { nodes -> scanned = nodes; this@DirectFeedTest.deepProbe(nodes) },
        )
        assertEquals(4, scanned.size)
        assertEquals(setOf("176.9.1.1", "176.9.2.1", "176.9.3.1", "176.9.4.1"), scanned.map { it.host }.toSet())
    }

    @Test
    fun proxyVerdictsStaySeparateAndOnlyCleanRowsAreUsable() = runBlocking {
        val body = "176.9.10.11:8080\n176.9.10.12:8080\n176.9.10.13:8080\n176.9.10.14:8080"
        val source = DirectFeed.Upstream(
            "http", "https://proxy.example/list",
            kind = DirectFeed.Kind.PROXY, protocolHint = "http",
        )
        val statuses = listOf(
            ProxyScanner.CLEAN,
            ProxyScanner.ROBOT_BLOCKED,
            ProxyScanner.PLATFORM_BLOCKED,
            ProxyScanner.DEAD,
        )
        val res = DirectFeed.buildFree(
            sources = listOf(source),
            lowWatermark = 0,
            fallbacks = emptyList(),
            fetch = Net(mapOf(source.url to body)).fetch,
            probe = probe,
            deepProbe = { nodes ->
                nodes.mapIndexed { index, node ->
                    val status = statuses[index]
                    node.id to ProxyScanner.Verdict(
                        status,
                        if (status == ProxyScanner.DEAD) null else 200L,
                        if (status == ProxyScanner.CLEAN) 1f else 1f / 3f,
                        status,
                        ProxyScanner.ENDPOINTS.mapIndexed { i, e ->
                            ProxyScanner.Check(e.id, if (status == ProxyScanner.CLEAN || i == 0) 204 else null, 200L)
                        },
                    )
                }.toMap()
            },
        )
        assertEquals(4, res.nodes.size) // rejected rows remain visible in the dedicated diagnostics list
        assertEquals(1, res.nodes.count { it.isUsable })
        assertEquals(1, res.proxyClean)
        assertEquals(1, res.proxyRobotBlocked)
        assertEquals(1, res.proxyPlatformBlocked)
        assertEquals(1, res.proxyDead)
    }

    @Test
    fun parsedButTcpInconclusivePrimaryIsRetainedAndFallbackIsPrioritised() = runBlocking {
        val primaryUrl = "https://primary.example/list"
        val fallbackUrl = "https://fallback.example/list"
        val primaryBody = (1..20).joinToString("\n") { vless("203.0.113.${it + 20}") }
        val fallbackBody = (1..3).joinToString("\n") { vless("198.51.100.${it + 20}") }
        val net = Net(mapOf(primaryUrl to primaryBody, fallbackUrl to fallbackBody))
        val res = DirectFeed.buildFree(
            sources = listOf(DirectFeed.Upstream("primary", primaryUrl)),
            lowWatermark = 2,
            probeLimit = 4,
            fallbacks = listOf(DirectFeed.Upstream("fallback", fallbackUrl)),
            fetch = net.fetch,
            probe = { nodes ->
                nodes.associate { node -> node.id to if (node.host.startsWith("198.51.100.")) 90L else -1L }
            },
        )
        assertTrue(net.calls.contains(fallbackUrl))
        assertEquals(8, res.nodes.size) // four primary + one extra primary probe, plus all three fallback rows
        assertEquals(3, res.nodes.count { it.host.startsWith("198.51.100.") })
        assertEquals(5, res.nodes.count { it.platformStatus == "tcp_inconclusive" })
        assertTrue("TCP-positive fallback rows sort ahead of inconclusive primaries", res.nodes.take(3).all {
            it.host.startsWith("198.51.100.") && it.platformStatus == "tcp_reachable"
        })
    }

    @Test
    fun allMinusOneTcpHintsStillPublishCandidatesForTheFullXrayProbe() = runBlocking {
        val source = DirectFeed.Upstream("verified", "https://primary.example/list")
        val body = (1..6).joinToString("\n") { vless("203.0.113.${it + 70}") }
        val res = DirectFeed.buildFree(
            sources = listOf(source),
            fallbacks = emptyList(),
            lowWatermark = 0,
            fetch = Net(mapOf(source.url to body)).fetch,
            probe = { nodes -> nodes.associate { it.id to -1L } },
        )
        assertEquals(6, res.nodes.size)
        assertEquals(6, res.probed)
        assertEquals(0, res.tunnelTcpOk)
        assertTrue(res.nodes.all {
            it.grade == "D" && it.latencyMs == null && it.platformStatus == "tcp_inconclusive" && it.isUsable
        })
        assertTrue(res.notes.any { it.contains("Xray") })
    }

    @Test
    fun fetchTelemetryNamesSourceRouteCategoryAndBytesWithoutUrls() = runBlocking {
        val source = DirectFeed.Upstream(
            "source-id", "https://api.example/list", mirrors = listOf("https://mirror.example/list")
        )
        val net = Net(mapOf("https://mirror.example/list" to vless("203.0.113.99")))
        val res = DirectFeed.buildFree(
            sources = listOf(source),
            fallbacks = emptyList(),
            lowWatermark = 0,
            fetch = net.fetch,
            probe = probe,
        )
        assertEquals(2, res.fetchOutcomes.size)
        assertEquals("primary", res.fetchOutcomes[0].route)
        assertTrue(res.fetchOutcomes[0].category.startsWith("exception_"))
        assertEquals("mirror_1", res.fetchOutcomes[1].route)
        assertEquals("ok", res.fetchOutcomes[1].category)
        assertTrue(res.fetchOutcomes[1].bytes > 0)
        assertTrue(res.fetchOutcomes.none { it.sourceId.contains("://") || it.route.contains("://") })
    }

    @Test
    fun veryLargeSourceIsBoundedByParseCapAndDoesNotStarveOtherSources() = runBlocking {
        val largeUrl = "https://large.example/list"
        val smallUrl = "https://small.example/list"
        val large = (1..5_000).joinToString("\n") { row ->
            vless("203.0.${(row / 250) + 1}.${(row % 250) + 1}", 443)
        }
        val smallNode = vless("198.51.100.222")
        val res = DirectFeed.buildFree(
            sources = listOf(
                DirectFeed.Upstream("large", largeUrl),
                DirectFeed.Upstream("small", smallUrl),
            ),
            parseCap = 32,
            probeLimit = 8,
            maxNodes = 8,
            lowWatermark = 0,
            fallbacks = emptyList(),
            fetch = Net(mapOf(largeUrl to large, smallUrl to smallNode)).fetch,
            probe = probe,
        )
        assertEquals(8, res.nodes.size)
        assertTrue(res.nodes.any { it.host == "198.51.100.222" })
        assertTrue(res.parsedTunnels <= 32)
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
        assertTrue(res.notes.any { it.contains("هیچ منبع عمومی") })
    }
}
