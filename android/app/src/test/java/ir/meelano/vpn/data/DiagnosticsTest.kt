package ir.meelano.vpn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only test in this app whose job is to prove something is *absent*. A support report that leaks
 * `host:port` publishes the private upstream to whoever the user sent it to, and no formatting test would
 * ever catch that, so these assertions are written against the leak shapes themselves.
 */
class DiagnosticsTest {

    private val ipShape = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")
    private val scheme = Regex("[a-zA-Z][a-zA-Z0-9+.\\-]{1,12}://")
    private val domain = Regex("[A-Za-z0-9][A-Za-z0-9\\-]*\\.[a-zA-Z]{2,10}(\\.[a-zA-Z]{2,3})?")

    private val nasty = "handshake failed for 185.120.22.9:8443 " +
        "(vless://1111@entry.example.com:443?security=reality&pbk=AAAABBBB&sid=deadbeef) " +
        "host=\"edge1.example.net\" sni=x.example.net"

    @Test
    fun redactKillsIpsUrlsConfigParamsAndDomains() {
        val r = Diagnostics.redact(nasty)
        assertFalse(ipShape.containsMatchIn(r))
        assertFalse(scheme.containsMatchIn(r))
        assertFalse("a bare domain must not survive either: $r", domain.containsMatchIn(r))
        assertFalse(r.contains("AAAABBBB"))
        assertFalse(r.contains("deadbeef"))
        // and it must announce itself, so the support conversation does not look like a truncated error
        assertTrue(r.contains("حذف شد"))
    }

    @Test
    fun reportStaysCleanEvenWhenTheErrorLineIsDirty() {
        val text = Diagnostics.render(Diagnostics.Input(error = nasty, blockReport = ""))
        assertFalse(ipShape.containsMatchIn(text))
        assertFalse(scheme.containsMatchIn(text))
        assertFalse(domain.containsMatchIn(text))
    }

    @Test
    fun anEmptyReportIsStillLegibleAndHonestAboutTheCore() {
        val t = Diagnostics.render(Diagnostics.Input())
        assertTrue(t.startsWith("M•A VPN"))
        assertTrue(t.contains("در این بیلد وصل نشده"))
        assertTrue(t.contains("گره: —"))
        assertTrue(t.endsWith("فرستاده شود."))
        assertFalse("null must never reach a user", t.contains("null"))
    }

    @Test
    fun aLinkedCoreIsReportedDifferentlyThanAScaffold() {
        val linked = Diagnostics.render(Diagnostics.Input(coreLinked = true, coreEngine = "xray", versionName = "2.3.0"))
        assertTrue(linked.contains("وصل‌شده (xray)"))
        assertTrue(linked.contains("2.3.0 (0)"))
    }

    @Test
    fun probeOnlyEverSpeaksNumbers() {
        val out = Diagnostics.probe("""{"dnsPoisoned":true,"tcpFail":0.5,"rtt":180,"host":"edge.example.net","pbk":"SECRET"}""")
        // under a JVM unit test org.json is a stub, so `out` may be null - what must never happen is a leak
        if (out != null) {
            assertFalse(out.contains("example.net"))
            assertFalse(out.contains("SECRET"))
            assertTrue(out.contains("DNS آلوده") || out.contains("180ms") || out.contains("—"))
        }
    }

    @Test
    fun theInputTypeCannotEvenHoldAHost() {
        val fields = Diagnostics.Input::class.java.declaredFields.map { it.name }
        for (forbidden in listOf("host", "port", "pbk", "sid", "sni", "path", "url", "config")) {
            assertFalse("Diagnostics.Input must not carry '$forbidden'", fields.contains(forbidden))
        }
        assertTrue(fields.contains("nodeLabel"))
    }
}
