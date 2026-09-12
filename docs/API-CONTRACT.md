# قرارداد API — `/v/`

نسخه‌ی قرارداد: `schema: 2`. اپ باید ناشناس نسبت به فیلدهای اضافی باشد (فیلد جدید = بدون کرش)،
و اگر `schema` بزرگ‌تر از `SCHEMA_MAX` بود، پاسخ را رد کند نه اینکه حدس بزند.

## قاعده‌ی کلی
```
GET /v/?action=vip        HEAD/GET
Header:  Accept-Encoding: gzip
Header:  If-None-Match: "<etag از پاسخ قبلی>"      ← پولینگ تقریباً صفر بایت
Header:  X-Feed-Key: <feedKey>                      ← فقط اگر در کانفیگ ست شده
200 → json   |   304 → بدون بدنه   |   429 → slow_down (retryAfter)
```
همچنین آدرس‌های تخت برای هاست‌هایی بدون mod_rewrite: `/v/vip.php`, `/v/free.php`, `/v/version.php`, `/v/health.php`.

## پاسخ لیست (vip و free یک شکل‌اند)
```jsonc
{
  "schema": 2,
  "kind": "vip",
  "appName": "Meelano VPN",
  "brand": "Vip Meelano",
  "generatedAt": 1789070000,
  "ttl": 600,
  "count": 6,
  "namePolicy": "masked:brand+cc",
  "etag": "9f2c1a7b44de01aa",
  "servers": [
    {
      "id": "1a2b3c4d5e6f",              // ثابت: sha1(proto|host|port|uuid|pubkey) → برای pin/favorite
      "slot": 1,                          // شماره‌ی نمایشی: وقتی همه نام‌ها یکی است، تنها راه تشخیص ردیف‌ها
      "name": "Vip Meelano",              // ← تنها نامی که اپ نمایش می‌دهد
      "title": "Vip Meelano",
      "subtitle": "DE · vless · 443",     // خط دوم ریز؛ قابل‌مقایسه بدون لو رفتن نام فروشنده
      "cc": "DE",                         // کد دو‌حرفی: پرچم را خودت از res/drawable بردار
      "ccFa": "آلمان",
      "flag": "DE",
      "tier": "vip",
      "proto": "vless",
      "host": "37.120.190.11", "port": 443,
      "tls": "reality", "network": "tcp", "sni": "www.microsoft.com",
      "path": "", "hostHeader": "", "alpn": "", "flow": "xtls-rprx-vision",
      "pbk": "SbVK…", "sid": "a1b2c3d4", "fingerprint": "chrome",
      "userId": "2a4e…", "alterId": 0, "password": "", "method": "", "cipher": "",
      "insecure": false,
      "supportsUdp": true,               // false ⇒ اپ UDP را از مسیر تونل رد نکند
      "raw": "vless://…#Vip%20Meelano%2001",  // کانفیگ قابل‌مصرف؛ remark بازنویسی‌شده
      "config": { /* زیرمجموعه‌ی فیلدهای بالا، برای موتورهای JSON‌باز */ },
      "quality": {
        "grade": "A",                     // A|B|C|D
        "latencyMs": 340,                 // null اگر پروب ممکن نبوده
        "reliability": 0.83,              // (ok+1)/(ok+fail+2) با وزن بازخورد کاربر
        "samples": 21,
        "alive": true,
        "gateOk": true                    // گیت «رسیدن به Google/Cloudflare»
      },
      "checkedAt": 1789069990
    }
  ],
  "meta": {
    "buildMs": 4210, "candidates": 640, "probed": 180, "gated": 120,
    "notes": ["…"], "sources": {"gfp-vless": {"ok": true, "nodes": 240}},
    "prefilter": {"insane": 12, "port": 402, "dup": 88, "banned": 3},
    "ledger": {"tracked": 1204, "banned": 7, "updatedAt": 1789069980}
  },
  "cache": { "state": "fresh|stale_served|rebuilt", "age": 12 }
}
```

**حذف نام فروشنده:** `remark` در پاسخ نیست، `raw` بازسازی می‌شود (fragment → `Vip Meelano 01`)،
و برای `vmess://` فیلد `ps` در JSON بیس۶۴ بازنویسی می‌شود. تست *no vendor remark anywhere in the payload*
همین را قفل می‌کند. اپ هم نباید `subtitle` را «نام» جا بزند.

