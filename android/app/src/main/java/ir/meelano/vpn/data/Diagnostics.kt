package ir.meelano.vpn.data

import org.json.JSONObject

/**
 * The one-tap self-test report a user pastes into a support chat (Telegram, an SMS, a screenshot of this).
 *
 * Why the renderer takes a flat [Input] instead of reading the app state itself: the interesting part of
 * this feature is not string assembly, it is deciding what must never be in the string. This text leaves
 * the phone by definition - the moment it is useful is the moment the user is talking to a stranger. And
 * `FeedNode` carries `host`, `port`, `pbk`, `sid`: one accidental `"$host:$port"` in a debug line and the
 * private upstream is published to the country. So:
 *  - [Input] has no host/port/config fields *at all* - the type cannot leak what it cannot hold;
 *  - free-form fields (the error line, the probe JSON) go through [redact], and the probe is rebuilt from
 *    a whitelist of numbers rather than filtered, because a whitelist cannot leak through a regex bug;
 *  - [DiagnosticsTest] asserts on the leak shapes (IPv4, `scheme://`, `"host":`) and not on formatting,
 *    so rewording the report never weakens the guarantee.
 *
 * Numbers stay raw (bytes, not "1.2 MB"): the reader of a support report is the person operating the
 * fleet, and `1240300 B` is exactly comparable while "≈1MB" is not. The UI's own `humanRate` is for rates
 * shown to a user under stress; these two audiences are different on purpose.
 */
object Diagnostics {

    data class Input(
        val versionName: String = "",
        val versionCode: Int = 0,
        val channel: String = "",
        val coreLinked: Boolean = false,
        val coreEngine: String = "",
        val regime: String = "",
        val detectedRegime: String = "",
        val mtu: Int = 0,
        val fragmentAuto: Boolean = false,
        val muxEnabled: Boolean = false,
        val killSwitch: Boolean = false,
        val secureDns: Boolean = false,
        /** the *masked* label from the feed ("M•A | DE"), never the upstream name - see namePolicy */
        val nodeLabel: String = "",
        val nodeGrade: String = "",
        val nodeLatencyMs: Long = -1L,
        val connectedSeconds: Int = 0,
        val rxBytes: Long = 0,
        val txBytes: Long = 0,
        val error: String = "",
        val blockReport: String = "",
        val feedCount: Int = 0,
    )

