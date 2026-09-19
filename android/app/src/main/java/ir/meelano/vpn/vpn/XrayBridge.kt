package ir.meelano.vpn.vpn

import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * The only place in the app that knows the *names* inside the linked core.
 *
 * Why reflection instead of a compile-time dependency: the whole project must stay buildable without an
 * AAR under app/libs - that is what `MEELANO_CORE_LINKED=false` buys us: CI compiles the UI, the feed
 * client and the anti-block brain on every push, on a repo that carries no binary blobs. (And no glob
 * stars in a KDoc: Kotlin nests block comments, so a glob there silently opens a second one.) A reflection
 * bridge keeps that property and fails as a *named* error rather than a NoClassDefFoundError at the worst
 * moment.
 *
 * WHAT IS IMPLEMENTED HERE (since v2.4.0-beta.5): the canonical XTLS/libXray `Invoke` API, pinned to
 * release `v26.9.9` in .github/workflows/apk.yml. One entry point - `invoke(String): String` - carries
 * every command as a JSON envelope:
 *
 *   request : {"apiVersion":3,"method":"runXray","payload":{"xrayJson":"..."}}
 *   response: {"success":true,"data":{...},"error":""}
 *
 * and three lifecycle helpers that gomobile generated beside it:
 *
 *   registerDialerController(DialerController)  - outbound socket protect (the anti-loop)
 *   registerListenerController(DialerController) - same for listeners we never bind
 *   setDNS(DialerController, String)            - pins Go's resolver to a protected IP:53 endpoint
 *   resetDNS()
 *
 * The TUN fd reaches the engine through config, not through a call: `env."xray.tun.fd"` is written into
 * the profile root right before `runXray` (that is the upstream replacement for the removed `SetTunFd`,
 * and it is why [bind] - not [start] - is where the engine actually starts).
 */
object XrayBridge {

    /** `local.properties` / `-PMEELANO_CORE_BRIDGE_CLASS=libxray.LibXray` style. */
    private val bridgeClass: String get() = ir.meelano.vpn.BuildConfig.CORE_BRIDGE_CLASS.trim()

    /** The names inside the pinned AAR. If libXray ever renames one, the javap gate in apk.yml fails first. */
    private const val M_INVOKE = "invoke"
    private const val M_REGISTER_DIALER = "registerDialerController"
    private const val M_REGISTER_LISTENER = "registerListenerController"
    private const val M_SET_DNS = "setDNS"
    private const val M_RESET_DNS = "resetDNS"

    /** gomobile generates this Go interface as a Java interface beside the wrapper class. */
    private const val DIALER_INTERFACE = "DialerController"

    private const val API_VERSION = 3
    private const val METHOD_RUN = "runXray"
    private const val METHOD_STOP = "stopXray"
    private const val METHOD_VERSION = "xrayVersion"

    /** Go's own DNS goes here, via a protected socket (same DNS the VpnService advertises). */
    private const val CORE_DNS = "1.1.1.1:53"

    /** Thrown when the bridge cannot serve the request; `why` is user-presentable. */
    class Missing(val why: String) : IllegalStateException("core bridge: $why")

    /** CoreApi installs this at connect time; the engine's dialer controller delegates to it. */
    @Volatile var protect: ((Int) -> Boolean)? = null

    @Volatile private var handle: Class<*>? = null
    @Volatile private var startedAt: Long = 0L
    @Volatile private var pendingProfile: String? = null
    @Volatile private var pendingTag: String = ""
    @Volatile private var pendingMtu: Int = 0

    private val clazz: Class<*>? by lazy {
        if (bridgeClass.isEmpty()) null
        else runCatching { Class.forName(bridgeClass, false, XrayBridge::class.java.classLoader) }.getOrNull()
    }

    val configured: Boolean get() = bridgeClass.isNotEmpty() && clazz != null

    /** Which entry points the shipped AAR actually has. All true = the build is operable. */
    fun probe(): Map<String, Boolean> {
        val c = clazz ?: return emptyMap()
        return mapOf(
            M_INVOKE to (find(c, M_INVOKE) != null),
            M_REGISTER_DIALER to (find(c, M_REGISTER_DIALER) != null),
            M_SET_DNS to (find(c, M_SET_DNS) != null),
            M_RESET_DNS to (find(c, M_RESET_DNS) != null),
        )
    }

    /** One-line diagnosis for the failure card / logcat; never throws. */
    fun describe(): String {
        if (bridgeClass.isEmpty()) {
            return "CORE_BRIDGE_CLASS is empty: this build carries no engine AAR " +
                "(docs/CORE-INTEGRATION.md §2; CI pins libXray v26.9.9)"
        }
        val c = clazz ?: return "class '$bridgeClass' not found in this build"
        val missing = probe().filterValues { !it }.keys
        return if (missing.isEmpty()) "ready ($bridgeClass)" else "class '$bridgeClass' has no ${missing.joinToString("/")}"
    }

    private fun find(c: Class<*>, name: String): Method? {
        val candidates = ArrayList<Class<*>>()
        candidates.add(c)
        for (inner in c.declaredClasses) candidates.add(inner)
        for (k in candidates) {
            for (m in k.methods) {
                if (m.name == name && !m.isSynthetic) return m
            }
        }
        return null
    }

    private fun receiver(m: Method): Any? {
        if (Modifier.isStatic(m.modifiers)) return null
        return throw Missing("${m.name} must be a static gomobile method; instance receivers are unsupported")
    }

