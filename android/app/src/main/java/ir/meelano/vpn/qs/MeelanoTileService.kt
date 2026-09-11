package ir.meelano.vpn.qs

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ir.meelano.vpn.MainActivity
import ir.meelano.vpn.R
import ir.meelano.vpn.data.FeedNodeStub
import ir.meelano.vpn.data.Prefs
import ir.meelano.vpn.data.FeedHolder
import ir.meelano.vpn.vpn.ConnectPhase
import ir.meelano.vpn.vpn.MeelanoVpnService
import ir.meelano.vpn.vpn.Traffic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile: the "keep-alive + one-tap disconnect + live speed" control that the
 * brief puts at the top of the phone. Pull the shade down and the tile *is* the dashboard —
 * no unlock, no opening the app, no waiting for a compose tree to attach.
 *
 *   tap        -> toggle the tunnel (connect with the last used node / disconnect now)
 *   long-press -> Android opens the app by default (activity-alias below makes it explicit)
 *
 * Two rules that keep it from being a source of jank:
 *   1. onClick must return fast: start the service, never resolve DNS or build a profile here
 *      (a TileService gets a few seconds of foreground time, and SystemUI is waiting on you);
 *   2. push a tile update only when the label actually changed, at most ~1 Hz.
 */
class MeelanoTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    private var lastKey = ""

    override fun onStartListening() {
        super.onStartListening()
        observing?.cancel()
        observing = scope.launch {
            combine(MeelanoVpnService.phase, MeelanoVpnService.traffic) { p, t -> p to t }
                .collect { (p, t) -> render(p, t) }
        }
    }

    override fun onStopListening() {
        observing?.cancel()
        observing = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(phase: ConnectPhase, traffic: Traffic) {
        val tile = qsTile ?: return
        val label: String
        val state: Int
        val sub: String
        when (phase) {
            is ConnectPhase.Connected -> {
                label = "Meelano"
                state = Tile.STATE_ACTIVE
                sub = "↓ ${human(traffic.rxPerSec)} · ↑ ${human(traffic.txPerSec)}"
            }
            is ConnectPhase.Idle -> {
                label = "قطع است"
                state = Tile.STATE_INACTIVE
                sub = "برای اتصال لمس کنید"
            }
            is ConnectPhase.Failed -> {
                label = "خطا"
                state = Tile.STATE_UNAVAILABLE
                sub = phase.reason.take(24)
            }
            else -> {
                label = "در حال اتصال…"
                state = Tile.STATE_ACTIVE
                sub = "${(phase.progress * 100).toInt()}%"
            }
        }
        val key = "$state|$label|$sub"
        if (key == lastKey) return                       // unchanged -> don't poke SystemUI
        lastKey = key
        tile.state = state
        tile.label = label
        tile.contentDescription = "Meelano VPN · $sub"
        if (Build.VERSION.SDK_INT >= 34) {
            tile.subtitle = sub        // Tile.setSubtitle: API 34+; the label line above covers older versions
        }
        runCatching { Icon.createWithResource(this, R.drawable.ic_qs_vpn) }.getOrNull()?.let { tile.icon = it }
        runCatching { tile.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (MeelanoVpnService.isRunning()) {
            MeelanoVpnService.stop(this)
            return
        }
        val id = Prefs.activeId(this)
        if (id == null) {
            openApp()
            return
        }
        // The service re-reads the cached profile by id on its own worker thread; here we only
        // hand it a hint so the very first tick already shows the right node.
        FeedHolder.pendingConnectId = id
        MeelanoVpnService.start(this, FeedNodeStub.forId(id))
    }

    @SuppressLint("MissingPermission")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityAndCollapse(i) }.onFailure { startActivity(i) }
        }
    }

    private fun human(bps: Long): String = when {
        bps <= 0 -> "0"
        bps < 1024 -> "${bps}B"
        bps < 1048576 -> "${bps / 1024}K"
        else -> String.format("%.1fM", bps / 1048576.0)
    }
}
