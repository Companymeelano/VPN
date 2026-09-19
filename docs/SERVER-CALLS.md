# همهٔ فراخوانی‌های سرور (و وصل‌کردنشان به هاست خودت)

تقسیمِ کار: `API-CONTRACT.md` *شکلِ بدنه* را نگه می‌دارد (اسکیما، فیلدها، قوانینِ سازگاری) و
`BACKEND-DEPLOY.md` *قدم‌های استقرار* را؛ این سند وسط را پر می‌کند — هر تماس، هر پارامتر، هر هدر، هر
عددِ زمانی، و اینکه کدام خطِ Kotlin آن را می‌خواند. اگر یک سندِ تک‌Piece می‌خواهی که از صفر تا نصب روی
هاست بردت، همین است.

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
| `data/ServerFeedRepository.kt:132` `refresh()` | `GET ?action=vip` و `?action=free` | — | `Accept-Encoding: gzip`، `X-Feed-Key`، `If-None-Match` | ۴s connect / ۶s read — و ۳۰s read تا وقتی `files/feed/<kind>.json` نساخته باشد (`:83`) | لیستِ کش‌شده روی دیسک می‌ماند؛ هیچ دیالوگی باز نمی‌شود |
| `ServerFeedRepository.kt:497` `flush()` | `POST ?action=feedback` | JSON: `reports[]`, `block`, `regime`, `app` | `X-Feed-Key` | همان کلاینت | بی‌صدا رها می‌شود (`feedback dropped (offline)`) |
| `ui/AdviceCard.kt:121` `fetchAdvice()` | `GET ?action=advice` | `err`, `proto`, `tier`, `regime` (urlencode) | `X-Feed-Key` | ۲٫۵s | کارت نمایش داده نمی‌شود — وینِ «اتصال برقرار نشد» سرِ جایش است |
| `update/UpdateManager.kt:69` `checkNow()` | `GET ?action=version` | `vc=<versionCode>`، و در صورت ست‌بودن `key=<feedKey>` | `Accept-Encoding: gzip` | ۶s | `State.Idle`؛ هیچ اخطاری به کاربر داده نمی‌شود |
| `UpdateManager` دانلود APK | `GET <apkUrl>` | — | — | ۶۰s read | `Failed("checksum")` یا `Failed("download")`، نصب صدا نمی‌کند |

**هیچ تماس دیگری از اپ بیرون نمی‌رود** — جز یک استثنا که پایین‌تر آمده: پروبِ نود
(`ServerFeedRepository.kt:540`) یک `Socket.connect()` مستقیم به `host:portِ` خودِ نود است، نه HTTP؛
و صفحهٔ وضعیت (`status.php`) برای آدم‌هاست، اپ آن را صدا نمی‌زند.

### ترتیبِ واقعیتِ شروعِ سرد (چیزی که بیشتر باگ‌های «لیست خالی» اینجاست)
`ServerFeedRepository.kt:93-99` — اول `loadCached()` از `files/feed/{vip,free}.json`، **بعد** `refresh()`.
یعنی: یک بار که فید سالم گرفته باشد، اپ حتی آفلاین هم لیست را نشان می‌دهد؛ اگر ترتیب برعکس شود،
هر cold start یک درخواستِ شبکه‌ای است و کاربرِ با اینترنتِ بد، صفحهٔ خالی می‌بیند.

`awaitNode(id)` (`:102`) هم همین منطق را دارد: از حافظه، بعد دیسک، بعد شبکه (۶ تلاش با ۴۰۰ms فاصله) —
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

- **هر بایتِ ورودی از upstream اول `Util::utf8()` رد می‌شود** (`Parser.php:45`): یک remarkِ CP1251 یا یک
  کاراکتر نصف‌شده، هم متن را خراب می‌کند و هم `json_decode` را (که با یک بایتِ بد، کل JSON منبع را رد
  می‌کند). این باگ روی هاستِ واقعی دیده شد، نه در تست.
