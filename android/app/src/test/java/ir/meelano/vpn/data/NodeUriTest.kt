package ir.meelano.vpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app feed builder, tested where the server-side one is not: on the parsing rules.
 *
 * These tests are pure JVM by design (`NodeUri`/`DirectFeed` import nothing from Android and nothing from
 * org.json), which is the only way any of this is testable in CI - org.json under Robolectric-less unit
 * tests is a stub that returns 0 for everything, so a test written against it proves nothing.
 */
class NodeUriTest {

    /* ------------------------------------------------------------------ vless / reality */

    @Test
    fun vlessRealityKeepsEveryFieldTheCoreNeeds() {
        val n = NodeUri.parseUri(
            "vless://11111111-2222-3333-4444-555555555555@185.1.2.3:443" +
                "?security=reality&type=tcp&sni=www speed&pbk=SHORTID&sfp=chrome&flow=xtls-rprx-vision#%F0%9F%87%A9%F0%9F%87%AA%20frankfurt"
        )
        checkNotNull(n)
        assertEquals("vless", n.proto)
        assertEquals("185.1.2.3", n.host)
        assertEquals(443, n.port)
        assertEquals("11111111-2222-3333-4444-555555555555", n.userId)
        assertEquals("reality", n.tls)                       // security=reality wins over anything else
        assertEquals("SHORTID", n.pbk)
        assertEquals("chrome", n.fingerprint)
        assertEquals("xtls-rprx-vision", n.flow)
        assertEquals("www%20speed", n.sni)                     // kept raw; the core unescapes
        assertEquals("tcp", n.network)
        assertEquals("DE", n.cc)                              // from the flag in the remark, nothing else
        assertEquals(12, n.id.length)
    }

    @Test
    fun idIsStableAndDistinctPerNode() {
        val a = NodeUri.parseUri("vless://u1@1.1.1.1:443?security=tls")!!
        val b = NodeUri.parseUri("vless://u1@1.1.1.1:443?security=tls")!!
        val c = NodeUri.parseUri("vless://u2@1.1.1.1:443?security=tls")!!
        assertEquals(a.id, b.id)
        assertFalse(a.id == c.id)
    }

    @Test
    fun tunnelWithoutSecretIsRefusedNotHalfBuilt() {
        // a vless node needs its uuid; trojan/hy2 need the password - a row the core cannot dial must not
        // reach the list at all, because the user would blame the app for a broken config
        assertNull(NodeUri.parseUri("trojan://@1.1.1.1:443"))
        assertNull(NodeUri.parseUri("vless://@1.1.1.1:443?security=tls"))
        assertNull(NodeUri.parseUri("ss://bogusthings@1.1.1.1:8388"))
        assertNotNull(NodeUri.parseUri("trojan://pw@1.1.1.1:443?sni=x"))
    }

    /* ------------------------------------------------------------------ shadowsocks */

    @Test
    fun sip002Base64CredentialsDecode() {
        val b64 = java.util.Base64.getEncoder()
            .encodeToString("chacha20-ietf-poly1305:s3cr3t-pass".toByteArray(Charsets.UTF_8))
        val n = NodeUri.parseUri("ss://$b64@198.177.1.2:8388?plugin=obfs-local#%F0%9F%87%BA%F0%9F%87%B8")
        checkNotNull(n)
        assertEquals("chacha20-ietf-poly1305", n.method)
        assertEquals("s3cr3t-pass", n.password)
        assertEquals("US", n.cc)
    }

    @Test
    fun plainMethodColonPasswordAlsoWorks() {
        val n = NodeUri.parseUri("ss://aes-256-gcm:hunter2@198.177.1.2:8388")
        checkNotNull(n)
        assertEquals("aes-256-gcm", n.method)
        assertEquals("hunter2", n.password)
    }

    @Test
    fun uuidStyleSsUserinfoIsRejected() {
        // The exact class of garbage that reads as a live node and dies on handshake. It decodes to bytes,
        // so the cipher test is what saves us - not the length.
        assertNull(NodeUri.parseUri("ss://11111111-2222-3333-4444-555555555555@1.1.1.1:8388"))
        assertNull(NodeUri.parseUri("ss:/**@1.1.1.1:8388"))
    }

    @Test
    fun unknownCipherIsNotSilentlyAccepted() {
        assertNull(NodeUri.parseUri("ss:**@1.1.1.1:8388"))
    }

    /* ------------------------------------------------------------------ vmess */

