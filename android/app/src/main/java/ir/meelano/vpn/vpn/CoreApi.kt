package ir.meelano.vpn.vpn

import android.content.Context
import android.net.VpnService
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.TunnelSpec
import java.io.File
import java.io.FileDescriptor
import android.net.TrafficStats

/**
 * The only place that knows which core the app uses. Keep it this way: the orchestrator,
 * the service and the UI must never learn tProxy/sing-box specifics, otherwise swapping the
 * engine becomes a rewrite and every bug fix has to be repeated in four files.
 *
 * Everything here runs on a worker thread (the orchestrator guarantees it). If your core's
 * API is synchronous and slow, wrap it in withContext(Dispatchers.IO) - do not "optimise"
 * by calling it from the service's main thread. That single mistake is the usual cause of
 * "the phone hangs a few seconds after I connect".
 */
object CoreApi {

    /**
     * Set by the build (`-PMEELANO_CORE_LINKED=true`, which you pass only after adding the engine
     * dependency and the real calls below). While it is false this build **refuses to pretend**:
     * startProxy throws instead of flipping a flag, so the UI cannot say "وصل است" on a phone that
     * tunnels nothing. A fake green ring is worse than a red one.
     */
    val LINKED: Boolean get() = ir.meelano.vpn.BuildConfig.CORE_LINKED

    /** the reason a UI can name without parsing stack traces */
    const val NOT_LINKED = "core_not_linked"

    class CoreNotLinked : IllegalStateException(NOT_LINKED)

    @Volatile private var running = false
    @Volatile private var tunFd: Int = -1
    @Volatile private var mtu: Int = 1280

    /**
     * tProxy example (adjust names to the version you ship):
     *
     *   tProxy.stopVpn()
     *   tProxy.setVpnConfigureConfig(profileJson)   // <- must be OFF main thread, it parses
     *   tProxy.startVpnProxy()
     *   tProxy.setBlockedAPP / setProxyPackageNames(...)
     */
    suspend fun startProxy(service: VpnService, spec: TunnelSpec) {
        if (!LINKED) throw CoreNotLinked()
        // The order is deliberate: the engine is configured first and the tunnel is bound a moment later
        // (VpnOrchestrator: startProxy -> establish() -> attachTunnelFd -> onTunnelUp). That is how tProxy
        // and libXray both work, and it is why the fd is not an argument here.
        //
        // protect() is the anti-loop: every socket the core itself dials must go *outside* the tunnel.
        // The bridge delegates to this lambda through libXray's dialer controller.
        mtu = runCatching { ir.meelano.vpn.data.AppSettings.tuneFor(spec.node).mtu }.getOrDefault(1280)
        XrayBridge.protect = { fd -> runCatching { service.protect(fd) }.getOrDefault(false) }
        XrayBridge.start(spec.profilePath, tag = spec.node.id, tunFd = tunFd, mtu = mtu)
        running = true
    }

    /** Hand the tunnel fd over; the core owns it from now on. */
    fun attachFd(service: VpnService, fd: FileDescriptor) {
        tunFd = rawFdOf(fd)
        if (LINKED) {
            // failing here is required: a core that is configured but not bound to the TUN carries no
            // traffic, and "connected, no traffic" is the single symptom users cannot diagnose.
            // bindRaw is where libXray actually starts: the fd travels as `env."xray.tun.fd"` inside the
            // config that `runXray` receives (XrayBridge.kt documents the two-stage start in full).
            XrayBridge.bindRaw(tunFd, mtu)
        }
        running = true
    }

    /**
     * The int inside a FileDescriptor: Android has no public accessor before API 33, so libcore's private
     * field is read reflectively (-1 if a future ROM renames it, which start0/bind0 then surfaces as a
     * named failure instead of a tunnel that reads nothing).
     */
    private fun rawFdOf(fd: FileDescriptor): Int = runCatching {
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true
        f.getInt(fd)
    }.getOrDefault(-1)