- **نودِ غیرقابل‌دیال منتشر نمی‌شود** (`Parser.php:544-576`): برای `ss`، cipher باید در لیستِ SIP002 باشد و
  فیلدهای اعتبارنامه بایتِ کنترلی نداشته باشند. پروبِ TCP چنین نودی «زنده، ۴ms، رتبه B» می‌کند (فقط
  بازبودنِ پورت را سنجیده)، ولی هیچ کلاینتی نمی‌تواند با cipherِ `ןz{mt` وصل شود — پس حذف، شفقت است.
- **اپ تنبل است** (`data/FeedJson.kt`): فیلدِ ناشناس نادیده گرفته می‌شود، پس سرور می‌تواند رشد کند بی‌آنکه
  نسخه‌ی اپ بالا برود. برعکسش ممنوع: حذفِ یک فیلدِ موجود = شکستنِ همهٔ نسخه‌های نصب‌شده.
- `quality.reliability = (ok+1)/(ok+fail+2)` — Laplace، پس یک نمونهٔ موفق، نود را «مطمئن» نمی‌کند.
- `tune` **پیشنهاد** است، نه فرمان: اولویت `پیش‌فرضِ regime ← tuneِ فید ← سوییچِ کاربر` فقط در
  `data/AppSettings.kt::tuneFor()` حل می‌شود. اگر جای دیگری این را بازسازی کنی، دو منبع حقیقت داری.
- `namePolicy: "masked:brand+cc"` یعنی `name` همان چیزی است که *مجاز* به نمایش است. نامِ upstream هیچ‌وقت
  در بدنه نیست (`Builder` ماسک می‌کند، `vip.maskNames = true`) — این یک انتخابِ طراحی نیست، یک الزاست.

### ETag و کش — چرا بعضی Pollها صفر بایت‌اند
`Util::respond` یک `ETag` از sha1ِ بدنه می‌دهد و `Cache-Control: public, max-age=<cache.httpMaxAge=300>`؛
اپ در `If-None-Match` همان را برمی‌گرداند و سرور `304` می‌دهد (`ServerFeedRepository.kt:122`) که یعنی
«نه بایت، نه پارس». `OkHttp` هم خودش یک کشِ ۶ مگابایتی در `cacheDir/http-feed` دارد (`:63`). اگر لاگکت
پر از ۲۰۰های بزرگ است: یا `httpMaxAge` را به ۰ برده‌ای، یا `generatedAt` با هر request عوض می‌شود
(باید با هر *build* عوض شود، نه با هر پاسخ).

---

## ۳) بازخورد (`?action=feedback`) — تنها چیزی که اپ «می‌سازد» و سرور یاد می‌گیرد

`ServerFeedRepository.kt:280-297`:

```jsonc
{
  "reports": [ { "sid": "n7", "ok": true, "latencyMs": 380, "err": "" } ],
  "block":   { "dnsPoisoned": false, "tcpFail": 0.12, "tlsFail": 0.0, "rtt": 210, "probes": 6, "at": 1757000000 },
  "regime":  "tight",
  "app":     "2.3.0"
}
```

- دسته‌ای کار می‌کند: صفِ داخلی، تا ۱۰ گزارش، ۴ ثانیه صبر، یک POST (`:269-276`).
- `sid` شناسهٔ **نود در فید** است، آدرس نیست؛ `block` همان `BlockReport` است که در
  `net/Regime.kt::toJson()` تعریف شده و تنها راهِ سرور برای دیدنِ «از داخلِ کشور» است.
- محدودیتِ سمتِ سرور: `free.feedback.maxPerIpPerMin = 12` (`index.php:100`)؛ خطا نمی‌دهد که اپ گیر کند،
  فقط می‌شمارد و رد می‌کند.
- اپ **هیچ شناسهٔ دستگاهی نمی‌فرستد** و هیچ IP دیگری جز IPِ خودِ درخواست در بدنه نیست.

سرور با این‌ها چه می‌کند: `Score.php` (ledger + گیت + بن ۶ ساعته پس از ۳ شکست)، `Score` با وزن
`feedback.weight = 0.55` نسبت به پروبِ فرانکفورت، و `regime` رأیِ ناوگان می‌شود (حداقل
`tune.minVotesForRegime = 3` رأی، و بعد از `tune.evidenceMaxAge = 3600s` سکوت فراموش می‌شود).

