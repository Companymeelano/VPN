package ir.meelano.vpn.vpn

import java.io.FileDescriptor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The only place in the app that knows the *names* inside the linked core.
 *
 * Why reflection instead of a compile-time dependency: the whole project must stay buildable without an
 * AAR under app/libs - that is what `MEELANO_CORE_LINKED=false` buys us: CI compiles the UI, the feed
 * client and the anti-block brain on every push, on a repo that carries no binary blobs. (And no glob
 * stars in a KDoc: Kotlin nests block comments, so a glob there silently opens a second one.) A `compileOnly`
 * + reflection pair keeps that property while making the real integration about ten lines long, and it
 * fails as a *named* error rather than a NoClassDefFoundError at the worst moment.
 *
 * What this file does without knowing anything about your AAR:
 *  - resolves the wrapper class (`CORE_BRIDGE_CLASS`, empty = not configured);
 *  - reports which entry points exist ([probe]) so a build can be verified before it ships;
 *  - refuses, loudly and with text the UI can print, to pretend it started a tunnel ([start]).
 *
 * The integrator's job is exactly one function: [start0]. See docs/CORE-INTEGRATION.md §3.
 */
object XrayBridge {

    /** `local.properties` / `-PMEELANO_CORE_BRIDGE_CLASS=life.xtls.libxray.Xray` style. */
    private val bridgeClass: String get() = ir.meelano.vpn.BuildConfig.CORE_BRIDGE_CLASS.trim()

    /** The three names a fork may rename. Everything else in the app is independent of them. */
    private const val M_START = "startLocal"
    private const val M_STOP = "stopLocal"
    private const val M_VERSION = "requireVersion"
    // informational: a build whose AAR exposes these can fill bind0() with two calls; a build whose engine
    // takes the fd at start time can ignore them entirely
    private const val M_BIND_READ = "ktBindTunRead"
    private const val M_BIND_WRITE = "ktBindTunWrite"

    /** Thrown when the bridge cannot serve the request; `why` is user-presentable. */
    class Missing(val why: String) : IllegalStateException("core bridge: $why")

    @Volatile private var handle: Any? = null
    @Volatile private var startedAt: Long = 0L

    private val clazz: Class<*>? by lazy {
        if (bridgeClass.isEmpty()) null else runCatching { Class.forName(bridgeClass, false, XrayBridge::class.java.classLoader) }.getOrNull()
    }

    val configured: Boolean get() = bridgeClass.isNotEmpty() && clazz != null

    /** Which entry points the shipped AAR actually has. A build where all three are true is integrable. */
    fun probe(): Map<String, Boolean> {
        val c = clazz ?: return mapOf(M_START to false, M_STOP to false, M_VERSION to false, M_BIND_READ to false)
        return mapOf(
            M_START to (find(c, M_START) != null),
            M_STOP to (find(c, M_STOP) != null),
            M_VERSION to (find(c, M_VERSION) != null),
            M_BIND_READ to (find(c, M_BIND_READ) != null),
            M_BIND_WRITE to (find(c, M_BIND_WRITE) != null),
        )
    }

    /** One-line diagnosis for the failure card / logcat; never throws. */
    fun describe(): String {
        if (bridgeClass.isEmpty()) {
            return "CORE_BRIDGE_CLASS is empty: set it to the wrapper class inside your AAR " +
                "(javap -classpath classes.jar -public \$(unzip -p app/libs/core.aar classes.jar >/dev/null; echo)) - see docs/CORE-INTEGRATION.md §2"
        }
        val c = clazz ?: return "class '$bridgeClass' not found in this build"
        val missing = probe().filterValues { !it }.keys
        return if (missing.isEmpty()) "ready ($bridgeClass)" else "class '$bridgeClass' has no ${missing.joinToString("/")}"
    }

