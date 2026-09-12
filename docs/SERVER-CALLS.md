# همهٔ فراخوانی‌های سرور (و وصل‌کردنشان به هاست خودت)

این تنها مرجعِ «اپ چطور با سرور حرف می‌زند» است: هر درخواست، هر پارامتر، هر هدر، هر عددِ زمانی، و
اینکه اگر آن تماس خراب شود کاربر دقیقاً چه می‌بیند. بقیهٔ اسناد *چرایی* را توضیح می‌دهند
([`API-CONTRACT.md`](API-CONTRACT.md) برای قراردادِ بدنه، [`AI.md`](AI.md) برای لایهٔ پیشنهاد،
[`ANTI-BLOCK.md`](ANTI-BLOCK.md) برای ریزتنظیم‌ها، [`ANDROID-BUILD.md`](ANDROID-BUILD.md) برای بیلد)؛
این سند **سطحِ قرارداد** است و برای کسی نوشته شده که می‌خواهد فید را روی هاستِ خودش ببرد.

> چیزی که در این سند «تأییدشده» است از روی کد نوشته شده، نه از روی حدس: هر سطرِ کلیدی یک
> `path:line` دارد. اگر کد عوض شد، همین‌جا را هم عوض کن — اسنادِ کدر‌نشده در این پروژه بی‌ارزش‌اند.

---

## ۰) سه خطِ اول که اگر غلط باشد هیچ‌چیز کار نمی‌کند

| | |
|---|---|
| آدرس پایه | `BuildConfig.FEED_BASE_URL` — در بیلد با `-PMEELANO_FEED_BASE=https://دامنه‌ت/v` (پیش‌فرض `https://ainetmee.ir/v`). **بدونِ `/` انتهایی**، چون همه‌ی مسیرها `/?action=…` می‌چسبند. |
| همه‌ی تماس‌ها | یک endpoint: `<base>/?action=<نام>` — و برای هاستِ بیِ `mod_rewrite`، فایل‌های تختِ `<base>/<action>.php` (`vip.php`، `free.php`، `version.php`، `health.php`، `status.php`) که فقط `$_GET['action']` را ست می‌کنند و `index.php` را require می‌کنند. |
| رمزها | سه‌تا، هرکدام یک جا: `secret` (امضای HMAC، هر دو طرف)، `access.feedKey` (کلِ اپ → هدر `X-Feed-Key`)، `access.toolKey` (کلِ ابزار/پنل؛ **هرگز در اپ نیست**). |

بعد از استقرار این دو را بزن و باید JSON سالم ببینی:

```bash
curl -s "https://دامنه‌ت/v/?action=health"
curl -s "https://دامنه‌ت/v/?action=selftest&key=<toolKey>" | head -c 600
```

---

## ۱) نقشهٔ کاملِ تماس‌ها (اپ ← سرور)

| کجا در اپ | درخواست | پارامترها / بدنه | هدرها | مهلت | اگر بخورد |
|---|---|---|---|---|---|
| `data/ServerFeedRepository.kt:98` `refresh()` | `GET ?action=vip` و `?action=free` | — | `Accept-Encoding: gzip`، `X-Feed-Key`، `If-None-Match` | ۴s connect / ۶s read | لیستِ کش‌شده روی دیسک می‌ماند؛ هیچ دیالوگی باز نمی‌شود |
| `ServerFeedRepository.kt:266` `flush()` | `POST ?action=feedback` | JSON: `reports[]`, `block`, `regime`, `app` | `X-Feed-Key` | همان کلاینت | بی‌صدا رها می‌شود (`feedback dropped (offline)`) |
| `ui/AdviceCard.kt:70` `fetchAdvice()` | `GET ?action=advice` | `err`, `proto`, `tier`, `regime` (urlencode) | `X-Feed-Key` | ۲٫۵s | کارت نمایش داده نمی‌شود — وینِ «اتصال برقرار نشد» سرِ جایش است |
| `update/UpdateManager.kt:72` `checkNow()` | `GET ?action=version` | `vc=<versionCode>`، و در صورت ست‌بودن `key=<feedKey>` | `Accept-Encoding: gzip` | ۶s | `State.Idle`؛ هیچ اخطاری به کاربر داده نمی‌شود |
| `UpdateManager` دانلود APK | `GET <apkUrl>` | — | — | ۶۰s read | `Failed("checksum")` یا `Failed("download")`، نصب صدا نمی‌کند |

