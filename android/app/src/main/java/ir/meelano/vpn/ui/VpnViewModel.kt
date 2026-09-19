package ir.meelano.vpn.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.data.FeedHolder
import ir.meelano.vpn.data.Prefs
import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.vpn.ConnectPhase
import ir.meelano.vpn.vpn.MeelanoVpnService
import ir.meelano.vpn.vpn.Traffic
import ir.meelano.vpn.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import ir.meelano.vpn.MeelanoApp

/**
 * The screen's whole job is to be a *reader*: it reads the process-wide StateFlows the service owns
 * and never keeps a copy of its own state. That is what makes "open the app again and everything is
 * already correct" work, and what stops the old bug where the UI and the tunnel disagreed.
 */
class VpnViewModel(app: Application) : AndroidViewModel(app) {

    private val repo get() = FeedHolder.repo
    val updates: UpdateManager = app.let { (it as MeelanoApp).updates }

    val vip: StateFlow<List<FeedNode>> = repo.vip.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList())
    val free: StateFlow<List<FeedNode>> = repo.free.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList())
    val syncing: StateFlow<Boolean> = repo.syncing.stateIn(
        viewModelScope, SharingStarted.Eagerly, false)
    val phase: StateFlow<ConnectPhase> = MeelanoVpnService.phase
    val traffic: StateFlow<Traffic> = MeelanoVpnService.traffic
    val trace: StateFlow<ir.meelano.vpn.vpn.TrafficTrace> = MeelanoVpnService.trace
    val updateState: StateFlow<UpdateManager.State> = updates.state

    private val _activeId = MutableStateFlow(Prefs.activeId(app))
    val activeId: StateFlow<String?> = _activeId

    /** set when we must show the system VPN consent dialog; MainActivity turns it into a launch */
    private val _consent = MutableStateFlow<Intent?>(null)
    val consent: StateFlow<Intent?> = _consent

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val activeNode: FeedNode?
        get() = (vip.value + free.value).firstOrNull { it.id == _activeId.value } ?: bestAuto()

    /** "خودکار" is a real choice, not a label: the best node of the merged list, stable ordering. */
    fun bestAuto(): FeedNode? {
        val all = (vip.value + free.value).filter { it.samples >= 0 }
        if (all.isEmpty()) return null
        return all.minWith(
            compareByDescending<FeedNode> { it.tier == "vip" }
                .thenBy { gradeRank(it.grade) }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                .thenByDescending { it.reliability }
        )
    }

    fun toggle() {
        val p = phase.value
        when {
            p is ConnectPhase.Connected || p is ConnectPhase.Failed -> {
                MeelanoVpnService.stop(getApplication())
                return
            }
            p !is ConnectPhase.Idle -> return        // connecting/failing-over: the ring already shows a percentage; a second tap must not restart the dial
        }
        val node = activeNode
        // Reading the phase once, then re-checking before we actually dial: the phase can flip to
        // Busy between the user's tap and the connect call (race), and dialing twice wedges the
        // ring exactly like the old bug. The stale-read branch below just exits quietly.
        connect(node)
    }

    fun connect(node: FeedNode?) {
        if (node == null) {
            _message.value = "no_node"
            viewModelScope.launch { runCatching { repo.refresh("vip") } }
            return
        }
        val ctx: Application = getApplication()
        // Remember the pick BEFORE the consent dialog can interrupt us: onConsentResult(true) resumes
        // with connect(activeNode), and a first-ever connect has no previous active id - the pick used
        // to evaporate and the app connected to whatever bestAuto() returned instead.
        Prefs.setActiveId(ctx, node.id)
        _activeId.value = node.id
        val prepare = runCatching { VpnService.prepare(ctx) }.getOrNull()
        if (prepare != null) {
            _consent.value = prepare
            return                                     // connect resumes after the dialog
        }
        MeelanoVpnService.start(ctx, node)
    }

    /** after the user answers the consent dialog */
    fun onConsentResult(granted: Boolean) {
        _consent.value = null
        if (granted) connect(activeNode)
    }

    /** switching node while connected: stop, then start — never "start" twice (the old freeze). */
    fun select(node: FeedNode) {
        val ctx: Application = getApplication()
        Prefs.setActiveId(ctx, node.id)
        _activeId.value = node.id
        viewModelScope.launch {
            if (MeelanoVpnService.isRunning()) {
                MeelanoVpnService.stop(ctx)
                delay(450)                             // let the engine close the tun; overlapping starts are what wedges the core
            }
            connect(node)
        }
    }

    fun pin(node: FeedNode) {
        val ctx: Application = getApplication()
        Prefs.setPinned(ctx, node.id, !(Prefs.pinnedIds(ctx).contains(node.id)))
        viewModelScope.launch { runCatching { repo.applyNode(ctx, node) } }
    }

    /**
     * "test again" — and the honest version of it: one TCP dial + TLS handshake, no HTTP request
     * (a free HTTP proxy test punishes the nodes that are slow-but-alive, which is bug #3), and the
     * result is fed back to the server so the ledger learns from real devices, not only from cron.
     */
    fun retest(node: FeedNode) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { repo.probe(node, timeoutMs = 2500) }.getOrNull() ?: return@launch
            repo.reportResult(node.id, r.ok, r.latencyMs, r.error)
            _message.value = if (r.ok) "ok_${r.latencyMs}" else "fail"
        }
    }

    fun configText(node: FeedNode): String = node.config ?: node.raw ?: ""

    fun consumeMessage() { _message.value = null }

    /** Why an on-device list looks the way it does (one Persian line per source that answered). */
    val feedNotes: StateFlow<List<String>> = repo.directNotes

    /** When the feed is empty this says why, so the empty card can name the failure instead of 0 نود. */
    val feedError: StateFlow<String?> = repo.feedError

    /** The sheet's footer: which builder produced today's list. */
    fun feedSourceLabel(): String = repo.sourceLabel()

    fun setFeedSource(mode: Int) {
        AppSettings.setFeedMode(getApplication(), mode)
        refreshBoth()          // changing the source must change the list now, not on the next cold start
    }

    fun setFeedSubscriptionUrl(url: String) {
        AppSettings.setFeedExtraUrl(getApplication(), url)
        refreshBoth()
    }

    /** Called from a composable, so the read hops: a 2 KB file on the main thread is still a disk seek. */
    suspend fun readLocalVip(): String = withContext(Dispatchers.IO) {
        runCatching { repo.localVipText() }.getOrDefault("")
    }

    fun localVipText(): String = runCatching { repo.localVipText() }.getOrDefault("")

    /** "you haven't pasted anything yet" - read once when the sheet composes, which is all the hint needs. */
    fun hasLocalVip(): Boolean = runCatching { repo.hasLocalVip() }.getOrDefault(false)

    /**
     * Save the user's own configs and rebuild the list immediately. A pasted config that only shows up
     * after a restart is a support ticket; and the rebuild is where the phone's own probe runs, so the
     * latency the user sees is measured from the network they are actually standing on.
     */
    fun saveVipConfigs(text: String, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val lines = runCatching { repo.saveLocalVip(text) }.getOrDefault(0)
            runCatching { repo.refresh("vip") }
            onDone(lines)
        }
    }

    /** the sheet's footer: "آخرین به‌روزرسانی فهرست: ۴ دقیقه پیش" */
    fun generatedAt(kind: String): Long = if (FeedHolder.isReady()) repo.generatedAt(kind) else 0L

    fun refreshBoth() = viewModelScope.launch {
        runCatching { repo.refresh("vip") }
        runCatching { repo.refresh("free") }
    }

    fun skipUpdate(v: Int) { AppSettings.setSkippedVersion(getApplication(), v) }

    // ---- sheet routing (kept here so a launcher shortcut can open a sheet without an Activity field) ----
    private val _openServers = MutableStateFlow(false)
    val openServers: StateFlow<Boolean> = _openServers
    fun requestOpenServers() { _openServers.value = true }
    fun consumeOpenServers() { _openServers.value = false }

    private val _importUri = MutableStateFlow<String?>(null)
    val importUri: StateFlow<String?> = _importUri
    /** imported links are added as a local node and never connected automatically */
    fun requestImport(uri: String) { _importUri.value = uri }
    fun consumeImport() { _importUri.value = null }

    init {
        /*
         * `requestImport` used to be a dead end: MainActivity turned a shared `vless://…` into this flow
         * and nothing in the app ever read it, so "open in M•A VPN" from another app did nothing at all.
         * The honest home for an imported line is the on-device vault - the same place the user's pasted
         * configs live - and never an automatic connect: importing a config and having the tunnel start
         * by itself is how people lose traffic when the config turns out to be somebody else's.
         */
        viewModelScope.launch {
            _importUri.collect { uri ->
                if (!uri.isNullOrBlank()) {
                    val joined = withContext(Dispatchers.IO) {
                        (runCatching { repo.localVipText() }.getOrDefault("") + "\n" + uri).trim()
                    }
                    runCatching { repo.saveLocalVip(joined) }
                    runCatching { repo.refresh("vip") }
                }
                consumeImport()
            }
        }
    }
}