    private fun find(c: Class<*>, name: String): Method? {
        // Kotlin `object` puts statics on the class or on its Companion; a gomobile wrapper puts them on
        // the generated class. Look in all three, take the first non-synthetic match by name.
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

    /**
     * Bind the tunnel the OS created. Split from [start] because that is the order the platform forces:
     * the engine must be configured before `VpnService.establish()` runs, and the fd exists only after it.
     */
    fun bind(fd: FileDescriptor, mtu: Int) {
        if (handle == null) throw Missing("start() has not run yet, so there is nothing to bind")
        bind0(fd, mtu)
    }

    /** Version string the core reports, or null. Used by the diagnostics row, never for logic. */
    fun version(): String? {
        val c = clazz ?: return null
        val m = find(c, M_VERSION) ?: return null
        return runCatching {
            val recv = receiver(m, c)
            m.invoke(recv, *emptyArray<Any?>())?.toString()
        }.getOrNull()
    }

    private fun receiver(m: Method, c: Class<*>): Any? {
        if (Modifier.isStatic(m.modifiers)) return null
        // INSTANCE is how Kotlin compiles `object`; if the fork exposed an instance method, its companion
        // or the field is what we need, and neither is worth guessing at from here.
        val instance = runCatching { c.getDeclaredField("INSTANCE").get(null) }.getOrNull()
        return instance ?: runCatching { m.declaringClass.getDeclaredField("INSTANCE").get(null) }.getOrNull()
            ?: throw Missing("${m.name} is an instance method with no INSTANCE field")
    }

    /**
     * @param profilePath the JSON written by CoreApi (never a raw string in memory: a file is what the
     *        engine can re-read after a process restart, and what a bug report can attach)
     * @param tag         stable id for the running instance; keep it equal to the node id so `stop` can
     *        be called by the service without holding state
     * @param mtu         the interface MTU, for engines that clamp MSS themselves (see CoreProfiles note)
     */
    fun start(profilePath: String, tag: String, tunFd: Int, mtu: Int): Boolean {
        if (!ir.meelano.vpn.BuildConfig.CORE_LINKED) throw CoreApi.CoreNotLinked()
        val c = clazz ?: throw Missing(describe())
        val m = find(c, M_START) ?: throw Missing(describe())
        start0(m, receiver(m, c), profilePath, tag, tunFd, mtu)
        handle = c
        startedAt = System.currentTimeMillis()
        return true
    }

    fun stop() {
        val c = clazz ?: return
        handle ?: return
        val m = find(c, M_STOP)
        if (m != null) {
            runCatching { m.invoke(receiver(m, c), *emptyArray<Any?>()) }
        }
        handle = null
    }

    val uptimeMs: Long get() = if (handle == null) 0L else System.currentTimeMillis() - startedAt

    /**
     * THE TEN LINES. Everything above is generic; this is where the engine's real call goes, because the
     * argument order/types of a gomobile wrapper are the one thing that cannot be guessed safely.
     *
     * Typical body once the AAR is in place:
     *
     *   m.invoke(recv, profilePath)                                   // startLocal(String configPath)
     *   // or, for engines that take the fd + an options object:
     *   // val opts = c.getDeclaredConstructor().newInstance()
     *   // opts.javaClass.getMethod("setConfigPath", String::class.java).invoke(opts, profilePath)
     *   // m.invoke(recv, tag, opts, tunFd, mtu)
     *
     * Do not catch-and-continue here. If the core cannot start, the caller must see it: that is what keeps
     * the ring honest (ConnectPhase.Failed + AdviceCard) instead of "connected with no traffic".
     */
    private fun start0(m: Method, recv: Any?, profilePath: String, tag: String, tunFd: Int, mtu: Int) {
        throw Missing(
            "start0() is still the scaffold: fill it in with ${m.declaringClass.name}.${m.name}(" +
                m.parameterTypes.joinToString(", ") { it.simpleName } + ") using profilePath=$profilePath, " +
                "tag=$tag, fd=$tunFd, mtu=$mtu (docs/CORE-INTEGRATION.md §3)"
        )
    }

    /**
     * THE OTHER HALF of the ten lines: hand the TUN descriptor to the engine.
     *
     * Typical body:
     *   val c = clazz!!                                     // the wrapper class
     *   find(c, "ktBindTunRead")!!.invoke(recv, fd, mtu, callback)
     *   find(c, "ktBindTunWrite")!!.invoke(recv, fd, mtu, callback)
     * or, for engines with one call: `startVpn(fd.detachFd())` - then do NOT close `fd` here, the engine
     * owns it from this point (double-close on a recycled fd is a fun bug to debug at 3am).
     */
    private fun bind0(fd: FileDescriptor, mtu: Int) {
        throw Missing(
            "bind0() is still the scaffold: bind the tunnel (fd=$fd, mtu=$mtu) to the engine - " +
                "docs/CORE-INTEGRATION.md §3"
        )
    }
}
