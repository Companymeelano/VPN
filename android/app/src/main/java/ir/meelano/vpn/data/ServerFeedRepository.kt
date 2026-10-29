package ir.meelano.vpn.data

import android.content.Context
import ir.meelano.vpn.BuildConfig
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ir.meelano.vpn.net.NetGuard
import ir.meelano.vpn.net.Ladder
import ir.meelano.vpn.net.ProbePolicy
import ir.meelano.vpn.net.BlockReport
import ir.meelano.vpn.net.Regime
import org.json.JSONArray
import org.json.JSONObject
import ir.meelano.vpn.keepalive.ActiveNodeCache
import ir.meelano.vpn.vpn.MeelanoVpnService

/** Wire-level identifiers must stay printable ASCII; OkHttp rejects the Persian UI brand's `•`. */
internal fun publicFeedUserAgent(version: String): String {
    val safeVersion = version.filter { it.code in 0x21..0x7e }.ifBlank { "unknown" }
    return "MA-VPN/$safeVersion"
}

/**
 * Sanitised runtime telemetry for the public feed. It intentionally cannot hold a URL, endpoint,
 * config or server address, so the diagnostics sheet can share it without publishing a node.
 */
data class FeedDiagnostics(
    val sourcesOk: Int = 0,
    val sourcesTotal: Int = 0,
    val fetchBytes: Long = 0,
    val parsedTunnels: Int = 0,
    val parsedProxies: Int = 0,
    val tcpAttempted: Int = 0,
    val tcpOk: Int = 0,
    val deepScanned: Int = 0,
    val proxyClean: Int = 0,
    val proxyRobotBlocked: Int = 0,
    val proxyPlatformBlocked: Int = 0,
    val proxyDead: Int = 0,
    val outcomes: List<DirectFeed.FetchOutcome> = emptyList(),
) {
    fun stages(): String =
        "fetch $sourcesOk/$sourcesTotal (${fetchBytes}B) · parse tunnel=$parsedTunnels proxy=$parsedProxies · " +
            "TCP $tcpOk/$tcpAttempted · deep $deepScanned (clean=$proxyClean robot=$proxyRobotBlocked " +
            "platform=$proxyPlatformBlocked dead=$proxyDead)"

    fun routes(limit: Int = 24): String {
        if (outcomes.isEmpty()) return "—"
        val shown = outcomes.take(limit).joinToString("; ") { outcome ->
            buildString {
                append(outcome.sourceId).append('/').append(outcome.route).append('=').append(outcome.category)
                if (outcome.bytes > 0) append('(').append(outcome.bytes).append("B)")
            }
        }
        val hidden = outcomes.size - limit
        return shown + if (hidden > 0) "; +$hidden" else ""
    }

    companion object {
        fun from(result: DirectFeed.Result): FeedDiagnostics = FeedDiagnostics(
            sourcesOk = result.sourcesOk,
            sourcesTotal = result.sourcesTotal,
            fetchBytes = result.fetchBytes,
            parsedTunnels = result.parsedTunnels,
            parsedProxies = result.parsedProxies,
            tcpAttempted = result.probed,
            tcpOk = result.tunnelTcpOk,
            deepScanned = result.proxyScanned,
            proxyClean = result.proxyClean,
            proxyRobotBlocked = result.proxyRobotBlocked,
            proxyPlatformBlocked = result.proxyPlatformBlocked,
            proxyDead = result.proxyDead,
            outcomes = result.fetchOutcomes,
        )
    }
}

/**
 * One repo for both lists (VIP + free) because both flows deliberately use the same strict model.
 *
 * The speed of this file is what the user feels as "the app is slow":
 *   - disk cache paints before the network;
 *   - public source and mirror requests run off the main thread;
 *   - unknown fields are ignored instead of crashing a release.
 * Direct public discovery, parse, TCP hints and deep proxy verification all converge on [apply], so
 * sorting, pinning and the connection service do not need separate host/direct implementations.
 */
