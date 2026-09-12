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

    /**
     * Same content as [toJson] without org.json, so `vpn/CoreProfiles` can keep its config builder
     * free of Android (and therefore unit-testable on a laptop). One source of truth: the map feeds the
     * echo in the profile, the JSON feeds the settings file.
     */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "fragSize" to fragSize, "fragCount" to fragCount, "fragStrategy" to fragStrategy,
        "fragDelayMs" to fragDelayMs, "alpn" to alpn, "fingerprint" to fingerprint,
        "sni" to sni, "ech" to ech, "keepAliveSec" to keepAliveSec,
        "mux" to mux, "muxConcurrency" to muxConcurrency, "allowLan" to allowLan,
        "mtu" to mtu, "mss" to mss, "grpcMode" to grpcMode, "connectionReuse" to connectionReuse,
    )

    companion object {
        val Default = Tune()

        /** What to use when the feed said nothing about a node (i.e. the country default). */
        fun forRegime(r: Regime): Tune = when (r) {
            // Cleanest transport wins; no fragmentation, no padding - camouflage is not free and
            // nobody is looking at the shape of the handshake today.
            Regime.CALM -> Default.copy(mux = true, keepAliveSec = 30, fragSize = 0)
            // 200-byte first record, then full ones: enough to break the shape signature the DPI
            // matches on, few enough writes that a phone on LTE doesn't feel it.
            Regime.TIGHT -> Default.copy(
                fragSize = 200, fragCount = 2, fragStrategy = "variable", fragDelayMs = 20,
                mux = true, keepAliveSec = 15, mtu = 1280, mss = 1300,
            )
            // Everything gets smaller and slower to identify; a *new* handshake is the risky event,
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
 * What the feed sends per node: a **patch**, never a full object. Sparse on purpose - the server only
 * states what it measured or decided ("this node needs 200-byte fragments, this one must not use mux
 * because its host drops it"), and everything else stays the client's call for the current regime.
 *
 * The wire shape is either that object or a preset name ("calm" | "tight" | "blackout"), which is what
 * the AI tuner emits when it wants to move a whole fleet at once: `{"tune": "tight"}`.
 */
data class TunePatch(
    val fragSize: Int? = null,
    val fragCount: Int? = null,
    val fragStrategy: String? = null,
    val fragDelayMs: Int? = null,
    val alpn: String? = null,
    val fingerprint: String? = null,
    val sni: String? = null,
    val ech: Boolean? = null,
    val keepAliveSec: Int? = null,
    val mux: Boolean? = null,
    val muxConcurrency: Int? = null,
    val allowLan: Boolean? = null,
    val mtu: Int? = null,
    val mss: Int? = null,
    val grpcMode: String? = null,
    val connectionReuse: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == Empty

    fun applyTo(base: Tune): Tune = copy2(base)

    private fun copy2(b: Tune) = Tune(
        fragSize = fragSize ?: b.fragSize,
        fragCount = fragCount ?: b.fragCount,
        fragStrategy = fragStrategy ?: b.fragStrategy,
        fragDelayMs = fragDelayMs ?: b.fragDelayMs,
        alpn = alpn ?: b.alpn,
        fingerprint = fingerprint ?: b.fingerprint,
        sni = sni ?: b.sni,
        ech = ech ?: b.ech,
        keepAliveSec = keepAliveSec ?: b.keepAliveSec,
        mux = mux ?: b.mux,
        muxConcurrency = muxConcurrency ?: b.muxConcurrency,
        allowLan = allowLan ?: b.allowLan,
        mtu = mtu ?: b.mtu,
        mss = mss ?: b.mss,
        grpcMode = grpcMode ?: b.grpcMode,
        connectionReuse = connectionReuse ?: b.connectionReuse,
    )

    fun toJson(): JSONObject = JSONObject()
        .apply {
            fragSize?.let { put("fragSize", it) }
            fragCount?.let { put("fragCount", it) }
            fragStrategy?.let { put("fragStrategy", it) }
            fragDelayMs?.let { put("fragDelayMs", it) }
            alpn?.let { put("alpn", it) }
            fingerprint?.let { put("fingerprint", it) }
            sni?.let { put("sni", it) }
            ech?.let { put("ech", it) }
            keepAliveSec?.let { put("keepAliveSec", it) }
            mux?.let { put("mux", it) }
            muxConcurrency?.let { put("muxConcurrency", it) }
            allowLan?.let { put("allowLan", it) }
            mtu?.let { put("mtu", it) }
            mss?.let { put("mss", it) }
            grpcMode?.let { put("grpcMode", it) }
            connectionReuse?.let { put("connectionReuse", it) }
        }

    companion object {
        val Empty = TunePatch()

        /** Lenient + clamped: a hostile or malformed feed must never produce a hostile profile. */
        fun fromAny(v: Any?): TunePatch {
            if (v is String) {
                // preset from the server ("tight") or a directive to follow the local regime ("auto")
                return if (v.isBlank() || v == "auto") Empty else fromObject(
                    JSONObject().put("preset", Regime.of(v).name.lowercase())
                )
            }
            if (v !is JSONObject) return Empty
            return fromObject(v)
        }

        private fun fromObject(o: JSONObject): TunePatch {
            val preset = o.optString("preset").let { Regime.of(it.ifBlank { null }) }
            val p = Tune.forRegime(preset)
            fun i(k: String, d: Int, lo: Int, hi: Int): Int? =
                if (!o.has(k) || o.isNull(k)) null else o.optInt(k, d).coerceIn(lo, hi)
            fun b(k: String, d: Boolean): Boolean? =
                if (!o.has(k) || o.isNull(k)) null else o.optBoolean(k, d)
            fun s(k: String, d: String): String? =
                if (!o.has(k) || o.isNull(k)) null else o.optString(k, d).ifBlank { null }
            return TunePatch(
                fragSize = i("fragSize", p.fragSize, 0, 16384),
                fragCount = i("fragCount", p.fragCount, 1, 64),
                fragStrategy = s("fragStrategy", p.fragStrategy),
                fragDelayMs = i("fragDelayMs", p.fragDelayMs, 0, 600),
                alpn = s("alpn", p.alpn),
                fingerprint = s("fingerprint", p.fingerprint),
                sni = s("sni", p.sni),
                ech = b("ech", p.ech),
                keepAliveSec = i("keepAliveSec", p.keepAliveSec, 0, 300),
                mux = b("mux", p.mux),
                muxConcurrency = i("muxConcurrency", p.muxConcurrency, 1, 64),
                allowLan = b("allowLan", p.allowLan),
                mtu = i("mtu", p.mtu, 576, 9000),
                mss = i("mss", p.mss, 300, 1460)?.let { if (o.optInt("mss") == 0) 0 else it },
                grpcMode = s("grpcMode", p.grpcMode),
                connectionReuse = b("connectionReuse", p.connectionReuse),
            )
        }
    }
}

/** The user's own switches (Settings sheet). Anything left null means "the app decides". */
data class Overrides(
    val fragment: Boolean? = null,
    val mux: Boolean? = null,
    val realityFirst: Boolean? = null,
    val mtu: Int? = null,
    val keepAliveSec: Int? = null,
    val fingerprint: String? = null,
    val autoBoot: Boolean? = null,
)

/**
 * The precedence, in one place, so nobody has to re-derive it:
 *
 *   regime default  <-  feed patch (server knows this node)  <-  user override (user knows their body)
 *
 * "auto" fragmenting means the *regime* decides; an explicit off from the user is honoured even when
 * it is a bad idea, because a phone that cannot reach anything is worse than a slightly detectable
 * handshake - and because a power user who turns it off after reading docs/ANTI-BLOCK.md must be able
 * to see their own choice in the profile we generate, or they will not trust anything else we show.
 */
object Tuner {
    fun resolve(regime: Regime, patch: TunePatch?, o: Overrides): Tune {
        var t = Tune.forRegime(regime)
        patch?.let { t = it.applyTo(t) }
        o.mux?.let { t = t.copy(mux = it) }
        o.mtu?.let { t = t.copy(mtu = it.coerceIn(576, 9000), mss = if (t.mss > 0) it - 60 else 0) }
        o.keepAliveSec?.let { t = t.copy(keepAliveSec = it.coerceIn(0, 300)) }
        o.fingerprint?.let { t = t.copy(fingerprint = it) }
        o.fragment?.let { on ->
            if (on) {
                if (t.fragSize <= 0) t = t.copy(
                    fragSize = if (regime == Regime.BLACKOUT) 120 else 200,
                    fragCount = if (regime == Regime.BLACKOUT) 3 else 2,
                    fragStrategy = t.fragStrategy.ifBlank { "variable" },
                    fragDelayMs = if (t.fragDelayMs == 0) 20 else t.fragDelayMs,
                )
            } else {
                t = t.copy(fragSize = 0, fragCount = 1, fragStrategy = "", fragDelayMs = 0)
            }
        }
        return t
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
    /** Numbers stay numbers: "%.2f"-style strings made every reader (ours and PHP's) parse a 0. */
    fun toJson(): JSONObject = JSONObject()
        .put("dnsPoisoned", dnsPoisoned)
        .put("tcpFail", String.format(java.util.Locale.US, "%.3f", tcpFailRatio).toDouble())
        .put("tlsFail", String.format(java.util.Locale.US, "%.3f", tlsFailRatio).toDouble())
        .put("rtt", medianRttMs).put("probes", probes).put("at", at)

    /** Local guess only - the server's aggregate verdict overwrites it. */
    fun guess(): Regime = when {
        probes < 3 -> Regime.TIGHT
        tcpFailRatio >= 0.65f -> Regime.BLACKOUT
        tlsFailRatio >= 0.35f || dnsPoisoned -> Regime.TIGHT
        else -> Regime.CALM
    }
}
