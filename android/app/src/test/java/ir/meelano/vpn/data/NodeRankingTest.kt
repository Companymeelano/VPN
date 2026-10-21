package ir.meelano.vpn.data

import ir.meelano.vpn.net.Regime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Iran-aware first-dial order. Each assertion here is a user-visible connect attempt:
 * get them wrong and auto-connect spends its eight candidates on nodes the DPI kills first.
 */
class NodeRankingTest {

    private fun node(
        proto: String = "vless",
        tls: String? = "reality",
        network: String? = "tcp",
        cc: String? = "DE",
        port: Int = 443,
    ) = FeedNode(
        id = "0123456789ab", proto = proto, host = "185.1.2.3", port = port,
        tls = tls, network = network, cc = cc,
    )

    @Test
    fun neighboursBeatEuropeBeatFarSide() {
        assertTrue(NodeRanking.bucket(node(cc = "TR")) < NodeRanking.bucket(node(cc = "DE")))
        assertTrue(NodeRanking.bucket(node(cc = "DE")) < NodeRanking.bucket(node(cc = "US")))
        assertTrue(NodeRanking.bucket(node(cc = "US")) < NodeRanking.bucket(node(cc = null)))
        // a domestic exit sits behind the same filter as the user: never auto-preferred
        assertTrue(NodeRanking.countryRank("IR") > NodeRanking.countryRank(null))
    }

    @Test
    fun survivableTransportBeatsGeography() {
        // Reality in Oregon still outranks plaintext in Istanbul under TIGHT: the transport
        // decides whether the handshake survives; geography only decides how fast it is.
        val realityFar = node(cc = "US")
        val plainNear = node(proto = "vmess", tls = null, network = "tcp", cc = "TR")
        assertTrue(NodeRanking.bucket(realityFar, Regime.TIGHT) < NodeRanking.bucket(plainNear, Regime.TIGHT))
        // and the canonical pair: Reality-DE before WS/TLS-DE
        val wsDe = node(tls = "tls", network = "ws")
        assertTrue(NodeRanking.bucket(node(), Regime.TIGHT) < NodeRanking.bucket(wsDe, Regime.TIGHT))
    }

    @Test
    fun port443BreaksTies() {
        assertTrue(NodeRanking.bucket(node(port = 443)) < NodeRanking.bucket(node(port = 8443)))
        assertTrue(NodeRanking.bucket(node(port = 8443)) < NodeRanking.bucket(node(port = 2080)))
    }

    @Test
    fun feedSpellingsNormaliseOntoTheLadder() {
        // splithttp is xhttp's other name; under TIGHT xhttp-tls sits right behind reality
        val split = node(tls = "tls", network = "splithttp")
        assertEquals(1, NodeRanking.transportIndex(split, Regime.TIGHT))
    }
}
