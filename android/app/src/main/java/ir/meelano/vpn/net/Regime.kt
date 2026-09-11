package ir.meelano.vpn.net

import org.json.JSONObject

/**
 * What the network is doing to us *right now*, and what to do about it. Pure Kotlin, no Android,
 * no coroutines, no sockets: everything here is a function of its inputs, which is what lets it be
 * reasoned about (and unit-tested) without a phone in the loop.
 *
 * Three levels, because Iran's filtering has behaved like this for years and the counter-measure that
 * is correct for one level is a slowdown at another:
 *
 *  CALM     - SNI filtering only (the historical default). Cleanest transport wins; no fragmentation,
 *             no padding: those cost round-trips and we would be paying for camouflage nobody needs.
 *  TIGHT    - active RST injection + TLS-in-ClientHello shape detection + UDP throttling on the usual
 *             ports (this is where the country spends most of its time). Reality first, 1-RTT
 *             handshake, small TLS records fragmented, TCP keepalives short enough to survive the
 *             NAT-and-flow-timeout gauntlet, UDP throttling where it is used.
 *  BLACKOUT - ports 443/80/8443 are being dropped wholesale and only a few IP ranges still answer.
 *             Order flips: reachability beats latency, long-lived connections beat new ones
 *             (each new handshake is a chance to be seen), fragment everything, and the app stops
 *             pretending a 30 ms test result means anything.
 *
 * The regime is *guessed locally* (see [RegimeEngines.detect]) and *confirmed in aggregate* by the
 * backend: the server sees every user's probes, so it knows "SNI on port 443 is dead in Mazandaran
 * tonight" better than one phone does. That is the division of labour this whole phase is built on.
 */
enum class Regime {
    CALM, TIGHT, BLACKOUT;

    companion object {
        /** Persisted/user-facing id. Unknown or null -> [TIGHT]: the safe default for Iran today. */
        fun of(id: String?): Regime = when (id?.trim()?.lowercase()) {
            "calm" -> CALM
            "blackout" -> BLACKOUT
            else -> TIGHT
        }
    }
}

/**
 * Per-node transport parameters, i.e. the thing that decides whether a node that "exists" actually
 * carries traffic. Every field has a working default; 0 / "" means "not specified", never "off"
 * unless the doc says so.
 */
data class Tune(
    /** TLS ClientHello record splitting: split the handshake into ~`fragSize`-byte writes. */
    val fragSize: Int = 0,
    val fragCount: Int = 1,
    /** "fixed" | "random" | "variable" - how the first packet's size is chosen. */
    val fragStrategy: String = "",
    /** inter-fragment pause in ms; 0 = send back-to-back (only matters when fragSize > 0). */
    val fragDelayMs: Int = 0,
    /** ALPN offered, in preference order. h3 first: a QUIC-looking hello matches a QUIC-looking site. */
    val alpn: String = "h3,h2",
    /** uTLS fingerprint for Reality ("chrome", "firefox", "safari", "ios", "android", "edge", "360", "wildcard"). */
    val fingerprint: String = "chrome",
    /** SNI override; "" = the node's host. Also the cover domain when the feed supplies one. */
    val sni: String = "",
    /** ECH (encrypted client hello). Opt-in per node: needs a server that actually publishes HTTPS DNS records. */
    val ech: Boolean = false,
    /** Application-level keepalive in seconds (SSH-style). TIGHT wants it well under the ~60s flow timeout. */
    val keepAliveSec: Int = 15,
    /** Mux: fewer handshakes = fewer chances to be seen, but head-of-line blocking on lossy links. */
    val mux: Boolean = true,
    /** Mux concurrency (h2/ymux). 8 is the sweet spot on mobile links; 1 disables effectively. */
    val muxConcurrency: Int = 8,
    /** Route LAN traffic through the tunnel too. False = bypass, so the printer and Chromecast survive. */
    val allowLan: Boolean = false,
    val mtu: Int = 1280,
    /** TCP MSS clamp; 0 = leave alone. 1220..1360 is what survives tunnel + fragment overhead. */
    val mss: Int = 0,
    /** grpc: "multi" (multi-mode, one long stream) | "one" | "normal". Multi is the mobile-friendly one. */
    val grpcMode: String = "multi",
    /** Reuse the connection for the next stream instead of dialing: BLACKOUT cares about this a lot. */
    val connectionReuse: Boolean = true,
) {
    val fragmented: Boolean get() = fragSize > 0

    fun toJson(): JSONObject = JSONObject()
        .put("fragSize", fragSize).put("fragCount", fragCount).put("fragStrategy", fragStrategy)
        .put("fragDelayMs", fragDelayMs).put("alpn", alpn).put("fingerprint", fingerprint)
        .put("sni", sni).put("ech", ech).put("keepAliveSec", keepAliveSec)
        .put("mux", mux).put("muxConcurrency", muxConcurrency).put("allowLan", allowLan)
        .put("mtu", mtu).put("mss", mss).put("grpcMode", grpcMode).put("connectionReuse", connectionReuse)

    companion object {
        val Default = Tune()

        /**
         * Lenient parser - same rules as FeedJson: a feed that sends garbage about fragmentation must
         * never produce a garbage profile, so every value is clamped to something the core accepts.
         */
        fun fromJson(o: JSONObject?): Tune? {
            if (o == null) return null
            val d = Default
            return Tune(
                fragSize = o.optInt("fragSize", d.fragSize).coerceIn(0, 16384),
                fragCount = o.optInt("fragCount", d.fragCount).coerceIn(1, 64),
                fragStrategy = o.optString("fragStrategy", "").ifBlank { "" },
                fragDelayMs = o.optInt("fragDelayMs", d.fragDelayMs).coerceIn(0, 600),
                alpn = o.optString("alpn", d.alpn).ifBlank { d.alpn },
                fingerprint = o.optString("fingerprint", d.fingerprint).ifBlank { d.fingerprint },
                sni = o.optString("sni", ""),
                ech = o.optBoolean("ech", false),
                keepAliveSec = o.optInt("keepAliveSec", d.keepAliveSec).coerceIn(0, 300),
                mux = o.optBoolean("mux", d.mux),
                muxConcurrency = o.optInt("muxConcurrency", d.muxConcurrency).coerceIn(1, 64),
                allowLan = o.optBoolean("allowLan", false),
                mtu = o.optInt("mtu", d.mtu).coerceIn(576, 9000),
                mss = o.optInt("mss", 0).let { if (it == 0) 0 else it.coerceIn(300, 1460) },
                grpcMode = o.optString("grpcMode", d.grpcMode).ifBlank { d.grpcMode },
                connectionReuse = o.optBoolean("connectionReuse", true),
            )
        }

        /** What to use when the feed said nothing about a node (i.e. the country default). */
        fun forRegime(r: Regime): Tune = when (r) {
            Regime.CALM -> Default.copy(mux = true, keepAliveSec = 30, fragSize = 0)
            // 200-byte first record, then full ones: enough to break the shape signature the DPI
            // matches on, few enough writes that a phone on LTE doesn't feel it.
            Regime.TIGHT -> Default.copy(
                fragSize = 200, fragCount = 2, fragStrategy = "variable", fragDelayMs = 20,
                mux = true, keepAliveSec = 15, mtu = 1280, mss = 1300,
            )
            // Everything gets smaller and slower to identify; a new handshake is the risky event,
            // so reuse hard and let one stream live as long as the path allows.
            Regime.BLACKOUT -> Default.copy(
                fragSize = 120, fragCount = 3, fragStrategy = "random", fragDelayMs = 40,
                mux = true, muxConcurrency = 16, keepAliveSec = 10, mtu = 1280, mss = 1240,
                grpcMode = "multi", connectionReuse = true,
            )
        }
    }
}