**سهمِ شکستِ پروب فقط از نودهای VIP خوانده می‌شود** (`Builder.php:449` + `Score.php:150`، آستانه در
`tune.minLedgerNodesForFailShare = 5`، `config.php:146`). دلیلش یک اشتباهِ واقعی است: ledger همه‌ی
پروب‌ها را با هم جمع می‌کرد، یعنی عمدتاً آینه‌های عمومیِ استخر free که ۹۹٪ مرده‌اند؛ روی هاستِ واقعی
`meta.fleet.tcpFail` به `0.991` رسید و `regimeFrom` کل ناوگان — شش نود VIPِ سالم هم — را blackout کرد.
نودِ عمومیِ مرده، شاهدِ فیلترینگ نیست. رأیِ کلاینت دست‌نخورده است؛ این فقط جلویِ دکمه‌ی خودکشی را می‌گیرد.

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

- نامِ فایلِ روی هاست **باید** `meelano-<versionName>-<versionCode>.apk` باشد؛ رگکسش:
  `^(.+)-(\d+\.\d+(?:\.\d+)?)-(\d+)\.apk$` (`Version.php:96`). پس `…-debug.apk` ایندکس **نمی‌شود**
  و `?action=version` می‌گوید `no_apk_published`. از `v2.3.2-beta.2` هم، assetِ خودِ CI هم با همین نامِ
  تمیز منتشر می‌شود، پس فایلِ دانلودی را می‌توانی بیِ تغییرنام در `public_html/v/apk/` بگذاری
  (پیش‌تر `-debug` می‌گرفت و همین یک بار کل مسیرِ آپدیت را بی‌صدا خاموش کرده بود).
- یک `….apk.sha256` کنارش بگذار (یا خروجیِ `sha256sum`، که هر دو قالب خوانده می‌شود) و **تازه‌تر از خودِ APK** باشد —
  تازگی با `filemtime` سنجیده می‌شود، نه با اندازه؛ این خطا یک‌بار باعث شد هر poll یک هَشِ ۱۳ مگابایتی روی
  هاست اشتراکی زده شود (`Version.php:166-172`).
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

## ۷) استقرار روی هاست — خلاصه؛ جزئیات در سندِ خودش

`docs/BACKEND-DEPLOY.md` تنها مرجعِ استقرار است (آپلود، انتخابِ PHP، پنل، لیستِ VIP، عیب‌یابی). اینجا فقط
همان‌ها که *قراردادِ تماس* را می‌بندند، به ترتیب:

1. کلِ `backend/v/` داخل `public_html/v/` — مسیرهای `data/` و `apk/` به `__DIR__` قفل‌اند، پس پوشه را
   جابه‌جا/تغییرنام نکن.
2. `public_html/v/config.local.php` را بساز (`config.php` با هر آپدیت بازنویسی می‌شود؛ `:201` آخرش
   `config.local.php` را deep-merge می‌کند). حداقلِ کلیدها: `secret`، `access.feedKey`، `access.toolKey`،
   `access.adminPassHash` (از `/v/admin/hash.php?pass=…`)، `update.publicBase`.
3. `data/` نوشتنی باشد — `?action=health` با `"writable": false` همان را می‌گوید، و `data/.htaccess`
   باید بماند (کش، ledger، و لیستِ VIP خام را از وب می‌بندد).
4. لیستِ VIP: `data/vip_raw.txt` یا پیست از پنل (الگو: `data/vip_raw.example.txt`)؛ هر فرمتی که
   `Parser.php` تحمل می‌کند: sublink، `ss://`، `vless://`، JSON، HTML، base64.
   **الگو را نگه ندار**: اگر کپی‌اش کنی، فید شش نودِ جعلی با `alive:false` می‌دهد که در اپ «VIP» به‌نظر
   می‌رسند و فقط ناامید می‌کنند. اگر نودِ خودت را هنوز نداری، فایل را خالی بگذار؛ `meta.notes`
   («vip list is empty») به اپ می‌گوید چرا لیست خالی است، و این صادقانه‌تر از شش نودِ مُرده است.