    /** Called after the tunnel exists: start DNS/DoH, apply rules, enable routing. */
    suspend fun tunnelUp(service: VpnService) {
        if (!LINKED) throw CoreNotLinked()
        // Core.updateRules(); Core.protectAll()
    }

    suspend fun rebindUnderlying(service: VpnService) {
        // after wifi->cellular: re-protect() the outbound sockets, keep the tunnel fd
        // Core.reprotect()
    }

    fun alive(): Boolean = running

    /**
     * Counters scoped to THIS uid: the linked core runs in-process, so every socket it dials carries
     * the app's uid and per-uid stats are, to a very good approximation, "traffic through the tunnel".
     * Device-wide totals (getTotalRxBytes/…TxBytes) also count what a video app does at the same moment
     * and make a dead tunnel look busy. Uid-scoped reads exist on API 28+; older ROMs keep the device
     * total as the fallback, wrapped because a few OEMs throw instead of returning -1.
     */
    fun rxBytes(): Long = counter(rx = true)
    fun txBytes(): Long = counter(rx = false)

    private fun counter(rx: Boolean): Long {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val uid = android.os.Process.myUid()
            val v = runCatching {
                if (rx) android.net.TrafficStats.getUidRxBytes(uid) else android.net.TrafficStats.getUidTxBytes(uid)
            }.getOrDefault(-1L)
            if (v >= 0) return v
        }
        return runCatching {
            if (rx) android.net.TrafficStats.getTotalRxBytes() else android.net.TrafficStats.getTotalTxBytes()
        }.getOrDefault(0L)
    }

    suspend fun stop(service: VpnService) {
        if (LINKED) XrayBridge.stop()
        running = false
        tunFd = -1
    }
}

/**
 * Which engine this build speaks to. `CORE_ENGINE` is a buildConfigField (app/build.gradle.kts), so the
 * dialect is chosen at build time and never at runtime: a phone that silently switched engines during
 * an update is a phone whose config bugs cannot be reproduced.
 *
 * XRAY is the default and the target: it is MPL-2.0 (so a closed-source app may ship it), it is what
 * `CoreProfiles` was written against, and VLESS+Reality+Vision is still the best-performing combination
 * under Iranian DPI. SING_BOX (GPL-3.0-or-later, including the Hiddify fork's `fragment` extension) is
 * kept because it *does* support transport-level fragmentation natively, which matters in BLACKOUT.
 */
enum class CoreEngine {
    XRAY,
    SING_BOX,
    ;

    companion object {
        fun of(id: String?): CoreEngine = when (id?.trim()?.lowercase()) {
            "sing-box", "singbox", "sfa", "hiddify" -> SING_BOX
            else -> XRAY
        }
    }
}

/**
 * One node's engine-neutral transport decision: everything a profile needs, derived from
 * (feed node, resolved tune) and nothing else.
 *
 * It is a plain data class - no JSONObject, no Context, no Android - because that is what lets
 * `app/src/test` assert the contract on a laptop: every field in here is a bug we can write a test for.
 * The resolution order that produced `tune` lives in net/Regime.kt (regime default <- feed patch <- user
 * switches) and must not be re-decided here; re-deciding it is exactly how "the settings screen says
 * fragmentation is on but the ClientHello still leaves whole" happens.
 */
data class Spec(
    val proto: String,
    val address: String,
    val port: Int,
    val userId: String,
    val alterId: Int,
    val password: String,
    val method: String,
    /** "none" | "tls" | "reality" */
    val security: String,
    val sni: String,
    val alpn: List<String>,
    val fingerprint: String,
    val ech: Boolean,
    val insecure: Boolean,
    /** "tcp" | "ws" | "grpc" | "h2" | "xhttp" */
    val network: String,
    val path: String,
    val hostHeader: String,
    val serviceName: String,
    val grpcMulti: Boolean,
    val keepAliveSec: Int,
    val flow: String,
    val packetEncoding: String,
    val publicKey: String,
    val shortId: String,
    val supportsUdp: Boolean,
    val mux: Boolean,
    val muxConcurrency: Int,
    val fragSize: Int,
    val fragCount: Int,
    val fragStrategy: String,
    val fragDelayMs: Int,
    val mtu: Int,
    val mss: Int,
    val allowLan: Boolean,
    val connectionReuse: Boolean,
    /** Verbatim copy of the resolved tune, echoed into the profile so a support screenshot proves it. */
    val tuneEcho: Map<String, Any?>,
) {
    val isReality: Boolean get() = security == "reality"
    val isTls: Boolean get() = security == "tls" || isReality
    val needsFragment: Boolean get() = fragSize > 0
}