**هیچ تماس دیگری از اپ بیرون نمی‌رود.** پروبِ نود (`ServerFeedRepository.kt:305`) یک `Socket.connect()`
مستقیم به `host:port` نود است، نه HTTP؛ و صفحهٔ وضعیت (`status.php`) برای آدم‌هاست، اپ آن را صدا نمی‌زند.

### ترتیبِ واقعیتِ شروعِ سرد (چیزی که بیشتر باگ‌های «لیست خالی» اینجاست)
`ServerFeedRepository.kt:80-85` — اول `loadCached()` از `files/feed/{vip,free}.json`، **بعد** `refresh()`.
یعنی: یک بار که فید سالم گرفته باشد، اپ حتی آفلاین هم لیست را نشان می‌دهد؛ اگر ترتیب برعکس شود،
هر cold start یک درخواستِ شبکه‌ای است و کاربرِ با اینترنتِ بد، صفحهٔ خالی می‌بیند.

`awaitNode(id)` (`:89`) هم همین منطق را دارد: از حافظه، بعد دیسک، بعد شبکه (۶ تلاش با ۴۰۰ms فاصله) —
چون سرویس VPN ممکن است از نوتیفیکیشن قبل از پرشدنِ ریپو بیدار شود.

---

## ۲) پاسخِ فید: بدنه‌ای که اپ می‌خواند

پوشش (`backend/v/lib/Builder.php` → `Util::respond`):

```jsonc
{
  "schema": 2, "kind": "vip", "brand": "M•A VPN",
  "generatedAt": 1757000000, "ttl": 600, "count": 18,
  "namePolicy": "masked:brand+cc",
  "etag": "a1b2c3d4e5f60718",
  "servers": [ { "id": "…", "name": "Vip M•A | DE", "cc": "de", "proto": "vless",
                 "host": "…", "port": 443, "tls": "reality", "network": "xhttp",
                 "quality": { "grade": "A", "latencyMs": 210, "reliability": 0.86,
                              "samples": 42, "alive": true, "gateOk": true },
                 "checkedAt": 1757000000, "tune": { "fragmentSize": 200 } } ],
  "meta": { "fleet": {…}, "tuned": true, "tunedBy": "heuristic", "regime": "tight",
            "candidates": 412, "probed": 96, "gated": 12, "buildMs": 840 },
  "cache": { "state": "fresh" }
}
```

قاعده‌های که این قرارداد را زنده نگه می‌دارد:

- **اپ تنبل است** (`data/FeedJson.kt`): فیلدِ ناشناس نادیده گرفته می‌شود، پس سرور می‌تواند رشد کند بی‌آنکه
  نسخه‌ی اپ بالا برود. برعکسش ممنوع: حذفِ یک فیلدِ موجود = شکستنِ همهٔ نسخه‌های نصب‌شده.
- `quality.reliability = (ok+1)/(ok+fail+2)` — Laplace، پس یک نمونهٔ موفق، نود را «مطمئن» نمی‌کند.
- `tune` **پیشنهاد** است، نه فرمان: اولویت `پیش‌فرضِ regime ← tuneِ فید ← سوییچِ کاربر` فقط در
  `data/AppSettings.kt::tuneFor()` حل می‌شود. اگر جای دیگری این را بازسازی کنی، دو منبع حقیقت داری.
- `namePolicy: "masked:brand+cc"` یعنی `name` همان چیزی است که *مجاز* به نمایش است. نامِ upstream هیچ‌وقت
  در بدنه نیست (`Builder` ماسک می‌کند، `vip.maskNames = true`) — این یک انتخابِ طراحی نیست، یک الزاست.

