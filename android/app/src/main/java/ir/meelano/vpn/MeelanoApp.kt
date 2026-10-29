package ir.meelano.vpn

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ir.meelano.vpn.data.FeedHolder
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process start-up. Everything here is off the main thread except the two things that must not be:
 * reading the preferences (mmap, ~1 ms) and creating the singletons.
 *
 * The reason `FeedHolder.init` exists at all: the service, the QS tile and the boot receiver cannot
 * share a DI graph, so one process-wide holder with an already-cold-started OkHttp client is cheaper
 * than three DNS lookups on first tap. A tap that fires a request *and* a DNS lookup *and* a TLS
 * handshake is the tap that feels frozen.
 */
class MeelanoApp : Application() {

    lateinit var updates: UpdateManager
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppSettings.load(this)
        ir.meelano.vpn.data.PerAppRouting.load(this)
        FeedHolder.init(this)
        // Every resolver socket (NetGuard/SmartResolver DoH) bypasses a live or kill-switched TUN:
        // during failover the old TUN is intentionally dead and would swallow unprotected lookups.
        ir.meelano.vpn.net.NetGuard.socketProtect = { s ->
            ir.meelano.vpn.vpn.MeelanoVpnService.protectFeedSocket(s)
        }
        updates = UpdateManager(this, BuildConfig.FEED_BASE_URL)

        // FeedHolder's repository paints its cache and starts one coalesced direct refresh. Starting a
        // second copy here used to download every source and deep-scan every proxy twice on cold launch.
        appScope.launch {
            delay(3_000)                     // never race the cold start with a network check
            // BuildConfig.SELF_UPDATE is the compile-time kill switch (a debug or a Play-bound
            // build must never try to install anything). «بررسی خودکار نسخه» (autoCheckUpdate) runs
            // on any network — it is one tiny GET; the *download* is what autoUpdate gates on Wi-Fi.
            if (BuildConfig.SELF_UPDATE && AppSettings.autoCheckUpdate) {
                updates.checkAsync()
            }
        }
        // 2.8.1 connect-speed warm-up: resolve the hostnames of the nodes the user is most likely
        // to dial BEFORE the first tap. SmartResolver.resolve fills both caches (its own + the
        // NetGuard DoH cache), so the hot path in orderCandidates/startOne hits memory instead of
        // the network — on a poisoned-DNS carrier this is the difference between an instant dial
        // and a full resolver budget per node. All six in parallel, fire-and-forget; a failure
        // costs nothing (the connect path resolves again anyway).
        appScope.launch {
            delay(2_500)                    // same cold-start courtesy as the update check above
            runCatching {
                val nodes = (FeedHolder.repo.vip.value + FeedHolder.repo.free.value).distinctBy { it.host }
                coroutineScope {
                    nodes.take(6).map { node ->
                        async {
                            runCatching { ir.meelano.vpn.net.SmartResolver.resolve(node.host) }
                        }
                    }
                }
            }
        }
        appScope.launch {
            delay(4_000)
            // Warm the installed-app scan (data/PerAppRouting.kt) off the hot paths: the first tunnel
            // establish() and the first routing-sheet open both read this cache, and doing the binder
            // walk over every installed app right there is a hesitation you can feel on a slow phone.
            runCatching { ir.meelano.vpn.data.PerAppRouting.scan(this@MeelanoApp) }
        }
        // «دریافت خودکار»: periodic feed sync per the user's interval (data/FeedScheduler).
        // Enqueued on every start: WorkManager dedupes by tag, so this is idempotent, and a changed
        // interval is applied by the UPDATE policy inside schedule().
        runCatching { ir.meelano.vpn.data.FeedScheduler.schedule(this) }
        // «بررسی خودکار نسخه»: daily version check (update/UpdateScheduler), same idiom.
        runCatching { ir.meelano.vpn.update.UpdateScheduler.schedule(this) }
    }

    companion object {
        @Volatile lateinit var instance: MeelanoApp private set
    }
}

fun onUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val n = cm.activeNetwork ?: return false
    val c = cm.getNetworkCapabilities(n) ?: return false
    return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