/**
 * Feed node + resolved tune -> the JSON the linked core parses.
 *
 * Three rules, each of which explains an "I picked a server and nothing happened":
 *  1. build the config from the *normalised* fields the server sent (validated by
 *     backend/v/lib/Parser.php), never by re-parsing a raw URI at the last minute;
 *  2. emit the *dialect* of the engine in this build. `stream` is not `streamSettings`, `reality` is
 *     not `realitySettings`; an engine that cannot parse a profile usually does not tell the UI - it
 *     keeps the previous tunnel alive while the ring turns green;
 *  3. if we cannot produce a profile, throw (the orchestrator turns it into ConnectPhase.Failed).
 *     A fake green ring is worse than a red one.
 *
 * What is deliberately NOT here: MTU/MSS. In this design the OS creates the TUN (VpnService) and the
 * core only ever sees a local socks/mixed stream, so MSS clamping is applied on the interface
 * (VpnOrchestrator.builderFor -> setMtu) and never in the core's JSON, where a wrong key would just be
 * ignored. The numbers still travel in `meelanoTune` so the log is honest about what was asked for.
 */
object CoreProfiles {

    /** From `-PMEELANO_CORE_ENGINE=xray|sing-box` (see app/build.gradle.kts). */
    val engine: CoreEngine get() = CoreEngine.of(ir.meelano.vpn.BuildConfig.CORE_ENGINE)

    /**
     * Public API, unchanged on purpose: MeelanoVpnService only ever asks for a profile, so a dialect
     * change can never ripple into the service. `protect` is handed to the linked core for its
     * outbound sockets (VpnService.protect has exactly three overloads: int, Socket, DatagramSocket).
     */
    fun toJson(
        node: FeedNode,
        protect: (Int) -> Boolean = { true },
        tune: ir.meelano.vpn.net.Tune = ir.meelano.vpn.net.Tune.Default,
        engine: CoreEngine = this.engine,
    ): String = render(plan(node, tune, engine))

    /** The parsed profile as nested maps/lists. The unit tests in app/src/test read *this*, not a string. */
    fun plan(node: FeedNode, tune: ir.meelano.vpn.net.Tune, engine: CoreEngine = this.engine): Map<String, Any?> {
        val s = specFor(node, tune)
        return when (engine) {
            CoreEngine.XRAY -> xray(s)
            CoreEngine.SING_BOX -> singBox(s)
        }
    }

