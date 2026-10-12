package ir.meelano.vpn.data

import java.io.File

/**
 * The unit of work handed to the core. Keeping it a value type (path + node) means the
 * service can be restarted from the cache dir alone - no living ViewModel required.
 */
data class TunnelSpec(
    val node: FeedNode,
    val profilePath: String,
) {
    val profileFile: File get() = File(profilePath)
    val isVip: Boolean get() = node.tier == "vip"
}

/**
 * Minimal node used when the *only* thing we know is an id (Quick Settings tap, boot receiver,
 * watchdog). The service replaces it with the real profile from the cached feed before dialing.
 */
object FeedNodeStub {
    fun forId(id: String): FeedNode = FeedNode(
        id = id, slot = 0, name = "Vip Meelano", subtitle = "", cc = null,
        kind = "vip", tier = "vip", proto = "", host = "", port = 0,
        tls = null, network = null, sni = null, path = null, hostHeader = null,
        alpn = null, flow = null, pbk = null, sid = null, fingerprint = null,
        userId = null, alterId = null, password = null, username = null,
        method = null, cipher = null, insecure = false, supportsUdp = false,
        raw = null, grade = "D", latencyMs = null, reliability = 0f, samples = 0,
        config = null,
    )
}
