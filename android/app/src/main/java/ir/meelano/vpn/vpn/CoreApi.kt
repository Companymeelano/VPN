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
        // Core.start(spec.profilePath)          // your call here
        running = true
    }

    /** Hand the tunnel fd over; the core owns it from now on. */
    fun attachFd(service: VpnService, fd: FileDescriptor) {
        // tunFd = service.let { ParcelFileDescriptor.adoptFd(...).detachFd() } style, or:
        // Core.startVpn(FileDescriptorUtil.detach(fd))
        running = true
    }

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
     * Device-wide counters. Named `getTotalRxBytes/getTotalTxBytes` (there is no getTotalRx).
     * Prefer swapping this for the core's own counters when you wire a real engine: these also
     * count non-VPN traffic, so a download in another app shows up in the speed row.
     */
    fun rxBytes(): Long = runCatching { android.net.TrafficStats.getTotalRxBytes() }.getOrDefault(0L)
    fun txBytes(): Long = runCatching { android.net.TrafficStats.getTotalTxBytes() }.getOrDefault(0L)

    suspend fun stop(service: VpnService) {
        // Core.stop()
        running = false
        tunFd = -1
    }
}

/**
 * Feed node -> the JSON your core expects.
 *
 * Two rules, both of which explain "I picked a proxy and nothing happened":
 *  1. build the config from the *normalised* fields the server sent (they are already
 *     validated), not by string-concatenating the raw URI at the last minute;
 *  2. if the core cannot parse it, fail loudly (ConnectPhase.Failed) instead of silently
 *     keeping the previous tunnel alive while the UI shows "connected".
 */
object CoreProfiles {