class ServerFeedRepository(
    private val context: Context,
    private val baseUrl: String = BuildConfig.FEED_BASE_URL,
) {

    companion object {
        private const val TAG = "MeelanoFeed"
        const val KIND_VIP = "vip"
        const val KIND_FREE = "free"

        /** Pasted VIP configs. Inside getDir("feed") so one `rm -rf` of the app's cache clears them. */
        private const val VIP_LOCAL_FILE = "vip_local.txt"

        fun isVip(node: FeedNode) = node.tier == KIND_VIP

        /** Masking brand for lists built on the phone - same policy as the host, different file. */
        const val VIP_BRAND = "Vip M\u2022A"
        const val FREE_BRAND = "Free M\u2022A"

        /**
         * A phone-side list is honest at six hours, not at ten minutes: these public lists churn every
         * few hours and the device has no gate to re-verify through, so re-running the fetch every
         * foreground would buy nothing but warmth.
         */
        const val DIRECT_TTL_SEC = 6 * 3600

        /** How often we re-ask a mid-build host, and how long a client waits per round. The host's own retryAfter is 15 s; 8 s keeps the first-launch UX inside a minute while the build is ~20 s. */
        private const val MAX_BUILDING_ROUNDS = 4
        private const val BUILDING_RETRY_MS = 8_000L
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http by lazy { client(6) }

    /**
     * A cold `?action=free` is not a file read: the host fetches every upstream, parses, probes and
     * gates them inside the request (that budget alone is ~18s - see free.probe.budgetMs). With a 6s
     * read timeout the app's very first poll *always* lost that race and showed "0 گره" while the
     * server was actually working; the second poll only succeeded once the hour-old cache existed.
     * So: short timeout when we have something cached, long one when we have nothing.
     */
    private val httpCold by lazy { client(30) }
    /** Public repository documents should fail over to their mirror quickly, not hold first launch 30s. */
    private val httpPublic by lazy { client(10) }

    private fun clientFor(kind: String): OkHttpClient =
        if (File(filesDir, "$kind.json").isFile) http else httpCold

    private fun client(readSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(Cache(File(context.cacheDir, "http-feed").apply { mkdirs() }, 6L * 1024 * 1024))
            .build()

    private val filesDir = context.getDir("feed", Context.MODE_PRIVATE)
    private val healthFile = File(filesDir, "node-health.tsv")
    private val health = NodeHealthLedger().apply {
        runCatching { if (healthFile.isFile) restore(healthFile.readText()) }
    }
    private val healthWriteMutex = Mutex()
    private val etags = mutableMapOf<String, String>()

    private val _vip = MutableStateFlow<List<FeedNode>>(emptyList())
    val vip: StateFlow<List<FeedNode>> = _vip
    private val _free = MutableStateFlow<List<FeedNode>>(emptyList())
    val free: StateFlow<List<FeedNode>> = _free
    /** Raw HTTP/SOCKS rows, including rejected rows with their explicit scan verdict. */
    private val _proxies = MutableStateFlow<List<FeedNode>>(emptyList())
    val proxies: StateFlow<List<FeedNode>> = _proxies
    private var vipProxies: List<FeedNode> = emptyList()
    private var publicProxies: List<FeedNode> = emptyList()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val syncJobs = AtomicInteger(0)
    private val refreshLocks = mapOf(KIND_VIP to Mutex(), KIND_FREE to Mutex())
    private fun beginSync() {
        syncJobs.incrementAndGet()
        _syncing.value = true
    }

    private fun endSync() {
        if (syncJobs.decrementAndGet().coerceAtLeast(0) == 0) {
            _syncing.value = false
            _syncProgress.value = SyncProgress.IDLE
        }
    }

    /** Real per-stage progress of the running sync; the 3D dials read this (data/SyncProgress). */
    private val _syncProgress = MutableStateFlow(SyncProgress.IDLE)
    val syncProgress: StateFlow<SyncProgress> = _syncProgress

    /** every node we know about, indexed by id (what the service resolves a connect request from) */
    @Volatile private var index: Map<String, FeedNode> = emptyMap()

    init {
        // 1. paint from disk immediately, 2. refresh in the background - never the other way round
        appScope.launch {
            listOf(KIND_VIP, KIND_FREE).forEach { loadCached(it) }
            refresh(KIND_VIP); refresh(KIND_FREE)
        }
    }

    /** Resolve an id from memory, then from disk, then from the network. */
    suspend fun awaitNode(id: String): FeedNode? {
        index[id]?.let { return it }
        // One refresh, then wait for the in-flight cache/build. Re-running a 48-proxy deep scan six
        // times for an id that was deleted is both slow and hostile to battery/network quota.
        refresh(KIND_VIP)
        refresh(KIND_FREE)
        repeat(6) { index[id]?.let { return it }; delay(400) }
        return null
    }

    private val platformVerdicts = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Candidate ladder used by the service for startup, health failover and network changes. */
    fun failoverCandidates(preferred: FeedNode, limit: Int = 12): List<FeedNode> {
        val all = (listOf(preferred) + _vip.value + _free.value + _proxies.value).distinctBy { it.id }
            .filter { it.proto in DirectFeed.TUNNEL_PROTOS && !it.insecure && it.isUsable }
        val grade = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3)
        val regime = AppSettings.effectiveRegime()
        return all.sortedWith(
            compareBy<FeedNode>(
                { if (it.id == preferred.id) 0 else 1 },
                { platformRank(platformVerdicts[it.id]?.first ?: it.platformStatus) },
                { localPenalty(it.id) },
                // Iran-aware order inside equal local health: a survivable transport near the
                // country on 443 gets its dial before a marginally faster plain node an ocean
                // away. (This phone's measured failures above still outrank geography.)
                { NodeRanking.bucket(it, regime) },
                { grade[it.grade] ?: 9 },
                { it.latencyMs ?: Long.MAX_VALUE },
                { if (it.isVip) 0 else 1 },
                { it.slot },
            )
        ).take(limit)
    }

    fun recordPlatformVerdict(nodeId: String, status: String) {
        platformVerdicts[nodeId] = status to System.currentTimeMillis()
    }

    private fun platformRank(status: String): Int = when (status) {
        "clean" -> 0
        "blocked", "platform_blocked" -> 3
        else -> 1
    }

    /* ------------------------------------------------------------ fetch */

    /**
     * Which builder produced the list. HOST (the default) is the /v/ build: probed, gated through
     * CONNECT-to-Google, ranked by a multi-day reliability ledger. DIRECT produces the list on the
     * phone so the app needs no host of yours at all; AUTO calls the host and only falls back when it
     * cannot be reached. All three end in the same apply() + the same cache file, so nothing downstream
     * - sorting, pinned nodes, the connect path, the sheet - has to know which one ran.
     */
    /**
     * Why the current list is empty, in one line, for the empty-state card. Silent failure is the
     * default trap of a feed app: an offline phone and a dead host both render as "no servers" and
     * the user blames the app. Never persisted - an error remembered from yesterday would be a lie.
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _lastError

    suspend fun refresh(kind: String) = withContext(Dispatchers.IO) {
        // Feed discovery is direct-only. The lock coalesces Application/Activity/service cold-start
        // races so one refresh means one source download and one proxy scan, never three in parallel.
        val lock = refreshLocks[kind] ?: return@withContext
        lock.withLock { refreshDirect(kind) }
    }

    private suspend fun refreshHost(kind: String) = withContext(Dispatchers.IO) {
        _syncing.value = true
        try {
            // A fresh host answers its very first polls with {"servers":[], "building":true,
            // "retryAfter":15} while its cold build is still running (fetch+probe+gate ≈ 20 s).
            // Caching that shell as a real list is how "the app shows 0 نود on day one" happens:
            // do NOT apply it, do NOT save it - poll a few times instead. The disk cache already
            // painted the old list, so the wait is background work, not a frozen screen.
            var round = 0
            while (true) {
                val r = refreshHostRound(kind)
                if (r != HostRound.BUILDING || round >= MAX_BUILDING_ROUNDS) {
                    if (r == HostRound.BUILDING) {
                        // not an outage: the host is mid cold-build; the message ends the mystery on
                        // day one and the next poll (TTL) will land on a full list
                        if (_lastError.value == null) _lastError.value = "host_building"
                    }
                    break
                }
                round++
                delay(BUILDING_RETRY_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refresh $kind failed: ${t.message}")   // offline -> keep showing cache
            _lastError.value = "refresh_$kind: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            _syncing.value = false
        }
        // Host path ended with nothing on screen and nothing on disk: say why instead of a silent zero.
        val have = if (kind == KIND_VIP) _vip.value else _free.value
        if (have.isEmpty() && !File(filesDir, "$kind.json").isFile && _lastError.value == null) {
            _lastError.value = "empty_after_host_fetch"
        }
    }

    private enum class HostRound { DONE, BUILDING }

    /** One GET + apply. [HostRound.BUILDING] means "server is mid-build; caller decides whether to retry". */
    private suspend fun refreshHostRound(kind: String): HostRound {
        val req = Request.Builder()
            .url("$baseUrl/?action=$kind")
            .header("X-Feed-Key", FEED_KEY)                 // optional; server ignores when unset
            .apply { etags[kind]?.let { header("If-None-Match", it) } }
            .build()
        open(req, clientFor(kind))?.use { resp ->
            when {
                resp.code == 304 -> {
                    Log.d(TAG, "$kind not modified (0 bytes)")
                    return@use
                }
                !resp.isSuccessful -> {
                    Log.w(TAG, "$kind http ${resp.code}")
                    _lastError.value = "http_${resp.code}"   // 403 = wrong feed key, 404 = endpoint moved - visible on the empty card
                    return@use
                }
                else -> {
                    val body = resp.body?.string() ?: return@use
                    if (isBuilding(body)) {
                        Log.d(TAG, "$kind: host is mid-build, will re-poll")
                        return HostRound.BUILDING
                    }
                    val parsed = decode(body)
                    if (parsed != null) {
                        File(filesDir, "$kind.json").writeText(body)   // raw for the next cold start
                        resp.header("ETag")?.let { etags[kind] = it }
                        lastDnsPoisoned = false
                        apply(parsed, kind)
                        // pasted configs never outrank the server's ranking, but they must not
                        // disappear because this list came from the host instead of from the phone
                        if (kind == KIND_VIP) mergeLocalVip()
                        probeAndReconcile(kind)
                    } else {
                        Log.w(TAG, "$kind payload rejected")
                        _lastError.value = "payload_rejected"   // schema/body mismatch - see the parse note in logcat
                    }
                }
            }
        } ?: run {
            // open() tries DNS-fallback itself; landing here means both paths were unreachable
            // (offline phone, hijacked resolver with no DoH answer, or a build pointing at a dead host)
            _lastError.value = "net_unreachable"
        }
        return HostRound.DONE
    }

    /** The host's "cold build in progress" sentinel (Builder::serve, `building: true`). A raw profile and a building notice are distinguishable by this flag alone - never by `servers` being empty, because a devastated pool is a legal payload. */
    private fun isBuilding(body: String): Boolean =
        runCatching { JSONObject(body).optBoolean("building", false) }.getOrDefault(false)

    /* ------------------------------------------------------------ on-device builder */

    private val _directNotes = MutableStateFlow<List<String>>(emptyList())

    /** Why a device-built list looks the way it does - shown in the sheet footer, never as an error. */
    val directNotes: StateFlow<List<String>> = _directNotes

    private val _feedDiagnostics = MutableStateFlow(FeedDiagnostics())
    /** Stage-by-stage, URL-free telemetry used by the copyable diagnostics report. */
    val feedDiagnostics: StateFlow<FeedDiagnostics> = _feedDiagnostics

    /** One line for Settings, so the user always knows where today's list came from. */
    fun sourceLabel(): String = "GitHub و منابع عمومی · آزمون روی گوشی"

    /**
     * Free: the public config lists. VIP: whatever you pasted in the app (+ your own subscription URL).
     * What DIRECT mode does *not* do is the host's gate, ban ledger and ranking - that work needs a
     * machine that is reachable from everywhere and is awake tomorrow. We say so in the footer instead
     * of pretending the two builders are equal.
     */
    private suspend fun refreshDirect(kind: String) {
        beginSync()
        try {
            val extra = AppSettings.feedExtraUrl.takeIf { it.startsWith("https://", ignoreCase = true) }?.let {
                DirectFeed.Upstream(
                    "user", it,
                    if (it.contains(".json")) DirectFeed.Kind.JSON else DirectFeed.Kind.CONFIG
                )
            }
            val res = if (kind == KIND_VIP) {
                localVip(extra)
            } else {
                DirectFeed.buildFree(
                    brand = FREE_BRAND,
                    regime = AppSettings.effectiveRegime(),
                    // The optional URL is the user's private subscription and belongs only to VIP;
                    // public free servers continue to come exclusively from the audited source set.
                    extra = null,
                    fetch = { u -> fetchPublicText(u.url, u.maxBytes) },
                    probe = { nodes -> probeBatch(nodes) },
                    deepProbe = { nodes ->
                        ProxyScanner.scan(
                            nodes = nodes,
                            protect = { socket -> MeelanoVpnService.protectFeedSocket(socket) },
                            onProgress = { done, total ->
                                _syncProgress.value = SyncProgress(SyncProgress.Stage.SCAN, done, total)
                            },
                        )
                    },
                    onTunnelsReady = { tunnels ->
                        // First-paint stage: healthy free servers appear as soon as their direct probe
                        // finishes; the independent exact HTTPS proxy scan continues under "syncing".
                        val stamp = System.currentTimeMillis() / 1000L
                        applyFreeTunnelsProgressively(tunnels, stamp)
                        runCatching {
                            File(filesDir, "$KIND_FREE.json").writeText(
                                FeedJson.encodePayload(KIND_FREE, tunnels, stamp, DIRECT_TTL_SEC)
                            )
                        }
                    },
                    onProgress = { _syncProgress.value = it },
                )
            }
            _directNotes.value = res.notes
            if (kind == KIND_FREE) _feedDiagnostics.value = FeedDiagnostics.from(res)
            if (res.nodes.isEmpty()) {
                // Nothing fetched this time: keep the last good cache but make the cause visible.
                Log.w(TAG, "direct $kind produced nothing")
                _lastError.value = "public_sources_empty"
                return
            }
            val now = System.currentTimeMillis() / 1000L
            apply(FeedPayload(kind, now, DIRECT_TTL_SEC, res.nodes), kind)
            if (kind == KIND_FREE && res.nodes.none { it.isUsable }) {
                _lastError.value = "no_usable_paths"
            }
            runCatching {
                File(filesDir, "$kind.json")
                    .writeText(FeedJson.encodePayload(kind, res.nodes, now, DIRECT_TTL_SEC, res.notes))
            }.onFailure { Log.w(TAG, "direct cache write failed: ${it.message}") }
            // No probeAndReconcile(): these were dialed *now*, from this network. Re-probing 12 sockets
            // for a number we already have is exactly the duplicated work that made the old build slow.
        } catch (t: Throwable) {
            Log.w(TAG, "direct $kind failed: ${t.message}")
            _lastError.value = "public_refresh_failed"
        } finally {
            endSync()
        }
    }

    /**
     * The user's own VIP configs, kept in the app's private dir. Probed here rather than trusted: a
     * dead node should read dead on the phone it is about to be dialed from, and this is the one place
     * where a phone's opinion is *better* than the host's (the host measures from its own DC).
     */
    private suspend fun localVip(extra: DirectFeed.Upstream?): DirectFeed.Result {
        val blob = StringBuilder()
        runCatching { File(filesDir, VIP_LOCAL_FILE).readText() }.getOrNull()
            ?.let { blob.append(it).append('\n') }
        var sources = 0
        if (extra != null) {
            runCatching { fetchText(extra.url, extra.maxBytes) }.getOrNull()?.let {
                sources++
                blob.append(it)
            }
        }
        _syncProgress.value = SyncProgress(SyncProgress.Stage.FETCH, 1, 1)
        val parsed = NodeUri.parseBlob(blob.toString(), tier = KIND_VIP, brand = VIP_BRAND)
        _syncProgress.value = SyncProgress(SyncProgress.Stage.PARSE, 1, 1)
        val head = NodeUri.dedupe(parsed, max = 48)
        val tunnels = head.filterNot { it.isRawProxy }
        val rawProxies = head.filter { it.isRawProxy }
        _syncProgress.value = SyncProgress(SyncProgress.Stage.PROBE, 0, tunnels.size.coerceAtLeast(1))
        val rtt = probeBatch(tunnels)
        _syncProgress.value = SyncProgress(SyncProgress.Stage.PROBE, tunnels.size, tunnels.size.coerceAtLeast(1))
        val proxyVerdicts = ProxyScanner.scan(
            nodes = rawProxies,
            limit = rawProxies.size,
            protect = { socket -> MeelanoVpnService.protectFeedSocket(socket) },
            onProgress = { done, total ->
                _syncProgress.value = SyncProgress(SyncProgress.Stage.SCAN, done, total)
            },
        )
        val checkedAt = System.currentTimeMillis() / 1000L
        val nodes = head.mapIndexed { i, n ->
            if (n.isRawProxy) {
                val verdict = proxyVerdicts[n.id] ?: ProxyScanner.Verdict(
                    ProxyScanner.DEAD, null, 0f, "scan_not_completed", emptyList()
                )
                n.copy(
                    slot = i + 1,
                    latencyMs = verdict.latencyMs,
                    grade = if (verdict.usable) NodeUri.grade(verdict.latencyMs) else "D",
                    reliability = verdict.reliability,
                    samples = verdict.checks.size,
                    platformStatus = verdict.status,
                    platformCheckedAt = checkedAt,
                    platformReason = verdict.reason,
                )
            } else {
                val ms = rtt[n.id]
                n.copy(
                    slot = i + 1,
                    latencyMs = if (ms != null && ms >= 0) ms else null,
                    grade = if (ms == null || ms < 0) "D" else NodeUri.grade(ms),
                    reliability = if (ms != null && ms >= 0) 0.5f else 0f,
                    samples = 1,
                )
            }
        }.sortedWith(
            compareBy({ if (it.isUsable && it.latencyMs != null) 0 else 1 }, { it.latencyMs ?: Long.MAX_VALUE })
        )
        val notes = ArrayList<String>(2)
        notes += if (parsed.isEmpty()) "هنوز چیزی وارد نکرده‌اید: در تنظیمات، پیکربندی‌هایتان را بچسبانید"
        else "محلی · ${parsed.size} خط خوانده شد"
        if (sources > 0) notes += "اشتراکِ شخصی از لینکِ خودتان خوانده شد"
        if (sources == 0 && blob.toString().isBlank()) notes += "لینکِ اشتراک تنظیم نشده"
        notes += "${nodes.count { it.latencyMs != null }} نود از همین شبکه پاسخ داد"
        _syncProgress.value = SyncProgress(SyncProgress.Stage.RANK, 1, 1)
        return DirectFeed.Result(nodes, sources, maxOf(1, sources), parsed.size, head.size, notes)
    }

    /**
     * The user's own pasted configs, appended to a host-built list. Without this, switching the feed
     * source would look like deleting their nodes - and a VIP entry you pasted yourself is the one piece
     * of data in this app you own outright. They get probed here too, because that is the only
     * measurement that matters for a node you are about to dial from this exact network.
     */
    private suspend fun mergeLocalVip() {
        val text = runCatching { File(filesDir, VIP_LOCAL_FILE).readText() }.getOrDefault("")
        if (text.isBlank()) return
        val known = _vip.value.map { it.id }.toSet()
        val fresh = NodeUri.parseBlob(text, tier = KIND_VIP, brand = VIP_BRAND).filter { it.id !in known }
        if (fresh.isEmpty()) return
        val probed = NodeUri.dedupe(fresh, max = 48)
        val rtt = probeBatch(probed)
        val graded = probed.map { n ->
            val ms = rtt[n.id]
            n.copy(
                latencyMs = if (ms != null && ms >= 0) ms else null,
                grade = if (ms == null || ms < 0) "D" else NodeUri.grade(ms),
                reliability = if (ms != null && ms >= 0) 1f else 0f,
                samples = 1,
            )
        }.sortedWith(
            compareBy({ if (it.latencyMs == null) 1 else 0 }, { it.latencyMs ?: Long.MAX_VALUE })
        ).mapIndexed { i, n -> n.copy(slot = _vip.value.size + i + 1) }
        val stamp = lastBuildAt[KIND_VIP] ?: (System.currentTimeMillis() / 1000L)
        apply(FeedPayload(KIND_VIP, stamp, DIRECT_TTL_SEC, _vip.value + graded), KIND_VIP)
    }

    /**
     * One capped GET on the short public-source client. Mirror failover belongs to [DirectFeed], so a
     * poisoned GitHub hostname is abandoned quickly instead of paying a second host-style retry here.
     *
     * No Accept-Encoding header on purpose: OkHttp only *transparently* inflates gzip when it added the
     * header itself; a hand-written one means we would be reading compressed bytes as text.
     */
    private suspend fun fetchPublicText(url: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("asset://")) return@withContext fetchText(url, maxBytes)
        val name = url.removePrefix("asset://")
        try {
            context.assets.open(name).use { input ->
                val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
                val chunk = ByteArray(16 * 1024)
                var remaining = maxBytes + 1
                while (remaining > 0) {
                    val count = input.read(chunk, 0, minOf(chunk.size, remaining))
                    if (count <= 0) break
                    out.write(chunk, 0, count)
                    remaining -= count
                }
                if (out.size() > maxBytes) throw DirectFeed.FetchFailure("asset_too_large")
                out.toString("UTF-8").takeIf { it.isNotBlank() }
                    ?: throw DirectFeed.FetchFailure("asset_empty")
            }
        } catch (error: DirectFeed.FetchFailure) {
            throw error
        } catch (_: java.io.FileNotFoundException) {
            throw DirectFeed.FetchFailure("asset_missing")
        } catch (error: Throwable) {
            throw DirectFeed.FetchFailure(publicFetchCategory(error))
        }
    }

    private suspend fun fetchText(url: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val isGithubApi = url.startsWith("https://api.github.com/")
        val req = try {
            Request.Builder().url(url)
                // OkHttp deliberately rejects non-ASCII header values. The old branded `M•A` value
                // threw IllegalArgumentException while constructing *every* network request, before a
                // socket existed; that is why the build snapshot loaded but all 60 GitHub/CDN routes
                // reported the exact same exception on the phone. Keep wire identifiers ASCII-only.
                .header("User-Agent", publicFeedUserAgent(BuildConfig.VERSION_NAME))
                .apply {
                    if (isGithubApi) {
                        header("Accept", "application/vnd.github.raw+json")
                        header("X-GitHub-Api-Version", "2022-11-28")
                    }
                }
                .build()
        } catch (_: IllegalArgumentException) {
            throw DirectFeed.FetchFailure("request_invalid")
        }
        // Do not use the old private-host DoH/pinned retry here. DirectFeed owns several independent
        // GitHub/jsDelivr routes and needs an explicit category for each failure before moving on.
        val response = try {
            httpPublic.newCall(req).execute()
        } catch (error: Throwable) {
            throw DirectFeed.FetchFailure(publicFetchCategory(error))
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw DirectFeed.FetchFailure("http_${resp.code}")
            val src = resp.body?.byteStream() ?: throw DirectFeed.FetchFailure("body_missing")
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
            val chunk = ByteArray(32 * 1024)
            var remaining = maxBytes + 1 // one extra byte proves whether the bounded body was truncated
            while (remaining > 0) {
                val n = try {
                    src.read(chunk, 0, minOf(chunk.size, remaining))
                } catch (error: Throwable) {
                    throw DirectFeed.FetchFailure(publicFetchCategory(error))
                }
                if (n <= 0) break
                out.write(chunk, 0, n)
                remaining -= n
            }
            val bytes = out.toByteArray()
            val truncated = bytes.size > maxBytes
            val bounded = if (truncated) bytes.copyOf(maxBytes) else bytes
            var text = String(bounded, Charsets.UTF_8)
            if (truncated) {
                // Raw URI lists remain useful as a bounded prefix. JSON/base64 subscriptions are one
                // giant line and cannot be safely cut, so let DirectFeed try the next public route.
                val lastLine = text.lastIndexOf('\n')
                if (lastLine <= 0) throw DirectFeed.FetchFailure("body_too_large")
                text = text.substring(0, lastLine)
            }
            if (text.isBlank()) throw DirectFeed.FetchFailure("body_empty")

            // GitHub may return the requested bytes directly for the raw media type, or a normal
            // Contents API envelope (notably through caches). Decode both. Files above the API limit
            // have no `content`; that is a categorised miss and jsDelivr/raw handles the fallback.
            if (isGithubApi && text.trimStart().startsWith('{')) {
                val json = runCatching { JSONObject(text) }
                    .getOrElse { throw DirectFeed.FetchFailure("github_json_invalid") }
                val encoded = json.optString("content", "").filterNot(Char::isWhitespace)
                if (encoded.isBlank()) throw DirectFeed.FetchFailure("github_content_unavailable")
                val decoded = runCatching {
                    String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                }.getOrElse { throw DirectFeed.FetchFailure("github_base64_invalid") }
                if (decoded.isBlank()) throw DirectFeed.FetchFailure("body_empty")
                return@withContext decoded
            }
            text
        }
    }

    /** Stable categories only: exception messages can contain a public URL or a resolved IP. */
    private fun publicFetchCategory(error: Throwable): String = when (error) {
        is DirectFeed.FetchFailure -> error.category
        is java.net.UnknownHostException -> "dns"
        is java.net.SocketTimeoutException -> "timeout"
        is javax.net.ssl.SSLException -> "tls"
        is java.net.ConnectException -> "connect"
        is java.io.IOException -> "io"
        else -> "exception_${error.javaClass.simpleName.lowercase().take(32)}"
    }

    /**
     * Bounded direct TCP pass. Protect each socket when a VPN is already running; otherwise a refresh
     * would measure endpoints from the old exit node and call them healthy even when this phone's own
     * network cannot reach them during failover.
     *
     * Endpoints are dialed by the *SmartResolver* address (foreign DoH, majority-ranked, v4-first),
     * never by a system-resolved hostname: on carriers whose DNS sinkholes foreign names (MCI) the
     * old path marked every domain-hosted node dead while IP-literal nodes sailed through — the
     * "works on Irancell, not on MCI" report. The poison verdict feeds the fleet report below.
     */
    private suspend fun probeBatch(nodes: List<FeedNode>): Map<String, Long> = coroutineScope {
        if (nodes.isEmpty()) return@coroutineScope emptyMap()
        // Adaptive budget (was a fixed 1500 ms): on a loaded Iranian cell the first SYN alone
        // can take most of that, and every false "dead" here sorts a working node to grade D.
        val timeoutMs = ProbePolicy.tcpTimeoutMs(lastReport.medianRttMs)
        val gate = Semaphore(12)
        nodes.map { node ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val started = System.nanoTime()
                    val resolved = ir.meelano.vpn.net.SmartResolver.resolve(node.host)
                    if (resolved.poisoned) lastDnsPoisoned = true
                    val dial = resolved.ip ?: node.host
                    val ok = runCatching {
                        java.net.Socket().use { socket ->
                            socket.tcpNoDelay = true
                            if (!MeelanoVpnService.protectFeedSocket(socket)) error("socket_protect_failed")
                            socket.connect(java.net.InetSocketAddress(dial, node.port), timeoutMs)
                        }
                    }.isSuccess
                    node.id to if (ok) {
                        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                    } else -1L
                }
            }
        }.awaitAll().toMap()
    }

    /* -------------------------------------------------- the user's own configs (VIP, on-device) */

    fun localVipText(): String = runCatching { File(filesDir, VIP_LOCAL_FILE).readText() }.getOrDefault("")

    fun hasLocalVip(): Boolean = File(filesDir, VIP_LOCAL_FILE).length() > 0L

    /**
     * Save pasted configs. Blank clears the vault. The caller decides when to refresh - saving and
     * fetching are separate so the keyboard closes instantly and the probe runs where it belongs.
     */
    suspend fun saveLocalVip(text: String): Int = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        val file = File(filesDir, VIP_LOCAL_FILE)
        if (trimmed.isEmpty()) file.delete() else file.writeText(trimmed + "\n")
        trimmed.lineSequence().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
    }

    /**
     * GET with a resolver fallback. If the normal path throws but a *pinned-IP* request to the same
     * URL succeeds, the local DNS lied to us - the single most useful fact a client inside Iran can
     * produce, because it changes what the app does (feed mirrors, transport ordering) instead of
     * merely what it shows. `lastDnsPoisoned` is read by the probe pass below and uploaded with the
     * feedback, so the whole fleet benefits from one user's poisoned answer.
     */
    @Volatile private var lastDnsPoisoned = false

    /** `client` is the *warm* one by default; a cold sync passes [httpCold] so the timeout matches the work. */
    private suspend fun open(req: okhttp3.Request, client: okhttp3.OkHttpClient = http): okhttp3.Response? {
        val normal = runCatching { client.newCall(req).execute() }
        normal.getOrNull()?.let { return it }
        val host0 = runCatching { req.url.host }.getOrDefault("")
        if (host0.isBlank()) return null
        val ips = NetGuard.doh(host0)
        if (ips.isEmpty()) return null
        val pinned = runCatching { NetGuard.pinnedClient(ips).newCall(req).execute() }.getOrNull() ?: return null
        lastDnsPoisoned = true
        Log.w(TAG, "resolver fallback: $host0 -> ${ips.joinToString()} (DNS was lying)")
        return pinned
    }

    private fun loadCached(kind: String) {
        runCatching {
            val f = File(filesDir, "$kind.json")
            if (f.isFile) decode(f.readText())?.let { apply(it, kind) }
        }
    }

    /**
     * Server decides the order; we add two local corrections it cannot know:
     *  1. pinned/favourite first (a user choice);
     *  2. nodes this *phone* cannot reach sink below nodes it can (compareBy: 0 = better), because
     *     "the port answers from Amsterdam" and "the port answers from TCI in Tehran" are different
     *     facts and only one of them is in the feed.
     * A probe result is a demotion, never a deletion: the path can come back in 90 seconds and an
     * empty list is the worst UI this product can show.
     */
    private fun applyFreeTunnelsProgressively(nodes: List<FeedNode>, generatedAt: Long) {
        val pinned = Prefs.pinnedIds(context)
        _free.value = nodes.map { health.enrich(it.copy(kind = KIND_FREE)) }
            .sortedWith(compareBy({ if (it.id in pinned) 0 else 1 }, { localPenalty(it.id) }, { it.slot }))
        index = (_vip.value + _free.value + _proxies.value).associateBy { it.id }
        lastBuildAt[KIND_FREE] = generatedAt
        _lastError.value = null
    }

    private fun apply(payload: FeedPayload, kind: String) {
        val pinned = Prefs.pinnedIds(context)
        val list = payload.nodes.map { health.enrich(it.copy(kind = kind)) }
            .sortedWith(
                compareBy({ if (it.id in pinned) 0 else 1 }, { localPenalty(it.id) }, { it.slot })
            )
        if (kind == KIND_VIP) {
            _vip.value = list.filterNot { it.isRawProxy }
            vipProxies = list.filter { it.isRawProxy }
        } else {
            // A raw proxy is never allowed to masquerade as a free tunnel row. Rejected rows remain in
            // the proxy section for diagnosis but [isUsable] keeps them out of auto-pick/failover.
            _free.value = list.filterNot { it.isRawProxy }
            publicProxies = list.filter { it.isRawProxy }
        }
        _proxies.value = (vipProxies + publicProxies).distinctBy { it.id }
        if (list.any { it.isUsable }) _lastError.value = null
        index = (_vip.value + _free.value + _proxies.value).associateBy { it.id }
        lastBuildAt[kind] = payload.generatedAt
        if (kind == KIND_FREE) lastBuildAt["proxy"] = payload.generatedAt
    }

    private val lastBuildAt = mutableMapOf<String, Long>()

    /* ------------------------------------------------------------ local block probing */

    /** id -> RTT in ms, or -1 for "answered nothing" (a fail is remembered briefly, not forever). */
    private val localProbe = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    @Volatile private var lastReport: BlockReport = BlockReport()
    fun blockReport(): BlockReport = lastReport

    private fun localPenalty(id: String): Int {
        val reachability = when (localProbe[id]) {
            -1L -> 4     // a current-network failure outweighs an old success ratio
            else -> 0
        }
        return reachability + health.penalty(id)
    }

    /**
     * Probe the head of a list (12 is plenty: nobody dials 40 nodes from a phone) and re-sort.
     * Runs off the fetch path, on the app scope, so a refresh never waits on the network twice.
     */
    private fun probeAndReconcile(kind: String) {
        appScope.launch {
            val nodes = (if (kind == KIND_VIP) _vip.value else _free.value).take(12)
            if (nodes.isEmpty()) return@launch
            val res = NetGuard.tcpProbe(
                nodes.map { Pair(it.host, it.port) },
                timeoutMs = ProbePolicy.tcpTimeoutMs(lastReport.medianRttMs),
            )
            nodes.forEachIndexed { i, n -> localProbe[n.id] = res.getOrElse(i) { -1L } }
            val rep0 = NetGuard.measure(
                nodes.map {
                    NetGuard.ProbeTarget(it.id, it.host, it.port, it.tls != null, it.sni ?: it.host, it.alpn)
                },
                previousMedianRttMs = lastReport.medianRttMs,
            )
            val rep = rep0.copy(dnsPoisoned = lastDnsPoisoned)
            lastReport = rep
            AppSettings.setBlockReport(context, rep.toJson().toString(), rep.guess().name.lowercase())
            // re-sort in place with the new knowledge (same comparator the fetch path uses)
            val pinned = Prefs.pinnedIds(context)
            val sort: (List<FeedNode>) -> List<FeedNode> = { l ->
                l.sortedWith(compareBy({ if (it.id in pinned) 0 else 1 }, { localPenalty(it.id) }, { it.slot }))
            }
            if (kind == KIND_VIP) _vip.value = sort(_vip.value) else _free.value = sort(_free.value)
            Log.d(TAG, "$kind probes: ${res.count { it > 0 }}/${res.size} alive, regime=${rep.guess()}")
        }
    }

    /** How this phone ranks a node against the current regime - used by the list sheet's badge. */
    fun regimePreference(node: FeedNode): Int =
        Ladder.preference(AppSettings.effectiveRegime(), Ladder.transportOf(node.proto, node.tls, node.network))

    /** when this list was last built *by the server* (0 = never). The sheet footer reads this. */
    fun generatedAt(kind: String): Long = lastBuildAt[kind] ?: 0L

    /**
     * Lenient by design (see FeedJson): unknown fields are ignored so the server can grow
     * without an APK release, and one bad node never discards the rest of the list.
     */
    private fun decode(body: String): FeedPayload? = FeedJson.payload(body)

    /* ------------------------------------------------------------ feedback loop */

    private val reportChannel = Channel<Report>(64)

    @Volatile private var reportingStarted = false

    /**
     * This is what makes the server-side "test" honest: the app measures real usability from
     * inside Iran and ships it back. Cheap, batched, and never on the connect path.
     */
    fun reportResult(nodeId: String, ok: Boolean, latencyMs: Long, error: String? = null) {
        // Smart choice keeps its own private history even though there is no feed host to upload to.
        if (AppSettings.feedback) {
            health.record(nodeId, ok, latencyMs)
            refreshHealthInMemory(nodeId)
            appScope.launch {
                healthWriteMutex.withLock {
                    runCatching {
                        val temp = File(filesDir, "node-health.tsv.tmp")
                        temp.writeText(health.encode())
                        if (!temp.renameTo(healthFile)) healthFile.writeText(temp.readText())
                        temp.delete()
                    }.onFailure { Log.d(TAG, "health history write skipped: ${it.message}") }
                }
            }
        }

        // Legacy feedback transport is intentionally unreachable in direct-only mode.
        if (!AppSettings.feedback || AppSettings.feedMode == AppSettings.FEED_DIRECT) return
        ensureReporting()
        reportChannel.trySend(Report(nodeId, ok, latencyMs, error))
    }

    private fun refreshHealthInMemory(nodeId: String) {
        fun update(list: List<FeedNode>): List<FeedNode> =
            list.map { if (it.id == nodeId) health.enrich(it) else it }
        _vip.value = update(_vip.value)
        _free.value = update(_free.value)
        _proxies.value = update(_proxies.value)
        index = (_vip.value + _free.value + _proxies.value).associateBy { it.id }
    }

    /** Kept for the wire-in call in [FeedHolder]: starting twice is safe and cheap. */
    fun startReporting() = ensureReporting()

    /**
     * Flusher runs forever once spawned. [startReporting] used to be called from a hook nobody wired in,
     * so every probe result sat in the channel and the host's ban-ledger/trust scores NEVER saw a report
     * (the whole "client feedback moves reliability" idea was dead outside the unit test that calls
     * startReporting explicitly). Now any report spawns the loop on first use.
     */
    private fun ensureReporting() {
        if (reportingStarted) return
        synchronized(this) {
            if (reportingStarted) return
            reportingStarted = true
        }
        appScope.launch {
            while (true) {
                val batch = ArrayList<Report>(10)
                batch += reportChannel.receive()
                repeat(9) { batch += reportChannel.tryReceive().getOrNull() ?: return@repeat }
                delay(4_000)                            // coalesce, then one POST
                flush(batch)
            }
        }
    }

    private suspend fun flush(batch: List<Report>) = withContext(Dispatchers.IO) {
        // The reports exist to feed the host's reliability ledger. With no host (DIRECT) there is
        // nothing to upload and a bounded channel to keep draining, so drop them here rather than
        // after building the JSON body.
        if (AppSettings.feedMode == AppSettings.FEED_DIRECT || !AppSettings.feedback) return@withContext
        val arr = JSONArray()
        batch.forEach {
            arr.put(JSONObject().put("sid", it.nodeId).put("ok", it.ok)
                .put("latencyMs", it.latencyMs).put("err", it.error ?: ""))
        }
        val payload = JSONObject().put("reports", arr)
            // aggregate signal for the backend's `regime` task: the server only ever sees its own
            // vantage point; this is the last mile, measured from inside the country
            .put("block", JSONObject(lastReport.toJson().toString()))
            .put("regime", AppSettings.effectiveRegime().name.lowercase())
            .put("app", ir.meelano.vpn.BuildConfig.VERSION_NAME)
        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url("$baseUrl/?action=feedback").post(body)
            .header("X-Feed-Key", FEED_KEY).build()
        runCatching { http.newCall(req).execute().use { it.code } }
            .onFailure { Log.d(TAG, "feedback dropped (offline): ${it.message}") }
    }

    private data class Report(val nodeId: String, val ok: Boolean, val latencyMs: Long, val error: String?)

    /* ------------------------------------------------------------ applying a node */

    /**
     * "I selected a proxy and nothing changed" is nearly always one of:
     *   - the config was saved but the tunnel was never restarted;
     *   - the tunnel was restarted with the *old* profile because the write was async;
     *   - the UI showed success from the tap instead of from the service state.
     * So: write, restart, and only trust the service's own state afterwards.
     */
    suspend fun applyNode(context: Context, node: FeedNode) = withContext(Dispatchers.IO) {
        FeedHolder.pendingConnectId = node.id
        Prefs.setActiveId(context, node.id)
        ir.meelano.vpn.keepalive.ActiveNodeCache.save(context, node)
        ir.meelano.vpn.vpn.MeelanoVpnService.start(context, node)
    }

    /** A stricter-but-fairer readiness probe than "ping once, 200ms or fail". */
    suspend fun probe(node: FeedNode, timeoutMs: Long = 3000): ProbeResult = withContext(Dispatchers.IO) {
        if (node.isRawProxy) {
            val verdict = ProxyScanner.scan(
                nodes = listOf(node),
                limit = 1,
                concurrency = 1,
                timeoutMs = timeoutMs.toInt().coerceAtLeast(1_500),
                protect = { socket -> MeelanoVpnService.protectFeedSocket(socket) },
            )[node.id]
            val ok = verdict?.usable == true
            recordPlatformVerdict(node.id, verdict?.status ?: ProxyScanner.DEAD)
            return@withContext ProbeResult(
                node.id,
                ok,
                verdict?.latencyMs ?: timeoutMs,
                if (ok) null else verdict?.reason ?: "proxy_scan_failed",
            ).also { reportResult(node.id, it.ok, it.latencyMs, it.error) }
        }

        val t0 = System.nanoTime()
        val ok = runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(node.host, node.port), timeoutMs.toInt())
                true
            }
        }.getOrDefault(false)
        val ms = (System.nanoTime() - t0) / 1_000_000
        ProbeResult(node.id, ok, ms, if (ok) null else "tcp_connect_failed").also {
            reportResult(node.id, it.ok, it.latencyMs, it.error)
        }
    }

    private val FEED_KEY: String get() = BuildConfig.MEELANO_FEED_KEY // "" disables the header

    /** refuse a payload from a future server we cannot understand, instead of guessing */
    private val SCHEMA_MAX = 10
}

