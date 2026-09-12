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

    /**
     * Which builder produced the list. HOST (the default) is the /v/ build: probed, gated through
     * CONNECT-to-Google, ranked by a multi-day reliability ledger. DIRECT produces the list on the
     * phone so the app needs no host of yours at all; AUTO calls the host and only falls back when it
     * cannot be reached. All three end in the same apply() + the same cache file, so nothing downstream
     * - sorting, pinned nodes, the connect path, the sheet - has to know which one ran.
     */
    suspend fun refresh(kind: String) = withContext(Dispatchers.IO) {
        when (AppSettings.feedMode) {
            AppSettings.FEED_DIRECT -> refreshDirect(kind)
            AppSettings.FEED_AUTO -> {
                refreshHost(kind)
                // "the host is down" must not mean "no list": fall through to the device builder
                if ((if (kind == KIND_VIP) _vip.value else _free.value).isEmpty()) refreshDirect(kind)
            }
            else -> refreshHost(kind)
        }
    }

    private suspend fun refreshHost(kind: String) = withContext(Dispatchers.IO) {
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

    /* ------------------------------------------------------------ on-device builder */

    private val _directNotes = MutableStateFlow<List<String>>(emptyList())

    /** Why a device-built list looks the way it does - shown in the sheet footer, never as an error. */
    val directNotes: StateFlow<List<String>> = _directNotes

    /** One line for Settings, so the user always knows where today's list came from. */
    fun sourceLabel(): String = when (AppSettings.feedMode) {
        AppSettings.FEED_DIRECT -> "آنبرد (بدون هاست)"
        AppSettings.FEED_AUTO -> "هاست، در قطع شدن: آنبرد"
        else -> "هاست"
    }

    /**
     * Free: the public config lists. VIP: whatever you pasted in the app (+ your own subscription URL).
     * What DIRECT mode does *not* do is the host's gate, ban ledger and ranking - that work needs a
     * machine that is reachable from everywhere and is awake tomorrow. We say so in the footer instead
     * of pretending the two builders are equal.
     */
    private suspend fun refreshDirect(kind: String) {
        _syncing.value = true
        try {
            val extra = AppSettings.feedExtraUrl.takeIf { it.startsWith("http") }?.let {
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
                    extra = extra,
                    fetch = { u -> fetchText(u.url, u.maxBytes) },
                    probe = { nodes -> probeBatch(nodes) },
                )
            }
            _directNotes.value = res.notes
            if (res.nodes.isEmpty()) {
                // Nothing usable this time: keep showing the last good list (and its cache), because a
                // filtered GitHub response is a Tuesday, not a reason to hand the user an empty screen.
                Log.w(TAG, "direct $kind produced nothing")
                return
            }
            val now = System.currentTimeMillis() / 1000L
            apply(FeedPayload(kind, now, DIRECT_TTL_SEC, res.nodes), kind)
            runCatching {
                File(filesDir, "$kind.json")
                    .writeText(FeedJson.encodePayload(kind, res.nodes, now, DIRECT_TTL_SEC, res.notes))
            }.onFailure { Log.w(TAG, "direct cache write failed: ${it.message}") }
            // No probeAndReconcile(): these were dialed *now*, from this network. Re-probing 12 sockets
            // for a number we already have is exactly the duplicated work that made the old build slow.
        } catch (t: Throwable) {
            Log.w(TAG, "direct $kind failed: ${t.message}")
        } finally {
            _syncing.value = false
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
            fetchText(extra.url, extra.maxBytes)?.let {
                sources++
                blob.append(it)
            }
        }
        val parsed = NodeUri.parseBlob(blob.toString(), tier = KIND_VIP, brand = VIP_BRAND)
        val head = NodeUri.dedupe(parsed, max = 48)
        val rtt = probeBatch(head)
        val nodes = head.mapIndexed { i, n ->
            val ms = rtt[n.id]
            n.copy(
                slot = i + 1,
                latencyMs = if (ms != null && ms >= 0) ms else null,
                grade = if (ms == null || ms < 0) "D" else NodeUri.grade(ms),
                reliability = if (ms != null && ms >= 0) 1f else 0f,
                samples = 1,
            )
        }.sortedWith(compareBy({ if (it.latencyMs == null) 1 else 0 }, { it.latencyMs ?: Long.MAX_VALUE }))
        val notes = ArrayList<String>(2)
        notes += if (parsed.isEmpty()) "هنوز چیزی وارد نکرده‌اید: در تنظیمات، پیکربندی‌هایتان را بچسبانید"
        else "محلی · ${parsed.size} خط خوانده شد"
        if (sources > 0) notes += "اشتراکِ شخصی از لینکِ خودتان خوانده شد"
        if (sources == 0 && blob.toString().isBlank()) notes += "لینکِ اشتراک تنظیم نشده"
        notes += "${nodes.count { it.latencyMs != null }} نود از همین شبکه پاسخ داد"
        return DirectFeed.Result(nodes, sources, maxOf(1, sources), parsed.size, head.size, notes)
    }

    /**
     * One capped GET, on the cold client (30 s read) because a public list can be slow, with the same
     * DoH/pinned fallback the host path uses since GitHub names get poisoned too.
     *
     * No Accept-Encoding header on purpose: OkHttp only *transparently* inflates gzip when it added the
     * header itself; a hand-written one means we would be reading compressed bytes as text.
     */
    private suspend fun fetchText(url: String, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "M\u2022A-VPN/${BuildConfig.VERSION_NAME}")
            .build()
        open(req, httpCold)?.use { resp ->
            if (!resp.isSuccessful) return@use null
            val src = resp.body?.byteStream() ?: return@use null
            val out = java.io.ByteArrayOutputStream(32 * 1024)
            val chunk = ByteArray(32 * 1024)
            var read = 0
            while (read < maxBytes) {
                val n = src.read(chunk)
                if (n <= 0) break
                out.write(chunk, 0, n)
                read += n
            }
            // Truncating mid-line can cut a UTF-8 sequence in half; drop the partial tail before parsing.
            val text = out.toString("UTF-8")
            if (read >= maxBytes) text.substringBeforeLast('\n') else text
        }
    }

    /** Batch dial through the same helper the "why did it not connect" card uses: 1 socket per node. */
    private suspend fun probeBatch(nodes: List<FeedNode>): Map<String, Long> {
        if (nodes.isEmpty()) return emptyMap()
        val res = NetGuard.tcpProbe(nodes.map { Pair(it.host, it.port) }, timeoutMs = 1500, limit = nodes.size)
        return nodes.mapIndexed { i, n -> n.id to res.getOrElse(i) { -1L } }.toMap()
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
        // The reports exist to feed the host's reliability ledger. With no host (DIRECT) there is
        // nothing to upload and a bounded channel to keep draining, so drop them here rather than
        // after building the JSON body.
        if (AppSettings.feedMode == AppSettings.FEED_DIRECT) return@withContext
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