    fun specFor(node: FeedNode, tune: ir.meelano.vpn.net.Tune): Spec {
        val net = normNetwork(node.network)
        val sec = when (node.tls?.trim()?.lowercase()) {
            "reality" -> "reality"
            "tls", "https" -> "tls"
            else -> "none"
        }
        val sni = tune.sni.ifBlank { node.sni ?: node.host }
        val alpn = (node.alpn ?: tune.alpn).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val feedFlow = node.flow ?: ""
        // Vision is what makes Reality fast, and it is only legal on plain TCP: an
        // "xtls-rprx-vision" flow on a ws/grpc node breaks the handshake, so it is derived here instead
        // of being copied from whatever the (volatile, community-sourced) feed happened to contain.
        val flow = if (feedFlow.isNotEmpty()) feedFlow
        else if (sec == "reality" && node.proto == "vless" && net == "tcp") "xtls-rprx-vision"
        else ""
        // Mux under Reality is a documented footgun (the mux handshake inside a reality session is what
        // the anti-replay check trips on) and grpc already multiplexes streams itself.
        val mux = tune.mux && net != "grpc" && sec != "reality"
        val reuse = tune.connectionReuse && net != "grpc"
        val method = when (node.proto) {
            "ss" -> node.method ?: node.cipher ?: "aes-128-gcm"
            "vmess" -> node.cipher ?: node.method ?: "auto"
            else -> node.method ?: ""
        }
        return Spec(
            proto = node.proto,
            address = node.host,
            port = node.port,
            userId = node.userId ?: "",
            alterId = node.alterId ?: 0,
            password = node.password ?: node.username ?: "",
            method = method,
            security = sec,
            sni = sni,
            alpn = alpn,
            fingerprint = node.fingerprint?.takeIf { it.isNotEmpty() } ?: tune.fingerprint,
            ech = tune.ech,
            insecure = node.insecure,
            network = net,
            path = node.path ?: "",
            hostHeader = node.hostHeader ?: "",
            // free-proxy sources do not carry a grpc service name; the vip feed may one day. The
            // fallback is the convention every public grpc deployment uses, so it works and is honest.
            serviceName = "grpc",
            grpcMulti = tune.grpcMode == "multi",
            keepAliveSec = tune.keepAliveSec,
            flow = flow,
            packetEncoding = if (node.proto == "vless" && node.supportsUdp) "xudp" else "",
            publicKey = node.pbk ?: "",
            shortId = node.sid ?: "",
            supportsUdp = node.supportsUdp,
            mux = mux,
            muxConcurrency = tune.muxConcurrency,
            fragSize = tune.fragSize,
            fragCount = tune.fragCount,
            fragStrategy = tune.fragStrategy,
            fragDelayMs = tune.fragDelayMs,
            mtu = tune.mtu,
            mss = tune.mss,
            allowLan = tune.allowLan,
            connectionReuse = reuse,
            tuneEcho = tune.toMap(),
        )
    }

    /** Source spellings differ ("raw", "net/http", "websocket"); every engine wants one of these five. */
    fun normNetwork(n: String?): String = when (n?.trim()?.lowercase()) {
        "ws", "websocket" -> "ws"
        "grpc" -> "grpc"
        "h2", "http", "httpupgrade" -> "h2"
        "xhttp" -> "xhttp"
        "raw", "tcp", "stream", "", null -> "tcp"
        else -> "tcp"
    }

    /* ------------------------------------------------------------------ Xray (MPL-2.0) dialect */