/**
 * How to order candidate transports under a regime. This is a *preference vector*, not a filter: a
 * node is never deleted for being out of favour, only ranked lower, because "the list is empty" is a
 * worse outcome than "the list is suboptimal" - and the user can always pin.
 */
object Ladder {

    /** transport keys the feed and the client agree on (see docs/API-CONTRACT.md §tune). */
    fun transportOf(proto: String, tls: String?, network: String?): String = when {
        tls == "reality" -> "reality"
        tls == "tls" && network == "grpc" -> "grpc-tls"
        tls == "tls" && network == "xhttp" -> "xhttp-tls"
        tls == "tls" && network == "ws" -> "ws-tls"
        tls == "tls" -> "tcp-tls"
        proto == "hysteria2" || proto == "hy2" -> "udp-hy2"
        proto == "tuic" -> "udp-tuic"
        proto == "ss" -> "tcp-ss"
        else -> "tcp-plain"
    }

    fun order(r: Regime): List<String> = when (r) {
        Regime.CALM -> listOf("reality", "grpc-tls", "tcp-tls", "xhttp-tls", "ws-tls", "udp-hy2", "tcp-ss", "tcp-plain")
        Regime.TIGHT -> listOf("reality", "xhttp-tls", "grpc-tls", "tcp-tls", "udp-hy2", "ws-tls", "tcp-ss", "tcp-plain")
        // plain TCP last: during a blackout a bare handshake is the first thing to be dropped.
        Regime.BLACKOUT -> listOf("reality", "grpc-tls", "xhttp-tls", "udp-hy2", "tcp-tls", "ws-tls", "tcp-ss", "tcp-plain")
    }

    /**
     * Score contribution from transport preference: linear and small (0..14). It must not outrank
     * measured quality - a reachable C node beats an unreachable A, always.
     */
    fun preference(r: Regime, transport: String): Int {
        val o = order(r)
        val i = o.indexOf(transport)
        return if (i < 0) 0 else (o.size - i) * (14 / o.size + 1)
    }
}

/**
 * The local half of the block detector: what one phone can learn in ~400 ms, without trusting DNS.
 *
 * Deliberately cheap - a probe storm from every client would itself look like an attack:
 *  - DNS answers for a known-good host are compared against DoH (poisoning detector);
 *  - a TCP connect to the node's port measures reachability and RTT (this is the signal the server
 *    cannot have: it measures from its own vantage point, not from your ISP's edge);
 *  - a TLS handshake with the node's SNI catches the "TCP opens, TLS dies" middlebox, which is the
 *    signature of the last two years of filtering;
 *  - one host at a time, small fan-out, results cached a few minutes.
 */
data class BlockReport(
    val dnsPoisoned: Boolean = false,
    val tcpFailRatio: Float = 0f,
    val tlsFailRatio: Float = 0f,
    val medianRttMs: Long = -1L,
    val probes: Int = 0,
    val at: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("dnsPoisoned", dnsPoisoned).put("tcpFail", "%.2f".format(tcpFailRatio))
        .put("tlsFail", "%.2f".format(tlsFailRatio)).put("rtt", medianRttMs)
        .put("probes", probes).put("at", at)

    /** Local guess only - the server's aggregate verdict overwrites it. */
    fun guess(): Regime = when {
        probes < 3 -> Regime.TIGHT
        tcpFailRatio >= 0.65f -> Regime.BLACKOUT
        tlsFailRatio >= 0.35f || dnsPoisoned -> Regime.TIGHT
        else -> Regime.CALM
    }
}
