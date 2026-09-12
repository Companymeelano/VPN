package ir.meelano.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import ir.meelano.vpn.MainActivity
import ir.meelano.vpn.R
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.FeedHolder
import ir.meelano.vpn.keepalive.KeepAlive
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ir.meelano.vpn.data.TunnelSpec
import java.io.FileDescriptor

/**
 * The tunnel must outlive the UI. Three rules that are usually broken in apps whose
 * "everything closes when I swipe the app away":
 *
 *   1. the connection is owned by THIS service (started, foreground, START_STICKY),
 *      never by a ViewModel or an Activity result;
 *   2. [onTaskRemoved] does not stop the tunnel - it only re-posts the notification;
 *   3. UI state is a process-wide [StateFlow] on the companion, so the screen can be
 *      destroyed and recreated (rotation, swipe-away, notification tap) and re-attach
 *      to the live connection instead of "losing" it.
 *
 * And one rule that fixes "my whole phone stuttered for a few seconds":
 *   4. the notification is updated at most once per second, and only when the numbers
 *      moved enough. notify() is a binder call into system_server; calling it per
 *      packet-read makes the *device* slow, not just your app.
 */
class MeelanoVpnService : VpnService(), TunnelEngine {

    companion object {
        const val ACTION_CONNECT = "ir.meelano.vpn.CONNECT"
        const val ACTION_DISCONNECT = "ir.meelano.vpn.DISCONNECT"
        const val EXTRA_NODE_ID = "node"

        private const val CHANNEL_ID = "meelano_vpn"
        private const val NOTIF_ID = 4201

        /** survives Activity death - single source of truth for the whole app */
        private val _phase = MutableStateFlow<ConnectPhase>(ConnectPhase.Idle)
        val phase: StateFlow<ConnectPhase> = _phase

        private val _traffic = MutableStateFlow(Traffic(0, 0, 0, startedAt = 0))
        val traffic: StateFlow<Traffic> = _traffic

        /**
         * One-second throughput history, for the sparkline on Home (and for the "the ring said
         * connected at 12:31" question in a support chat). It lives here rather than in the ViewModel
         * for the same reason `traffic` does: the tunnel outlives the Activity, and a chart that restarts
         * when you rotate the phone is a chart that lies about your session.
         */
        private val _trace = MutableStateFlow(TrafficTrace())
        val trace: StateFlow<TrafficTrace> = _trace

        /** set while a service instance is alive, so commands issued during a restart are not lost */
        @Volatile private var alive: MeelanoVpnService? = null

        fun start(context: Context, node: FeedNode) {
            val i = Intent(context, MeelanoVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_NODE_ID, node.id)
            }
            runCatching { context.startForegroundService(i) }
                .onFailure { context.startService(i) }   // pre-O / restricted-launch fallback
            // the full node payload goes through the repo (already cached) instead of an Intent:
            // Intent extras are size-limited, and the service re-reads the node by id anyway.
            FeedHolder.pendingConnectId = node.id
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MeelanoVpnService::class.java).setAction(ACTION_DISCONNECT)
            )
        }

        fun isRunning(): Boolean = _phase.value is ConnectPhase.Connected && alive != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var orchestrator: VpnOrchestrator
    private val statHandler = Handler(Looper.getMainLooper())
    private var engineStartedAt = 0L
    private var lastNotifiedAt = 0L
    private var lastNotifiedRx = -1L
    private var lastNotifiedTx = -1L
    private var lastTraceStart = -1L

    override fun onCreate() {
        super.onCreate()
        alive = this
        createChannel()
        // IMPORTANT: post the notification first, cheaply. Android 12+ kills the process
        // if startForeground() is not reached within ~10s of startForegroundService().
        startForeground(NOTIF_ID, buildNotification("در حال آماده‌سازی…"))
        orchestrator = VpnOrchestrator(this, this, _phase)
        registerNetworkCallback()
        registerReceiver(stopReceiver, IntentFilter(ACTION_DISCONNECT))
        serviceScope.launch { KeepAlive.onVpnStarted(this@MeelanoVpnService) }
        FeedHolder.init(this)
        // The 1 Hz sampler starts with the service. It used to be armed only from onTaskRemoved(), i.e.
        // only after the user swiped the app away - so while the app was open (the normal case: they are
        // looking at the ring) nothing ever called updateTraffic() and the live speed and session timer
        // stayed at zero for the whole connection.
        statHandler.postDelayed(ticker, 1000)
    }

    /**
     * Returns immediately. All real work (DNS, profile write, core bootstrap) happens in
     * [VpnOrchestrator] on Dispatchers.Default - never on the service's main thread.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    orchestrator.disconnect()
                    stopSelfSafely()
                }
                return START_NOT_STICKY
            }
            else -> {
                val id = FeedHolder.pendingConnectId ?: intent?.getStringExtra(EXTRA_NODE_ID)
                serviceScope.launch {
                    val node = id?.let { FeedHolder.repo.awaitNode(it) }
                    if (node == null) {
                        _phase.value = ConnectPhase.Failed("profile_missing")
                        stopSelfSafely()
                    } else {
                        orchestrator.connect(node)
                    }
                }
            }
        }
        // be sticky: if the low-memory killer takes us, restart and auto-reconnect
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // the user swiped the app away: keep the tunnel, keep the notification
        statHandler.removeCallbacks(ticker)
        statHandler.postDelayed(ticker, 1000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        statHandler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(stopReceiver) }
        networkCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        serviceScope.cancel()
        alive = null
        _phase.value = ConnectPhase.Idle
        super.onDestroy()
    }

    override fun onRevoke() {
        serviceScope.launch { orchestrator.disconnect() }
        super.onRevoke()
    }

    /* ------------------------------------------------------------ ticker + notification */

    /** Set while a sample is in flight, so a blocked read cannot pile up a queue of them. */
    private val statBusy = AtomicBoolean(false)

    private val ticker = object : Runnable {
        override fun run() {
            // The handler lives on the main looper, but the work must not: reading the core's counters
            // crosses into it and can wait on its stats mutex, and "no heavy work on main while the
            // tunnel is up" is the whole reason the original hang report was fixed. Main is the
            // metronome; IO does the reading; StateFlow carries it back to the UI.
            if (statBusy.compareAndSet(false, true)) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        updateTraffic()
                    } finally {
                        statBusy.set(false)
                    }
                }
            }
            statHandler.postDelayed(this, 1000)
        }
    }

    /** 1 Hz sampling; notification text is only pushed when it actually changed. */
    private fun updateTraffic() {
        val rx = engineRx()
        val tx = engineTx()
        val dt = ((System.currentTimeMillis() - engineStartedAt).coerceAtLeast(1)) / 1000f
        val rxD = ((rx - _traffic.value.rx) / dt).toLong().coerceAtLeast(0)
        val txD = ((tx - _traffic.value.tx) / dt).toLong().coerceAtLeast(0)
        _traffic.value = Traffic(rx, tx, rxD, txD, startedAt = engineStartedAt, seconds = dt.toInt())

        // one sample per tick, and a marker where the session flipped: the first flip is the connect,
        // a later one is a reconnect. Nobody presses a button for that, so the chart is the only place a
        // user can see it happened at all.
        val flipped = engineStartedAt != lastTraceStart
        lastTraceStart = engineStartedAt
        _trace.value = _trace.value.push(rxD.toFloat(), txD.toFloat(), mark = flipped && _trace.value.totalSamples > 0)

        // Coalescing: at most one push a second, and only when a human would see a difference.
        //
        // The old test was `abs(rx - last)*12 > abs(last)*100 + 4096` on *cumulative* counters. rx only
        // grows, so that is "the total must jump by 833%" - after the first few hundred kilobytes the
        // notification effectively never updated again, which is how the bug report read as "the speed in
        // the notification is frozen". 8% of what has moved is what was meant.
        //
        // The absolute floor is deliberately large (8 MB since the last push): during a download this
        // keeps the pace at a few seconds per notify(). notify() is a binder call into system_server and
        // one per second *while streaming* is exactly what made the whole phone stutter before, so the
        // 1 Hz clock belongs to the in-app UI, which reads _traffic with no binder at all.
        val now = System.currentTimeMillis()
        val lastRx = lastNotifiedRx.coerceAtLeast(0L)
        val lastTx = lastNotifiedTx.coerceAtLeast(0L)
        val delta = (rx - lastRx).coerceAtLeast(0L) + (tx - lastTx).coerceAtLeast(0L)
        val base = lastRx + lastTx
        val movedEnough = lastNotifiedRx < 0L ||            // first sample of a session: always push
            flipped ||                                     // (re)connected: the text changed meaning
            delta * 100 > base * 8 ||                       // 8% more than everything notified so far
            delta >= 8_388_608L ||                         // or 8 MB of real traffic
            now - lastNotifiedAt > 30_000                   // or keep the session timer honest
        if (now - lastNotifiedAt > 1000 && movedEnough) {
            lastNotifiedAt = now
            lastNotifiedRx = rx
            lastNotifiedTx = tx
            notify(buildNotification(statusLine()))
        }
    }

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    private fun statusLine(): String {
        val t = _traffic.value
        val ph = _phase.value
        return when (ph) {
            is ConnectPhase.Connected -> "متصل · ↓ ${rate(t.rxPerSec)} · ↑ ${rate(t.txPerSec)}"
            is ConnectPhase.Failed -> "قطع شد (${ph.reason})"
            is ConnectPhase.Idle -> "غيرفعال"
            else -> "در حال اتصال… ${((ph.progress * 100).toInt())}%"
        }
    }

    private fun rate(bps: Long): String =
        if (bps < 1024) "$bps B/s" else Formatter.formatShortFileSize(this, bps) + "/s"

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable()
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, MeelanoVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            // the app name comes from resources, so the rename to "M•A VPN" is one line in
            // strings.xml and the notification follows it; the package id stays ir.meelano.vpn
            // on purpose (changing it would orphan every install that already trusts us).
            .setContentTitle(getString(R.string.app_name))
            // accent from @color so the shade the system derives from the notification tracks the
            // theme: mint on graphite in dark, the darker green on paper in light.
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.accent))
            .setColorized(false)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)                    // no beep storm on every 1s update
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_disconnect), disconnect)
            .build()
    }

    private fun flagImmutable() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "اتصال VPN", NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "نمایش سرعت و وضعیت اتصال؛ برای نگه‌داشتن تونل لازم است"
                        setShowBadge(false)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    }
                )
            }
        }
    }

    /* ------------------------------------------------------------ resilience */

    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * On a network switch (wifi -> cell) the tunnel socket is dead. Reconnect the *engine*,
     * do not tear the notification down: a flash of "disconnected" is what users read as a crash.
     */
    private fun registerNetworkCallback() {
        connectivity = getSystemService(ConnectivityManager::class.java)
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (!hasVpnUnderlying()) return
                serviceScope.launch { runCatching { CoreApi.rebindUnderlying(this@MeelanoVpnService) } }
            }

            override fun onLost(network: android.net.Network) {
                _phase.value = ConnectPhase.Handshake(0.4f)
                serviceScope.launch {
                    KeepAlive.retryWithBackoff(this@MeelanoVpnService)
                }
            }
        }
        networkCallback = cb
        runCatching { connectivity?.registerDefaultNetworkCallback(cb) }
    }

    private fun hasVpnUnderlying(): Boolean {
        val cm = connectivity ?: return false
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION") runCatching { stopForeground(true) }
        }
        stopSelf()
    }

    /* ------------------------------------------------------------ TunnelEngine (device side)
     * Replace the bodies with your core's calls. Keep everything suspend/IO here; the
     * orchestrator already guarantees this never runs on a main thread.
     */
        /**
     * The profile is a pure function of (node, resolved tune). Resolution order lives in
     * net/Regime.kt: regime default <- feed patch <- user switches. Keeping it here (and nowhere
     else) is what makes "why did this node fail" answerable from the log.
     */
    override fun buildProfileJson(node: FeedNode): String =
        CoreProfiles.toJson(node, protect = ::protect, tune = ir.meelano.vpn.data.AppSettings.tuneFor(node))
    override suspend fun startProxy(spec: TunnelSpec) {
        engineStartedAt = System.currentTimeMillis()
        CoreApi.startProxy(this, spec)
    }

    override fun attachTunnelFd(fd: java.io.FileDescriptor) {
        CoreApi.attachFd(this, fd)
    }

    override suspend fun onTunnelUp() = CoreApi.tunnelUp(this)
    override fun isTunnelAlive(): Boolean = CoreApi.alive()
    override fun rxBytes(): Long = engineRx()
    override fun txBytes(): Long = engineTx()
    override suspend fun stop() = CoreApi.stop(this)

    private fun engineRx(): Long = runCatching { CoreApi.rxBytes() }.getOrDefault(0L)
    private fun engineTx(): Long = runCatching { CoreApi.txBytes() }.getOrDefault(0L)

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_DISCONNECT) {
                serviceScope.launch { orchestrator.disconnect(); stopSelfSafely() }
            }
        }
    }
}