    private fun xray(s: Spec): Map<String, Any?> {
        val server = LinkedHashMap<String, Any?>()
        server["address"] = s.address
        server["port"] = s.port
        when (s.proto) {
            "vless" -> {
                server["id"] = s.userId
                server["encryption"] = "none"
                if (s.flow.isNotEmpty()) server["flow"] = s.flow
                if (s.packetEncoding.isNotEmpty()) server["packet_encoding"] = s.packetEncoding
            }
            "vmess" -> {
                server["id"] = s.userId
                server["alterId"] = s.alterId
                server["security"] = s.method
            }
            "trojan" -> server["password"] = s.password
            else -> {                                   // ss / ss2022
                server["method"] = s.method
                server["password"] = s.password
                server["level"] = 1
                server["ota"] = false
            }
        }
        val settings: Map<String, Any?> = when (s.proto) {
            "vless", "vmess" -> mapOf("vnext" to listOf(server))
            else -> mapOf("servers" to listOf(server))
        }

        val stream = LinkedHashMap<String, Any?>()
        stream["network"] = s.network
        when (s.network) {
            "ws" -> {
                val ws = linkedMapOf<String, Any?>("path" to s.path.ifEmpty { "/" })
                if (s.hostHeader.isNotEmpty()) ws["host"] = s.hostHeader
                stream["wsSettings"] = ws
            }
            "grpc" -> {
                val g = LinkedHashMap<String, Any?>()
                g["serviceName"] = s.serviceName
                // multi-mode keeps one long-lived stream open per connection: on a mobile link that
                // drops an idle stream at ~60s, it is the difference between "connected" and "usable"
                g["multiMode"] = s.grpcMulti
                if (s.keepAliveSec > 0) {
                    // both are seconds in Xray, and both must stay inside the flow's idle window
                    g["idle_timeout"] = (s.keepAliveSec * 2).coerceAtLeast(30)
                    g["health_check_timeout"] = (s.keepAliveSec * 4).coerceAtLeast(60)
                }
                stream["grpcSettings"] = g
            }
            "xhttp" -> {
                val x = linkedMapOf<String, Any?>("path" to s.path.ifEmpty { "/" }, "mode" to "auto")
                if (s.hostHeader.isNotEmpty()) x["host"] = s.hostHeader
                stream["xhttpSettings"] = x
            }
            "h2" -> {
                val h = linkedMapOf<String, Any?>("path" to s.path.ifEmpty { "/" })
                if (s.hostHeader.isNotEmpty()) h["host"] = s.hostHeader
                stream["httpSettings"] = h
            }
            else -> stream["tcpSettings"] = mapOf("header" to mapOf("type" to "none"))
        }
        if (s.isTls) {
            stream["security"] = s.security
            val tls = LinkedHashMap<String, Any?>()
            tls["serverName"] = s.sni
            tls["allowInsecure"] = s.insecure
            if (s.alpn.isNotEmpty() && !s.isReality) tls["alpn"] = s.alpn
            if (!s.isReality && s.fingerprint.isNotEmpty()) tls["fingerprint"] = s.fingerprint
            stream["tlsSettings"] = tls
        }
        if (s.isReality) {
            val r = LinkedHashMap<String, Any?>()
            r["fingerprint"] = s.fingerprint
            r["serverName"] = s.sni
            r["publicKey"] = s.publicKey
            r["shortId"] = s.shortId
            r["spiderX"] = ""
            stream["realitySettings"] = r
        }
        // Xray's sockopt knows these two and nothing else; do not "add" tcpMss here, it is a sing-box
        // option and an unknown field here is silently dropped, which is worse than absent.
        stream["sockopt"] = mapOf("tcpNoDelay" to true, "tcpFastOpen" to s.connectionReuse)

        val outbound = LinkedHashMap<String, Any?>()
        outbound["sendThrough"] = "0.0.0.0"
        outbound["protocol"] = s.proto
        outbound["tag"] = "proxy"
        outbound["settings"] = settings
        outbound["streamSettings"] = stream
        if (s.mux) {
            outbound["mux"] = mapOf("enabled" to true, "concurrency" to s.muxConcurrency.coerceIn(1, 64))
        }
        if (s.needsFragment) {
            // Upstream Xray has no transport fragmenter. It is echoed rather than faked: a build that
            // needs fragmentation in BLACKOUT selects the sing-box dialect, where the same numbers are
            // a real field (see singBox() below).
            outbound["meelanoFragment"] = fragmentPlan(s)
        }
        outbound["meelanoTune"] = s.tuneEcho

        val rules = mutableListOf<Map<String, Any?>>()
        if (!s.allowLan) {
            // "route everything" is how a VPN eats a Chromecast, a local NAS and the printer.
            rules.add(
                mapOf(
                    "type" to "field",
                    "outboundTag" to "direct",
                    "ip" to LAN_CIDR,
                )
            )
        }
        // the tunnel must own DNS or the local resolver keeps leaking what we look up
        rules.add(mapOf("type" to "field", "network" to "tcp,udp", "port" to "53", "outboundTag" to "proxy"))
        rules.add(mapOf("type" to "field", "protocol" to listOf("tls", "quic"), "outboundTag" to "proxy"))

        val profile = LinkedHashMap<String, Any?>()
        profile["log"] = mapOf("loglevel" to "warning")
        profile["inbounds"] = listOf(
            // The TUN inbound, not a socket: Xray-core (>= the v26 line pinned by libXray v26.9.9) reads
            // packets straight off the VpnService fd, which is handed over in env."xray.tun.fd" at bind
            // time (XrayBridge.bindRaw). port/listen are ignored by schema for this inbound; the MTU is
            // mirrored from the tune so the core and Builder::setMtu clamp together, never apart.
            mapOf(
                "port" to 0,
                "protocol" to "tun",
                "tag" to "tun-in",
                "settings" to mapOf(
                    "name" to "xray0",
                    "MTU" to s.mtu,
                ),
                "sniffing" to mapOf(
                    "enabled" to true,
                    "destOverride" to listOf("http", "tls", "quic"),
                    "routeOnly" to true,
                ),
            )
        )
        profile["outbounds"] = listOf(
            outbound,
            mapOf("protocol" to "freedom", "tag" to "direct"),
            mapOf("protocol" to "blackhole", "tag" to "block"),
        )
        profile["routing"] = mapOf("domainStrategy" to "IPIfNonMatch", "rules" to rules)
        return profile
    }