### ETag و کش — چرا بعضی Pollها صفر بایت‌اند
`Util::respond` یک `ETag` از sha1ِ بدنه می‌دهد و `Cache-Control: public, max-age=<cache.httpMaxAge=300>`؛
اپ در `If-None-Match` همان را برمی‌گرداند و سرور `304` می‌دهد (`ServerFeedRepository.kt:109`) که یعنی
«نه بایت، نه پارس». `OkHttp` هم خودش یک کشِ ۶ مگابایتی در `cacheDir/http-feed` دارد (`:63`). اگر لاگکت
پر از ۲۰۰های بزرگ است: یا `httpMaxAge` را به ۰ برده‌ای، یا `generatedAt` با هر request عوض می‌شود
(باید با هر *build* عوض شود، نه با هر پاسخ).

---

## ۳) بازخورد (`?action=feedback`) — تنها چیزی که اپ «می‌سازد» و سرور یاد می‌گیرد

`ServerFeedRepository.kt:266-283`:

```jsonc
{
  "reports": [ { "sid": "n7", "ok": true, "latencyMs": 380, "err": "" } ],
  "block":   { "dnsPoisoned": false, "tcpFail": 0.12, "tlsFail": 0.0, "rtt": 210, "probes": 6, "at": 1757000000 },
  "regime":  "tight",
  "app":     "2.3.0"
}
```

- دسته‌ای کار می‌کند: صفِ داخلی، تا ۱۰ گزارش، ۴ ثانیه صبر، یک POST (`:255-262`).
- `sid` شناسهٔ **نود در فید** است، آدرس نیست؛ `block` همان `BlockReport` است که در
  `net/Regime.kt::toJson()` تعریف شده و تنها راهِ سرور برای دیدنِ «از داخلِ کشور» است.
- محدودیتِ سمتِ سرور: `free.feedback.maxPerIpPerMin = 12` (`index.php:100`)؛ خطا نمی‌دهد که اپ گیر کند،
  فقط می‌شمارد و رد می‌کند.
- اپ **هیچ شناسهٔ دستگاهی نمی‌فرستد** و هیچ IP دیگری جز IPِ خودِ درخواست در بدنه نیست.

سرور با این‌ها چه می‌کند: `Score.php` (ledger + گیت + بن ۶ ساعته پس از ۳ شکست)، `Score` با وزن
`feedback.weight = 0.55` نسبت به پروبِ فرانکفورت، و `regime` رأیِ ناوگان می‌شود (حداقل
`tune.minVotesForRegime = 3` رأی، و بعد از `tune.evidenceMaxAge = 3600s` سکوت فراموش می‌شود).

---

## ۴) مشاوره (`?action=advice`) — «چرا وصل نشد» که سرور می‌نویسد

```
GET /v/?action=advice&err=tls_timeout&proto=vless&tier=free&regime=tight
→ { "advice": { "title": "…", "body": "…", "action": "fragment_on|change_regime|switch_node|retry",
                "canned": true|false } }
```

- همیشه ۲۰۰ برمی‌گرداند؛ اگر مدلِ زبانی مرده باشد، جدولِ کانِ `lib/Ai.php` فارسی را می‌سازد و
  `canned: true` است — یعنی «مرگِ API» هرگز نباید به «بی‌کمک‌ماندنِ کاربر» تبدیل شود.
- اپ به‌ازای هر امضای خطا یک‌بار می‌پرسد (`adviceCache` در `AdviceCard.kt:71`)؛ پنج retry = پنج درخواست نیست.
- `action` را اپ به تنظیمِ واقعی وصل می‌کند (`AppSettings.fragmentAuto`, `AppSettings.regime`, بازکردنِ
  لیست نود)؛ اکشنِ ناشناس = دکمه «تلاش دوباره». این قاعده را نشکن: enumِ ناش نباید صفحه را ببندد.
- سقفِ سرور: `rateLimit('advice', 30)` (`index.php:139`).

---

## ۵) به‌روزرسانی خودکار (`?action=version`) — تنها مسیرِ نصبِ فایل جدید

