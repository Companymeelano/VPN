package ir.meelano.vpn.vpn

import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.net.Tune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract between this app and whatever core is linked. These assertions are the reason
 * `MEELANO_CORE_LINKED` can ever become true: a profile the engine cannot parse is indistinguishable
 * from a dead server on a phone, and it is perfectly distinguishable here, in milliseconds.
 *
 * Deliberately free of Robolectric and of `org.json`: the plan is plain Maps/Lists (see CoreProfiles.plan)
 * and that is what we test. Only the last test touches the string renderer, and it checks the failure
 * mode that actually bit us once — `{address=1.2.3.4}` from a non-recursing JSONObject(Map).
 */
class CoreProfilesTest {

    private fun node(
        proto: String = "vless",
        tls: String? = "reality",
        network: String? = "tcp",
        flow: String? = null,
        alpn: String? = null,
        pbk: String? = "PBKabc",
        sid: String? = "01234567",
        path: String? = null,
        hostHeader: String? = null,
        sni: String? = "www.speedtest.net",
        supportsUdp: Boolean = true,
    ) = FeedNode(
        id = "n1", slot = 1, name = "M•A 01", subtitle = "", cc = "DE", kind = "vip", tier = "vip",
        proto = proto, host = "185.1.2.3", port = 443, tls = tls, network = network, sni = sni,
        path = path, hostHeader = hostHeader, alpn = alpn, flow = flow, pbk = pbk, sid = sid,
        fingerprint = null, userId = "11111111-2222-3333-4444-555555555555", alterId = 0,
        password = null, username = null, method = null, cipher = null, insecure = false,
        supportsUdp = supportsUdp, raw = null, grade = "A", latencyMs = 180L, reliability = 0.9f,
        samples = 4, config = null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.path(vararg keys: String): Any? {
        var cur: Any? = this
        for (k in keys) {
            cur = (cur as? Map<String, Any?>)?.get(k) ?: return null
        }
        return cur
    }

    @Suppress("UNCHECKED_CAST")
    private fun profile(node: FeedNode, tune: Tune = Tune.Default, engine: CoreEngine = CoreEngine.XRAY): Map<String, Any?> =
        CoreProfiles.plan(node, tune, engine)

    @Suppress("UNCHECKED_CAST")
    private fun outbound(p: Map<String, Any?>): Map<String, Any?> =
        ((p["outbounds"] as List<Any?>)[0]) as Map<String, Any?>

    @Test
    fun xrayDialectUsesTheNamesXrayActuallyParses() {
        val p = profile(node())
        val o = outbound(p)
        assertEquals("vless", o["protocol"])
        assertEquals("proxy", o["tag"])
        // the two renames that make or break a link: `stream`/`reality` are sing-box, not Xray
        assertNull(o["stream"])
        assertNull(o["reality"])
        assertEquals("reality", o.path("streamSettings", "security"))
        assertEquals("PBKabc", o.path("streamSettings", "realitySettings", "publicKey"))
        assertEquals("01234567", o.path("streamSettings", "realitySettings", "shortId"))
        assertEquals("www.speedtest.net", o.path("streamSettings", "realitySettings", "serverName"))
        assertEquals("chrome", o.path("streamSettings", "realitySettings", "fingerprint"))
        assertTrue(p.containsKey("inbounds"))
        assertTrue(p.containsKey("routing"))
    }

    @Test
    fun visionFlowOnTcpReality() {
        // Reality + vless + tcp -> Vision. It is the biggest throughput-and-block-resistance lever we
        // have, and the community feeds we ingest mostly omit it, so it is derived here (never copied).
        val vnext = (outbound(profile(node())).path("settings", "vnext") as List<*>)[0] as Map<*, *>
        assertEquals("xtls-rprx-vision", vnext["flow"])
        assertEquals("xudp", vnext["packet_encoding"])
        assertEquals("none", vnext["encryption"])
    }

    @Test
    fun visionFlowNeverOnWebsocket() {
        val n = node(network = "ws", path = "/ws")
        val vnext = (outbound(profile(n)).path("settings", "vnext") as List<*>)[0] as Map<*, *>
        // applying Vision on a stream transport is a guaranteed handshake failure, so it must be absent
        assertFalse("flow must not be set on ws", vnext.containsKey("flow"))
    }

    @Test
    fun feedSuppliedFlowWins() {
        val vnext = (outbound(profile(node(flow = "xtls-rprx-vision"))).path("settings", "vnext") as List<*>)[0] as Map<*, *>
        assertEquals("xtls-rprx-vision", vnext["flow"])
        val plain = (outbound(profile(node(tls = "tls", flow = "something-else"))).path("settings", "vnext") as List<*>)[0] as Map<*, *>
        assertEquals("something-else", plain["flow"])
    }

    @Test
    fun muxIsNeverAppliedWhereItBreaksThings() {
        val tune = Tune(mux = true, muxConcurrency = 8)
        // under Reality the mux handshake is what the anti-replay check trips on
        assertNull(outbound(profile(node(), tune)).path("mux"))
        // grpc already multiplexes streams; a second layer only adds head-of-line blocking
        assertNull(outbound(profile(node(tls = "tls", network = "grpc"), tune)).path("mux"))
        // on ws/tls it is exactly what we want: fewer handshakes on the wire
        val ws = outbound(profile(node(tls = "tls", network = "ws", path = "/"), tune))
        assertEquals(true, ws.path("mux", "enabled"))
        assertEquals(8, ws.path("mux", "concurrency"))
    }

    @Test
    fun lanBypassFollowsTheSwitch() {
        val off = profile(node(), Tune(allowLan = false))
        val on = profile(node(), Tune(allowLan = true))
        @Suppress("UNCHECKED_CAST")
        val rulesOff = off.path("routing", "rules") as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val rulesOn = on.path("routing", "rules") as List<Map<String, Any?>>
        assertTrue(rulesOff.any { it["outboundTag"] == "direct" && (it["ip"] as List<*>).contains("192.168.0.0/16") })
        assertFalse(rulesOn.any { it["outboundTag"] == "direct" })
        // a "block" outbound must exist: without it, kill-switch has nowhere to route
        assertTrue((off["outbounds"] as List<*>).any { (it as Map<*, *>)["tag"] == "block" })
        // DNS inside the tunnel, or the local resolver leaks every name we look up
        assertTrue(rulesOff.any { it["port"] == "53" && it["outboundTag"] == "proxy" })
    }

    @Test
    fun transportSettingsArePerTransport() {
        val ws = outbound(profile(node(tls = "tls", network = "ws", path = "/ray", hostHeader = "t.com")))
        assertEquals("/ray", ws.path("streamSettings", "wsSettings", "path"))
        assertEquals("t.com", ws.path("streamSettings", "wsSettings", "host"))
        val g = outbound(profile(node(tls = "tls", network = "grpc"), Tune(grpcMode = "multi", keepAliveSec = 15)))
        assertEquals(true, g.path("streamSettings", "grpcSettings", "multiMode"))
        assertEquals(30, g.path("streamSettings", "grpcSettings", "idle_timeout"))
        assertEquals(60, g.path("streamSettings", "grpcSettings", "health_check_timeout"))
        // Xray's sockopt has no MSS; emitting it would be a silently-ignored lie
        assertEquals(true, g.path("streamSettings", "sockopt", "tcpNoDelay"))
        assertNull(g.path("streamSettings", "sockopt", "tcpMss"))
    }

    @Test
    fun networkNamesAreNormalised() {
        assertEquals("ws", CoreProfiles.normNetwork("websocket"))
        assertEquals("tcp", CoreProfiles.normNetwork("raw"))
        assertEquals("h2", CoreProfiles.normNetwork("http"))
        assertEquals("xhttp", CoreProfiles.normNetwork("XHTTP"))
        assertEquals("tcp", CoreProfiles.normNetwork(null))
        assertEquals("tcp", CoreProfiles.normNetwork("gibberish"))
    }

    @Test
    fun singBoxDialectCarriesRealFragmentation() {
        val tune = Tune(fragSize = 200, fragCount = 2, fragStrategy = "variable", fragDelayMs = 20, mux = true)
        val o = outbound(profile(node(tls = "tls", network = "ws", path = "/"), tune, CoreEngine.SING_BOX))
        assertEquals("vless", o["type"])
        assertEquals("150-250", o.path("fragment", "length"))
        assertEquals("tlstls", o.path("fragment", "packets"))
        assertEquals("20-40", o.path("fragment", "interval"))
        assertEquals(true, o.path("tls", "enabled"))
        assertEquals(8, o.path("multiplex", "max_connections"))
        // and the Xray dialect must not pretend: it echoes the intent, it does not invent a field
        val xo = outbound(profile(node(tls = "tls", network = "ws", path = "/"), tune, CoreEngine.XRAY))
        assertNull(xo["fragment"])
        assertTrue(xo.containsKey("meelanoFragment"))
    }

    @Test
    fun plaintextFragmentsUseTheOtherPacketMode() {
        val tune = Tune(fragSize = 120, fragStrategy = "random", fragDelayMs = 40)
        val o = outbound(profile(node(tls = null, network = "tcp"), tune, CoreEngine.SING_BOX))
        assertEquals("1-1", o.path("fragment", "packets"))
        assertEquals("90-150", o.path("fragment", "length"))
        assertEquals("40-80", o.path("fragment", "interval"))
    }

    @Test
    fun theTuneTravelsIntoTheProfileForSupport() {
        val tune = Tune(mtu = 1280, mss = 1300, sni = "example.com")
        val echo = outbound(profile(node(), tune)).path("meelanoTune") as Map<*, *>
        assertEquals(1280, echo["mtu"])
        assertEquals(1300, echo["mss"])
        // `why`/free text never ships (docs/AI.md): only resolved numbers, so a screenshot cannot leak
        assertFalse(echo.containsKey("why"))
    }

    @Test
    fun rendererEmitsJsonNotKotlinToasters() {
        val json = CoreProfiles.render(
            linkedMapOf(
                "s" to "a\"b",
                "n" to 42,
                "b" to true,
                "list" to listOf(1, 2),
                "inner" to linkedMapOf("k" to "v"),
                "nil" to null,
            )
        )
        assertEquals("{\"s\":\"a\\\"b\",\"n\":42,\"b\":true,\"list\":[1,2],\"inner\":{\"k\":\"v\"},\"nil\":null}", json)
    }

    @Test
    fun everyProfileHasALocalInboundOnOnePort() {
        for (engine in listOf(CoreEngine.XRAY, CoreEngine.SING_BOX)) {
            val p = profile(node(tls = "tls", network = "ws", path = "/"), Tune.Default, engine)
            @Suppress("UNCHECKED_CAST")
            val inb = (p["inbounds"] as List<Map<String, Any?>>)[0]
            val port = inb["port"] ?: inb["listen_port"]
            assertEquals(10808, port)
            val listen = inb["listen"]
            assertEquals("127.0.0.1", listen)
        }
    }

    @Test
    fun engineChoiceIsBuiltNotGuessed() {
        assertEquals(CoreEngine.XRAY, CoreEngine.of("xray"))
        assertEquals(CoreEngine.XRAY, CoreEngine.of(null))
        assertEquals(CoreEngine.XRAY, CoreEngine.of(""))
        assertEquals(CoreEngine.SING_BOX, CoreEngine.of("sing-box"))
        assertEquals(CoreEngine.SING_BOX, CoreEngine.of("Hiddify"))
    }
}