    /* -------------------------------------------------------- sing-box / Hiddify-fork dialect */

    private fun singBox(s: Spec): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["type"] = if (s.proto == "ss") "shadowsocks" else s.proto
        out["tag"] = "proxy"
        out["server"] = s.address
        out["server_port"] = s.port
        when (s.proto) {
            "vless" -> {
                out["uuid"] = s.userId
                if (s.flow.isNotEmpty()) out["flow"] = s.flow
                if (s.packetEncoding.isNotEmpty()) out["packet_encoding"] = s.packetEncoding
            }
            "vmess" -> {
                out["uuid"] = s.userId
                out["security"] = s.method
            }
            "trojan" -> out["password"] = s.password
            else -> {
                out["method"] = s.method
                out["password"] = s.password
            }
        }
        if (s.isTls) {
            val tls = LinkedHashMap<String, Any?>()
            tls["enabled"] = true
            tls["server_name"] = s.sni
            tls["insecure"] = s.insecure
            if (s.alpn.isNotEmpty()) tls["alpn"] = s.alpn
            if (!s.isReality && s.fingerprint.isNotEmpty()) {
                tls["utls"] = mapOf("enabled" to true, "fingerprint" to s.fingerprint)
            }
            if (s.isReality) {
                tls["reality"] = mapOf(
                    "enabled" to true,
                    "public_key" to s.publicKey,
                    "short_id" to s.shortId,
                )
            }
            if (s.ech) tls["ech"] = mapOf("enabled" to true)
            out["tls"] = tls
        }
        val tr = LinkedHashMap<String, Any?>()
        when (s.network) {
            "ws" -> {
                tr["type"] = "ws"
                tr["path"] = s.path.ifEmpty { "/" }
                if (s.hostHeader.isNotEmpty()) tr["headers"] = mapOf("Host" to s.hostHeader)
            }
            "grpc" -> {
                tr["type"] = "grpc"
                tr["service_name"] = s.serviceName
                // `multi_mode` is the Hiddify fork's spelling; upstream sing-box uses min/max streams.
                if (s.grpcMulti) tr["multi_mode"] = true
                if (s.keepAliveSec > 0) tr["idle_timeout"] = "${(s.keepAliveSec * 2).coerceAtLeast(30)}s"
            }
            "xhttp" -> {
                tr["type"] = "xhttp"
                tr["path"] = s.path.ifEmpty { "/" }
                tr["mode"] = "auto"
            }
            "h2" -> {
                tr["type"] = "http"
                tr["path"] = s.path.ifEmpty { "/" }
                tr["host"] = s.hostHeader
            }
            else -> tr["type"] = "tcp"
        }
        if (tr.size > 1) out["transport"] = tr
        if (s.mux) {
            out["multiplex"] = mapOf(
                "enabled" to true,
                "protocol" to listOf("h2mux", "yamux", "smux"),
                "max_connections" to s.muxConcurrency.coerceIn(1, 64),
                "padding" to true,
            )
        }
        if (s.needsFragment) {
            val f = LinkedHashMap<String, Any?>()
            // "tlstls" fragments the TLS record layer (the ClientHello); "1-1" is for plaintext probes
            f["packets"] = if (s.isTls) "tlstls" else "1-1"
            f["length"] = fragRange(s.fragSize, s.fragStrategy, s.fragCount)
            f["interval"] = fragInterval(s.fragDelayMs, s.fragStrategy)
            out["fragment"] = f
        }
        out["meelanoTune"] = s.tuneEcho