    @Test
    fun vmessReadsItsConfigOutOfTheBase64Body() {
        // vmess carries its whole body base64'd in the userinfo: there is no host or port in the
        // authority, so a parser that validated host/port first (as every lazy one does) drops
        // every vmess node in existence.
        val real = """{"v":"2","ps":"💂 Germ-656 | Telecom","add":"89.1.2.3","port":"443",""" +
            """"id":"aaaa-bbbb-cccc","aid":"16","net":"ws","host":"cdn.example.net","path":"/vless","tls":"tls"}"""
        val token = "vmess://" + java.util.Base64.getEncoder().encodeToString(real.toByteArray(Charsets.UTF_8))
        val n = NodeUri.parseUri(token)
        checkNotNull(n)
        assertEquals("vmess", n.proto)
        assertEquals("89.1.2.3", n.host)
        assertEquals(443, n.port)
        assertEquals("aaaa-bbbb-cccc", n.userId)
        assertEquals(16, n.alterId)
        assertEquals("ws", n.network)
        assertEquals("cdn.example.net", n.hostHeader)
        assertEquals("/vless", n.path)
        assertEquals("tls", n.tls)
        assertNull(n.cc)                                     // "Germ" is not a country code we would show
    }

    /* ------------------------------------------------------------------ bare proxy lines */

    @Test
    fun bareProxyLineFormatsAllParse() {
        val a = NodeUri.parseProxyLine("176.9.10.11:8080:user:pass")
        checkNotNull(a)
        assertEquals("http", a.proto)                          // creds imply an authenticating HTTP proxy
        assertEquals("user", a.username)
        assertEquals("pass", a.password)
        val b = NodeUri.parseProxyLine("socks5://176.9.10.11:1080")
        checkNotNull(b)
        assertEquals("socks5", b.proto)
        assertNull(b.username)
        val c = NodeUri.parseProxyLine("user:***@176.9.10.11:3128")
        checkNotNull(c)
        assertEquals("user", c.username)
        assertEquals("http", c.proto)
    }

    @Test
    fun addressesAPhoneShouldNeverDialAreRefused() {
        listOf(
            "127.0.0.1", "10.0.0.5", "192.168.1.1", "172.20.3.4", "169.254.9.9",
            "100.64.1.2",                                        // carrier-grade NAT: the usual fake "alive"
            "0.0.0.0", "239.1.1.1", "8.8.8.8",                  // multicast / Google DNS probe magnets
        ).forEach {
            // message FIRST: JUnit has assertNull(Object) and assertNull(String, Object) - the
            // trailing-argument habit belongs to AssertJ, and here it is a compile error
            assertNull("should refuse $it", NodeUri.parseProxyLine("$it:8080"))
            assertNull("should refuse host $it", NodeUri.parseUri("trojan://pw@$it:443"))
        }
        assertNull(NodeUri.parseProxyLine("localhost:8080"))
    }

    /* ------------------------------------------------------------------ blobs, masking, grading */

