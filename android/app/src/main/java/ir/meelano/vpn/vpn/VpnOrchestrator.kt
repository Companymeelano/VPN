package ir.meelano.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.TunnelSpec
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything about *starting* a tunnel that must NOT happen on a main thread.
 *
 * Why this file exists: "the whole phone freezes for a few seconds right after I hit connect"
 * is almost always one of these four, and all four are in the connect path of a typical app:
 *
 *   1. DNS for the server hostname (InetAddress.getAllByName) before the tunnel is up  ~0.2-2s
 *   2. the core's own bootstrap (tProxy: InitConfig/StartVpn, DoH setup, TLS handshake) 1-3s
 *   3. building/parsing the profile JSON (free lists are megabytes) + SharedPreferences.commit()
 *   4. startForeground() called AFTER 1-3, so system_server is blocked waiting for the service
 *
 * The rule: the service returns from onStartCommand() immediately, and the *stages* below report
 * progress so the UI can animate a real percentage instead of an anonymous spinner. A user who
 * sees "DNS 40%" does not call the app "hang".
 */
class VpnOrchestrator(
    private val context: Context,
    private val engine: TunnelEngine,
    private val state: MutableStateFlow<ConnectPhase>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null
    @Volatile private var tunPfd: android.os.ParcelFileDescriptor? = null

    val phase: StateFlow<ConnectPhase> get() = state

    /** Never blocks: hands the work to [Dispatchers.Default] and returns. */
    fun connect(node: FeedNode) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                run(node)
            } catch (c: CancellationException) {
                releaseTun()
                state.value = ConnectPhase.Idle
                throw c
            } catch (t: Throwable) {
                // a failed dial must not leave a half-open tunnel fd behind: that is how "it says
                // connected but nothing passes, restart fixes it" happens
                releaseTun()
                state.value = ConnectPhase.Failed(t.message ?: "tunnel_error")
            }
        }
    }

    suspend fun disconnect() {
        job?.cancelAndJoin()
        job = null
        withContext(Dispatchers.Default) { runCatching { engine.stop() } }
        releaseTun()
        state.value = ConnectPhase.Idle
    }

    /** Closes the ParcelFileDescriptor we deliberately kept alive during the session. */
    private fun releaseTun() {
        runCatching { tunPfd?.close() }
        tunPfd = null
    }

    private suspend fun run(node: FeedNode) = withContext(Dispatchers.Default) {
        // ---- 0. permission must already be granted; prepare() is cheap but not free
        if (VpnService.prepare(context) != null) {
            state.value = ConnectPhase.Failed("permission_required")
            return@withContext
        }

        // ---- 1. profile materialisation: pure disk/CPU work, off the main thread, always.
        state.value = ConnectPhase.WritingProfile(0.15f)
        val profileDir = File(context.cacheDir, "profile").apply { mkdirs() }
        val spec = withContext(Dispatchers.IO) {
            val f = File(profileDir, "config.json")
            val json = engine.buildProfileJson(node)
            // write-to-temp + rename: a torn config file is a classic "works after a reboot only" bug
            val tmp = File(profileDir, "config.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) {
                f.writeText(json)
            }
            TunnelSpec(node, f.absolutePath)
        }

        // ---- 2. resolve the server ourselves and hand the IP to the builder, so the *first*
        //         route/DNS push can't loop back through a tunnel that is not up yet.
        state.value = ConnectPhase.Resolving(0.30f)
        val serverIp = withTimeoutOrNull(2_500) {
            runCatching {
                withContext(Dispatchers.IO) {
                    java.net.InetAddress.getByName(spec.node.host).hostAddress
                }
            }.getOrNull()
        }
        if (serverIp.isNullOrBlank() && spec.node.host.matches(HOSTNAME)) {
            // a hostname we cannot resolve must fail fast (1-2s) instead of hanging in the core
            state.value = ConnectPhase.Failed("dns_failed:${spec.node.host}")
            return@withContext
        }

        // ---- 3. bring up the engine BEFORE pushing DNS/routes: no orphaned lookups for other apps
        state.value = ConnectPhase.Handshake(0.50f)
        engine.startProxy(spec)

        // ---- 4. open the tunnel
        state.value = ConnectPhase.TunnelUp(0.70f)
        val pfd: ParcelFileDescriptor = builderFor(spec, serverIp).establish()
            ?: run {
                runCatching { engine.stop() }
                state.value = ConnectPhase.Failed("establish_returned_null")
                return@withContext
            }
        // ParcelFileDescriptor has no `autoClose` switch: what keeps the tunnel alive is a strong
        // reference. Without this field the finalizer closes the fd while the core is still using it,
        // which shows up as "connected for 2 seconds, then dead".
        tunPfd = pfd
        engine.attachTunnelFd(pfd.fileDescriptor)

        // ---- 5. only now is the tunnel healthy enough to advertise DNS to the whole device
        state.value = ConnectPhase.Verifying(0.88f)
        engine.onTunnelUp()

        state.value = ConnectPhase.Connected(spec.node.id, latencyMs = measureHandshakeMs(spec))
    }

    /**
     * Route order matters for the "other apps froze" symptom:
     * a host route for our own server through the *underlying* network, then the default route.
     */
    private fun builderFor(spec: TunnelSpec, serverIp: String?): VpnService.Builder {
        // The Builder must come from the *service* instance: a Builder that is not attached to the
        // live VpnService yields a tunnel that opens, routes nothing, and looks "connected".
        // error() here is caught by connect() and surfaced as ConnectPhase.Failed.
        val service = context as? VpnService
            ?: error("VpnOrchestrator must be constructed with the VpnService instance")
        val b = service.Builder()
        // the name Android shows in Settings > VPN and in the connect prompt
        // MTU comes from the resolved tune, not from a constant: 1420 is a fine number until a node on
        // a 1280-byte MTU link (Reality + fragment) needs something else, and the settings screen's
        // MTU row has to actually change the tunnel. Clamp, because VpnService rejects absurd values
        // and the resulting exception surfaces as "connect failed" with no hint of why.
        val tune = runCatching { ir.meelano.vpn.data.AppSettings.tuneFor(spec.node) }.getOrNull()
        val mtu = (tune?.mtu ?: 0).takeIf { it in 576..9000 } ?: MTU
        b.setSession(context.getString(ir.meelano.vpn.R.string.app_name))
            .setMtu(mtu)
            .addAddress(VPN_IP, 32)
            .addDnsServer(DNS_PRIMARY)
            .addRoute("0.0.0.0", 0)
        // There is no "exclude this route" API on VpnService.Builder: addRoute() only *includes*
        // networks, and adding a host route for our own server with a default route already inside the
        // tunnel is exactly how you get a loop (tunnel traffic dialling the tunnel). The supported
        // mechanisms are the two below, and both are the core's job, which is why CoreApi exists.
        if (serverIp != null) {
            // 1) protect(): the core calls protectOutbound() on the socket/fd it uses to reach
            //    [serverIp] BEFORE connecting, so that one connection bypasses the tunnel.
            // 2) whole-app split tunnelling (the only route-level exclusion the platform offers) is
            //    b.addDisallowedApplication(pkg), driven by the "این اپ‌ها خارج از VPN" setting.
        }
        return b
    }

    /**
     * What the engine must call on its *own* outbound socket before dialling the server.
     * This — not a route — is how a VPN keeps its control connection outside the tunnel.
     *
     * `VpnService.protect` has exactly three overloads: `protect(int)`, `protect(Socket)` and
     * `protect(DatagramSocket)`. There is no FileDescriptor and no ParcelFileDescriptor variant —
     * a core holding a pfd must call `pfd.detachFd()` on its own side and hand us the int, because
     * detaching is also a transfer of ownership and that decision belongs to whoever owns the tunnel.
     * So the raw int here is the same value `CoreApi.set_fd` receives; nothing is dup'd or cached.
     */
    fun protectOutbound(fd: Int): Boolean =
        runCatching { (context as? VpnService)?.protect(fd) ?: false }.getOrDefault(false)

    fun protectOutbound(socket: java.net.Socket): Boolean =
        runCatching { (context as? VpnService)?.protect(socket) ?: false }.getOrDefault(false)

    fun protectOutbound(socket: java.net.DatagramSocket): Boolean =
        runCatching { (context as? VpnService)?.protect(socket) ?: false }.getOrDefault(false)

    private suspend fun measureHandshakeMs(spec: TunnelSpec): Long {
        val t0 = System.nanoTime()
        withTimeoutOrNull(6_000) {
            while (!engine.isTunnelAlive()) delay(120)
        }
        return ((System.nanoTime() - t0) / 1_000_000).coerceAtMost(6_000)
    }

    companion object {
        private const val MTU = 1420                 // 1280-ish is safest over ws/tls tunnels
        private const val VPN_IP = "10.128.0.2"
        private const val DNS_PRIMARY = "1.1.1.1"
        private val HOSTNAME = Regex("^[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    }
}

/** Progress the UI animates. Percentages come from real stages, not from a fake timer. */
sealed class ConnectPhase(open val progress: Float = 0f) {
    object Idle : ConnectPhase(0f)
    class WritingProfile(override val progress: Float) : ConnectPhase(progress)
    class Resolving(override val progress: Float) : ConnectPhase(progress)
    class Handshake(override val progress: Float) : ConnectPhase(progress)
    class TunnelUp(override val progress: Float) : ConnectPhase(progress)
    class Verifying(override val progress: Float) : ConnectPhase(progress)
    class Connected(val nodeId: String, val latencyMs: Long) : ConnectPhase(1f)
    class Failed(val reason: String) : ConnectPhase(0f)
}

/**
 * The core (tProxy / sing-box / your own) hides behind this, so the orchestrator above is
 * testable and survives swapping engines. Adapter example for tProxy:
 *
 *   override fun startProxy(spec) {
 *       tProxy.setVpnConfigureConfig(spec.profileJson)   // must run OFF the main thread
 *       tProxy.startVpnProxy()
 *   }
 *   override fun attachTunnelFd(fd: FileDescriptor) { tProxy.startVpn(fd.detachFd()) }
 */
interface TunnelEngine {
    fun buildProfileJson(node: FeedNode): String
    suspend fun startProxy(spec: TunnelSpec)
    fun attachTunnelFd(fd: FileDescriptor)
    suspend fun onTunnelUp()
    fun isTunnelAlive(): Boolean
    fun rxBytes(): Long
    fun txBytes(): Long
    suspend fun stop()
}

private typealias FileDescriptor = java.io.FileDescriptor
