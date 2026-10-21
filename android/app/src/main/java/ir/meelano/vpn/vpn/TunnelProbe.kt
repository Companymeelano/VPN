package ir.meelano.vpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Proof that packets crossed the Android TUN and the selected outbound, not merely that Xray parsed a
 * profile. These requests intentionally run in the VPN app process without VpnService.protect(); the
 * core's own remote sockets are protected by XrayBridge, while these probe sockets must enter the TUN.
 *
 * The verdict is two-tiered, and the split is the lesson of the Irancell field report:
 *  - [Result.clean]  - every platform answered exactly as expected. The best grade a node can get.
 *  - [Result.usable] - the tunnel provably carries internet traffic (the dedicated Cloudflare
 *    connectivity endpoint, or a majority of endpoints), even if one platform is slow, geo-blocked
 *    at this exit, or dropped a single probe to mobile jitter.
 *
 * Connect/health decisions gate on *usable*; *clean* only ranks. The previous rule - all three
 * endpoints or failure, re-checked every health interval - meant one slow `generate_204` on a
 * jittery carrier could reject a healthy tunnel at connect time and then keep killing it every
 * minute after. That is indistinguishable, in user reports, from "it only connects sometimes".
 */
class TunnelProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) {
    data class Endpoint(val id: String, val url: String, val expectedStatus: Int = 204)
    data class Check(val id: String, val status: Int?, val latencyMs: Long, val error: String? = null) {
        val reached: Boolean get() = status != null
    }

    data class Result(val checks: List<Check>) {
        private fun ok(id: String): Boolean {
            val endpoint = ENDPOINTS.first { it.id == id }
            return checks.firstOrNull { it.id == id }?.status == endpoint.expectedStatus
        }

        val cloudflareOk: Boolean get() = ok("cloudflare")
        val googleOk: Boolean get() = ok("google")
        val gstaticOk: Boolean get() = ok("gstatic")
        val internet: Boolean get() = checks.any { it.reached }
        val clean: Boolean get() = cloudflareOk && googleOk && gstaticOk
        val platformBlocked: Boolean get() = cloudflareOk && (!googleOk || !gstaticOk)

        /** How many endpoints returned their exact expected status. */
        val okCount: Int get() = ENDPOINTS.count { ok(it.id) }

        /**
         * "Internet demonstrably flows through this tunnel": a majority verdict, or the one
         * purpose-built connectivity endpoint. A single Google timeout on a congested cell is
         * weather, not a dead tunnel - the platform note still travels in [reason] and in the
         * node's platform verdict, it just no longer vetoes the connection.
         */
        val usable: Boolean get() = okCount >= 2 || cloudflareOk

        /**
         * Median successful-check latency. The old "slowest check" punished a node for its one
         * unlucky endpoint and overwrote a genuinely fast path in the health ledger.
         */
        val latencyMs: Long
            get() {
                val good = ENDPOINTS.mapNotNull { e ->
                    checks.firstOrNull { c -> c.id == e.id && c.status == e.expectedStatus }?.latencyMs
                }.sorted()
                val pool = good.ifEmpty { checks.filter { it.status != null }.map { it.latencyMs }.sorted() }
                return if (pool.isEmpty()) 0L else pool[pool.size / 2]
            }
        val reason: String
            get() = when {
                clean -> "clean"
                platformBlocked -> "platform_blocked"
                usable -> "partial"
                internet -> "unexpected_status"
                else -> "no_internet_through_tunnel"
            }
    }

    suspend fun run(): Result = coroutineScope {
        val checks = ENDPOINTS.map { endpoint -> async(Dispatchers.IO) { check(endpoint) } }.map { it.await() }
        classify(checks)
    }

    private suspend fun check(endpoint: Endpoint): Check = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val request = Request.Builder()
            .url(endpoint.url)
            .get()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Connection", "close")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                Check(endpoint.id, response.code, elapsedMs(started))
            }
        }.getOrElse { error ->
            Check(endpoint.id, null, elapsedMs(started), error.javaClass.simpleName.ifBlank { "io_error" })
        }
    }

    companion object {
        val ENDPOINTS = listOf(
            Endpoint("cloudflare", "https://cp.cloudflare.com/generate_204"),
            Endpoint("google", "https://www.google.com/generate_204"),
            Endpoint("gstatic", "https://connectivitycheck.gstatic.com/generate_204"),
        )

        fun classify(checks: List<Check>): Result = Result(checks)
        private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
    }
}
