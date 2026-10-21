package ir.meelano.vpn.data

import ir.meelano.vpn.net.Ladder
import ir.meelano.vpn.net.Regime
import java.util.Locale

/**
 * "From Iran, on a mobile carrier, which of these nodes should be tried first?" - as one pure
 * function, shared by the on-device feed builder and the service's failover ladder so the two
 * can never disagree about what a good first try looks like.
 *
 * Before this existed, candidates were ordered by a single bare-TCP RTT (or, server-side, by
 * reachability measured from the hosting box in Europe). That is the wrong order twice over:
 *  1. *transport survivability beats raw speed*: a 300 ms plaintext VMess that the DPI kills
 *     on sight is worth less than a 380 ms Reality node that stays up all evening. The
 *     regime ladder ([Ladder]) encodes that knowledge and dominates the bucket;
 *  2. *geography is latency you cannot tune away*: from Tehran, Istanbul/Dubai/Yerevan answer
 *     in 40-90 ms and their routes rarely cross the congested westbound links; a US exit adds
 *     180+ ms before the first handshake byte. Country proximity is the second key;
 *  3. *port 443* is the last port any filtering round blocks wholesale, so it breaks ties.
 *
 * The result is a coarse *bucket*, not a score: measured latency and this phone's own health
 * ledger still decide inside a bucket, and nothing here ever deletes a node - it only orders.
 */
object NodeRanking {

    /** Lower is better. Unknown country sorts after every known one except a domestic exit. */
    fun countryRank(cc: String?): Int = when (cc?.trim()?.uppercase(Locale.ROOT)) {
        // The neighbourhood: one or two hops from Iranian carriers, sub-100 ms RTTs.
        "TR", "AE", "AM", "AZ", "GE", "IQ", "QA", "KW", "OM", "BH", "CY" -> 0
        // Near abroad / eastern Europe: still short paths, little congestion.
        "KZ", "RU", "UA", "RO", "BG", "MD", "RS", "HU", "AT", "CZ", "PL", "SK", "GR" -> 1
        // The usual high-quality exits: DE/NL/FI first among equals in practice.
        "DE", "NL", "FI", "SE", "NO", "DK", "CH", "FR", "BE", "IT", "ES", "PT",
        "GB", "IE", "EE", "LV", "LT", "IS", "LU", "SI" -> 2
        // Asia-Pacific: reachable and often unfiltered, but the RTT tax is real.
        "IN", "SG", "MY", "TH", "ID", "VN", "HK", "TW", "JP", "KR", "CN" -> 3
        // The far side of the planet: works, pays 180-300 ms before anything else.
        "US", "CA", "AU", "NZ", "BR", "AR", "MX", "ZA", "EG", "IL", "SA" -> 4
        // A domestic exit cannot cross the filter it sits behind; keep it dialable (a user may
        // pin it deliberately for testing) but never auto-prefer it.
        "IR" -> 6
        else -> 5
    }

    /** 443 first (the port of the web itself), then the CDN-style TLS alternates, then 80. */
    fun portRank(port: Int): Int = when (port) {
        443 -> 0
        8443, 2053, 2083, 2087, 2096 -> 1
        80, 8080 -> 2
        else -> 3
    }

    /** Position of this node's transport on the regime's survivability ladder. */
    fun transportIndex(node: FeedNode, regime: Regime = Regime.TIGHT): Int {
        val order = Ladder.order(regime)
        val proto = when (node.proto.trim().lowercase(Locale.ROOT)) {
            "shadowsocks" -> "ss"
            else -> node.proto.trim().lowercase(Locale.ROOT)
        }
        val key = Ladder.transportOf(proto, node.tls, normalizedNetwork(node.network))
        val i = order.indexOf(key)
        return if (i < 0) order.size else i
    }

    /**
     * One comparable number: transport dominates (x1000), geography next (x100), port last.
     * Two nodes in the same bucket are ordered by measured latency at the call site.
     */
    fun bucket(node: FeedNode, regime: Regime = Regime.TIGHT): Int =
        transportIndex(node, regime) * 1000 + countryRank(node.cc) * 100 + portRank(node.port) * 10

    /** Feed spellings the ladder does not know, folded onto the names it does. */
    private fun normalizedNetwork(network: String?): String? = when (network?.trim()?.lowercase(Locale.ROOT)) {
        "websocket" -> "ws"
        "splithttp" -> "xhttp"
        "raw" -> "tcp"
        else -> network?.trim()?.lowercase(Locale.ROOT)
    }
}