پاسخ `backend/v/lib/Version.php::serve()`:

```jsonc
{ "versionCode": 203000, "versionName": "2.3.0", "channel": "stable", "releasedAt": 1757000000,
  "apkUrl": "https://دامنه‌ت/v/apk/meelano-2.3.0-203000.apk",
  "sizeBytes": 12933866, "sha256": "…۶۴ رقم…",
  "sig": "…HMAC-sha256…", "sigInput": "v203000|2.3.0|https://…|…|12933866|",
  "policy": { "autoDownloadOverWifi": true, "checkIntervalHours": 12, "requireHttps": true },
  "current": { "versionCode": 203000, "versionName": "2.3.0", "channel": "stable", "releasedAt": … },
  "mandatoryBelow": 0, "mandatory": false, "updateAvailable": true }
```

زنجیرهٔ اعتماد، به همان ترتیبی که `UpdateManager.kt` اجرا می‌کند:

1. `sigInput` **همان رشته‌ای است که امضا شده** — اپ از فیلدهای پارس‌شده بازسازی‌اش می‌کند
   (`canonical()`, `UpdateManager.kt:204-207`) و اگر نخواند: `bad_signature`. این جلوی «امضای سالم روی
   چیزِ دیگری» را می‌گیرد، نه فقط خرابیِ ترافیک را.
2. HMAC با `BuildConfig.MEELANO_FEED_SECRET` ← که باید با `secret` در `config.php` یکی باشد.
3. دانلود APK → `sha256(فایل) == sha256` وگرنه هیچ‌وقت به نصب‌ده داده نمی‌شود (`:166`).
4. `FileProvider` + `Intent.ACTION_VIEW` = **یک تپ** برای نصب (`:189`). ساکوتِ کامل ممکن نیست:
   اندروید برای اپِ غیرِ Device Owner نصبِ بی‌صدا را نمی‌پذیرد (و در Google Play هم ممنوع است).
5. لنگرِ واقعیِ اعتماد **امضای خودِ APK** است، نه این HMAC — پس کلیدِ release را ابدی نگه دار.

قاعده‌های عملی که اگر نشکنی دردسر نداری:

- نامِ فایل روی هاست **باید** `meelano-<versionName>-<versionCode>.apk` باشد؛ رگکسش:
  `^(.+)-(\d+\.\d+(?:\.\d+)?)-(\d+)\.apk$` (`Version.php:96`). پس `meelano-2.3.0-203000-debug.apk`
  **ایندکس نمی‌شود** و `?action=version` می‌گوید `no_apk_published`. (CI آن را با suffix می‌سازد؛
  هنگام آپلود روی هاست نام را تمیز کن.)
- یک `….apk.sha256` کنارش بگذار (فقط ۶۴ رقم هگز، و mtime‌اش باید از APK تازه‌تر باشد — `:163`)؛
  با آن، `sha256` در JSON حتی بدونِ محاسبهٔ سمت‌سرور درست است.
- `update.apkDir` بیرون `data/` است (`backend/v/apk`) چون `data/` روی HTTP بسته است؛
  `update.publicBase` را ست کن تا `apkUrl` مطلق و HTTPS باشد.
- `requireHttpsForApk = true` را خاموش نکن: HTTP یعنی یک MITM می‌تواند APK را عوض کند.
- `SELF_UPDATE` در بیلد با `CHANNEL` گیت می‌شود (`app/build.gradle.kts`): بیلدِ `debug`
  `CHANNEL=debug` و خودِ بررسیِ آپدیت خاموش. برای تستِ مسیرِ آپدیت، `-PMEELANO_SELF_UPDATE=true` بده.
- اگر کاربر «به‌روزرسانی نمی‌آید» گفت: `versionCode` را بالا نبرده‌ای. `versionName` برای نمایش است،
  مقایسه با عددِ `vc` انجام می‌شود و `updateAvailable = vc < published`.

---

## ۶) صفحهٔ وضعیتِ عمومی (`?action=status`)