    /** libXray's one-call command channel. Parses the envelope and throws [Missing] on success=false. */
    private fun invoke(method: String, payload: org.json.JSONObject?): org.json.JSONObject {
        val c = clazz ?: throw Missing(describe())
        val m = find(c, M_INVOKE) ?: throw Missing(describe())
        val req = org.json.JSONObject()
        req.put("apiVersion", API_VERSION)
        req.put("method", method)
        if (payload != null) req.put("payload", payload)
        val raw = invokeUnchecked(req.toString()).ifBlank { "{}" }
        val resp = runCatching { org.json.JSONObject(raw) }.getOrElse {
            throw Missing("$method: unreadable response (${raw.take(120)})")
        }
        if (!resp.optBoolean("success", false)) {
            val err = resp.optString("error").ifBlank { "unknown error from $method" }
            throw Missing(err)
        }
        return resp.optJSONObject("data") ?: org.json.JSONObject()
    }

    private fun invokeUnchecked(requestJson: String): String {
        val c = clazz ?: throw Missing(describe())
        val m = find(c, M_INVOKE) ?: throw Missing("class '$bridgeClass' has no $M_INVOKE")
        return try {
            (m.invoke(receiver(m), requestJson) as? String) ?: ""
        } catch (e: InvocationTargetException) {
            throw Missing("invoke: ${(e.targetException ?: e).message}")
        }
    }

    /**
     * The gomobile `DialerController` interface, created dynamically so this file never names a
     * compile-time type from the AAR. Go's `int` crosses the JNI boundary as a Java `long`.
     */
    private fun dialerController(c: Class<*>): Any {
        val iface = runCatching { Class.forName(c.`package`?.name?.let { "$it.$DIALER_INTERFACE" } ?: DIALER_INTERFACE) }
            .getOrElse { throw Missing("interface $DIALER_INTERFACE not found beside ${c.name}") }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val fd = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
            if (fd < 0) false
            else when (method.name) {
                // gomobile lowercases the first letter: ProtectFd -> protectFd
                "protectFd" -> protect?.invoke(fd) ?: false
                else -> false
            }
        }
    }

    /**
     * Stage 1 of the two-stage start: store the profile, install the protect controller, pin the DNS.
     * The engine itself starts in [bind], because that is where the TUN fd first exists (call order is
     * platform-mandated: config before `VpnService.establish()`, fd after it).
     */
    fun start(profilePath: String, tag: String, tunFd: Int, mtu: Int): Boolean {
        if (!ir.meelano.vpn.BuildConfig.CORE_LINKED) throw CoreApi.CoreNotLinked()
        val c = clazz ?: throw Missing(describe())
        pendingProfile = profilePath
        pendingTag = tag
        pendingMtu = mtu
        startedAt = System.currentTimeMillis()

        val controller = dialerController(c)
        find(c, M_REGISTER_DIALER)?.invoke(null, controller)
            ?: throw Missing("class '$bridgeClass' has no $M_REGISTER_DIALER")
        find(c, M_REGISTER_LISTENER)?.invoke(null, controller)   // optional; sockets we never bind stay harmless
        find(c, M_SET_DNS)?.let { m ->
            try {
                m.invoke(null, controller, CORE_DNS)
            } catch (e: InvocationTargetException) {
                throw Missing("setDNS: ${(e.targetException ?: e).message}")
            }
        }
        return true
    }

    /**
     * Stage 2: the fd arrived - inject it into the profile root env and start the engine for real.
     * A failure here means "configured but no traffic", the single symptom users cannot diagnose, so it
     * throws by design (the orchestrator turns it into ConnectPhase.Failed, not a green ring).
     */
    fun bind(fd: FileDescriptor, mtu: Int) {
        bindRaw(rawFdOf(fd) ?: throw Missing("cannot read the tunnel fd (FileDescriptor.descriptor renamed?)"), mtu)
    }

    fun bindRaw(tunFd: Int, mtu: Int) {
        val path = pendingProfile ?: throw Missing("start() has not run yet, so there is nothing to bind")
        val profileText = runCatching { File(path).readText() }.getOrElse { throw Missing("profile unreadable: $path") }
        val cfg = runCatching { org.json.JSONObject(profileText) }.getOrElse { throw Missing("profile is not JSON: $path") }
        val env = cfg.optJSONObject("env") ?: org.json.JSONObject().also { cfg.put("env", it) }
        env.put("xray.tun.fd", tunFd)
        val payload = org.json.JSONObject()
        payload.put("xrayJson", cfg.toString())
        invoke(METHOD_RUN, payload)
        handle = clazz
    }

    /** Version string the core reports; the diagnostics row prints it, logic never depends on it. */
    fun version(): String? {
        if (clazz == null) return null
        return runCatching {
            val data = invoke(METHOD_VERSION, null)
            data.optString("version").ifBlank { data.toString().take(80) }
        }.getOrNull()
    }

    fun stop() {
        val c = clazz ?: return
        if (handle != null) {
            runCatching { invoke(METHOD_STOP, null) }
            handle = null
        }
        pendingProfile = null
        pendingTag = ""
        find(c, M_RESET_DNS)?.let { runCatching { it.invoke(null) } }
        protect = null
        startedAt = 0L
    }

    val uptimeMs: Long get() = if (handle == null) 0L else System.currentTimeMillis() - startedAt

    /** Same reflective read CoreApi does, kept local so the bridge owns its own edge cases. */
    private fun rawFdOf(fd: FileDescriptor): Int? = runCatching {
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true
        f.getInt(fd).takeIf { it >= 0 }
    }.getOrNull()
}