/**
 * A rolling window of per-second rates for [SpeedChart]. Value type with copy-on-write arrays on purpose:
 * at 1 Hz and 120 samples that is ~1 KB per frame of garbage at most, and `remember(trace)` can then diff
 * by identity instead of threading a snapshot-mutable list through Compose (which is how chart code
 * usually ends up not recomposing at all).
 *
 * `marks` are indices *into the current window*, so trimming shifts them; a mark that scrolls out is
 * dropped, never clamped to zero - a marker pinned to the left edge would claim "the session started now"
 * forever, which is the kind of detail that makes people distrust the whole chart.
 */
data class TrafficTrace(
    val down: FloatArray = FloatArray(0),
    val up: FloatArray = FloatArray(0),
    val marks: IntArray = IntArray(0),
    /** Samples since the service started, so trimming can shift marks without losing the real index. */
    val totalSamples: Int = 0,
) {
    fun push(d: Float, u: Float, mark: Boolean): TrafficTrace {
        val nd = down + d
        val nu = up + u
        val nm = if (mark) marks + (totalSamples) else marks
        val drop = nd.size - CAPACITY
        if (drop <= 0) {
            return TrafficTrace(nd, nu, nm, totalSamples + 1)
        }
        val shifted = nm.filter { it - drop >= 0 }.map { it - drop }.toIntArray()
        return TrafficTrace(
            nd.copyOfRange(drop, nd.size),
            nu.copyOfRange(drop, nu.size),
            shifted,
            totalSamples + 1,
        )
    }

    /** The scale. Floored, because dividing by ~0 is how an idle link draws a spike storm. */
    fun peak(): Float {
        var m = 0f
        var i = 0
        while (i < down.size) { if (down[i] > m) m = down[i]; if (up[i] > m) m = up[i]; i++ }
        return if (m < MIN_PEAK) MIN_PEAK else m
    }

    companion object {
        const val CAPACITY = 120
        /** An idle link still gets a sane axis; 24 KB/s. */
        const val MIN_PEAK = 24_576f
    }
}

/** UI-friendly traffic snapshot (bytes + per-second rates). */
data class Traffic(
    val rx: Long = 0,
    val tx: Long = 0,
    val rxPerSec: Long = 0,
    val txPerSec: Long = 0,
    val startedAt: Long = 0,
    val seconds: Int = 0,
)