        val rules = mutableListOf<Map<String, Any?>>()
        rules.add(mapOf("protocol" to listOf("dns"), "action" to "hijack-dns"))
        if (!s.allowLan) rules.add(mapOf("ip_cidr" to LAN_CIDR, "outbound" to "direct"))
        rules.add(mapOf("action" to "sniff"))

        val profile = LinkedHashMap<String, Any?>()
        profile["log"] = mapOf("level" to "warn", "timestamp" to true)
        profile["inbounds"] = listOf(
            mapOf(
                "type" to "mixed",
                "tag" to "mixed-in",
                "listen" to "127.0.0.1",
                "listen_port" to LOCAL_PORT,
                "sniff" to true,
            )
        )
        profile["outbounds"] = listOf(
            out,
            mapOf("type" to "direct", "tag" to "direct"),
            mapOf("type" to "block", "tag" to "block"),
        )
        profile["route"] = mapOf(
            "final" to "proxy",
            "auto_detect_interface" to true,
            "override_android_vpn" to false,
            "rules" to rules,
        )
        return profile
    }

    /** "100-200" style range: variable widens it, fixed pins it. Both engines read this format. */
    fun fragRange(size: Int, strategy: String, count: Int): String = when (strategy) {
        "random", "variable" -> "${(size * 0.75).toInt()}-${(size * 1.25).toInt()}"
        else -> if (count > 1) "$size-$size" else "$size"
    }

    fun fragInterval(delayMs: Int, strategy: String): String =
        if (strategy == "random" || strategy == "variable")
            "$delayMs-${(delayMs * 2).coerceAtLeast(delayMs + 10)}"
        else "$delayMs"

    private fun fragmentPlan(s: Spec): Map<String, Any?> = mapOf(
        "packets" to if (s.isTls) "tlstls" else "1-1",
        "length" to fragRange(s.fragSize, s.fragStrategy, s.fragCount),
        "interval" to fragInterval(s.fragDelayMs, s.fragStrategy),
    )

    /* -------------------------------------------------------------------- tiny JSON writer */

    /**
     * Rendered by hand instead of org.json because the plan contains nested Maps and Lists: Android's
     * JSONObject(Map) does not recurse, it would print `{address=1.2.3.4}` into a config file.
     */
    internal fun render(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is Number -> v.toString()
        is String -> quote(v)
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + render(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { render(it) }
        is Array<*> -> v.toList().let { render(it) }
        else -> quote(v.toString())
    }

    private fun quote(s: String): String {
        // JSONObject.quote is trivial, but a JVM unit test has the android.jar stub of it, which
        // returns null instead of throwing - so a null result is treated as "unavailable" here. Without
        // this, every string in a rendered profile silently becomes `null` in the test and the test
        // passes for the wrong reason.
        val q = try {
            org.json.JSONObject.quote(s)
        } catch (e: Throwable) {
            null
        }
        if (q != null) return q
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Optional: profile files are safer in internal storage than in a world-readable cache. */
    fun profileFile(context: Context, node: FeedNode): File =
        File(context.getDir("profile", Context.MODE_PRIVATE), "${node.id}.json")

    private const val LOCAL_PORT = 10808

    private val LAN_CIDR = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "::1/128",
        "fe80::/10", "fc00::/7",
    )
}
