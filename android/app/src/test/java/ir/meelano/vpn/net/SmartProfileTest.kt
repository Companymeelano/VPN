package ir.meelano.vpn.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smart-profile rules as the Irancell/MCI field work dictated them: each test is one rule the
 * sheet quotes back to the user, so a change here is a change in what «اعمال بهترین تنظیمات» does.
 */
class SmartProfileTest {

    @Test fun `calm never fragments`() {
        val a = SmartProfile.advise(Regime.CALM, "free", "tcp-tls", medianRttMs = 200, measured = 12)
        assertFalse(a.fragment)
        assertEquals(0, a.fragSize)
        assertEquals("calm", a.regime)
    }

    @Test fun `tls transports fragment under tight, reality and plaintext do not`() {
        for (transport in listOf("tcp-tls", "ws-tls", "grpc-tls", "xhttp-tls")) {
            val a = SmartProfile.advise(Regime.TIGHT, "free", transport, 200, 12)
            assertTrue("$transport must fragment", a.fragment)
            assertTrue(a.fragSize > 0)
        }
        for (transport in listOf("reality", "tcp-plain", "tcp-ss", "udp-hy2")) {
            val a = SmartProfile.advise(Regime.TIGHT, "free", transport, 200, 12)
            assertFalse("$transport must not fragment", a.fragment)
        }
    }

    @Test fun `mux is off under reality and grpc and on fast links`() {
        assertFalse(SmartProfile.advise(Regime.TIGHT, "vip", "reality", 80, 12).mux)
        assertFalse(SmartProfile.advise(Regime.TIGHT, "free", "grpc-tls", 80, 12).mux)
        assertTrue(SmartProfile.advise(Regime.TIGHT, "free", "ws-tls", 120, 12).mux)
    }

    @Test fun `mux is off on slow links but blackout forces it on`() {
        assertFalse(SmartProfile.advise(Regime.TIGHT, "free", "ws-tls", 800, 12).mux)
        assertTrue(SmartProfile.advise(Regime.BLACKOUT, "free", "ws-tls", 800, 12).mux)
    }

    @Test fun `fast measured vip path may raise mtu, everything else stays at the nat floor`() {
        assertEquals(1400, SmartProfile.advise(Regime.TIGHT, "vip", "reality", 120, 12).mtu)
        assertEquals(1280, SmartProfile.advise(Regime.TIGHT, "free", "tcp-tls", 120, 12).mtu)
        assertEquals(1280, SmartProfile.advise(Regime.TIGHT, "vip", "reality", 600, 12).mtu)
        assertEquals(1280, SmartProfile.advise(Regime.BLACKOUT, "vip", "reality", 120, 12).mtu)
    }

    @Test fun `keepalive always stays under the carrier idle window`() {
        val cases = listOf(
            SmartProfile.advise(Regime.TIGHT, "free", "ws-tls", 50, 12),
            SmartProfile.advise(Regime.TIGHT, "free", "ws-tls", 900, 12),
            SmartProfile.advise(Regime.BLACKOUT, "vip", "reality", 50, 12),
        )
        cases.forEach { a ->
            assertTrue("keepalive ${a.keepAliveSec} must be > 0", a.keepAliveSec > 0)
            assertTrue("keepalive ${a.keepAliveSec} must be < 60", a.keepAliveSec < 60)
        }
    }

    @Test fun `reality-first is on for reality transports and for vip`() {
        assertTrue(SmartProfile.advise(Regime.TIGHT, "vip", "ws-tls", 200, 12).realityFirst)
        assertTrue(SmartProfile.advise(Regime.TIGHT, "free", "reality", 200, 12).realityFirst)
        assertFalse(SmartProfile.advise(Regime.TIGHT, "free", "ws-tls", 200, 12).realityFirst)
    }

    @Test fun `unmeasured networks get the safe defaults and say so`() {
        val a = SmartProfile.advise(Regime.TIGHT, "free", "tcp-tls", medianRttMs = -1, measured = 0)
        assertEquals(1280, a.mtu)
        assertFalse(a.reasons.none { it.contains("اندازه‌گیری") })
    }

    @Test fun `targets map writes exactly the five app settings dials`() {
        val t = SmartProfile.targets(SmartProfile.advise(Regime.TIGHT, "vip", "reality", 120, 12))
        assertEquals(setOf("regime", "fragment_auto", "mux_enabled", "reality_first", "mtu"), t.keys)
        assertEquals("tight", t["regime"])
    }

    @Test fun `blackout concurrency doubles onto one connection`() {
        val a = SmartProfile.advise(Regime.BLACKOUT, "free", "tcp-tls", 700, 12)
        assertTrue(a.mux)
        assertEquals(16, a.muxConcurrency)
    }
}