5. **قبل از اینکه کانفیگِ واقعی VIP را بریزی، `access.feedKey` را ست‌کن** و `-PMEELANO_FEED_KEY=<همان`
   با بیلد بده. `?action=vip` بدون کلید، از بیرون مثلِ یک فایلِ عمومی است: `pbk`، `sid`، `userId`،
   پسوردِ trojan و PSK هدر — یعنی کلیدهای ورود به سرورهای تو. خالی‌بودنِ `feedKey` تنها چیزی است که
   این فید را از «فهرستِ عمومی» جدا می‌کند (CORS هم روی endpoint باز است).
6. APK در `public_html/v/apk/meelano-<versionName>-<versionCode>.apk` + `….sha256` کنارش.
7. دو پاسخ را ببین:

   ```bash
   curl -s "https://دامنه‌ت/v/?action=health"
   curl -s "https://دامنه‌ت/v/?action=version&vc=1" | head -c 400   # versionName/apkUrl/sha256/sig
   curl -s "https://دامنه‌ت/v/?action=selftest&key=<toolKey>&html"  # ۲۲ بررسی؛ ردکردنش یعنی هنوز آماده نیست
   ```

8. اپ را به همین هاست وصل کن (این مرحله **بیلد** است، نه هاست):

   ```bash
   ./gradlew assembleDebug \
     -PMEELANO_FEED_BASE=https://دامنه‌ت/v \
     -PMEELANO_FEED_KEY=<feedKey> -PMEELANO_FEED_SECRET=<secret>
   ```

   یا در `android/local.properties` تا commit نشود (`docs/ANDROID-BUILD.md`). در CI همان‌ها ورودیِ
   `workflow_dispatch`‌اند: `feed_base`، `feed_key`، و سِرّ در `secrets.MEELANO_FEED_SECRET`.
8. کرون اختیاری است: فید *when asked* ساخته می‌شود و `serveStaleWhileRebuild = true` یعنی پاسخِ کهنه
   فوراً می‌آید و بازسازی در پس‌زمینه است. اگر همیشه‌تازه می‌خواهی:
   `*/10 * * * * curl -s "…/v/?action=refresh&key=<toolKey>" >/dev/null`

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
| `json_encode_failed: Malformed UTF-8` روی `?action=free` | یک بایت نامعتبرِ UTF-8 در remark یکی از لیست‌های عمومی؛ از نسخهٔ فعلی `Util::utf8()` جلوی `Parser::parseBlob()` همه‌ی ورودی‌ها را نرمال می‌کند (پیش‌تر کل اندپوینت می‌مرد) | `lib/Util.php` + `lib/Parser.php` را دوباره آپلود کن |
| `payload rejected` (لاگکت: `FeedRepo`) | JSON ناپارس، `schema` پشتیبانی‌نشده، یا `servers` خالی | `?action=vip` را در مرورگر باز کن؛ `count: 0` یعنی لیستِ VIP خام خالی است |
| `?action=version` → `no_apk_published` | نامِ فایل APK با رگکس نمی‌خواند | نام را به `meelano-<name>-<code>.apk` برگردان |
| لیست پر است اما همه `D` | پروبِ سرور از خارج ایران می‌کند و بازخوردِ کاربر هنوز نیست | طبیعی است؛ `feedback.weight = 0.55` به‌مرتبته نظرِ کاربران داخل کشور را غالب می‌کند |
| سرعتِ اولِ بازکردن اپ پایین | `refresh` در cold start روی شبکه رفته | طبیعی است وقتی کشِ دیسک خالی است؛ از بار دوم `loadCached()` اول می‌آید |
| گزارشِ عیب‌یابی می‌گوید «فید: ۰ گره» ولی `?action=free` روی هاست `count` دارد | syncِ اولِ اپ با سرورِ در حالِ build مسابقه می‌داد: `free` همان لحظه fetch/probe/gate می‌کند (بودجه ~۱۸s) و مهلتِ خواندن ۶s بود ⇒ timeout و فهرست خالی، و کشِ دیسکی هم که نبود | از ۲٫۳٫۱ مهلتِ syncِ اول ۳۰s است؛ روی نسخه‌های قدیمی‌تر یک بار «کشیدن به پایین» برای همگام‌سازی کافی است (دومین درخواست از `free.json`ِ هاست سریع پاسخ می‌گیرد) |
| `meta.regime` روی همه‌ی نصب‌ها `blackout` است درحالی‌که شبکه سالم | سابقاً سهمِ شکستِ پروبِ استخر free (آینه‌های عمومیِ مرده) به‌عنوان شاهدِ فیلترینگ خوانده می‌شد | آپدیتِ سرور کافی است؛ حالا فقط پروبِ نودهای VIP در این عدد می‌آید (`tune.minLedgerNodesForFailShare`) |

