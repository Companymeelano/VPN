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
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import ir.meelano.vpn.MainActivity
import ir.meelano.vpn.R
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.NodeRanking
import ir.meelano.vpn.data.FeedHolder
import ir.meelano.vpn.data.Prefs
import ir.meelano.vpn.keepalive.KeepAlive
import ir.meelano.vpn.keepalive.ActiveNodeCache
import ir.meelano.vpn.net.RecoveryPolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ir.meelano.vpn.data.TunnelSpec

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
        const val EXTRA_REORDER = "smart_order"

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

        fun start(context: Context, node: FeedNode, smartOrder: Boolean = true) {
            Prefs.setVpnDesired(context, true)
            val i = Intent(context, MeelanoVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_NODE_ID, node.id)
                putExtra(EXTRA_REORDER, smartOrder)
            }
            runCatching { context.startForegroundService(i) }
                .onFailure {
                    // pre-O / restricted-launch fallback. This call can itself throw
                    // IllegalStateException (background-start denied on 12+): swallow it here - the
                    // WorkManager watchdog re-tries in every case, and an uncaught throw inside a
                    // broadcast receiver (boot) takes the whole process down with zero user benefit.
                    runCatching { context.startService(i) }
                }
            // the full node payload goes through the repo (already cached) instead of an Intent:
            // Intent extras are size-limited, and the service re-reads the node by id anyway.
            FeedHolder.pendingConnectId = node.id
        }

        fun stop(context: Context) {
            Prefs.setVpnDesired(context, false)
            context.startService(
                Intent(context, MeelanoVpnService::class.java).setAction(ACTION_DISCONNECT)
            )
        }

        fun isRunning(): Boolean = _phase.value is ConnectPhase.Connected && alive != null

        /**
         * True while a connect attempt is mid-flight (profile write .. verify). KeepAlive's retry
         * loop uses this to *wait out* a live attempt instead of superseding it: restarting a race
         * that is one candidate away from success is how "retry for resilience" becomes
         * "reconnect forever". (A stalled Handshake(0.2f) also reads as connecting here - the
         * health monitor either re-publishes Connected or failovers within seconds, and the retry
         * loop's bounded settle window then steps aside for that attempt too.)
         */
        fun isConnecting(): Boolean = alive != null && when (_phase.value) {
            is ConnectPhase.WritingProfile, is ConnectPhase.Resolving, is ConnectPhase.Handshake,
            is ConnectPhase.TunnelUp, is ConnectPhase.Verifying -> true
            else -> false
        }

        /**
         * The user edited the per-app routing rules while a tunnel was up. Interface rules are baked
         * at establish() time - nothing short of rebuilding the TUN applies them - so restart the
         * connection on the CURRENT node with reorder=false: the node itself is fine, only the
         * disallowed-app list changed, and re-probing the whole pool would punish the edit with a
         * ten-second re-race. No-op unless the user actually wants the tunnel on.
         */
        fun reapplyRouting(context: Context) {
            if (!Prefs.vpnDesired(context)) return
            val id = Prefs.activeId(context) ?: return
            runCatching {
                context.startService(
                    Intent(context, MeelanoVpnService::class.java)
                        .setAction(ACTION_CONNECT)
                        .putExtra(EXTRA_NODE_ID, id)
                        .putExtra(EXTRA_REORDER, false)
                )
            }
        }

        /**
         * Public-source proxy scans must bypass an already-running VPN or they only prove that a proxy
         * works from the old exit server. No service exists during a normal cold scan, in which case a
         * raw socket is already direct and needs no protection call.
         */
        fun protectFeedSocket(socket: java.net.Socket): Boolean =
            alive?.let { service -> runCatching { service.protect(socket) }.getOrDefault(false) } ?: true
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var orchestrator: VpnOrchestrator
    private val statHandler = Handler(Looper.getMainLooper())
    private var engineStartedAt = 0L
    private val tunnelProbe = TunnelProbe()
    private var lastNotifiedAt = 0L
    private var lastNotifiedRx = -1L
    private var lastNotifiedTx = -1L
    private var lastTraceStart = -1L

    /**
     * One recovery chain per down episode (see the collector in onCreate). Re-armed only on
     * Connected/Idle, so a genuinely dead network cannot turn KeepAlive.retryWithBackoff into a
     * retry storm: the chain is the budget, the 15-minute watchdog is the parked-state backstop.
     */
    private val recoveryArmed = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        alive = this
        createChannel()
        // IMPORTANT: post the notification first, cheaply. Android 12+ kills the process
        // if startForeground() is not reached within ~10s of startForegroundService().
        startForeground(NOTIF_ID, buildNotification("در حال آماده‌سازی…"))
        orchestrator = VpnOrchestrator(
            this, this, _phase,
            onConnected = { node, probe ->
                FeedHolder.pendingConnectId = node.id
                Prefs.setActiveId(this, node.id)
                ActiveNodeCache.save(this, node)
                // The *real* platform verdict, not a constant "clean": startOne's comment promises
                // the caller records what the probe saw (a geo-blocked Google at this exit is a fact
                // the failover ladder must rank on). 2.6.0 wrote "clean" unconditionally, so
                // platformRank() could never demote a platform-blocked node after it connected.
                FeedHolder.repo.recordPlatformVerdict(
                    node.id,
                    if (probe.platformBlocked) "platform_blocked" else "clean",
                )
                notify(buildNotification("متصل · آزمون اینترنت ${probe.latencyMs}ms"))
            },
            onResult = { node, ok, latency, error ->
                if (!ok && error?.contains("platform_blocked") == true) {
                    FeedHolder.repo.recordPlatformVerdict(node.id, "platform_blocked")
                }
                FeedHolder.repo.reportResult(node.id, ok, latency, error)
            },
        )
        registerNetworkCallback()
        // Seconds-level recovery on final failure. KeepAlive.retryWithBackoff existed since 2.5.0
        // with zero callers (bug 3 of the 2.6.1 review): every all_candidates_failed landed in the
        // Failed state and the *only* resurrection left was the 15-minute watchdog. The latch keeps
        // one chain per episode; KeepAlive itself waits out in-flight attempts instead of killing them.
        serviceScope.launch {
            _phase.collect { phase ->
                when {
                    phase is ConnectPhase.Connected || phase is ConnectPhase.Idle ->
                        recoveryArmed.set(false)
                    phase is ConnectPhase.Failed &&
                        RecoveryPolicy.isRetryableFailure(phase.reason) &&
                        Prefs.vpnDesired(this@MeelanoVpnService) &&
                        KeepAlive.autoConnectEnabled(this@MeelanoVpnService) &&
                        recoveryArmed.compareAndSet(false, true) -> {
                        KeepAlive.retryWithBackoff(this@MeelanoVpnService)
                    }
                }
            }
        }
        // targetSdk 35 = Android's new export rule: registering a receiver with no export flag
        // throws SecurityException on API 33+ *right here in onCreate*, so the service dies at birth
        // and the user only sees "اتصال برقرار نشد". NOT_EXPORTED: this action is the app's own
        // notification button, no other package should be able to deliver it.
        androidx.core.content.ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ACTION_DISCONNECT),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
                Prefs.setVpnDesired(this, false)
                serviceScope.launch {
                    orchestrator.disconnect()
                    stopSelfSafely()
                }
                return START_NOT_STICKY
            }
            else -> {
                // A null intent is Android restarting a sticky service. Reconnect only if the user left
                // the VPN on; an old active-id is history, not consent to resurrect after Disconnect.
                if (intent == null && !Prefs.vpnDesired(this)) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                if (intent?.action == ACTION_CONNECT) Prefs.setVpnDesired(this, true)
                val id = intent?.getStringExtra(EXTRA_NODE_ID) ?: FeedHolder.pendingConnectId ?: Prefs.activeId(this)
                val reorder = intent?.getBooleanExtra(EXTRA_REORDER, true) ?: true
                serviceScope.launch {
                    val node = id?.let { FeedHolder.repo.awaitNode(it) }
                        ?: id?.let { ActiveNodeCache.get(this@MeelanoVpnService, it) }
                    if (node == null) {
                        _phase.value = ConnectPhase.Failed("profile_missing")
                        stopSelfSafely()
                    } else {
                        val pool = FeedHolder.repo.failoverCandidates(node)
                        orchestrator.connect(pool, reorder = reorder)
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
        if (::orchestrator.isInitialized) orchestrator.destroy()
        runCatching { XrayBridge.stop() }
        serviceScope.cancel()
        alive = null
        _phase.value = ConnectPhase.Idle
        super.onDestroy()
    }

    override fun onRevoke() {
        Prefs.setVpnDesired(this, false)
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

    /** baseline for the per-tick rate; reset by the session flip, never by the clock */
    private var lastSampleAt = 0L
    private var lastSampleRx = 0L
    private var lastSampleTx = 0L

    /**
     * 1 Hz sampling; notification text is only pushed when it actually changed.
     *
     * The *rate* is bytes-per-second over the tick, not over the session. The old line divided the
     * increment since the previous sample by `now - engineStartedAt`, which makes the number a running
     * average that can only decay: a 5 MB/s download reads as ~100 KB/s one minute in, and a dead tunnel
     * reads as "slowly improving" forever. It was invisible for as long as nothing called this while the
     * app was open. A light EWMA (alpha 0.4) sits on top, because the raw per-second value flickers
     * enough to be unreadable next to the ring - but flickering-by-a-little beats lying-by-a-lot.
     */
    private fun updateTraffic() {
        val rx = engineRx()
        val tx = engineTx()
        val now = System.currentTimeMillis()
        val flipped = engineStartedAt != lastTraceStart
        val tickSec = if (flipped || lastSampleAt == 0L) 0f else (now - lastSampleAt) / 1000f
        val rxRaw = if (tickSec > 0.05f) ((rx - lastSampleRx).coerceAtLeast(0L) / tickSec).toLong() else -1L
        val txRaw = if (tickSec > 0.05f) ((tx - lastSampleTx).coerceAtLeast(0L) / tickSec).toLong() else -1L
        lastSampleAt = now
        lastSampleRx = rx
        lastSampleTx = tx
        val prev = _traffic.value
        val rxD = smoothRate(rxRaw, prev.rxPerSec, flipped)
        val txD = smoothRate(txRaw, prev.txPerSec, flipped)
        val dt = (now - engineStartedAt).coerceAtLeast(1) / 1000f
        _traffic.value = Traffic(rx, tx, rxD, txD, startedAt = engineStartedAt, seconds = dt.toInt())

        // one sample per tick, and a marker where the session flipped: the first flip is the connect,
        // a later one is a reconnect. Nobody presses a button for that, so the chart is the only place a
        // user can see it happened at all.
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

    /** -1 marks "no sample yet"; the first real reading always starts from the raw value. */
    private fun smoothRate(raw: Long, previous: Long, reset: Boolean): Long = when {
        raw < 0L || reset || previous < 0L -> raw.coerceAtLeast(0L)
        else -> ((previous * 0.6f) + (raw * 0.4f)).toLong().coerceAtLeast(0L)
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
     *
     * Two structural rules, both learned from the "connects only sometimes on Irancell" report:
     *
     *  1. We listen with a [NetworkRequest] carrying NET_CAPABILITY_NOT_VPN. The earlier
     *     `registerDefaultNetworkCallback` reports *the VPN itself* as the default network
     *     (carrying the underlying transports), so the callback could mistake our own tunnel
     *     coming up for a physical network change - a self-triggered reconnect right after a
     *     successful connect, precisely when the route is settling.
     *  2. The system is told which networks are underneath us via [setUnderlyingNetworks] on
     *     every change. Without it, Android cannot present the right metered/roaming state for
     *     the tunnel and OEM traffic policers treat the tunnel as unsourced; with it, a cell<->wifi
     *     hand-over is one coherent event instead of a stream of capability churn.
     *
     * A reconnect is scheduled only when the *primary* underlying network actually changes
     * while connected - capability noise on the same network (signal bars, bandwidth estimates,
     * which cellular fires constantly) is no longer an event at all.
     */
    private val underlyingLock = Any()
    private val underlyingNetworks = LinkedHashSet<Network>()
    private var networkReconnect: kotlinx.coroutines.Job? = null

    /**
     * Set when the last physical network died under a live tunnel (the Handshake(0.2f) stall);
     * consumed exactly once by the restore that follows it. See net/RecoveryPolicy.
     */
    @Volatile private var networkOutage = false

    private fun registerNetworkCallback() {
        connectivity = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateUnderlying(network, added = true)
            override fun onLost(network: Network) = updateUnderlying(network, added = false)
        }
        networkCallback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { connectivity?.registerNetworkCallback(request, cb) }
    }

    private fun updateUnderlying(network: Network, added: Boolean) {
        val oldPrimary: Network?
        val newPrimary: Network?
        synchronized(underlyingLock) {
            oldPrimary = underlyingNetworks.firstOrNull()
            if (added) {
                // Most-recently-available first: Android's own default follows the newest good
                // network (wifi joining while on cellular becomes the path), and the head of the
                // set is both our "primary" and the preference order setUnderlyingNetworks wants.
                val reordered = LinkedHashSet<Network>()
                reordered += network
                reordered += underlyingNetworks
                underlyingNetworks.clear()
                underlyingNetworks.addAll(reordered)
            } else {
                underlyingNetworks -= network
            }
            newPrimary = underlyingNetworks.firstOrNull()
            // Tell the platform what this tunnel stands on, every time that changes. null is the
            // documented "no specific underlying networks" value, and it is what a moment with no
            // physical network at all must look like - not yesterday's wifi.
            runCatching {
                setUnderlyingNetworks(if (underlyingNetworks.isEmpty()) null else underlyingNetworks.toTypedArray())
            }
        }
        // Every decision below lives in net/RecoveryPolicy (unit-tested row by row); this method only
        // carries the effects out. In particular the *restore* event (zero networks -> one) is the
        // one 2.6.0 dropped via an `oldPrimary == null` early return: after a failover burned every
        // candidate while the radio was down, nothing ever re-dialed and the app stayed down until
        // the 15-minute watchdog.
        when (RecoveryPolicy.onUnderlayEvent(
            event = RecoveryPolicy.classify(oldPrimary, newPrimary),
            phaseConnected = _phase.value is ConnectPhase.Connected,
            recoverable = _phase.value is ConnectPhase.Failed || _phase.value is ConnectPhase.Idle,
            outageMarked = networkOutage,
        )) {
            RecoveryPolicy.Action.NONE -> Unit
            RecoveryPolicy.Action.MARK_OUTAGE -> {
                // No retry loop here: the orchestrator's health monitor owns failover proofs.
                // Duplicate reconnectors are how two cores/two TUNs are born.
                networkOutage = true
                _phase.value = ConnectPhase.Handshake(0.2f)
            }
            RecoveryPolicy.Action.RECONNECT_CHANGED -> scheduleReconnect("network_changed")
            RecoveryPolicy.Action.RECONNECT_RESTORED -> {
                networkOutage = false
                scheduleReconnect("network_restored")
            }
        }
    }

    /**
     * The physical path moved under a live tunnel (or returned under a stalled one): the core's
     * sockets are bound to the old network and protect() cannot revive them. Reconnect through the
     * orchestrator (its generation guard supersedes any health-monitor attempt already in flight),
     * debounced briefly so a wifi flap mid-hand-over does not dial twice.
     */
    private fun scheduleReconnect(reason: String) {
        networkReconnect?.cancel()
        networkReconnect = serviceScope.launch {
            delay(900)
            // The physical network just changed: every cached answer (SmartResolver's pick cache
            // and NetGuard's DoH cache) was learned on the OLD path. A 30 s negative entry from a
            // dead Wi-Fi must not stall the first cellular connect — this is the «به سختی متصل
            // می‌شود بعد از جابه‌جایی» fix. Cheap (two map clears) and always correct to do.
            ir.meelano.vpn.net.SmartResolver.clearCache()
            ir.meelano.vpn.net.NetGuard.clearDnsCache()
            reconnectActive(reason)
        }
    }

    private suspend fun reconnectActive(reason: String) {
        if (!Prefs.vpnDesired(this)) return
        val id = Prefs.activeId(this) ?: return
        val node = FeedHolder.repo.awaitNode(id) ?: ActiveNodeCache.get(this, id) ?: return
        _phase.value = ConnectPhase.Handshake(0.15f)
        orchestrator.connect(FeedHolder.repo.failoverCandidates(node), reorder = true)
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
    override fun buildProfileJson(node: FeedNode, dialAddress: String): String =
        CoreProfiles.toJson(
            node,
            protect = ::protect,
            tune = ir.meelano.vpn.data.AppSettings.tuneFor(node),
            dialAddress = dialAddress,
        )

    override suspend fun orderCandidates(nodes: List<FeedNode>): List<FeedNode> = withContext(Dispatchers.IO) {
        if (!CoreApi.LINKED || nodes.size < 2) return@withContext nodes
        // runCandidates stops the previous core before this race. Re-register protection so these
        // temporary-core sockets bypass the still-held kill-switch TUN instead of looping into it.
        CoreApi.prepareProbeSockets(this@MeelanoVpnService)
        // Same poison-proof dial as startOne: the race must measure the address we will actually
        // use, or MCI's sinkhole answers rank real nodes dead before they are ever tried.
        //
        // 2.8.1 speed: resolution is PARALLEL (4 at a time) under one shared 4 s collection
        // budget — the old `associateWith` walked nodes one by one and a single uncached lookup
        // could eat seconds, so the pre-connect tax grew with the candidate list. With the budget
        // spent, whatever already resolved is used and the rest dial their hostnames (today's
        // behaviour). The app-start warm-up (MeelanoApp) keeps this block near-zero on the tap.
        val resolveStarted = System.currentTimeMillis()
        val dials: Map<FeedNode, String?> = runCatching {
            kotlinx.coroutines.coroutineScope {
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                val jobs = nodes.map { node ->
                    async {
                        gate.acquire()
                        try {
                            node to runCatching {
                                ir.meelano.vpn.net.SmartResolver.resolve(node.host).ip
                            }.getOrNull()
                        } finally {
                            gate.release()
                        }
                    }
                }
                jobs.mapNotNull { job ->
                    val left = 4_000L - (System.currentTimeMillis() - resolveStarted)
                    if (left <= 0) return@mapNotNull null
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(left) { job.await() }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        val configs = nodes.mapNotNull { node ->
            runCatching {
                node to CoreProfiles.pingConfigJson(
                    node,
                    ir.meelano.vpn.data.AppSettings.tuneFor(node),
                    dials[node].orEmpty(),
                )
            }.getOrNull()
        }
        if (configs.isEmpty()) return@withContext nodes

        fun measure(url: String): Map<String, Long>? {
            val result = LinkedHashMap<String, Long>()
            for (chunk in configs.chunked(5)) {
                // 3 s, not 5 s: pingBatch's timeout is per chunk (5 dials in parallel inside the
                // core), so the old value billed a dead chunk 5 s of connect time. Two chunks = the
                // whole race fits the 6 s budget below with room for the tie-breaker.
                val delays = XrayBridge.pingBatch(chunk.map { it.second }, timeoutSec = 3, url = url) ?: return null
                chunk.forEachIndexed { i, pair -> result[pair.first.id] = delays.getOrElse(i) { 11_000L } }
            }
            return result
        }

        fun ranked(delays: Map<String, Long>): List<FeedNode> =
            nodes.sortedWith(compareBy<FeedNode>(
                { node -> if ((delays[node.id] ?: 11_000L) < 10_000L) 0 else 1 },
                { node -> delays[node.id] ?: 11_000L },
                { NodeRanking.bucket(it, ir.meelano.vpn.data.AppSettings.effectiveRegime()) },
                { it.slot },
            ))

        // One race, not two: Cloudflare's connectivity endpoint answers from any working exit,
        // so a candidate that wins it has already proved the full transport. The old code then
        // *always* ran a second, sequential Google race before dialling anything - up to ten
        // extra seconds on a slow carrier, paid on every reconnect, to learn a fact (does this
        // exit reach Google?) that TunnelProbe measures again through the real TUN anyway.
        // Google is now only the tie-breaker of last resort, when the first race proved nothing.
        // The whole race is a CONNECT-time cost, so it gets a hard 6 s ceiling (2.8.1): on timeout
        // the pool keeps its current order and dialing simply begins — the old code had no ceiling
        // and a network that answered nothing made the user watch «در حال اتصال» for the full sum
        // of every chunk timeout before the first attempt. Giving up on ranking is cheap (the
        // per-node probe still measures truth); giving up on *connecting* is not.
        val raced: List<FeedNode>? = kotlinx.coroutines.withTimeoutOrNull(6_000L) {
            val cloudflare = measure("https://cp.cloudflare.com/generate_204")
            when {
                cloudflare == null -> nodes
                cloudflare.values.any { it < 10_000L } -> ranked(cloudflare)
                else -> {
                    val google = measure("https://www.google.com/generate_204")
                    when {
                        google == null -> nodes
                        google.values.none { it < 10_000L } -> nodes
                        else -> ranked(google)
                    }
                }
            }
        }
        raced ?: nodes
    }

    override suspend fun startProxy(spec: TunnelSpec) {
        engineStartedAt = System.currentTimeMillis()
        CoreApi.startProxy(this, spec)
    }

    override fun attachTunnelFd(fd: Int) {
        CoreApi.attachFd(this, fd)
    }

    override suspend fun onTunnelUp() = CoreApi.tunnelUp(this)
    override suspend fun verifyTunnel(): TunnelProbe.Result = tunnelProbe.run()
    override fun isTunnelAlive(): Boolean = CoreApi.alive()
    override fun rxBytes(): Long = engineRx()
    override fun txBytes(): Long = engineTx()
    override suspend fun stop() = CoreApi.stop(this)

    private fun engineRx(): Long = runCatching { CoreApi.rxBytes() }.getOrDefault(0L)
    private fun engineTx(): Long = runCatching { CoreApi.txBytes() }.getOrDefault(0L)

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_DISCONNECT) {
                Prefs.setVpnDesired(this@MeelanoVpnService, false)
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
