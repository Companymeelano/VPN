package ir.meelano.vpn.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import ir.meelano.vpn.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Self-update from the shared host: /v/?action=version -> download -> verify -> install.
 *
 * Read the "auto" part honestly, because Android does not allow what the brief implies:
 *   - an app can DOWNLOAD and VERIFY an APK by itself, and prompt with one tap;
 *   - it can NOT silently install one unless it is a Device Owner / system app. So the UX here is
 *     "already downloaded, verified, one tap to install" - which users read as automatic
 *     (no browsing, no waiting, no file picker);
 *   - install permission (REQUEST_INSTALL_PACKAGES / "unknown sources") must be granted once,
 *     and we route the user to that toggle instead of showing a dead error;
 *   - if you publish on Google Play, ship this whole path off for Play builds and use
 *     Play In-App Updates instead - sideloading an APK from your own host violates Play policy.
 *
 * Integrity: HTTPS + sha256 + a server HMAC. The real trust anchor is the APK signature -
 * a v2/v3-signed APK cannot be replaced by a tampered one, and Android refuses to update an app
 * whose signature differs. That is why you must keep one release key forever.
 */
class UpdateManager(private val context: Context, private val baseUrl: String = "https://ainetmee.ir/v") {

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class Available(val info: UpdateInfo) : State()
        data class Downloading(val percent: Int, val bytes: Long) : State()
        object Verifying : State()
        data class ReadyToInstall(val info: UpdateInfo, val file: File) : State()
        data class BlockedNeedPermission(val info: UpdateInfo, val file: File) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun checkAsync() = scope.launch { checkNow() }

    suspend fun checkNow(): UpdateInfo? = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        runCatching {
            val url = "$baseUrl/?action=version&vc=${BuildConfig.VERSION_CODE}" +
                if (BuildConfig.MEELANO_FEED_KEY.isNotEmpty()) "&key=${BuildConfig.MEELANO_FEED_KEY}" else ""
            val resp = http.newCall(Request.Builder().url(url).header("Accept-Encoding", "gzip").build()).execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    _state.value = State.Idle
                    return@use null
                }
                val body = r.body?.string() ?: return@use null
                val info = parse(body)
                when {
                    info == null -> _state.value = State.Idle
                    !info.signatureOk -> {
                        _state.value = State.Failed("bad_signature")   // host or CDN tampered with
                    }
                    !info.updateAvailable -> _state.value = State.Idle
                    else -> _state.value = State.Available(info)
                }
                info
            }
        }.getOrElse {
            _state.value = State.Idle      // offline is normal: stay silent, retry on next launch
            null
        }
    }

    private fun parse(body: String): UpdateInfo? = runCatching {
        val o = JSONObject(body)
        if (o.has("error")) return null
        val published = o.optInt("versionCode")
        val sig = o.optString("sig")
        val requireHttps = o.optJSONObject("policy")?.optBoolean("requireHttps", true) ?: true
        val url = o.optString("apkUrl")
        val mandatoryBelow = o.optInt("mandatoryBelow")
        val canonical = canonical(
            versionCode = published,
            versionName = o.optString("versionName"),
            url = url,
            sha256 = o.optString("sha256"),
            sizeBytes = o.optLong("sizeBytes"),
            mandatory = o.optBoolean("mandatory"),
            mandatoryBelow = mandatoryBelow,
            channel = o.optString("channel", "stable"),
        )
        // never trust a signed blob whose signature covers something else than what we will use
        if (sig.isNotBlank() && canonical != o.optString("sigInput")) return null
        UpdateInfo(
            versionCode = published,
            versionName = o.optString("versionName"),
            url = url,
            sha256 = o.optString("sha256"),
            sizeBytes = o.optLong("sizeBytes"),
            changelog = o.optString("changelogFa"),
            // server decides (it knows both versionCode and mandatoryBelow); client only obeys
            mandatory = o.optBoolean("mandatory") ||
                (o.optInt("mandatoryBelow") > 0 && BuildConfig.VERSION_CODE < o.optInt("mandatoryBelow")),
            updateAvailable = o.optBoolean("updateAvailable", published > BuildConfig.VERSION_CODE),
            signatureOk = (sig.isBlank() || Verify.hmac(canonical, BuildConfig.MEELANO_FEED_SECRET, sig)) &&
                (!requireHttps || url.startsWith("https://")),
            releasedAt = o.optString("releasedAt"),
        )
    }.getOrNull()

    fun downloadAndInstall(info: UpdateInfo) = scope.launch {
        val file = File(context.cacheDir, "update-${info.versionCode}.apk")
        try {
            if (file.length() == info.sizeBytes && info.sha256.isNotBlank() && sha256(file) == info.sha256) {
                _state.value = State.Verifying
            } else {
                _state.value = State.Downloading(0, 0)
                http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("http_${r.code}")
                    val total = if (info.sizeBytes > 0) info.sizeBytes else r.body?.contentLength() ?: -1L
                    file.outputStream().use { out ->
                        val input = r.body!!.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastTick = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val t = System.currentTimeMillis()
                            if (t - lastTick > 250) {     // do not spam Compose per 64 KB chunk
                                lastTick = t
                                _state.value = State.Downloading(
                                    if (total > 0) ((done * 100) / total).toInt() else 0, done
                                )
                            }
                        }
                    }
                }
                _state.value = State.Verifying
                if (info.sha256.isNotBlank() && sha256(file) != info.sha256) {
                    file.delete()
                    _state.value = State.Failed("checksum_mismatch")
                    return@launch
                }
            }
            if (!canInstall()) {
                _state.value = State.BlockedNeedPermission(info, file)
                return@launch
            }
            _state.value = State.ReadyToInstall(info, file)
            launchInstaller(file)
        } catch (t: Throwable) {
            file.delete()
            _state.value = State.Failed(t.message ?: "download_failed")
        }
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Open the system "install this file" sheet. One tap for the user, nothing else. */
    fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
            .onFailure { _state.value = State.Failed("installer_unavailable") }
    }

    /** Only used on Android 8+ when the user has not allowed installing unknown apps yet. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** MUST mirror Version::canonical() on the server, field order included. */
    private fun canonical(
        versionCode: Int, versionName: String, url: String, sha256: String,
        sizeBytes: Long, mandatory: Boolean, mandatoryBelow: Int, channel: String,
    ) = "v$versionCode|$versionName|$url|${'$'}{sha256.lowercase()}|$sizeBytes|" +
        "${'$'}{if (mandatory) 1 else 0}|$mandatoryBelow|$channel"

    private suspend fun sha256(f: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Notification action: "نصب" -> the same flow, without the user opening the app. */
        fun installPendingIntent(context: Context, file: File): PendingIntent {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getActivity(context, 99, i, flags)
        }
    }
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val changelog: String,
    val mandatory: Boolean,
    val updateAvailable: Boolean,
    val signatureOk: Boolean,
    val releasedAt: String,
)

/**
 * The HMAC is defence-in-depth (a stolen/compromised host or a rogue CDN mirror), not the trust
 * anchor. Android's own APK signature check is what actually stops a malicious swap.
 */
object Verify {
    fun hmac(canonicalMessage: String, secret: String, expectedHex: String): Boolean {
        if (secret.isBlank() || expectedHex.isBlank()) return true
        return runCatching {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            val hex = mac.doFinal(canonicalMessage.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(hex.toByteArray(), expectedHex.lowercase().toByteArray())
        }.getOrDefault(false)
    }
}
