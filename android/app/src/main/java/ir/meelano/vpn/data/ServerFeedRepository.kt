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

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(Cache(File(context.cacheDir, "http-feed").apply { mkdirs() }, 6L * 1024 * 1024))
            .build()
    }

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
            http.newCall(req).execute().use { resp ->
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
                            apply(parsed, kind)
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

    private fun loadCached(kind: String) {
        runCatching {
            val f = File(filesDir, "$kind.json")
            if (f.isFile) decode(f.readText())?.let { apply(it, kind) }
        }
    }

    /** Server decides the order; we only add the local "favourite/pinned" bias. */
    private fun apply(payload: FeedPayload, kind: String) {
        val pinned = Prefs.pinnedIds(context)
        val list = payload.nodes.map { it.copy(kind = kind) }
            .sortedWith(compareBy({ if (it.id in pinned) 0 else 1 }, { it.slot }))
        if (kind == KIND_VIP) _vip.value = list else _free.value = list
        index = (list + _vip.value + _free.value).associateBy { it.id }
        lastBuildAt[kind] = payload.generatedAt
    }

    private val lastBuildAt = mutableMapOf<String, Long>()

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
        val body = JSONObject().put("reports", arr).toString()
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
    private val SCHEMA_MAX = 9
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
