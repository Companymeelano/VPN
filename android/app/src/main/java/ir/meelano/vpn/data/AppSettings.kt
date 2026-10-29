package ir.meelano.vpn.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User-visible settings, in their own SharedPreferences file (`meelano_prefs`) so the backup rule in
 * AndroidManifest can name it. Deliberately NOT DataStore: the QS tile, the boot receiver and the
 * service all need to read this synchronously, from three different processes' worth of lifecycles,
 * and a blocking `runBlocking` in a tile's onClick is how you get a 200 ms tile lag.
 *
 * A snapshot flow exists only so the Settings screen recomposes; the widgets read the cache.
 */
object AppSettings {
    private const val PREF = "meelano_prefs"

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    const val THEME_SYSTEM = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2

    /**
     * Legacy numeric values are retained for preference/back-up compatibility. Current builds migrate
     * every value to DIRECT at startup: server/config discovery and proxy testing are phone-side only.
     */
    const val FEED_HOST = 0
    const val FEED_AUTO = 1
    const val FEED_DIRECT = 2

    var smartReconnect by mutableStateOf(true)
        private set
    var killSwitch by mutableStateOf(true)
        private set
    var secureDns by mutableStateOf(true)
        private set
    var reducedMotion by mutableStateOf(false)
        private set
    var autoUpdate by mutableStateOf(true)
        private set
    /** «بررسی خودکار نسخه» — periodic version check (update/UpdateScheduler); the download stays gated on [autoUpdate] + unmetered. */
    var autoCheckUpdate by mutableStateOf(true)
        private set
    /** «دریافت خودکار» — the periodic background feed sync (data/FeedScheduler). */
    var autoFetch by mutableStateOf(true)
        private set
    /** Hours between automatic feed refreshes; 6 is the honest TTL of a public list. */
    var feedIntervalHours by mutableStateOf(6)
        private set
    /** Always FEED_DIRECT; public constants only exist to migrate older installs safely. */
    var feedMode by mutableStateOf(FEED_DIRECT)
        private set
    /** Optional: the user's own subscription/list URL, read by the phone in DIRECT mode. */
    var feedExtraUrl by mutableStateOf("")
        private set
    var feedback by mutableStateOf(true)
        private set
    var themeMode by mutableStateOf(THEME_SYSTEM)
        private set
    var autoSelect by mutableStateOf(true)
        private set
    var onboardingDone by mutableStateOf(false)
        private set
    var skippedVersion by mutableStateOf(0)
        private set
    var seenVersion by mutableStateOf(0)
        private set

    /*
     * Anti-blocking state (phase 6). These are not decoration: they are what the generated core
     * profile is built from, and "tight" is the honest answer for Iran in 2026 - SNI filtering plus
     * RST injection plus TLS-shape detection, which is exactly the case that needs fragmentation and
     * Reality-first ordering. `autoConnectOnBoot` exists because a VPN that stays off until you notice
     * it is off is not a VPN.
     */
    var regime by mutableStateOf("tight")
        private set
    var fragmentAuto by mutableStateOf(true)
        private set
    var muxEnabled by mutableStateOf(true)
        private set
    var realityFirst by mutableStateOf(true)
        private set
    var autoConnectOnBoot by mutableStateOf(true)
        private set
    var mtu by mutableStateOf(1280)
        private set
    /** JSON blob of the last local probe verdict; uploaded with the feedback so the fleet learns. */
    var lastBlockReport by mutableStateOf("")
        private set
    /** What the local detector concluded from that report ("auto" regime follows this). */
    var detectedRegime by mutableStateOf("tight")
        private set

    private val _snapshot = MutableStateFlow<Map<String, Any>>(emptyMap())
    val snapshot: StateFlow<Map<String, Any>> = _snapshot

    /** call once from Application.onCreate; also the only place that touches disk on the main thread (it is mmap'd, ~1 ms) */
    fun load(context: Context) {
        val p = sp(context)
        smartReconnect = p.getBoolean("smart_reconnect", true)
        killSwitch = p.getBoolean("kill_switch", true)
        secureDns = p.getBoolean("secure_dns", true)
        reducedMotion = p.getBoolean("reduced_motion", false)
        regime = p.getString("regime", "tight") ?: "tight"
        fragmentAuto = p.getBoolean("fragment_auto", true)
        muxEnabled = p.getBoolean("mux_enabled", true)
        realityFirst = p.getBoolean("reality_first", true)
        autoConnectOnBoot = p.getBoolean("auto_connect_boot", true)
        mtu = p.getInt("mtu", 1280)
        lastBlockReport = p.getString("block_report", "") ?: ""
        detectedRegime = p.getString("detected_regime", "tight") ?: "tight"
        autoUpdate = p.getBoolean("auto_update", true)
        autoCheckUpdate = p.getBoolean("auto_check_update", true)
        autoFetch = p.getBoolean("auto_fetch", true)
        feedIntervalHours = p.getInt("feed_interval_hours", 6).coerceIn(1, 24)
        // One-way migration: old releases persisted HOST/AUTO. Merely changing the default would leave
        // those users waiting on the private backend forever, so rewrite the stored value as well.
        feedMode = FEED_DIRECT
        if (p.getInt("feed_mode", FEED_DIRECT) != FEED_DIRECT) {
            p.edit().putInt("feed_mode", FEED_DIRECT).apply()
        }
        feedExtraUrl = p.getString("feed_extra_url", "") ?: ""
        feedback = p.getBoolean("feedback", true)
        themeMode = p.getInt("theme_mode", THEME_SYSTEM)
        autoSelect = p.getBoolean("auto_select", true)
        onboardingDone = p.getBoolean("onboarding_done", false)
        skippedVersion = p.getInt("skip_version", 0)
        seenVersion = p.getInt("seen_version", 0)
        publish()
    }