    @Test
    fun blobMixesCommentsAndKindsAndDedupes() {
        val line = "vless://u1@1.1.1.1:443?security=tls#one"
        val blob = "# list of things\n\n$line\n$line\nnot a config at all\n" +
            "trojan://pw@203.0.113.9:443?sni=x\n"
        val nodes = NodeUri.parseBlob(blob, tier = "free", brand = "Free M•A")
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.tier == "free" })
        assertTrue(nodes.all { it.name == "Free M•A" })
    }

    @Test
    fun base64SubscriptionIsUnwrapped() {
        val raw = "vless://u@1.1.1.1:443?security=tls\nss://***/@1.1.1.1:8388"
        val blob = java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        val nodes = NodeUri.parseBlob(blob, tier = "vip", brand = "Vip M•A")
        assertEquals(1, nodes.size)                           // only the dialable one survives
        assertEquals("Vip M•A", nodes[0].name)
    }

    @Test
    fun vendorRemarksNeverSurviveMasking() {
        val n = NodeUri.parseUri("trojan://pw@203.0.113.9:443?sni=x#SomeNet%20VIP%204%20\u2014%20Turkey")!!
        val m = NodeUri.mask(n, "Vip M•A")
        assertEquals("Vip M•A", m.name)
        assertFalse(m.subtitle.contains("SomeNet", ignoreCase = true))
        // `raw` intentionally still holds the config: it is what the core dials, and Diagnostics.redact
        // is the layer that keeps it out of anything a user pastes to a stranger.
        assertTrue(m.subtitle.contains("turkey"))            // the country is allowed, the vendor is not
        assertEquals("TR", m.cc)
    }

    @Test
    fun gradeMatchesTheServersThresholds() {
        assertEquals("A", NodeUri.grade(399))
        assertEquals("B", NodeUri.grade(400))
        assertEquals("B", NodeUri.grade(1000))
        assertEquals("C", NodeUri.grade(1001))
        assertEquals("C", NodeUri.grade(2200))
        assertEquals("D", NodeUri.grade(2201))
        assertEquals("D", NodeUri.grade(null))
        assertEquals("D", NodeUri.grade(-1))
    }

    @Test
    fun dedupeCapsByHostPortNotByLine() {
        val nodes = listOf(
            NodeUri.parseUri("vless://a@1.1.1.1:443?security=tls")!!,
            NodeUri.parseUri("vless://b@1.1.1.1:443?security=tls")!!,     // same endpoint, other uuid
            NodeUri.parseUri("vless://c@2.2.2.2:443?security=tls")!!,
        )
        assertEquals(2, NodeUri.dedupe(nodes, max = 10).size)
        assertEquals(1, NodeUri.dedupe(nodes, max = 1).size)
    }

    /* ------------------------------------------------------------------ tiny JSON reader */

    @Test
    fun miniJsonReadsWhatSubscriptionsActuallyLookLike() {
        val o = NodeUri.MiniJson.parse("""{"a":1,"b":[true,null,"x\ny"],"c":{"d":-2.5}}""") as Map<*, *>
        assertEquals(1L, o["a"])
        val arr = o["b"] as List<*>
        assertEquals(true, arr[0])
        assertNull(arr[1])
        assertEquals("x\ny", arr[2])
        assertEquals(-2.5, (o["c"] as Map<*, *>)["d"])
    }

    @Test
    fun jsonProxyListIsReadAndFlaggedByCountryCode() {
        val body = """[{"ip":"203.0.113.7","port":8080,"user":"u","pass":"p","country_code":"DE"},""" +
            """ {"ip":"10.0.0.1","port":80,"protocol":"socks5"}]"""
        val nodes = NodeUri.parseBlob(body, tier = "free", brand = "Free M•A")
        assertEquals(1, nodes.size)                            // the private one is refused
        assertEquals("DE", nodes[0].cc)
        assertEquals("germany · http · 8080", nodes[0].subtitle)
    }

    /* ------------------------------------------------------------------ the builder's rules */

    @Test
    fun directBuildGradesSortsAndDropsUndialables() = runBlocking {
        val body = listOf(
            "vless://slow@203.0.113.20:443?security=tls",
            "vless://fast@203.0.113.21:443?security=tls",
            "vless://dead@203.0.113.22:443?security=tls",
            "176.9.10.11:8080:user:pass",                       // an HTTP proxy: not dialable by the core
        ).joinToString("\n")
        val r = DirectFeed.buildFree(
            sources = listOf(DirectFeed.Upstream("t", "https://example.invalid/l.txt")),
            brand = "Free M•A",
            fetch = { body },
            probe = { list ->
                list.associate { n ->
                    n.id to when (n.userId) {
                        "fast" -> 90L
                        "slow" -> 1800L
                        else -> -1L
                    }
                }
            },
        )
        assertEquals(1, r.sourcesOk)
        assertEquals(3, r.nodes.size)
        assertTrue(r.nodes.all { it.proto in DirectFeed.TUNNEL_PROTOS })
        assertEquals("fast", r.nodes.first().userId)             // A beats C beats D
        assertEquals("A", r.nodes[0].grade)
        assertEquals("C", r.nodes[1].grade)
        assertEquals("D", r.nodes[2].grade)
        assertEquals(listOf(1, 2, 3), r.nodes.map { it.slot })
        assertTrue(r.notes.any { it.contains("پروکسی") })
    }

    @Test
    fun directBuildKeepsGoingWhenASourceDies() = runBlocking {
        val r = DirectFeed.buildFree(
            sources = listOf(
                DirectFeed.Upstream("a", "https://example.invalid/a.txt"),
                DirectFeed.Upstream("b", "https://example.invalid/b.txt"),
            ),
            fetch = { u -> if (u.id == "a") "vless://u@203.0.113.30:443?security=tls" else null },
            probe = { emptyMap() },
        )
        assertEquals(1, r.sourcesOk)
        assertEquals(2, r.sourcesTotal)
        assertEquals(1, r.nodes.size)
        assertTrue(r.notes.any { it.contains("جواب نداد") })
    }

    @Test
    fun directBuildOnGarbageYieldsNothingInsteadOfThrowing() = runBlocking {
        val r = DirectFeed.buildFree(
            sources = listOf(DirectFeed.Upstream("a", "https://example.invalid/a.txt")),
            fetch = { "<html><body>Rate limited</body></html>" },
            probe = { emptyMap() },
        )
        assertTrue(r.nodes.isEmpty())
        assertTrue(r.notes.isNotEmpty())
    }

    /** Every node id the app stores must survive `encode -> payload()`, or the cache is not a cache. */
    @Test
    fun parserOutputIsShapedForTheSameJsonBridgeTheHostUses() {
        val n = NodeUri.parseUri(
            "vless://u@203.0.113.40:2053?security=tls&type=ws&path=%2Fray&sni=cdn&host=cdn.example"
        )
        checkNotNull(n)
        assertEquals(2053, n.port)
        assertEquals("/ray", n.path)                            // %-decoded, like the server does
        assertEquals("ws", n.network)
        assertEquals("cdn.example", n.hostHeader)
    }
}