data class FeedPayload(val kind: String, val generatedAt: Long, val ttl: Int, val nodes: List<FeedNode>)

/** Normalised node. `raw` is what the core actually consumes; the rest is for the UI. */
data class FeedNode(
    // Defaults everywhere: a node can now be built from a single pasted line (NodeUri) without dragging
    // thirty "null" arguments through the call site. The feed path passes every field anyway.
    val id: String,
    val slot: Int = 0,
    val name: String = "",
    val subtitle: String = "",
    val cc: String? = null,
    var kind: String = "vip",
    val tier: String = "vip",
    val proto: String = "",
    val host: String = "",
    val port: Int = 0,
    val tls: String? = null,
    val network: String? = null,
    val sni: String? = null,
    val path: String? = null,
    val hostHeader: String? = null,
    val serviceName: String? = null,
    val alpn: String? = null,
    val flow: String? = null,
    val pbk: String? = null,
    val sid: String? = null,
    val fingerprint: String? = null,
    val userId: String? = null,
    val alterId: Int? = null,
    val password: String? = null,
    val username: String? = null,
    val method: String? = null,
    val cipher: String? = null,
    val insecure: Boolean = false,
    val supportsUdp: Boolean = true,
    val raw: String? = null,
    val grade: String = "D",
    val latencyMs: Long? = null,
    val reliability: Float = 0f,
    val samples: Int = 0,
    val config: String? = null,
    /** Device deep-probe verdict: clean | robot_blocked | platform_blocked | dead | unknown. */
    val platformStatus: String = "unknown",
    val platformCheckedAt: Long = 0L,
    /** Stable machine reason for a rejected proxy; UI translates the category, diagnostics keeps detail. */
    val platformReason: String? = null,
    /**
     * The server's per-node transport patch (see net/Regime.kt). A *patch*, not a full config: absent
     * keys stay the client's decision. Default = empty, so an old feed payload still parses.
     */
    val tune: ir.meelano.vpn.net.TunePatch = ir.meelano.vpn.net.TunePatch.Empty,
) {
    val latencyLabel: String get() = latencyMs?.let { if (it < 1000) "$it" else String.format("%.1fs", it / 1000.0) } ?: "—"
    val isVip: Boolean get() = tier == "vip"
    val isRawProxy: Boolean get() = proto.lowercase() in setOf("http", "socks", "socks5")
    val isUsable: Boolean get() = !isRawProxy || platformStatus == ProxyScanner.CLEAN
}

data class ProbeResult(val nodeId: String, val ok: Boolean, val latencyMs: Long, val error: String?)

/** pinned / active-id persistence: keep it off SharedPreferences.commit() on the main thread. */
object Prefs {
    private const val PREF = "meelano_feed"
    fun pinnedIds(context: Context): Set<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet("pinned", emptySet()) ?: emptySet()

    fun setPinned(context: Context, id: String, pinned: Boolean) {
        val cur = pinnedIds(context).toMutableSet()
        if (pinned) cur += id else cur -= id
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet("pinned", cur).apply()
    }

    fun setActiveId(context: Context, id: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("active", id).apply()
    }

    fun activeId(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("active", null)

    fun setVpnDesired(context: Context, desired: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("vpn_desired", desired).apply()
    }

    fun vpnDesired(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("vpn_desired", false)
}
