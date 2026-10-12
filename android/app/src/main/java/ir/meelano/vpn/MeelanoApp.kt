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

        // FeedHolder's repository paints its cache and starts one coalesced direct refresh. Starting a
        // second copy here used to download every source and deep-scan every proxy twice on cold launch.
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

fun onUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val n = cm.activeNetwork ?: return false
    val c = cm.getNetworkCapabilities(n) ?: return false
    return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