اپ صدا نمی‌زند؛ آدم‌ها می‌بینند. `lib/Status.php::summary()` کشِ ۱۲۰ ثانیه‌ای با کلید `status`،
`rateLimit('status', 90, 60)`، و `?r=1` برای ردکردنِ کش. دو قالب از **یک** منبع:
`?action=status` (JSON) و `?action=status&html` / `<base>/status.php` (HTML با CSS درون‌خطی، RTL،
مطیعِ `prefers-color-scheme`). صفحه فقط از همان بارِ عمومیِ فید ساخته می‌شود: شمارشِ هر فید،
هیستوگرامِ رتبه، میانهٔ تأخیر، شش کشورِ برتر، پروتکل‌ها، regime، `tunedBy`، سنِ کش‌ها.
**هرگز**: نامِ upstream، هاست/پورت، شناسهٔ نود خام، نسخهٔ PHP، یا دادهٔ هر کاربر — و این را تست‌ها
میسازند (رج: `backend/v/tests/run.php`، بلوکِ Status: regex آی‌پیِ IPv4، نبودِ کلیدهای ممنوعه، و
`medianLatencyMs: null` وقتی داده نیست).

اسنپ‌شاتِ ایستا برای جایی که PHP نداری: `php tools/status-snapshot.php > design/preview/status.html`.

---

## ۷) استقرار روی هاست اشتراکی — قدم‌به‌قدم (cPanel، PHP، بدون SSH لازم)

۱. **فایل‌ها** — کلِ `backend/v/` را داخل `public_html/v/` بریز (نه `backend/v` را با نامِ دیگر؛ مسیرِ
   `data/` و `apk/` به `__DIR__` قفل است). `config.local.php` را نساز نه ویرایش کن: `config.php`
   با هر آپدیت بازنویسی می‌شود، و `:201` آخرِ فایل `config.local.php` را deep-merge می‌کند.

۲. **دسترسی نوشتن** — `data/` باید برای PHP نوشتنی باشد (معمولاً ۷۵۵ کافی است؛ اگر نشد ۷۷۵ و بعد
   `data/.htaccess` را چک کن که باشد — همان چیزی است که کش، ledger، و لیستِ VIP خام را از وب می‌بندد).
   `?action=health` با `"writable": false` همین را می‌گوید.

۳. **رمزها** — `public_html/v/config.local.php`:

   ```php
   <?php
   return [
       'secret'  => bin2hex(random_bytes(32)),          // 64 هگز؛ همین مقدار در BuildConfig.MEELANO_FEED_SECRET
       'access'  => [
           'feedKey'       => 'یک‌رشته‌ی‌تصادفی‌۳۲تایی',   // اپ با هدر X-Feed-Key می‌فرستد
           'toolKey'       => 'کلیدِ‌ابزارِ‌دیگر',          // refresh|selftest|stats؛ در اپ نیست
           'adminPassHash' => '',                        // از /v/admin/hash.php?pass=… بگیر و پیست کن
           'adminSessionTtl' => 7200,
       ],
       'update'  => [ 'publicBase' => 'https://دامنه‌ت/v/apk', 'channel' => 'stable' ],
       'vip'     => [ 'sourceFile' => 'vip_raw.txt' ],   // لیست VIP: از پنل پیست کن
   ];
   ```
   (هر کلیدی که ننویسی، مقدارِ `config.php` می‌ماند؛ `secret` خالی = نسخهٔ امضانشده = اپ نصب‌شده با
   `MEELANO_FEED_SECRET=CHANGE_ME_64_HEX` فقط وقتی کار می‌کند که همان مقدارِ پیش‌فرض باشد. یکی‌شان را
   عوض کنی، باید هر دو را عوض کنی.)

۴. **فایلِ VIP** — `public_html/v/data/vip_raw.txt` (الگو: `data/vip_raw.example.txt`) یا از پنل پیست کن.
   هر فرمتی که `Parser.php` تحمل می‌کند: sublink، `ss://`، `vless://`، JSON، HTML، base64.