    fun setSmartReconnect(c: Context, v: Boolean) = write(c, "smart_reconnect", v) { smartReconnect = v }
    fun setKillSwitch(c: Context, v: Boolean) = write(c, "kill_switch", v) { killSwitch = v }
    fun setSecureDns(c: Context, v: Boolean) = write(c, "secure_dns", v) { secureDns = v }
    fun setReducedMotion(c: Context, v: Boolean) = write(c, "reduced_motion", v) { reducedMotion = v }
    fun setAutoUpdate(c: Context, v: Boolean) = write(c, "auto_update", v) { autoUpdate = v }
    fun setAutoCheckUpdate(c: Context, v: Boolean) = write(c, "auto_check_update", v) { autoCheckUpdate = v }
    fun setAutoFetch(c: Context, v: Boolean) = write(c, "auto_fetch", v) { autoFetch = v }
    fun setFeedIntervalHours(c: Context, v: Int) = write(c, "feed_interval_hours", v.coerceIn(1, 24)) {
        feedIntervalHours = v.coerceIn(1, 24)
    }
    /** Legacy UI/API call: private-host modes are no longer selectable or reachable. */
    fun setFeedMode(c: Context, @Suppress("UNUSED_PARAMETER") v: Int) =
        write(c, "feed_mode", FEED_DIRECT) { feedMode = FEED_DIRECT }
    fun setFeedExtraUrl(c: Context, v: String) = write(c, "feed_extra_url", v.trim()) { feedExtraUrl = v.trim() }
    fun setFeedback(c: Context, v: Boolean) = write(c, "feedback", v) { feedback = v }
    fun setAutoSelect(c: Context, v: Boolean) = write(c, "auto_select", v) { autoSelect = v }
    fun setThemeMode(c: Context, v: Int) = write(c, "theme_mode", v) { themeMode = v }
    fun setOnboardingDone(c: Context) = write(c, "onboarding_done", true) { onboardingDone = true }
    fun setSkippedVersion(c: Context, v: Int) = write(c, "skip_version", v) { skippedVersion = v }
    fun setSeenVersion(c: Context, v: Int) = write(c, "seen_version", v) { seenVersion = v }
    fun setRegime(c: Context, v: String) = write(c, "regime", v) { regime = v }
    fun setFragmentAuto(c: Context, v: Boolean) = write(c, "fragment_auto", v) { fragmentAuto = v }
    fun setMuxEnabled(c: Context, v: Boolean) = write(c, "mux_enabled", v) { muxEnabled = v }
    fun setRealityFirst(c: Context, v: Boolean) = write(c, "reality_first", v) { realityFirst = v }
    fun setAutoConnectOnBoot(c: Context, v: Boolean) = write(c, "auto_connect_boot", v) { autoConnectOnBoot = v }
    fun setMtu(c: Context, v: Int) = write(c, "mtu", v) { mtu = v }
    fun setBlockReport(c: Context, json: String, regime: String) {
        val e = sp(c).edit().putString("block_report", json).putString("detected_regime", regime)
        e.apply()
        lastBlockReport = json
        detectedRegime = regime
        publish()
    }

    fun reset(context: Context) {
        sp(context).edit().clear().apply()
        load(context)
        // same prefs file: a factory reset must also return the routing rules to defaults, and the
        // in-memory mirror does not notice the disk was cleared under it unless reloaded.
        ir.meelano.vpn.data.PerAppRouting.load(context)
    }

    private inline fun write(c: Context, key: String, v: Any, crossinline apply: () -> Unit) {
        val e = sp(c).edit()
        when (v) {
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is String -> e.putString(key, v)
            else -> e
        }.apply()
        apply()
        publish()
    }

    /**
     * The regime the app should actually act on. "auto" is the interesting case: it means "the local
     * probes decide", and the probes only run on a feed refresh, so a stale-but-safe guess (TIGHT) is
     * better than trusting a report from yesterday's network.
     */
    fun effectiveRegime(): ir.meelano.vpn.net.Regime =
        if (regime == "auto") ir.meelano.vpn.net.Regime.of(detectedRegime)
        else ir.meelano.vpn.net.Regime.of(regime)

    /** The user's half of the precedence chain (see net/Regime.kt Tuner). */
    fun overrides(): ir.meelano.vpn.net.Overrides = ir.meelano.vpn.net.Overrides(
        fragment = if (fragmentAuto) null else false,       // null = "regime/feed decides"
        mux = muxEnabled,
        realityFirst = realityFirst,
        mtu = mtu,
    )

    /** Final transport parameters for a node: regime default <- feed patch <- user switches. */
    fun tuneFor(node: FeedNode): ir.meelano.vpn.net.Tune =
        ir.meelano.vpn.net.Tuner.resolve(effectiveRegime(), node.tune, overrides())

    private fun publish() {
        _snapshot.value = mapOf(
            "smart" to smartReconnect, "kill" to killSwitch, "dns" to secureDns,
            "motion" to reducedMotion, "update" to autoUpdate, "fb" to feedback,
            "theme" to themeMode, "auto" to autoSelect, "seen" to seenVersion,
            "regime" to regime, "frag" to fragmentAuto, "mux" to muxEnabled,
            "reality" to realityFirst, "boot" to autoConnectOnBoot, "mtu" to mtu,
            "feed" to feedMode, "autofetch" to autoFetch, "interval" to feedIntervalHours,
            "autoupdate" to autoUpdate, "autocheck" to autoCheckUpdate,
        )
    }
}
