package ir.meelano.vpn.net

import java.net.InetAddress
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Poison-tolerant resolution for node hostnames — the fix for «روی ایرانسل وصل می‌شود، روی همراه اول نه».
 *
 * Root cause this removes: every dial path (the TCP pre-probe and the core's outbound) resolved
 * `node.host` through the *system* resolver. Inside Iran that resolver is carrier-controlled:
 * Irancell answers honestly, MCI answers with a sinkhole/blackhole address for foreign hostnames
 * (and hands out AAAA records whose v6 path is blackholed). Every domain-hosted node then read
 * "dead" on MCI while the same build connected fine on Irancell — carrier-dependent, exactly as
 * reported. The feed fetch already survived this (NetGuard's DoH fallback); the node dials did not.
 *
 * The rules, in order, are the whole product:
 *  1. an IP literal is its own answer;
 *  2. ask *both* the poisoned-capable system resolver and the three foreign DoH resolvers
 *     (NetGuard.doh — dialed by IP, majority-ranked), in parallel;
 *  3. when DoH answered, its ranked candidates win; a system answer that agrees with none of them
 *     is recorded as `poisoned` (the fleet learns from that flag) and never dialed;
 *  4. IPv4-first: on MCI the v6 path is the classic hang — prefer A records unless nothing else
 *     exists and the caller explicitly allows v6;
 *  5. when *every* resolver is silent, return no IP and let the caller keep the hostname — today's
 *     behaviour, never worse than today's behaviour.
 *
 * [pick] is pure and unit-tested; [resolve] is the IO shell (cache + parallel lookups). The sockets
 * the DoH requests use are protected (NetGuard.socketProtect) so this works even during failover
 * while the previous kill-switch TUN is still up — the exact trap VpnOrchestrator warns about.
 */
object SmartResolver {

    data class Pick(
        /** The address to dial, or null when nothing could be resolved (caller keeps the hostname). */
        val ip: String?,
        /** True when the system resolver disagreed with every DoH answer — a poisoned local DNS. */
        val poisoned: Boolean,
        /** "literal" | "doh" | "system" | "none" — diagnostics, never a user-facing string. */
        val via: String,
    )

    /** Cache: 4 minutes, mirroring NetGuard — poisoned records are a moving target, and so are CDN ones. */
    private val cache = Collections.synchronizedMap(HashMap<String, Pair<Long, Pick>>())
    private const val TTL_MS = 4L * 60_000L

    private val LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * Pure fusion of the two resolver opinions. Tests in SmartResolverTest pin every branch:
     * majority wins, poison is agreement-based, v4 comes first, silence stays silence.
     */
    fun pick(systemIps: List<String>, dohIps: List<String>): Pick {
        fun isV4(ip: String) = !ip.contains(':')
        // v4-first is the MCI blackhole guard: when both families exist the v6 path is the classic
        // hang, so it is never dialed first. An only-v6 host is still tried on v6 — its only shot.
        fun order(ips: List<String>): List<String> = ips.filter(::isV4).ifEmpty { ips }

        val sys = systemIps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val doh = dohIps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val sysOrdered = order(sys)
        val dohOrdered = order(doh)

        return when {
            dohOrdered.isNotEmpty() -> Pick(
                ip = dohOrdered.first(),
                poisoned = sys.isNotEmpty() && sys.none { it in doh },
                via = "doh",
            )
            sysOrdered.isNotEmpty() -> Pick(sysOrdered.first(), poisoned = false, via = "system")
            else -> Pick(null, poisoned = false, via = "none")
        }
    }

    /** Resolve [host] with both resolvers in parallel. IP literals never touch the network. */
    suspend fun resolve(host: String): Pick = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) return@withContext Pick(null, false, "none")
        if (LITERAL.matches(trimmed) || looksLikeV6(trimmed)) {
            return@withContext Pick(trimmed, poisoned = false, "literal")
        }
        cache[trimmed]?.let { (at, pick) -> if (System.currentTimeMillis() - at < TTL_MS) return@withContext pick }
        val (sys, doh) = coroutineScope {
            val s = async(Dispatchers.IO) {
                runCatching {
                    InetAddress.getAllByName(trimmed).map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
            }
            val d = async(Dispatchers.IO) {
                runCatching { NetGuard.doh(trimmed) }.getOrDefault(emptyList())
            }
            s.await() to d.await()
        }
        val result = pick(sys, doh)
        if (result.ip != null) cache[trimmed] = System.currentTimeMillis() to result
        result
    }

    /** An IPv6 literal shape (contains ':' and only hex/colon) — checked without touching DNS. */
    private fun looksLikeV6(s: String): Boolean =
        s.contains(':') && s.all { it.isDigit() || it in "abcdefABCDEF:" }
}
