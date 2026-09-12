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
        FeedHolder.init(this)
        updates = UpdateManager(this, BuildConfig.FEED_BASE_URL)

        appScope.launch {
            // 1) the feeds are what the whole first screen depends on: start them before the UI asks
            runCatching { FeedHolder.repo.refresh(ServerFeedKinds.VIP) }
            delay(120)                       // let the VIP list land first: it is small and it is the one that unlocks connect
            runCatching { FeedHolder.repo.refresh(ServerFeedKinds.FREE) }
        }
        appScope.launch {
            delay(3_000)                     // never race the cold start with a network check
            // BuildConfig.SELF_UPDATE is the compile-time kill switch (a debug or a Play-bound
            // build must never try to install anything); AppSettings.autoUpdate is the user's choice
            if (BuildConfig.SELF_UPDATE && AppSettings.autoUpdate && onUnmetered(this@MeelanoApp)) {
                updates.checkAsync()
            }
        }
    }

    companion object {
        @Volatile lateinit var instance: MeelanoApp private set
    }
}

private object ServerFeedKinds {
    const val VIP = "vip"
    const val FREE = "free"
}

fun onUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val n = cm.activeNetwork ?: return false
    val c = cm.getNetworkCapabilities(n) ?: return false
    return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
