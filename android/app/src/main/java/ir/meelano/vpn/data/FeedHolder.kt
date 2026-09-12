package ir.meelano.vpn.data

import android.content.Context

/**
 * Deliberately tiny: the service, the QS tile and the boot receiver all need the repository,
 * and none of them can inject it (a started Service is not created by the app's DI graph).
 * `init()` runs in Application.onCreate; `pendingConnectId` is the "which node did the user just
 * pick" hint that survives the hand-off into the service - that indirection is what fixes
 * "I selected a proxy and nothing changed".
 */
object FeedHolder {
    @Volatile lateinit var repo: ServerFeedRepository
    @Volatile var pendingConnectId: String? = null
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            repo = ServerFeedRepository(context.applicationContext)
            ready = true
        }
    }

    fun isReady(): Boolean = ready

    /** Called by the service when it is done dialing: the repo can start reporting too. */
    fun onTunnelStarted() {
        if (ready) runCatching { repo.startReporting() }
    }
}
