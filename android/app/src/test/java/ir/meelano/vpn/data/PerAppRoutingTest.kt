package ir.meelano.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-app routing policy, tested as the pure contract it is: [RoutingPolicy] imports nothing from
 * Android, so these run in the plain JVM CI step. The failure modes worth pinning are the quiet ones:
 * a bank app classified as foreign (card details go through the tunnel), an override that the other
 * override silently cancels, and the app disallowing itself (which would blind the feed machinery).
 */
class PerAppRoutingTest {

    /* ------------------------------------------------------------------ classification */

    @Test
    fun `banking apps are recognised by bank token`() {
        assertEquals(RouteClass.BANKING, RoutingPolicy.classify("ir.bankmellat.mobilebank"))
        assertEquals(RouteClass.BANKING, RoutingPolicy.classify("com.bmi.bam"))
        assertEquals(RouteClass.BANKING, RoutingPolicy.classify("ir.bpm.payment"))
    }

    @Test
    fun `payment keywords classify as banking regardless of prefix`() {
        assertEquals(RouteClass.BANKING, RoutingPolicy.classify("ir.asanpardakht.app"))
        assertEquals(RouteClass.BANKING, RoutingPolicy.classify("com.example.shaparak"))
        assertEquals(RouteClass.BANKING, RoutingPolicy.classify("ir.sadad.wallet"))
    }

    @Test
    fun `iranian vendors without the ir dot are still iranian`() {
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("com.snapp.taxi"))
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("com.snappfood.user"))
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("com.divar"))
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("taxi.tap30.passenger"))
    }

    @Test
    fun `the ir reverse-domain prefix marks the long tail as iranian`() {
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("ir.eitaa.messenger"))
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("ir.gov.mygov"))
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("ir.meelano.vpn"))
    }

    @Test
    fun `foreign apps stay foreign`() {
        assertEquals(RouteClass.FOREIGN, RoutingPolicy.classify("org.telegram.messenger"))
        assertEquals(RouteClass.FOREIGN, RoutingPolicy.classify("com.instagram.android"))
        assertEquals(RouteClass.FOREIGN, RoutingPolicy.classify("com.android.chrome"))
    }

    @Test
    fun `classification is case insensitive`() {
        assertEquals(RouteClass.BANKING, RoutingPolicy.classify("IR.BANKMELLAT.MB"))
        assertEquals(RouteClass.IRANIAN, RoutingPolicy.classify("Com.Snapp.Taxi"))
    }

    /* ------------------------------------------------------------------ effective set */

    private val installed = setOf(
        "ir.bankmellat.mobilebank",   // banking -> auto bypass
        "com.snapp.taxi",             // iranian vendor -> auto bypass
        "com.divar",                  // iranian vendor -> auto bypass
        "org.telegram.messenger",     // foreign -> tunnel
        "ir.meelano.vpn",             // ourselves -> never disallowed
    )

    private fun effective(
        enabled: Boolean = true,
        auto: Boolean = true,
        bypass: Set<String> = emptySet(),
        tunnel: Set<String> = emptySet(),
    ): Set<String> = RoutingPolicy.effectiveBypass(
        installed = installed,
        forcedBypass = bypass,
        forcedTunnel = tunnel,
        enabled = enabled,
        autoBypass = auto,
        self = "ir.meelano.vpn",
    )

    @Test
    fun `auto mode bypasses exactly the iranian and banking apps`() {
        assertEquals(
            setOf("ir.bankmellat.mobilebank", "com.snapp.taxi", "com.divar"),
            effective(),
        )
    }

    @Test
    fun `master switch off disables everything including forced entries`() {
        assertTrue(effective(enabled = false, bypass = setOf("com.instagram.android")).isEmpty())
    }

    @Test
    fun `auto off keeps only what the user explicitly forced`() {
        assertEquals(
            setOf("com.snapp.taxi"),
            effective(auto = false, bypass = setOf("com.snapp.taxi")),
        )
    }

    @Test
    fun `forced tunnel beats auto-detection`() {
        // someone who explicitly wants Snapp through the tunnel knows what they are doing
        assertFalse(effective(tunnel = setOf("com.snapp.taxi")).contains("com.snapp.taxi"))
        assertTrue(effective(tunnel = setOf("com.snapp.taxi")).contains("com.divar"))
    }

    @Test
    fun `forced bypass can pull a foreign app out of the tunnel`() {
        val set = effective(bypass = setOf("org.telegram.messenger"))
        assertTrue(set.contains("org.telegram.messenger"))
    }

    @Test
    fun `forced tunnel beats forced bypass for the same package`() {
        val set = effective(
            bypass = setOf("com.snapp.taxi"),
            tunnel = setOf("com.snapp.taxi"),
        )
        assertFalse(set.contains("com.snapp.taxi"))
    }

    @Test
    fun `the app's own package is never disallowed`() {
        val set = effective(bypass = setOf("ir.meelano.vpn"))
        assertFalse(set.contains("ir.meelano.vpn"))
    }

    @Test
    fun `uninstalled forced entries still apply`() {
        // a manual entry for an app on a work profile or a beta channel must not vanish
        // just because this scan did not see it installed
        val set = effective(bypass = setOf("com.example.not.installed"))
        assertTrue(set.contains("com.example.not.installed"))
    }

    /* ------------------------------------------------------------------ package validation */

    @Test
    fun `package name validation accepts reverse dns and rejects noise`() {
        assertTrue(RoutingPolicy.isValidPackage("com.example.app"))
        assertTrue(RoutingPolicy.isValidPackage("ir.bankmellat.mobilebank"))
        assertTrue(RoutingPolicy.isValidPackage(" com.snapp.taxi "))
        assertFalse(RoutingPolicy.isValidPackage(""))
        assertFalse(RoutingPolicy.isValidPackage("com"))
        assertFalse(RoutingPolicy.isValidPackage("com.example app"))
        assertFalse(RoutingPolicy.isValidPackage("http://com.example"))
    }
}
