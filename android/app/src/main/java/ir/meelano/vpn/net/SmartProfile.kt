package ir.meelano.vpn.net

/**
 * The brain of the «پروفایل هوشمند» sheet: after the free/VIP lists are downloaded, probed and
 * separated, this answers one question per service — "given *this* network regime, *this* tier's
 * best transport and the RTT we just measured, which connection settings give that service the
 * best chance in Iran?"
 *
 * Pure Kotlin on purpose: every rule below is a unit test, and the sheet only renders the verdict.
 * The rules encode what the Irancell/MCI field work taught (docs/ANTI-BLOCK.md):
 *
 *  - fragmentation is a *TLS-only* defence; it costs latency, so CALM must not pay for it;
 *  - mux hides handshakes but head-of-line blocks on lossy links — on slow RTT it is a speed tax;
 *  - small MTU + MSS clamp is what survives carrier NAT; 1280 is the safe floor, 1400 the fast case;
 *  - keepalive must stay under the ~60 s carrier flow timeout or idle tunnels die silently;
 *  - Reality-first ordering is the single strongest transport preference available;
 *  - VIP nodes are usually one well-configured hop (often Reality/ws on 443) and benefit from
 *    aggressive reuse; free pools are heterogeneous, so the safest *common* denominator wins.
 */
object SmartProfile {

    /** What the app should run for one service tier. Every field maps 1:1 onto a real dial. */
    data class Advice(
        /** "auto" | "calm" | "tight" | "blackout" — the regime the app should act on. */
        val regime: String,
        /** true = fragmentation follows the regime; false = forced off. */
        val fragment: Boolean,
        val fragSize: Int,
        val fragCount: Int,
        val fragStrategy: String,
        val fragDelayMs: Int,
        val mux: Boolean,
        val muxConcurrency: Int,
        val mtu: Int,
        val keepAliveSec: Int,
        val realityFirst: Boolean,
        /** Human-readable one-liners (Persian) explaining each choice — the sheet shows these. */
        val reasons: List<String>,
    )

    /**
     * @param tier        "vip" or "free" — the service being tuned for
     * @param transport   Ladder.transportOf(...) key of the tier's best measured node
     * @param medianRttMs median TCP RTT of the tier's usable nodes; <= 0 when unmeasured
     * @param measured    how many nodes actually answered (0 = nothing measured yet)
     */
    fun advise(
        regime: Regime,
        tier: String,
        transport: String,
        medianRttMs: Long,
        measured: Int,
    ): Advice {
        val vip = tier.equals("vip", ignoreCase = true)
        val base = Tune.forRegime(regime)
        val reasons = ArrayList<String>(6)

        // Fragmentation: only meaningful on TLS-shaped transports, never on Reality/plain.
        val fragmentable = transport in setOf("tcp-tls", "ws-tls", "grpc-tls", "xhttp-tls")
        val fragment = when {
            regime == Regime.CALM -> {
                reasons += "در حالت آرام، تکه‌تکه‌سازی فقط تأخیر می‌خرد؛ خاموش می‌ماند"
                false
            }
            !fragmentable -> {
                reasons += "این ترابری (Reality/بدون TLS) ساختار hello برای شکستن ندارد؛ تکه‌تکه‌سازی بی‌اثر است"
                false
            }
            vip -> {
                reasons += "سرور VIP معمولاً یک پرش تمیز روی ۴۴۳ است؛ تکه‌تکه‌سازی سبک (۲۰۰ بایت) کافی است"
                true
            }
            else -> {
                reasons += "استخر رایگان ناهمگن است؛ تکه‌تکه‌سازی کاملِ حالت سنگین امضای ClientHello را می‌شکند"
                true
            }
        }

        // Mux: handshake hiding vs head-of-line blocking. Slow links lose more than they hide.
        val mux = when {
            regime == Regime.BLACKOUT -> {
                reasons += "در قطعی، دست‌دادن‌های کمتر از سرعت مهم‌تر است؛ Mux روی می‌ماند"
                true
            }
            transport == "reality" -> {
                reasons += "زیر Reality، Mux با anti-replay تداخل می‌کند؛ خاموش است"
                false
            }
            transport == "grpc-tls" -> {
                reasons += "gRPC خودش چند stream را روی یک اتصال نگه می‌دارد؛ Mux اضافی است"
                false
            }
            medianRttMs in 1..399 -> {
                reasons += "لینک سریع است؛ Mux دست‌دادن‌ها را کم می‌کند بدون هزینه‌ی محسوس"
                true
            }
            medianRttMs >= 400 -> {
                reasons += "تأخیر بالا: Mux سرصفحه‌ی مشترک روی لینک پرنویز کندتر است؛ خاموش می‌ماند"
                false
            }
            else -> {
                reasons += "بدون اندازه‌گیری، رفتار پیش‌فرضِ حالت انتخاب می‌شود"
                base.mux
            }
        }

        // MTU/MSS: carrier NAT eats big segments; the fast measured path can afford 1400.
        val mtu = when {
            regime == Regime.BLACKOUT -> 1280
            medianRttMs in 1..249 && vip -> 1400
            else -> 1280
        }
        if (mtu == 1400) reasons += "مسیر VIP سریع اندازه‌گیری شد؛ MTU ۱۴۰۰ سقفِ بدون تکه‌خوردگی است"
        else reasons += "MTU ۱۲۸۰ کفِ امن عبور از NAT اپراتورها است"

        // Keepalive: always inside the carrier's ~60 s idle window.
        val keepAlive = when {
            regime == Regime.BLACKOUT -> 10
            medianRttMs >= 400 -> 12
            else -> 15
        }
        reasons += "keepalive هر ${keepAlive}s زیر مهلتِ جریان بی‌کارِ اپراتور می‌ماند"

        // Concurrency: 8 is the mobile sweet spot; blackout pushes streams onto one connection.
        val concurrency = if (regime == Regime.BLACKOUT) 16 else 8

        val realityFirst = transport == "reality" || vip
        if (realityFirst) reasons += "اولویت Reality: مقاوم‌ترین ترابریِ در دسترس، اول از همه دیال می‌شود"

        if (measured == 0) {
            reasons += "هنوز چیزی اندازه‌گیری نشده؛ مقادیر امنِ پیش‌فرض اعمال شد"
        }

        return Advice(
            regime = when (regime) {
                Regime.CALM -> "calm"
                Regime.TIGHT -> "tight"
                Regime.BLACKOUT -> "blackout"
            },
            fragment = fragment,
            fragSize = if (fragment) base.fragSize else 0,
            fragCount = if (fragment) base.fragCount else 1,
            fragStrategy = if (fragment) base.fragStrategy else "",
            fragDelayMs = if (fragment) base.fragDelayMs else 0,
            mux = mux,
            muxConcurrency = concurrency,
            mtu = mtu,
            keepAliveSec = keepAlive,
            realityFirst = realityFirst,
            reasons = reasons,
        )
    }

    /** Which AppSettings dials an [Advice] wants written, as a tiny map the sheet can apply. */
    fun targets(a: Advice): Map<String, Any> = linkedMapOf(
        "regime" to a.regime,
        "fragment_auto" to a.fragment,
        "mux_enabled" to a.mux,
        "reality_first" to a.realityFirst,
        "mtu" to a.mtu,
    )
}
