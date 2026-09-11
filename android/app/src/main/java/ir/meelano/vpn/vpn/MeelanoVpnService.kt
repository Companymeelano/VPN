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

    private val ticker = object : Runnable {
        override fun run() {
            updateTraffic()
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

        // coalescing rules: >=1s since last push AND the numbers moved by >8% (or state flipped)
        val now = System.currentTimeMillis()
        val movedEnough = kotlin.math.abs(rx - lastNotifiedRx) * 12 > kotlin.math.abs(lastNotifiedRx) * 100 + 4096 ||
            kotlin.math.abs(tx - lastNotifiedTx) * 12 > kotlin.math.abs(lastNotifiedTx) * 100 + 4096
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
            .setContentTitle("Meelano")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)                    // no beep storm on every 1s update
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "قطع اتصال", disconnect)
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
    override fun buildProfileJson(node: FeedNode): String = CoreProfiles.toJson(node, protect = ::protect)
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

/** UI-friendly traffic snapshot (bytes + per-second rates). */
data class Traffic(
    val rx: Long = 0,
    val tx: Long = 0,
    val rxPerSec: Long = 0,
    val txPerSec: Long = 0,
    val startedAt: Long = 0,
    val seconds: Int = 0,
)
