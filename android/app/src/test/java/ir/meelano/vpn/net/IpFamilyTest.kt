package ir.meelano.vpn.net

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The global/not-global line for IPv6. Two features lean on it (TUN v6 capture, share-link
 * parsing), and both fail in the worst direction if it is wrong: capture-and-blackhole on
 * v4-only carriers, or dialing a link-local address from a pasted config.
 */
class IpFamilyTest {

    private fun global(literal: String): Boolean = IpFamily.isGlobalIpv6(InetAddress.getByName(literal))

    @Test
    fun realGlobalAddressesPass() {
        assertTrue(global("2606:4700:4700::1111")) // Cloudflare
        assertTrue(global("2001:4860:4860::8888")) // Google
        assertTrue(IpFamily.isGlobalIpv6Literal("[2a01:4f8::1]"))
    }

    @Test
    fun localAndSpecialRangesDoNotPass() {
        assertFalse(global("::1"))            // loopback
        assertFalse(global("fe80::1"))        // link-local
        assertFalse(global("fd00::1"))        // unique-local (our own TUN address shape)
        assertFalse(global("ff02::1"))        // multicast
        assertFalse(global("2001:db8::1"))    // documentation range
        assertFalse(global("::"))             // unspecified
    }

    @Test
    fun literalsRefuseNonLiterals() {
        assertFalse(IpFamily.isGlobalIpv6Literal("example.com"))
        assertFalse(IpFamily.isGlobalIpv6Literal("185.1.2.3"))
        assertFalse(IpFamily.isGlobalIpv6Literal("not-an-address"))
        assertFalse(IpFamily.isGlobalIpv6(InetAddress.getByName("185.1.2.3")))
    }

    @Test
    fun hasGlobalScansTheWholeInterface() {
        val v4Only = listOf(InetAddress.getByName("10.0.0.2"), InetAddress.getByName("fe80::9"))
        assertFalse(IpFamily.hasGlobalIpv6(v4Only))
        assertTrue(IpFamily.hasGlobalIpv6(v4Only + InetAddress.getByName("2a02::7")))
    }
}