**اگر `servers` خالی بود:** لیست قبلی را نگه‌دار و پیام «در دسترس نیست» نشان بده — هرگز
پروفایل فعال کاربر را پاک نکن. اگر `building: true` دیدی، بعد از `retryAfter` دوباره بپرس.

## بازخورد (این قسمت «تست دقیق» را ممکن می‌کند)
```
POST /v/?action=feedback
{ "reports": [ {"sid":"1a2b3c4d5e6f","ok":true,"latencyMs":380,"err":""},
               {"sid":"…","ok":false,"latencyMs":3000,"err":"handshake"} ] }
→ 200 {"ok":true,"accepted":2}
```
- batch کن (۱۰ به ۱۰، هر ۴ ثانیه)، نه بعد از هر attempt.
- محدودیت نرخ دارد (پیش‌فرض ۱۲/دقیقه/IP).
- `err` فقط برای لاگ سرور است؛ اپ آن را در UI نشان نده.

## نسخه و بروزرسانی
```
GET /v/?action=version&vc=<versionCode فعلی>
```
```jsonc
{
  "schema": 1,
  "channel": "stable",
  "versionCode": 21, "versionName": "1.7.0",
  "apkUrl": "https://ainetmee.ir/v/apk/meelano-1.7.0-21.apk",
  "sha256": "…", "sizeBytes": 27487790,
  "releasedAt": "2026-09-10T18:00:00+00:00",
  "changelogFa": "• رفع لگ بعد از اتصال\n• اعمال خودکار سرور VIP",
  "mandatory": false, "mandatoryBelow": 0,
  "updateAvailable": true, "currentVersionCode": 20,
  "sigInput": "v21|1.7.0|https://ainetmee.ir/v/apk/meelano-1.7.0-21.apk|<sha>|27487790|0|0|stable",
  "sig": "hmac-sha256(sigInput)",
  "policy": { "autoDownloadOverWifi": true, "checkIntervalHours": 12, "requireHttps": true },
  "current": { "versionCode": 21, "versionName": "1.7.0", "channel": "stable", "releasedAt": "…" }
}
```
قوانین سمت اپ:
1. `sigInput` را از فیلدهای پارس‌شده **بازسازی** کن و با `sigInput` سرور مقایسه کن؛ بعد `sig` را
   HMAC کن. (چنین کردم در `UpdateManager.canonical()` — ترتیب فیلدها باید با `Version::canonical()`
   یکی بماند. امضای «کل JSON» عمداً استفاده نشده: کاننول‌سریال‌کردن JSON در دو زبان منبع باگ کلاسیک است.)
2. `updateAvailable` را سرور تصمیم می‌گیرد؛ اپ فقط اطاعت کند.
3. قبل از نصب: `sha256(فایل) == sha256` و `apkUrl` با `https://` شروع شود.
4. نصب: FileProvider + `ACTION_VIEW`. اندروید یک تأیید کاربر می‌خواهد؛ «نصب بی‌صدا» فقط با
   Device Owner/MDM ممکن است. تجربه‌ای که کاربر «خودکار» می‌پندارد = دانلود خودکار + وریفای + یک لمس.

## اکشن‌های مدیریتی (نیازمند `key=<access.toolKey>`)
| اکشن | کار |
|---|---|
| `?action=stats` | سن/تازگی کش، شمارش‌ها، ledger |
| `?action=refresh` | بازسازی فوری vip + free |
| `?action=selftest` | عیب‌یابی استقرار (`&html=1` برای نسخه‌ی قابل‌خواندن) |
| `?action=health` | بدون احراز هویت: php + writable |

## آنچه هرگز نباید در پاسخ باشد
`vip_raw.txt`، نام اصلی سرویس‌ها، مسیرهای مطلق سرور، و `secret`. همه‌ی این‌ها پشت
`data/.htaccess` و `?action=` هستند؛ `?action=stats` هم عمداً کلید می‌خواهد.


## tune — پچِ ترابری هر گره (schema ≥ 10 در کلاینت)

