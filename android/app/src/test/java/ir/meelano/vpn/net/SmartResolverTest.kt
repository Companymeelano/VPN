package ir.meelano.vpn.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCI/Irancell rules as contracts. Each test is one sentence of the field report:
 * "the system resolver lied, DoH knew better" is `doH majority wins and poison is recorded`.
 */
class SmartResolverTest {

    @Test fun `doh wins over a disagreeing system answer and poison is recorded`() {
        // MCI: system answers a sinkhole, DoH answers the real CDN IPs
        val p = SmartResolver.pick(
            systemIps = listOf("10.10.10.10"),
            dohIps = listOf("188.114.97.3", "188.114.96.3"),
        )
        assertEquals("188.114.97.3", p.ip)
        assertEquals("doh", p.via)
        assertTrue(p.poisoned)
    }

    @Test fun `system agreeing with doh is not poison`() {
        val p = SmartResolver.pick(
            systemIps = listOf("188.114.97.3", "192.0.2.1"),
            dohIps = listOf("188.114.97.3"),
        )
        assertEquals("188.114.97.3", p.ip)
        assertFalse(p.poisoned)
    }

    @Test fun `when doh is silent the system answer is used and poison is not claimed`() {
        // DoH blocked entirely (SNI-throttled day): never worse than today's behaviour
        val p = SmartResolver.pick(systemIps = listOf("203.0.113.7"), dohIps = emptyList())
        assertEquals("203.0.113.7", p.ip)
        assertEquals("system", p.via)
        assertFalse(p.poisoned)
    }

    @Test fun `total silence returns no ip so the caller keeps the hostname`() {
        val p = SmartResolver.pick(emptyList(), emptyList())
        assertNull(p.ip)
        assertEquals("none", p.via)
        assertFalse(p.poisoned)
    }

    @Test fun `ipv4 is preferred over ipv6 on v6-blackhole carriers`() {
        val p = SmartResolver.pick(
            systemIps = listOf("2001:db8::1", "203.0.113.9"),
            dohIps = listOf("2001:db8::2", "198.51.100.4"),
        )
        assertEquals("198.51.100.4", p.ip) // doh rank order, v4-only
    }

    @Test fun `an only-v6 host is still tried on v6 - it is the only shot`() {
        val p = SmartResolver.pick(systemIps = emptyList(), dohIps = listOf("2001:db8::2"))
        assertEquals("2001:db8::2", p.ip)
    }

    @Test fun `doh order is preserved - it is already majority-ranked`() {
        val p = SmartResolver.pick(emptyList(), listOf("1.2.3.4", "5.6.7.8"))
        assertEquals("1.2.3.4", p.ip)
    }

    @Test fun `blank entries are ignored`() {
        val p = SmartResolver.pick(listOf("", " "), listOf("  9.9.9.9  ", ""))
        assertEquals("9.9.9.9", p.ip)
        assertFalse(p.poisoned) // a silent system resolver is not a lying one
    }
}
