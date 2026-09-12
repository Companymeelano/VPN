package ir.meelano.vpn.data

import android.content.Context
import ir.meelano.vpn.BuildConfig
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ir.meelano.vpn.net.NetGuard
import ir.meelano.vpn.net.Ladder
import ir.meelano.vpn.net.BlockReport
import ir.meelano.vpn.net.Regime
import org.json.JSONArray
import org.json.JSONObject
import ir.meelano.vpn.keepalive.ActiveNodeCache
import ir.meelano.vpn.vpn.MeelanoVpnService

/**
 * One repo for both lists (VIP + free) because the server deliberately returns the SAME shape.
 *
 * The speed of this file is what the user feels as "the app is slow":
 *   - ETag / If-None-Match  -> most polls cost 0 bytes and 0 parse time
 *   - disk cache (OkHttp)   -> first paint never waits on the network
 *   - stale-while-revalidate-> the list renders instantly, then silently gets better
 *   - a tiny strict model    -> unknown fields are ignored instead of crashing a release
 * Nothing here touches the main thread, and nothing here parses the raw proxy lists:
 * the server did that already, which is the whole point of moving the work to /v/.
 */
class ServerFeedRepository(
    private val context: Context,
    private val baseUrl: String = "https://ainetmee.ir/v",
) {

    companion object {
        private const val TAG = "MeelanoFeed"
        const val KIND_VIP = "vip"
        const val KIND_FREE = "free"

        fun isVip(node: FeedNode) = node.tier == KIND_VIP
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
    private val etags = mutableMapOf<String, String>()

    private val _vip = MutableStateFlow<List<FeedNode>>(emptyList())
    val vip: StateFlow<List<FeedNode>> = _vip
    private val _free = MutableStateFlow<List<FeedNode>>(emptyList())
    val free: StateFlow<List<FeedNode>> = _free
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

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
        // the service can be started from a notification tap before the repo loaded: give it a moment
        repeat(6) { refresh(KIND_VIP); refresh(KIND_FREE); index[id]?.let { return it }; delay(400) }
        return null
    }

    /* ------------------------------------------------------------ fetch */

    suspend fun refresh(kind: String) = withContext(Dispatchers.IO) {
        _syncing.value = true
        try {
            val req = Request.Builder()
                .url("$baseUrl/?action=$kind")
                .header("Accept-Encoding", "gzip")
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
                        return@use
                    }
                    else -> {
                        val body = resp.body?.string() ?: return@use
                        val parsed = decode(body)
                        if (parsed != null) {
                            File(filesDir, "$kind.json").writeText(body)   // raw for the next cold start
                            resp.header("ETag")?.let { etags[kind] = it }
                            lastDnsPoisoned = false
                            apply(parsed, kind)
                            probeAndReconcile(kind)
                        } else {
                            Log.w(TAG, "$kind payload rejected")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refresh $kind failed: ${t.message}")   // offline -> keep showing cache
        } finally {
            _syncing.value = false
        }
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
    private fun apply(payload: FeedPayload, kind: String) {
        val pinned = Prefs.pinnedIds(context)
        val list = payload.nodes.map { it.copy(kind = kind) }
            .sortedWith(
                compareBy({ if (it.id in pinned) 0 else 1 }, { localPenalty(it.id) }, { it.slot })
            )
        if (kind == KIND_VIP) _vip.value = list else _free.value = list
        index = (list + _vip.value + _free.value).associateBy { it.id }
        lastBuildAt[kind] = payload.generatedAt
    }

    private val lastBuildAt = mutableMapOf<String, Long>()

    /* ------------------------------------------------------------ local block probing */

    /** id -> RTT in ms, or -1 for "answered nothing" (a fail is remembered briefly, not forever). */
    private val localProbe = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    @Volatile private var lastReport: BlockReport = BlockReport()
    fun blockReport(): BlockReport = lastReport

    private fun localPenalty(id: String): Int = when (localProbe[id]) {
        null -> 0        // unproven: trust the server
        -1L -> 1         // we tried and it did not answer from here
        else -> 0
    }

    /**
     * Probe the head of a list (12 is plenty: nobody dials 40 nodes from a phone) and re-sort.
     * Runs off the fetch path, on the app scope, so a refresh never waits on the network twice.
     */
    private fun probeAndReconcile(kind: String) {
        appScope.launch {
            val nodes = (if (kind == KIND_VIP) _vip.value else _free.value).take(12)
            if (nodes.isEmpty()) return@launch
            val res = NetGuard.tcpProbe(nodes.map { Pair(it.host, it.port) })
            nodes.forEachIndexed { i, n -> localProbe[n.id] = res.getOrElse(i) { -1L } }
            val rep0 = NetGuard.measure(nodes.map {
                NetGuard.ProbeTarget(it.id, it.host, it.port, it.tls != null, it.sni ?: it.host, it.alpn)
            })
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

    /**
     * This is what makes the server-side "test" honest: the app measures real usability from
     * inside Iran and ships it back. Cheap, batched, and never on the connect path.
     */
    fun reportResult(nodeId: String, ok: Boolean, latencyMs: Long, error: String? = null) {
        reportChannel.trySend(Report(nodeId, ok, latencyMs, error))
    }

    fun startReporting() {
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
    val id: String,
    val slot: Int,
    val name: String,
    val subtitle: String,
    val cc: String?,
    var kind: String = "vip",
    val tier: String,
    val proto: String,
    val host: String,
    val port: Int,
    val tls: String?,
    val network: String?,
    val sni: String?,
    val path: String?,
    val hostHeader: String?,
    val alpn: String?,
    val flow: String?,
    val pbk: String?,
    val sid: String?,
    val fingerprint: String?,
    val userId: String?,
    val alterId: Int?,
    val password: String?,
    val username: String?,
    val method: String?,
    val cipher: String?,
    val insecure: Boolean,
    val supportsUdp: Boolean,
    val raw: String?,
    val grade: String,
    val latencyMs: Long?,
    val reliability: Float,
    val samples: Int,
    val config: String?,
    /**
     * The server's per-node transport patch (see net/Regime.kt). A *patch*, not a full config: absent
     * keys stay the client's decision. Default = empty, so an old feed payload still parses.
     */
    val tune: ir.meelano.vpn.net.TunePatch = ir.meelano.vpn.net.TunePatch.Empty,
) {
    val latencyLabel: String get() = latencyMs?.let { if (it < 1000) "$it" else String.format("%.1fs", it / 1000.0) } ?: "—"
    val isVip: Boolean get() = tier == "vip"
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
}
