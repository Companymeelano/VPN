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
        val latencyMs: Long get() = checks.filter { it.status != null }.maxOfOrNull { it.latencyMs } ?: 0L
        val reason: String
            get() = when {
                clean -> "clean"
                platformBlocked -> "platform_blocked"
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