۵. **APK** — `public_html/v/apk/meelano-<name>-<code>.apk` + `….sha256`. سپس:

   ```bash
   curl -s "https://دامنه‌ت/v/?action=version&vc=1" | head -c 400   # باید versionName/apkUrl/sha256/sig باشد
   ```

۶. **خودآزمون** — `…/v/?action=selftest&key=<toolKey>&html` یک صفحه می‌دهد که همین را می‌سنجد: نوشتنِ
   `data/`، کشِ gzip، ETag، خروجیِ پروب (اگر outbound TCP بسته باشد `autoDisableOnBlocked` رتبه‌بندی را
   به بازخوردِ کاربران می‌سپارد — این فالتِ سالم است، نه خرابی)، موجودبودنِ APK، و اینکه `index.php`
   بدونِ rewrite هم جواب می‌دهد. `php backend/v/tests/run.php` را هم می‌توانی روی هاست اجرا کنی
   (شبکه لازم ندارد؛ fixture می‌خواند).

۷. **اپ را به همین هاست وصل کن**:

   ```bash
   ./gradlew assembleDebug \
     -PMEELANO_FEED_BASE=https://دامنه‌ت/v \
     -PMEELANO_FEED_KEY=<feedKey> \
     -PMEELANO_FEED_SECRET=<secret>
   ```
   یا در `android/local.properties` (همان‌جا می‌ماند و commit نمی‌شود — رج: `docs/ANDROID-BUILD.md`).
   در CI، ورودی‌های `workflow_dispatch` همان‌هاست: `feed_base`، `feed_key`، و سِرّ در
   `secrets.MEELANO_FEED_SECRET`.

۸. **کرون (اختیاری است، نه لازم)** — فید *when asked* ساخته می‌شود و `serveStaleWhileRebuild = true`
   یعنی پاسخِ کهنه فوراً داده می‌شود و بازسازی در پس‌زمینه run می‌شود. اگر می‌خواهی همیشه تازه باشد:

   ```
   */10 * * * * curl -s "https://دامنه‌ت/v/?action=refresh&key=<toolKey>" >/dev/null 2>&1
   ```
   (هر `vip`/`free` build حدود ۰٫۸–۱٫۵ ثانیه CPU؛ `maxCandidates = 900` و `budgetMs = 18000` سقفِ
   محافظِ همان CPU‌اند. روی هاست‌های ضعیف، `free.probe.enabled = false` همه‌چیز را سالم و سبک می‌کند.)

---

## ۸) رمزها هر دو طرفِ خودشان را می‌خواهند

| سمت سرور (`config.local.php`) | سمت اپ | چه کسی می‌فرستد | اگر ست نشود |
|---|---|---|---|
| `secret` | `BuildConfig.MEELANO_FEED_SECRET` | امضای `version.json` | اپ `bad_signature` می‌دهد و نصب نمی‌کند |
| `access.feedKey` | `BuildConfig.MEELANO_FEED_KEY` → هدر `X-Feed-Key` (و `&key=` در `?action=version`) | همه‌ی GETها + POSTِ feedback | `403 feed_key_required` برای vip/free |
| `access.toolKey` | — (در اپ نیست) | تو، از curl/پنل | `refresh|selftest|stats` با `503 tool_key_not_configured` |
| `access.adminPassHash` | — | پنل `/v/admin/` | پنل خاموش است |

---

## ۹) پیام‌های سرور و معنی‌شان