---

## ۱۰) چه چیزی این قرارداد را *آزمون* می‌کند

- `php backend/v/tests/run.php` — **۹۹ تست**، بدون شبکه (fixture + stub)، روی PHP 7.4 و 8.3 در CI
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

---

## ۱۲) حالت «بدون هاست» — وقتی اپ فهرست را خودش می‌سازد

از `۲٫۴`، کاربر می‌تواند در **تنظیمات ← فهرست سرورها** منبعِ ساختِ فهرست را عوض کند. این یک
«آفلاین‌مود» تزئینی نیست: مسیرِ فهرست واقعاً از داخلِ اپ ساخته می‌شود و هاستِ تو برای *فهرست* اصلاً
صدا زده نمی‌شود. کد: `data/NodeUri.kt` (پارس)، `data/DirectFeed.kt` (چیدنِ فهرست)،
`ServerFeedRepository.refreshDirect()` (اتصال به شبکه و کش).

سه حالت، که در `AppSettings.feedMode` می‌ماند (`FEED_HOST` پیش‌فرض است تا کسی که هاستش سالم است متوجه
چیزی نشود):

| حالت | فهرست رایگان | فهرست وی‌آی‌پی | تماس به `/v/` |
|---|---|---|---|
| **هاست** | `?action=free` — پروب + گیت + رتبه‌بندیِ چندهفته‌ای | `?action=vip` + چسباندنِ کانفیگِ کاربر (merge) | همهٔ §۱ |
| **خودکار** | اول هاست؛ اگر نرسید، آنبرد | مثل رایگان | تا وقتی هاست جواب می‌دهد |
| **بدون هاست** | `DirectFeed` از فهرست‌های عمومی | فقط کانفیگِ خودِ کاربر (+ لینک اشتراکش) | فقط `?action=version` — و آن هم با خاموش‌کردن «بروزرسانی خودکار» می‌رود |

### در حالت «بدون هاست» اپ چه کار می‌کند

۱. پنج فهرستِ *config-grade* را از `raw.githubusercontent.com` می‌خواند (vless / trojan / ss / vmess /
   hysteria2 از `gfpcom/free-proxy-list`)، هر کدام سقف ۱٫۴ مگابایت. فهرست‌های پروکسیِ خام
   (`TheSpeedX`, `monosans`, …) نیامده‌اند: خروجی‌شان `ip:port:user:pass` است و `CoreApi` نمی‌تواند
   HTTP/SOCKS را به‌عنوان outbound دیال کند — گذاشتنشان یعنی پرکردن لیست با ردیف‌هایی که کاربر
   می‌زند و شکست می‌خورند.
۲. `NodeUri.parseBlob` هر خط را می‌خواند: `vless/vmess/trojan/ss/hy2`، خطِ base64ِ اشتراک، و آرایۀ JSON.
   اعتبارسنجی‌های عمدی: cipher سفیدلیست‌شده برای `ss` (هرچه UUID-مانند باشد رد می‌شود)، آدرس
   خصوصی/CGNAT/loopback رد، بدون رمز هیچ تونلی ساخته نمی‌شود.
۳. سقف ۱۶۰ کاندید، دی‌دوپ روی `proto|host:port`، بعد `NetGuard.tcpProbe` روی ۳۶ تای اول
   (۱٫۵ ثانیه، موازی، یک سوکت به هر نود). نمره از همان آستانه‌های سرور: A≤۴۰۰ B≤۱۰۰۰ C≤۲۲۰۰ D.