    fun render(i: Input): String {
        val sb = StringBuilder()
        sb.appendLine("M•A VPN — گزارشِ عیب‌یابی")
        line(sb, "نسخه", "${i.versionName} (${i.versionCode})${if (i.channel.isBlank()) "" else " ${i.channel}"}")
        line(
            sb, "هسته",
            if (i.coreLinked) "وصل‌شده (${i.coreEngine.ifBlank { "نامشخص" }})"
            else "در این بیلد وصل نشده — تونل عملاً برقرار نمی‌شود",
        )
        line(sb, "حالتِ ضدفیلتر", i.regime.ifBlank { "—" })
        if (i.detectedRegime.isNotBlank() && i.detectedRegime != i.regime) {
            line(sb, "تشخیصِ خودکار", i.detectedRegime)
        }
        line(sb, "MTU", if (i.mtu > 0) i.mtu.toString() else "—")
        line(sb, "قطعه‌بندی", if (i.fragmentAuto) "خودکار" else "دستی")
        line(sb, "MUX", onOff(i.muxEnabled))
        line(sb, "Kill-switch", onOff(i.killSwitch))
        line(sb, "DNS امن", onOff(i.secureDns))
        line(sb, "فید", "${i.feedCount} گره")
        val node = listOf(i.nodeLabel, i.nodeGrade, if (i.nodeLatencyMs >= 0) "${i.nodeLatencyMs}ms" else "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        line(sb, "گره", node.ifBlank { "—" })
        line(sb, "سابقهٔ اتصال", if (i.connectedSeconds > 0) "${i.connectedSeconds} ثانیه" else "متصل نبوده")
        line(sb, "حجم", "${i.rxBytes} ↓ / ${i.txBytes} ↑ بایت")
        if (i.error.isNotBlank()) line(sb, "آخرین خطا", redact(i.error))
        probe(i.blockReport)?.let { line(sb, "سنجشِ مسیر", it) }
        sb.appendLine()
        sb.append("این گزارش آدرس سرور، IP، یا کانفیگ ندارد؛ همان‌طور که هست می‌تواند فرستاده شود.")
        return sb.toString().trimEnd()
    }

    /**
     * The local probe verdict, rebuilt from numbers only. `block_report` is written straight from
     * `BlockReport.toJson()` (net/Regime.kt), so the key set is known - and if a future field arrives that
     * this whitelist doesn't mention, it is simply not shared. That is the failure mode we want.
     */
    internal fun probe(json: String): String? = runCatching {
        if (json.isBlank()) return@runCatching null
        val o = JSONObject(json)
        val parts = mutableListOf<String>()
        if (o.optBoolean("dnsPoisoned", false)) parts.add("DNS آلوده")
        parts.add("شکست TCP ${pct(o.optDouble("tcpFail", -1.0))}")
        parts.add("شکست TLS ${pct(o.optDouble("tlsFail", -1.0))}")
        val rtt = o.optLong("rtt", -1L)
        if (rtt >= 0) parts.add("RTT میانه ${rtt}ms")
        val n = o.optInt("probes", 0)
        if (n > 0) parts.add("$n پروب")
        parts.joinToString(" · ")
    }.getOrNull()

    private fun pct(v: Double) = if (v < 0) "—" else "${(v * 100).toInt()}٪"

    private fun onOff(b: Boolean) = if (b) "روشن" else "خاموش"

    private fun line(sb: StringBuilder, k: String, v: String) {
        sb.append(k).append(": ").append(v.ifBlank { "—" }).append('\n')
    }

    private val URLISH = Regex("""\b[a-z][a-z0-9+.\-]{1,12}://\S+""", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
    private val DOMAIN_AFTER_COLON = Regex("""\b[\w\-]+\.[a-z]{2,10}:\d{1,5}\b""", RegexOption.IGNORE_CASE)
    private val CONFIG_KEYS = Regex("""["](host|port|server|serverName|pbk|sid|publicKey|shortId|uuid|password|pass|path|sni|remarks|address)["]\s*:\s*["][^"]*["]""")

    /**
     * A bare hostname. Error strings love to quote `sni=…` or `host=…` with no scheme and no port, which
     * is exactly the shape that slips past a URL rule. The price is that a domain in an error message
     * reads as redacted even when it was innocent - paid gladly: this text leaves the phone.
     */
    private val HOSTNAME = Regex("""\b[\w\-]+\.[a-z]{2,10}(\.[a-z]{2,3})?\b""", RegexOption.IGNORE_CASE)

    /** `?pbk=…&sid=…` — the query form a copied config link uses, which `CONFIG_KEYS` (JSON form) misses. */
    private val PARAM = Regex("""[?&;](?:pbk|sid|publicKey|shortId|password|passwd|uuid|id|sni|host|port|security|flow|type)=[^&;\s,]+""")

    /** Replace, never delete-with-no-trace: a redacted report must *say* it redacted, or a user assumes the app lost the error. */
    internal fun redact(s: String): String {
        var out = URLISH.replace(s, "«نشانی حذف شد»")
        // order matters: host:port first (so the port goes with it), then a bare host, then addresses
        out = DOMAIN_AFTER_COLON.replace(out, "«نشانی:پورت حذف شد»")
        out = HOSTNAME.replace(out, "«نشانی حذف شد»")
        out = IPV4.replace(out, "«IP حذف شد»")
        out = PARAM.replace(out, "«پارامترِ کانفیگ حذف شد»")
        out = CONFIG_KEYS.replace(out, "")
        return out.replace(Regex("""\s{2,}"""), " ").trim()
    }
}
