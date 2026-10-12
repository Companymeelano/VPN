package ir.meelano.vpn.vpn

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.TunnelSpec
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the complete VPN lifecycle: candidate ordering, profile creation, TUN replacement, proof of
 * internet through that TUN, and periodic failover. Starting the local core is never treated as a
 * successful connection; only exact 204 responses from the independent probe endpoints turn green.
 */
class VpnOrchestrator(
    private val context: Context,
    private val engine: TunnelEngine,
    private val state: MutableStateFlow<ConnectPhase>,
    private val onConnected: (FeedNode, TunnelProbe.Result) -> Unit = { _, _ -> },
    private val onResult: (FeedNode, Boolean, Long, String?) -> Unit = { _, _, _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private var job: Job? = null
    private var healthJob: Job? = null
    @Volatile private var tunPfd: ParcelFileDescriptor? = null
    private val generation = AtomicLong(0L)
    private val cooledUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val phase: StateFlow<ConnectPhase> get() = state

    fun connect(node: FeedNode) = connect(listOf(node), reorder = false)

    /** Never blocks the service main thread. A new request supersedes an in-flight startup. */
    fun connect(candidates: List<FeedNode>, reorder: Boolean = true) {
        if (candidates.isEmpty()) {
            state.value = ConnectPhase.Failed("no_candidates")
            return
        }
        val mine = generation.incrementAndGet()
        job?.cancel()
        healthJob?.cancel()
        job = scope.launch {
            try {
                lifecycle.withLock { runCandidates(candidates, reorder, mine) }
            } catch (cancelled: CancellationException) {
                if (generation.get() == mine) state.value = ConnectPhase.Idle
                throw cancelled
            } catch (error: Throwable) {
                if (!killSwitch()) releaseTun()
                if (generation.get() == mine) state.value = ConnectPhase.Failed(error.message ?: "tunnel_error")
            }
        }
    }

    /** Synchronous service-destruction safety net; normal disconnect still uses the suspend path. */
    fun destroy() {
        generation.incrementAndGet()
        healthJob?.cancel()
        job?.cancel()
        releaseTun()
    }

    suspend fun disconnect() {
        generation.incrementAndGet()
        healthJob?.cancelAndJoin()
        healthJob = null
        job?.cancelAndJoin()
        job = null
        lifecycle.withLock { withContext(Dispatchers.Default) { runCatching { engine.stop() } } }
        releaseTun()
        state.value = ConnectPhase.Idle
    }

    private fun releaseTun() {
        runCatching { tunPfd?.close() }
        tunPfd = null
    }

    private suspend fun runCandidates(source: List<FeedNode>, reorder: Boolean, mine: Long) {
        require(VpnService.prepare(context) == null) { "permission_required" }

        // Stop only the core first. With kill switch enabled the old TUN remains the default route and
        // black-holes packets while the replacement is prepared, avoiding a direct-network gap.
        runCatching { engine.stop() }
        if (!killSwitch()) releaseTun()

        val distinct = source.distinctBy { it.id }.filterNot { it.insecure }.take(MAX_CANDIDATES)
        require(distinct.isNotEmpty()) { "no_supported_candidates" }
        val now = System.currentTimeMillis()
        val warm = distinct.filter { (cooledUntil[it.id] ?: 0L) <= now }.ifEmpty { distinct }
        val ordered = if (reorder) runCatching { engine.orderCandidates(warm) }.getOrDefault(warm) else warm
        var last: Throwable = IllegalStateException("all_candidates_failed")

        for ((index, node) in ordered.withIndex()) {
            if (generation.get() != mine) throw CancellationException("superseded")
            try {
                val probe = startOne(node, index, ordered.size)
                cooledUntil.remove(node.id)
                onResult(node, true, probe.latencyMs, null)
                onConnected(node, probe)
                state.value = ConnectPhase.Connected(node.id, probe.latencyMs)
                startHealthMonitor(node, ordered, mine)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                last = error
                val reason = error.message ?: "candidate_failed"
                val cool = if (reason.contains("platform_blocked")) PLATFORM_COOLDOWN_MS else FAILURE_COOLDOWN_MS
                cooledUntil[node.id] = System.currentTimeMillis() + cool
                onResult(node, false, 0, reason)
                runCatching { engine.stop() }
                // Keep the just-established interface as a dead default route until the next establish()
                // atomically replaces it. Without kill switch, restore direct networking immediately.
                if (!killSwitch()) releaseTun()
            }
        }
        throw IllegalStateException("all_candidates_failed:${last.message ?: "unknown"}", last)
    }

    private suspend fun startOne(node: FeedNode, index: Int, total: Int): TunnelProbe.Result {
        val offset = (index.toFloat() / total.coerceAtLeast(1)) * 0.25f
        state.value = ConnectPhase.WritingProfile(0.08f + offset)
        val profileDir = File(context.cacheDir, "profile").apply { mkdirs() }
        val spec = withContext(Dispatchers.IO) {
            val file = File(profileDir, "config-${node.id}.json")
            val temp = File(profileDir, "config-${node.id}.json.tmp")
            temp.writeText(engine.buildProfileJson(node))
            if (!temp.renameTo(file)) file.writeText(temp.readText())
            temp.delete()
            TunnelSpec(node, file.absolutePath)
        }

        // Do not pre-resolve with InetAddress here: during failover the old kill-switch TUN is kept
        // open, so an app-level DNS query would enter that intentionally dead interface. libXray's
        // protected resolver resolves the endpoint outside the TUN when the core starts below.
        state.value = ConnectPhase.Resolving(0.30f + offset)

        state.value = ConnectPhase.Handshake(0.48f + offset)
        engine.startProxy(spec)

        state.value = ConnectPhase.TunnelUp(0.68f + offset)
        val next = builderFor(spec).establish() ?: error("establish_returned_null")
        val previous = tunPfd
        tunPfd = next
        // ParcelFileDescriptor.fd is the public Android API. Reflecting FileDescriptor.descriptor is a
        // hidden-API violation and fails on hardened ROMs.
        try {
            engine.attachTunnelFd(next.fd)
        } finally {
            // establish() has already replaced the system VPN; never leak the superseded descriptor,
            // even if native fd attachment fails and the new dead TUN is kept as the kill switch.
            runCatching { previous?.close() }
        }

        state.value = ConnectPhase.Verifying(0.88f)
        engine.onTunnelUp()
        delay(PROBE_SETTLE_MS)
        val probe = engine.verifyTunnel()
        if (!probe.clean) error(probe.reason)
        return probe
    }

    private fun startHealthMonitor(current: FeedNode, pool: List<FeedNode>, mine: Long) {
        healthJob?.cancel()
        healthJob = scope.launch {
            delay(HEALTH_INITIAL_DELAY_MS)
            var consecutiveFailures = 0
            while (generation.get() == mine) {
                val result = runCatching { engine.verifyTunnel() }.getOrNull()
                if (result?.clean == true && engine.isTunnelAlive()) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                }
                if (consecutiveFailures >= HEALTH_FAILURES) {
                    val reason = result?.reason ?: "health_probe_failed"
                    onResult(current, false, 0, reason)
                    cooledUntil[current.id] = System.currentTimeMillis() +
                        if (result?.platformBlocked == true) PLATFORM_COOLDOWN_MS else FAILURE_COOLDOWN_MS
                    state.value = ConnectPhase.Handshake(0.12f)
                    val rotated = pool.filterNot { it.id == current.id } + current
                    connect(rotated, reorder = true)
                    return@launch
                }
                delay(HEALTH_INTERVAL_MS)
            }
        }
    }

    private fun builderFor(spec: TunnelSpec): VpnService.Builder {
        val service = context as? VpnService
            ?: error("VpnOrchestrator must be constructed with the VpnService instance")
        val tune = runCatching { AppSettings.tuneFor(spec.node) }.getOrNull()
        val mtu = (tune?.mtu ?: 0).takeIf { it in 576..9000 } ?: MTU
        return service.Builder()
            .setSession(context.getString(ir.meelano.vpn.R.string.app_name))
            .setMtu(mtu)
            .addAddress(VPN_IP4, 32)
            .addAddress(VPN_IP6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .apply {
                // These are virtual destinations only. The profile's dns-out intercepts port 53 and
                // resolves over DoH tagged to the selected proxy, including for TCP-only HTTP proxies.
                DNS_VIRTUAL.forEach { addDnsServer(it) }
            }
    }

    fun protectOutbound(fd: Int): Boolean =
        runCatching { (context as? VpnService)?.protect(fd) ?: false }.getOrDefault(false)

    fun protectOutbound(socket: java.net.Socket): Boolean =
        runCatching { (context as? VpnService)?.protect(socket) ?: false }.getOrDefault(false)

    fun protectOutbound(socket: java.net.DatagramSocket): Boolean =
        runCatching { (context as? VpnService)?.protect(socket) ?: false }.getOrDefault(false)

    private fun killSwitch(): Boolean = runCatching { AppSettings.killSwitch }.getOrDefault(false)

    companion object {
        private const val MTU = 1280
        private const val VPN_IP4 = "10.128.0.2"
        private const val VPN_IP6 = "fd00:6d65:656c::2"
        private val DNS_VIRTUAL = listOf("1.1.1.1", "2606:4700:4700::1111")
        private const val MAX_CANDIDATES = 8
        private const val PROBE_SETTLE_MS = 650L
        private const val HEALTH_INITIAL_DELAY_MS = 15_000L
        private const val HEALTH_INTERVAL_MS = 25_000L
        private const val HEALTH_FAILURES = 2
        private const val FAILURE_COOLDOWN_MS = 3 * 60_000L
        private const val PLATFORM_COOLDOWN_MS = 15 * 60_000L
    }
}

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

interface TunnelEngine {
    fun buildProfileJson(node: FeedNode): String
    suspend fun orderCandidates(nodes: List<FeedNode>): List<FeedNode> = nodes
    suspend fun startProxy(spec: TunnelSpec)
    fun attachTunnelFd(fd: Int)
    suspend fun onTunnelUp()
    suspend fun verifyTunnel(): TunnelProbe.Result
    fun isTunnelAlive(): Boolean
    fun rxBytes(): Long
    fun txBytes(): Long
    suspend fun stop()
}