| پاسخ | چرا | چکار کنی |
|---|---|---|
| `403 feed_key_required` (`index.php:64`) | هدر `X-Feed-Key` نبوده یا فرق دارد | `-PMEELANO_FEED_KEY` را دوباره بیلد کن؛ اپِ قدیمی کلیدِ قدیمی دارد |
| `429 rate_limited` (`:67`) | بیشتر از `limits.reqPerMinPerIp = 60` از یک IP | طبیعی است اگر چند دستگاه پشت یک NAT‌اند؛ اپ کش دارد و دوباره تلاش نمی‌کند |
| `503 tool_key_not_configured` | `access.toolKey` خالی | برای ابزارها ستش کن |
| `401 bad_tool_key` | `?key=` غلط | دقیقاً همان رشته، با `hash_equals` |
| `payload rejected` (لاگکت: `FeedRepo`) | JSON ناپارس، `schema` پشتیبانی‌نشده، یا `servers` خالی | `?action=vip` را در مرورگر باز کن؛ `count: 0` یعنی لیستِ VIP خام خالی است |
| `list.php`/`?action=version` → `no_apk_published` | نامِ فایل APK با رگکس نمی‌خواند | نام را به `meelano-<name>-<code>.apk` برگردان |
| لیست پر است اما همه `D` | پروبِ سرور از خارج ایران می‌کند و بازخوردِ کاربر هنوز نیست | طبیعی است؛ `feedback.weight = 0.55` به‌مرتبته نظرِ کاربران داخل کشور را غالب می‌کند |
| سرعتِ اولِ بازکردن اپ پایین | `refresh` در cold start روی شبکه رفته | طبیعی است وقتی کشِ دیسک خالی است؛ از بار دوم `loadCached()` اول می‌آید |

---

## ۱۰) چه چیزی این قرارداد را *آزمون* می‌کند

- `php backend/v/tests/run.php` — **۸۹ تست**، بدون شبکه (fixture + stub)، روی PHP 7.4 و 8.3 در CI
  (`.github/workflows/checks.yml`؛ همان‌جا `lint` هم می‌خورد). بلوک‌ها: Parser، Score/ledger، Builder
  (ماسک‌کردن نام‌ها، degrade)، Version (نام‌گذاری APK، canonical، sig)، Status (نبودِ نشتی)، AiTune
  (clamp‌ها، حذفِ idهای ناشناس)، selftest.
- `android/app/src/test/java/…/CoreProfilesTest.kt` — ۱۴ تستِ JVM: شکلِ لهجه‌ی هر موتور، کلیدهای واقعی،
  قوانین flow، block/kill-switch، و اینکه `tcpMss` در Xray نباشد.
- `TrafficTraceTest.kt` (۴) — برشِ پنجره و جابه‌جاییِ نشانگرها؛ `DiagnosticsTest.kt` (۶) — اینکه گزارشِ
  قابل‌ارسال **هیچ** آدرس/IP/کانفیگی ندارد.
- `bash tools/check-theme.sh` — سه آینهٔ رنگ (Kotlin / CSS پروتوتایپ / `colors.xml`) باید ۱۷ توکنِ یکسان
  داشته باشند؛ این هم در `checks.yml` است.

هیچ‌کدام از این‌ها روی *دستگاه* چیزی ثابت نمی‌کند. رفتارِ TUN، مصرفِ باتری، و foreground service فقط روی
گوشی سنجیده می‌شوند — رج: `docs/ANDROID-INTEGRATION.md`.

---

## ۱۱) چه چیزی هرگز از اپ بیرون نمی‌رود / منتشر نمی‌شود

- نام یا برندِ سرویسِ upstream (فقط `name` ماسک‌شده + پرچمِ کشور نمایش داده می‌شود؛ رج:
  `docs/BRAND.md` §۵، `docs/API-CONTRACT.md` و `vip.maskNames`).
- شناسهٔ دستگاه، شمارهٔ تلفن، مخاطبین، محل، و هر شناسهٔ پایدارِ دیگر — اپ هیچ‌کدام را در payload‌ها ندارد.
- کانفیگِ کامل (`host`, `port`, `pbk`, `sid`, `path`, `sni`) در هیچ درخواستِ خروجی نیست؛ تنها جایی که
  رشتهٔ آزاد از اپ بیرون می‌رود `err`ِ مشاوره و `err`ِ بازخورد است، و `Diagnostics.redact()` همین قاعده را
  برای گزارشی که کاربر *دستی* می‌فرستد اعمال می‌کند (رج: `data/Diagnostics.kt`).
- `why`ِ مدل زبانی هیچ‌وقت به اپ نمی‌رسد (`lib/Ai.php`)؛ فقط عدد و بایتِ اقدام.