    /**
     * `tune` is the resolved transport decision (see net/Regime.kt): the caller resolves
     * regime-default <- feed-patch <- user-overrides and passes the result down, so the profile is a
     * pure function of (node, tune) and a bug in the ordering shows up in one place only.
     */
    fun toJson(
        node: FeedNode,
        protect: (Int) -> Boolean = { true },
        tune: ir.meelano.vpn.net.Tune = ir.meelano.vpn.net.Tune.Default,
    ): String {
        val o = org.json.JSONObject()
        val inbounds = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("listen", "127.0.0.1")
                .put("port", 10808)
                .put("protocol", "dokodemo-door")
                .put("settings", org.json.JSONObject().put("network", "tcp,udp"))
        )
        val out = org.json.JSONObject()
            .put("protocol", node.proto)
            .put("tag", "proxy")
            .put("settings", settingsOf(node, tune))
            .put("stream", streamOf(node, tune))
        if (node.tls == "reality") {
            out.put("reality", org.json.JSONObject()
                .put("enable", true)
                .put("public_key", node.pbk ?: "")
                .put("short_id", node.sid ?: "")
                .put("fingerprint", node.fingerprint ?: tune.fingerprint)
                .put("server_name", tune.sni.ifBlank { node.sni ?: node.host }))
        }
        /*
         * The anti-blocking payload. Paths below follow the sing-box/xray hybrid shape this project
         * documents in docs/API-CONTRACT.md §tune; the linked core reads what it understands and the
         * `meelanoTune` block is our own echo, so a support screenshot can prove what was applied.
         * ADAPT-ON-LINK: rename the four paths your engine actually uses - the values are already right.
         */
        val sock = org.json.JSONObject()
        if (tune.mss > 0) sock.put("tcpMss", tune.mss)
        if (tune.keepAliveSec > 0) sock.put("tcpKeepAlive", true).put("keepalive", tune.keepAliveSec)
        if (sock.length() > 0) out.put("sockopt", sock)
        if (tune.mux) {
            out.put("mux", org.json.JSONObject()
                .put("enabled", true)
                .put("concurrency", tune.muxConcurrency)
                .put("protocols", org.json.JSONArray().put("h2mux").put("yamux").put("smux")))
        }
        if (tune.fragmented) {
            out.put("fragment", org.json.JSONObject()
                .put("packets", if (node.tls == null) "1-1" else "tlstls")
                .put("length", fragRange(tune.fragSize, tune.fragStrategy, tune.fragCount))
                .put("interval", fragInterval(tune.fragDelayMs, tune.fragStrategy)))
        }
        out.put("meelanoTune", tune.toJson())
        val rules = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("type", "field")
                .put("outboundTag", "proxy")
                .put("network", if (node.supportsUdp) "tcp,udp" else "tcp")
        )
        if (!tune.allowLan) {
            // "route everything" is how a VPN breaks a Chromecast, a local NAS and the printer.
            // LAN/loopback/link-local go direct; the tunnel still owns the public path.
            rules.put(
                org.json.JSONObject()
                    .put("type", "field")
                    .put("outboundTag", "direct")
                    .put("ip", org.json.JSONArray()
                        .put("0.0.0.0/8").put("10.0.0.0/8").put("100.64.0.0/10").put("127.0.0.0/8")
                        .put("169.254.0.0/16").put("172.16.0.0/12").put("192.168.0.0/16").put("::1/128")
                        .put("fe80::/10").put("fc00::/7"))
            )
        }
        val routing = org.json.JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules)
        return o.put("log", org.json.JSONObject().put("loglevel", "warning").put("dns", ""))
            .put("inbounds", inbounds)
            .put("outbounds", org.json.JSONArray().put(out).put(
                org.json.JSONObject().put("protocol", "freedom").put("tag", "direct")
            ))
            .put("routing", routing)
            .toString()
    }

    private fun settingsOf(n: FeedNode, tune: ir.meelano.vpn.net.Tune): org.json.JSONObject = when (n.proto) {
        "vless" -> org.json.JSONObject().put("vnext", org.json.JSONArray().put(
            org.json.JSONObject()
                .put("address", n.host).put("port", n.port).put("id", n.userId ?: "")
                .put("encryption", "none")
                .apply { n.flow?.let { put("flow", it) } }
        ))
        "vmess" -> org.json.JSONObject().put("vnext", org.json.JSONArray().put(
            org.json.JSONObject()
                .put("address", n.host).put("port", n.port).put("id", n.userId ?: "")
                .put("alterId", n.alterId ?: 0)
                .put("security", n.cipher ?: "auto")
        ))
        "trojan" -> org.json.JSONObject().put("servers", org.json.JSONArray().put(
            org.json.JSONObject()
                .put("address", n.host).put("port", n.port).put("password", n.password ?: "")
        ))
        "ss" -> org.json.JSONObject().put("servers", org.json.JSONArray().put(
            org.json.JSONObject()
                .put("address", n.host).put("port", n.port)
                .put("method", n.method ?: "aes-128-gcm").put("password", n.password ?: "")
                .put("level", 1)
        ))
        else -> org.json.JSONObject()
            .put("address", n.host).put("port", n.port)
            .put("password", n.password ?: "").put("method", n.method ?: "")
    }

    /** "100-200" style range, the format both engines expect; variable widens it, fixed pins it. */
    private fun fragRange(size: Int, strategy: String, count: Int): String = when (strategy) {
        "random", "variable" -> "${(size * 0.75).toInt()}-${(size * 1.25).toInt()}"
        else -> if (count > 1) "$size-$size" else "$size"
    }

    private fun fragInterval(delayMs: Int, strategy: String): String =
        if (strategy == "random" || strategy == "variable") "$delayMs-${(delayMs * 2).coerceAtLeast(delayMs + 10)}"
        else "$delayMs"

    private fun streamOf(n: FeedNode, tune: ir.meelano.vpn.net.Tune): org.json.JSONObject {
        val s = org.json.JSONObject().put("network", n.network ?: "tcp")
        if (n.tls != null) {
            s.put("security", n.tls)
            s.put("tlsSettings", org.json.JSONObject()
                .put("serverName", tune.sni.ifBlank { n.sni ?: n.host })
                .put("allowInsecure", n.insecure)
                .apply {
                    // the feed's alpn wins; otherwise the tune's (h3,h2 by default: a QUIC-looking hello
                    // matches a QUIC-looking cover site, which is the whole point of the cover)
                    val al = (n.alpn ?: tune.alpn).ifBlank { null }
                    if (al != null) put("alpn", org.json.JSONArray().apply { al.split(",").forEach { put(it.trim()) } })
                    if (tune.ech) put("ech", org.json.JSONObject().put("enabled", true))
                }
        }
        val net = org.json.JSONObject()
        (n.path ?: n.hostHeader ?: n.serviceNameOf())?.let {
            net.put("path", n.path ?: "/")
            net.put("host", org.json.JSONArray().put(n.hostHeader ?: ""))
        }
        if (n.network == "grpc") {
            net.put("serviceName", n.serviceNameOf() ?: "grpc")
            net.put("multiMode", tune.grpcMode == "multi")
            // a mobile path drops an idle stream at ~60s; our keepalive must be inside that window
            net.put("idle_timeout", (tune.keepAliveSec * 2).coerceAtLeast(30))
            net.put("health_check_timeout", (tune.keepAliveSec * 4).coerceAtLeast(60))
        }
        if (net.length() > 0) s.put("network", s.optString("network")).put("settings", net)
        return s
    }

    private fun FeedNode.serviceNameOf(): String? = null   // server sends it as a field; keep the hook

    /** Optional: profile files are safer in internal storage than in a world-readable cache. */
    fun profileFile(context: Context, node: FeedNode): File =
        File(context.getDir("profile", Context.MODE_PRIVATE), "${node.id}.json")
}
