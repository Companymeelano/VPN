package ir.meelano.vpn.keepalive

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ir.meelano.vpn.data.FeedJson
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.Prefs
import ir.meelano.vpn.vpn.MeelanoVpnService
import java.io.File
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * "Closing the app must not close the VPN" + "if the tunnel dies, it must come back".
 *
 * What actually works on Android 12-15 without root or device-owner:
 *   - a started foreground service with a persistent notification (see MeelanoVpnService)
 *   - START_STICKY + an explicit reconnect path
 *   - a WorkManager watchdog (15 min) for the case where the process was killed hard
 *   - an exact alarm to relaunch after a reboot / crash
 *   - the user whitelisting us from battery optimisation (we must ASK, we cannot opt in)
 *
 * What does NOT work, so don't promise it in the UI:
 *   - `android:persistent` (system apps only)
 *   - silently surviving an aggressive OEM "auto-start = off" (Xiaomi/Huawei/Oppo): the only
 *     honest fix is an onboarding screen that walks them to the right toggle.
 */
object KeepAlive {

    private const val WORK_TAG = "meelano_watchdog"

    fun enableWatchdog(context: Context) {
        if (!autoConnectEnabled(context)) return
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG, ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    fun disableWatchdog(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG)
    }

    suspend fun onVpnStarted(context: Context) {
        enableWatchdog(context)
    }

    /** Called when the tunnel dropped: retry a few times, then park - never loop at 0ms. */
    suspend fun retryWithBackoff(context: Context, attempts: Int = 4) {
        var wait = 1_500L
        repeat(attempts) {
            delay(wait)
            val id = Prefs.activeId(context) ?: return
            val node = ActiveNodeCache.get(context, id) ?: return
            if (!MeelanoVpnService.isRunning()) {
                MeelanoVpnService.start(context, node)
            }
            wait = (wait * 2).coerceAtMost(30_000L)
        }
    }

    /**
     * The user's "اتصال خودکار/هوشمند" switch (the same `smart_reconnect` pref the Settings row writes).
     * It used to read an `auto_connect` key that *nothing in the app ever wrote* - which made the
     * watchdog, the boot receiver and the retry loop permanently on and the toggle a fake. One key, one
     * meaning: when the user turns smart reconnect off, every automatic resurrection path goes quiet.
     */
    fun autoConnectEnabled(context: Context) =
        context.getSharedPreferences("meelano_prefs", Context.MODE_PRIVATE)
            .getBoolean("smart_reconnect", true)

    /** Intent for the onboarding row: "اجازه‌ی اجرا در پس‌زمینه" */
    fun batteryOptimizationIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val pm = context.getSystemService(PowerManager::class.java) ?: return null
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return null
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }
}

class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val id = Prefs.activeId(ctx)
        if (id == null || !KeepAlive.autoConnectEnabled(ctx)) return Result.success()
        if (VpnService.prepare(ctx) != null) return Result.success()   // user must re-consent
        if (!MeelanoVpnService.isRunning()) {
            ActiveNodeCache.get(ctx, id)?.let { MeelanoVpnService.start(ctx, it) }
        }
        return Result.success()
    }
}

/** Relaunch the tunnel after a reboot, if the user left it "on". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED &&
            a != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        if (!KeepAlive.autoConnectEnabled(context)) return
        val id = Prefs.activeId(context) ?: return
        val node = ActiveNodeCache.get(context, id) ?: return
        if (VpnService.prepare(context) == null) {
            MeelanoVpnService.start(context, node)
        }
    }
}

/**
 * The service must be able to reconnect with no UI alive, so the last chosen node is cached on
 * disk (the in-memory repo would be gone). We store the node JSON, not a built profile: a
 * server-side config fix then applies itself on the next boot.
 */
object ActiveNodeCache {
    private const val FILE = "active_node.json"

    fun save(context: Context, node: FeedNode) {
        runCatching {
            File(context.getDir("feed", Context.MODE_PRIVATE), FILE)
                .writeText(FeedJson.encode(node).toString())
        }
    }

    fun get(context: Context, id: String): FeedNode? = runCatching {
        val f = File(context.getDir("feed", Context.MODE_PRIVATE), FILE)
        if (!f.isFile) return@runCatching null
        val o = JSONObject(f.readText())
        if (o.optString("id") != id) return@runCatching null
        FeedJson.node(o)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.getDir("feed", Context.MODE_PRIVATE), FILE).delete() }
    }
}
