package ir.meelano.vpn.data

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Per-app routing: which apps bypass the tunnel entirely (`VpnService.Builder.addDisallowedApplication`).
 *
 * The reason this exists as a first-class feature and not a footnote: in Iran the VPN and the local
 * internet are two parallel worlds. Banking apps, payment apps (آپ، همراه‌کارت…), Snapp, Divar and the
 * rest of the `.ir` ecosystem either refuse to work or trip their own fraud detection when their
 * traffic exits from a foreign IP. Before this module the only fix was "turn the VPN off, pay, turn it
 * back on" - which is exactly the moment people forget and their card details go through the tunnel
 * anyway. The fix here is structural: those apps never enter the tunnel, on any connection, including
 * while the kill switch holds.
 *
 * Three layers, each one able to overrule the one above it:
 *   1. auto-detection ([RoutingPolicy.classify]): installed apps whose package name marks them as
 *      Iranian (the `ir.` reverse-domain prefix plus a curated vendor list) or as banking/payment;
 *   2. user overrides: force an app into the tunnel, or force a bypass the detector missed;
 *   3. manual entries: a package name typed in by hand (an app on another profile, a beta build…).
 *
 * The classifier and the set algebra are deliberately pure (no Context, no Android) so
 * `app/src/test` can assert the contract - a wrong classification is silent on the phone ("my bank app
 * is slow again") and invisible in a log.
 */

/** What an app is, for routing. Banking wins over Iranian: a bank is also Iranian, but the chip the
 * UI shows and the reason it is bypassed are different. */
enum class RouteClass { BANKING, IRANIAN, FOREIGN }

/**
 * The pure policy. [classify] decides one package; [effectiveBypass] resolves the whole set for one
 * `establish()` call. Everything the unit tests can name lives here, nowhere else.
 */
object RoutingPolicy {

    /**
     * Package-name tokens that mark a banking/payment app. Deliberately substring-based: Iranian
     * banks do not agree on a naming scheme (`ir.bankmellat…`, `com.bmi…`, `ir.bmi…` all exist), but
     * they all carry "bank" or a payment word somewhere in the id. False positives here are cheap -
     * the user sees the row and can flip it - a missed bank app is the expensive error.
     */
    private val BANKING_TOKENS = listOf(
        "bank", "pardakht", "payment", "bpm", "sadad", "shaparak", "pec", "irancard",
    )

    /**
     * Known Iranian vendor prefixes that do NOT use the `ir.` reverse domain. The `ir.` prefix alone
     * catches the long tail (it is the `.ir` ccTLD convention, and foreign apps effectively never use
     * it); this list exists for the few big names that registered elsewhere: Snapp, Divar, Tap30…
     * Entries are package *prefixes*, so a vendor's family of apps (taxi/food/box…) rides one entry.
     */
    private val IRANIAN_PREFIXES = listOf(
        "ir.",
        "com.snapp",
        "com.snappfood",
        "com.divar",
        "ir.divar",
        "taxi.tap30",
        "com.balad",
        "com.neshan",
    )

    fun classify(packageName: String): RouteClass {
        val pkg = packageName.lowercase()
        if (BANKING_TOKENS.any { pkg.contains(it) }) return RouteClass.BANKING
        if (IRANIAN_PREFIXES.any { pkg.startsWith(it) }) return RouteClass.IRANIAN
        return RouteClass.FOREIGN
    }

    /**
     * The exact set handed to `addDisallowedApplication` at `establish()` time.
     *
     * Precedence: user-forced tunnel always beats auto-detection (someone who explicitly says "route
     * Snapp through the tunnel" knows what they are doing), forced bypass beats everything except the
     * master switch, and the app's own package is never disallowed - its sockets are managed with
     * `protect()` and disallowing ourselves would blind the feed/update machinery.
     *
     * Kill-switch note: a disallowed app stays direct even when the kill switch holds a dead TUN.
     * That is the intended semantic here - "my bank app must work no matter what" - and the settings
     * copy says so plainly.
     */
    fun effectiveBypass(
        installed: Set<String>,
        forcedBypass: Set<String>,
        forcedTunnel: Set<String>,
        enabled: Boolean,
        autoBypass: Boolean,
        self: String,
    ): Set<String> {
        if (!enabled) return emptySet()
        val auto = if (autoBypass) {
            installed.filterTo(mutableSetOf()) { it != self && classify(it) != RouteClass.FOREIGN }
        } else {
            mutableSetOf()
        }
        auto += forcedBypass
        auto -= forcedTunnel
        auto -= self
        return auto
    }

    /** Reverse-DNS shape, at least two segments. Loose on purpose: we store what the user types. */
    private val PKG_RE = Regex("^[a-zA-Z_][\\w]*(\\.[a-zA-Z_][\\w]*)+$")
    fun isValidPackage(s: String): Boolean = PKG_RE.matches(s.trim())
}

/** One installed app as the routing sheet shows it. */
data class ScannedApp(
    val pkg: String,
    val label: String,
    val cls: RouteClass,
)

/**
 * Settings + device scan cache. Same persistence contract as [AppSettings]: SharedPreferences
 * (`meelano_prefs`), synchronous reads, a Compose state mirror so sheets recompose. The two StringSets
 * are the overrides; what is *not* stored anywhere is the auto-detected set - it is recomputed from
 * the installed-app scan every time, because "it was installed back then" is not "it is installed now".
 */
object PerAppRouting {
    private const val PREF = "meelano_prefs"
    private const val KEY_ENABLED = "routing_enabled"
    private const val KEY_AUTO = "routing_auto_bypass"
    private const val KEY_BYPASS = "routing_bypass"
    private const val KEY_TUNNEL = "routing_tunnel"

    /** Master switch: per-app routing at all. */
    var enabled by mutableStateOf(true)
        private set

    /** Auto layer: detected Iranian/banking apps bypass the tunnel by default. */
    var autoBypass by mutableStateOf(true)
        private set

    /** User-forced bypass (also where manual entries live). */
    var bypassSet by mutableStateOf<Set<String>>(emptySet())
        private set

    /** User-forced tunnel: beats auto-detection for that package. */
    var tunnelSet by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Bumped on every rules change so observing screens recompose without deep equality. */
    var revision by mutableStateOf(0)
        private set

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = sp(context)
        enabled = p.getBoolean(KEY_ENABLED, true)
        autoBypass = p.getBoolean(KEY_AUTO, true)
        // getStringSet's return value is owned by the prefs cache - always copy before keeping.
        bypassSet = p.getStringSet(KEY_BYPASS, emptySet())?.toSet() ?: emptySet()
        tunnelSet = p.getStringSet(KEY_TUNNEL, emptySet())?.toSet() ?: emptySet()
        revision++
    }

    fun setEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply()
        enabled = v
        revision++
    }

    fun setAutoBypass(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTO, v).apply()
        autoBypass = v
        revision++
    }

    /** Force this package outside the tunnel (covers both "detector missed it" and manual adds). */
    fun forceBypass(c: Context, pkg: String) = mutateSets(c, addBypass = pkg.trim())

    /** Force this package through the tunnel even if the detector says Iranian/banking. */
    fun forceTunnel(c: Context, pkg: String) = mutateSets(c, addTunnel = pkg.trim())

    /** Drop every override for a package: it returns to whatever auto-detection decides. */
    fun clearOverride(c: Context, pkg: String) = mutateSets(c, removeBoth = pkg)

    private fun mutateSets(
        c: Context,
        addBypass: String? = null,
        addTunnel: String? = null,
        removeBoth: String? = null,
    ) {
        val b = bypassSet.toMutableSet()
        val t = tunnelSet.toMutableSet()
        addBypass?.takeIf { RoutingPolicy.isValidPackage(it) }?.let { b += it; t -= it }
        addTunnel?.takeIf { RoutingPolicy.isValidPackage(it) }?.let { t += it; b -= it }
        removeBoth?.let { b -= it; t -= it }
        sp(c).edit().putStringSet(KEY_BYPASS, b).putStringSet(KEY_TUNNEL, t).apply()
        bypassSet = b
        tunnelSet = t
        revision++
    }

    /* ------------------------------------------------------------ installed-app scan */

    @Volatile private var cache: List<ScannedApp>? = null
    @Volatile private var cacheAt = 0L
    private const val CACHE_TTL_MS = 5 * 60_000L

    /**
     * Installed, *launchable* apps. Launchable-only is the filter that drops system services and
     * invisible providers - nobody routes those, and 300 rows of "com.android.providers.*" would bury
     * the rows that matter. Needs QUERY_ALL_PACKAGES on Android 11+ (declared in the manifest): this
     * app is sideloaded, not on Play, and per-app routing is the reason the permission exists.
     */
    fun scan(context: Context, refresh: Boolean = false): List<ScannedApp> {
        val now = System.currentTimeMillis()
        cache?.let { if (!refresh && now - cacheAt < CACHE_TTL_MS) return it }
        val list = runCatching {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .filter { it.packageName != context.packageName }
                .map { ai ->
                    ScannedApp(
                        pkg = ai.packageName,
                        label = runCatching { pm.getApplicationLabel(ai).toString() }
                            .getOrDefault(ai.packageName),
                        cls = RoutingPolicy.classify(ai.packageName),
                    )
                }
                .sortedWith(compareBy({ it.cls.ordinal }, { it.label }))
                .toList()
        }.getOrDefault(emptyList())
        cache = list
        cacheAt = now
        return list
    }

    /** The set for `VpnOrchestrator.builderFor`; the scan is cached, so this is cheap mid-connect. */
    fun effectiveBypassFor(context: Context, self: String): Set<String> =
        RoutingPolicy.effectiveBypass(
            installed = scan(context).mapTo(mutableSetOf()) { it.pkg },
            forcedBypass = bypassSet,
            forcedTunnel = tunnelSet,
            enabled = enabled,
            autoBypass = autoBypass,
            self = self,
        )
}