۴. نام‌ها ماسک می‌شوند (`brand + کشور`) — همان قاعده‌ای که سرور اعمال می‌کند، پس نامِ سرویسِ upstream
   حتی در این حالت هم به صفحهٔ نمی‌رسد.
۵. خروجی در همان `files/feed/free.json` با همان schema نوشته می‌شود؛ یعنی `apply()`، سورتِ
   pinned، شیتِ سرورها، و مسیر اتصال هیچ‌کدام نمی‌دانند فهرست از کجا آمده.

### چیزی که این حالت *ندارد* (و نداشتنش عمدی است)

- **گیت**: سرور قبل از انتشار، از inside به `google.com:443` / `1.1.1.1` / `gstatic` CONNECT می‌زند تا
  پروکسی که فقط «پورتش باز است» را حذف کند. این ۳ راندتریپ به ازای هر نود است؛ روی LTE یعنی باتری و
  یعنی دادهٔ کاربر.
- **دفترِ سابقه و بن**: سه شکست → شش ساعت بن. گوشی فردا روشن نیست.
- **رتبهٔ چندهفته‌ای (`reliability`)**: در حالت آنبرد `samples=1` است، پس «انتخاب خودکار» فقط با
  latency کار می‌کند.
- **بازخورد**: `?action=feedback` در این حالت اصلاً ارسال نمی‌شود (`flush()` اول چک می‌کند) — چون
  مقصدی ندارد.
- **`tune` سرور**: پچِ ترابریِ هر نود از `config.php` می‌آید؛ بدون سرور، رجولاتِ خودِ اپ
  (`AppSettings.regime`) تنها منبع است.

### وی‌آی‌پی بدون هاست

`NodeUri` همان چیزی را می‌خواند که `Parser.php` می‌خواند (vless/vmess/trojan/ss/hy2/tuic، اشتراک
base64، خروجی HTML/تلگرام، JSON، `ip:port:user:pass`)، و کاربر می‌تواند کانفیگ‌هایش را مستقیم در
**تنظیمات ← فهرست سرورها ← پیکربندیِ دستی** بچسباند. محل ذخیره:
`files/feed/vip_local.txt` — داخلِ دایرکتوری خصوصی اپ، بدون بکاپ خودکار، بدون هیچ ارسالِ شبکه‌ای.
این همان جایی است که deep-link هم می‌نشیند: اسکیم‌های `vless/vmess/trojan/ss/hysteria2` که از
برنامهٔ دیگری share می‌شوند (`MainActivity.IMPORT_SCHEMES`)؛ قبلاً
`MainActivity` آن را به `VpnViewModel.requestImport` می‌داد و *هیچ‌کس* نمی‌خواندش.

نکتهٔ مهم: کانفیگ‌های چسبانده‌شده در حالت **هاست** و **خودکار** هم روی لیستِ سرور merge می‌شوند
(`mergeLocalVip()`، همان‌جا پروب و نمره می‌گیرند) — عوض‌کردن منبعِ فهرست نباید چیزی را از لیست کاربر
حذف کند.

### دو چیزی که در این حالت هنوز بیرون می‌رود

1. درخواست‌های HTTPS به `raw.githubusercontent.com` (و اگر لینک اشتراکِ شخصی گذاشته باشی، به آن دامنه).
   یعنی GitHub در لاگش می‌بیند که این IP این فایل‌ها را خواسته — درونِ ایران این را باید بدانی؛ اگر
   برایت مهم است، همان فهرست را در یک هاستِ شخصی/مirror بگذار و در «لینکِ اشتراک» آدرسش را وارد کن.
2. `?action=version` برای بروزرسانی. تنها راه بستنش: خاموش‌کردن «بروزرسانی خودکار» در همان شیت.

هزینهٔ باتری: یک بیلد = ۵ درخواست + حداکثر ۳۶ سوکت، و TTLِ آنبرد ۶ ساعت است
(`DIRECT_TTL_SEC`) — یعنی در استفادهٔ عادی روزی دو بار، آن‌هم وقتی اپ باز است.