```jsonc
"servers": [{
  "id": "9f2c1a",
  "proto": "vless", "tls": "reality",
  "tune": {                       // اختیاری؛ نبودنش = «کлиنت خودش تصمیم می‌گیرد»
    "fragSize": 200, "fragCount": 2, "fragStrategy": "variable", "fragDelayMs": 20,
    "alpn": "h3,h2", "fingerprint": "chrome", "sni": "www.speedtest.net",
    "ech": false, "keepAliveSec": 15, "mux": false, "muxConcurrency": 8,
    "allowLan": false, "mtu": 1280, "mss": 1300, "grpcMode": "multi", "connectionReuse": true
  }
}]
```

قراردادِ `tune` (چهار قاعده، همه در `net/Regime.kt` + `lib/AiTune.php` پیاده شده‌اند):

1. **patch است، config نه.** فقط کلیدهایی که سرور درباره‌شان نظر داده می‌آیند؛ بقیه از regime‌ی
   کلاینت پر می‌شود. `{"tune":"tight"}` هم مجاز است (presetِ کل ناوگان) و `{"tune":"auto"}` یعنی
   «سکوت کن، خودت ببین».
2. **همه‌چیز clamp می‌شود، دو بار.** یک بار در `AiTune::patchSchema()` (PHP)، یک بار در
   `TunePatch.fromJson()` (Kotlin). بازه‌ها یکی‌اند؛ اگر یکی را عوض کردید، باگ از همان‌جا شروع می‌شود.
3. **بدنه‌ی خالی نفرستید.** `{"tune":{}}` و نبودِ `tune` یک معنا دارند؛ یکی‌شان را انتخاب کنید: نبودنش.
4. **هیچ‌وقت در مسیرِ اتصال لازم نیست.** نبودنش اتصال را خراب نمی‌کند، فقط به پیش‌فرضِ regime برمی‌گرداند.

پیش‌نیازِ ساخت: `?action=vip|free` در `meta` این‌ها را هم می‌فرستد —
`fleet` (شواهد ناوگان)، `tuned` (تعداد گره‌های پچ‌شده)، `tunedBy` (`heuristic` | `heuristic+ai`)،
`regime`. مصرف‌کننده‌ی UI فقط `regime` را نشان می‌دهد؛ بقیه برای تشخیصِ «چرا این گره این شکلی است».

## بازخوردِ بلاک (سوختِ تسک `regime`)

```jsonc
POST /v/?action=feedback
{ "reports": [ {"sid":"9f2c1a","ok":false,"latencyMs":0,"err":"tls_timeout"} ],
  "block":  {"dnsPoisoned":true,"tcpFail":0.62,"tlsFail":0.25,"rtt":48,"probes":12,"at":1730000000},
  "regime": "tight",
  "app": "2.2.0" }
```

`block`/`regime` اختیاری‌اند و قدیمی‌ها نادیده گرفته می‌شوند؛ اگر باشند، در `data/block.json` با EWMA
(آلفای `tune.ewmaAlpha`) جمع می‌شوند و رأی‌های regime با ضریب ۰٫۸ فرسوده می‌شوند — یک کاربر نمی‌تواند
ناوگان را تکان بدهد (`tune.minVotesForRegime=3` لازم است)، ولی یک الگوی واقعی در ده دقیقه دیده می‌شود.

## `?action=advice` — «چرا وصل نشد»، فارسی

```
GET /v/?action=advice&err=tls_timeout&regime=tight&proto=vless&tier=free&attempt=3
→ 200 {"ok":true,"err":"tls_timeout","advice":{"title":"مسیر بسته است",
       "body":"…","action":"fragment_on","canned":false}}
```

- همیشه ۲۰۰؛ مدلِ نبود، جدولِ ازپیش‌نوشته برمی‌گردد (`canned:true`) — پس اپ هرگز «متن در دسترس نیست» ندارد.
- `action` از فهرستِ بسته است: `retry | switch_node | change_regime | fragment_on | fragment_off | wait | contact`.
  UI باید روی این enum سوییچ کند، نه روی متن؛ متنِ فارسی عوض‌شدنی است، رفتار نه.
- rate limit: `advice` ۳۰ بار در دقیقه برای IP (۴۲ با `too_many_requests`).
- کش سرور ۳۶۰۰ ثانیه به ازای هر (`err`, ctx)؛ پس هزینه‌ی این تسک مستقل از تعداد کاربر است.
