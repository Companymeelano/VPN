package ir.meelano.vpn.net

import java.net.Inet6Address
import java.net.InetAddress

/**
 * One question, answered carefully: "is this IPv6 address a real, globally routable one?"
 *
 * Two features depend on not guessing here:
 *  - the TUN builder only captures `::/0` when the underlying network actually has global
 *    IPv6. On the (very common) IPv4+CGNAT Iranian mobile path, an always-on v6 route sends
 *    every AAAA-first app into a tunnel whose exit often has no v6 egress at all - the classic
 *    "connected, half the apps hang" report;
 *  - the URI parser accepts IPv6-literal node addresses, but only global ones: a share link
 *    pointing at `fe80::1` or `::1` must never become a dial target.
 *
 * Pure `java.net`, no Android: both call sites stay unit-testable on the JVM.
 */
object IpFamily {

    fun isGlobalIpv6(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress || address.isAnyLocalAddress
        ) return false
        val b = address.address
        if (b.size != 16) return false
        // 2001:db8::/32 is the documentation range: globally shaped, never routed.
        if (b[0] == 0x20.toByte() && b[1] == 0x01.toByte() &&
            b[2] == 0x0d.toByte() && b[3] == 0xb8.toByte()
        ) return false
        // Global unicast is 2000::/3. Everything outside it that survived the checks above
        // (e.g. the deprecated 3ffe:: space or benchmarking ranges) is not an exit either.
        return (b[0].toInt() and 0xE0) == 0x20
    }

    /**
     * Literal-only variant for text input (share links, feed rows). Refuses anything that is
     * not shaped like an IPv6 literal *before* `getByName`, so a hostname can never trigger a
     * DNS lookup from a parser.
     */
    fun isGlobalIpv6Literal(host: String): Boolean {
        val h = host.trim().trim('[', ']').substringBefore('%')
        if (!h.contains(':')) return false
        if (h.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
        val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        return isGlobalIpv6(addr)
    }

    fun hasGlobalIpv6(addresses: Iterable<InetAddress>): Boolean = addresses.any { isGlobalIpv6(it) }
}
