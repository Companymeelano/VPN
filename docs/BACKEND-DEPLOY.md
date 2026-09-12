# استقرار بک‌اند روی هاست اشتراکی (cPanel، بدون Node، بدون SSH)

فایل‌ها: `backend/v/` → روی هاست تو: **`https://ainetmee.ir/v/`**
این سند **تنها مرجع استقرار** است؛ قراردادِ هر تماس (پارامتر، هدر، پاسخ، و کجا در اپ خوانده می‌شود) در
[`SERVER-CALLS.md`](SERVER-CALLS.md) است. اینجا فقط «چطور روی هاست بالا بیاوری و چک کنی».

---

## ۰) وضعیتِ هاستِ من — چیزی که امروز واقعاً اندازه گرفته شد

این جدول را با همان ترتیب بخوان؛ هر ردیف یک کار است. (اعداد از پاسخِ زندهٔ هاست، نه از حدس.)

| چه چیزی دیده شد | معنی | کاری که باید بکنی |
|---|---|---|
| `?action=health` → `{"ok":true,"php":"8.4.25","writable":true}` | آپلود شده، PHP تازه، `data/` نوشتنی | هیچ |
| `?action=free` → `count: 80` با نودهای A/B | استخر رایگان کار می‌کند؛ `lib/Util.php` + `lib/Parser.php` درست آپلود شده‌اند | هیچ |
| `?action=vip` → `count: 6`، همه `alive:false`، رتبه D | محتوای `data/vip_raw.txt` **همان فایلِ الگو** است (`…serversid1`، `L0ngTr0janKey`، `stockholm.edge.me`) | §۶ — با کانفیگ واقعی عوضش کن، یا خالی بگذار |
| `?action=selftest` → `503 tool_key_not_configured` | `config.local.php` وجود ندارد | §۳ |
| `/v/admin/` قبل از این آپدیت `403` می‌داد | قاعدهٔ `.htaccess` خودِ پنل را می‌بست (رفع شد: `admin/.htaccess`) | `backend/v/.htaccess` و `admin/.htaccess` را دوباره آپلود کن |
| `?action=version` → `no_apk_published` | `v/apk/` خالی است | §۸ |

قاعده‌ای که کل این سند رویش می‌ایستد: **هر عددی که در پاسخِ سرور می‌بینی، از یک جای کد می‌آید.** پس اگر
چیزی صفر یا خالی بود، دنبال «کدام خط از `config.php` یا کدام فایل در `data/`» بگرد، نه «شانس».

---

## ۱) آپلود

1. cPanel → **File Manager** (یا FTP با FileZilla) → `public_html/`.
2. کل پوشهٔ `v` را داخل `public_html/` کپی کن. نتیجه باید این باشد:

   ```
   public_html/v/index.php            ← همه‌ی اکشن‌ها از اینجا
   public_html/v/config.php           ← پیش‌فرض‌ها؛ ویرایشش نکن
   public_html/v/sources.php          ← لیستِ منبع‌های استخر رایگان
   public_html/v/lib/*.php            ← ۱۱ فایل
   public_html/v/data/                ← باید writable باشد + .htaccess خودش بماند
   public_html/v/apk/                 ← فایل نصب اپ
   public_html/v/admin/index.php      ← پنل
   public_html/v/admin/hash.php       ← سازندهٔ کلیدها
   public_html/v/admin/.htaccess      ← قفلِ داخلیِ پنل (اگر این نباشد، نیمه‌باز است)
   public_html/v/status.php           ← صفحهٔ آدم‌ها
   ```

3. روی `v/data` راست‌کلیک → Permissions → `775` (اگر host اجازه نداد `755` و owner را چک کن).
   **هرگز `chmod 777` نکن**: روی خیلی از هاست‌ها فایلِ 777-شده بی‌اثر یا خودِ دلیلِ تعلیق است.
4. `.htaccess` را «ذاتی/پنهان» در File Manager روشن کن تا ببینی منتقل شده — نبودنش یعنی `data/`
   و `lib/` از وب خواندنی‌اند.

### در هر آپدیتِ کد، چه فایل‌هایی را دوباره آپلود کن

| فایل | چرا |
|---|---|
| `lib/Parser.php` | نرمال‌سازی UTF-8 و فیلترِ نودِ خراب (§۷) |
| `lib/Score.php` | ledger و tierِ نود |
| `lib/Builder.php` | ساختِ فید، ماسک‌کردن نام‌ها، شواهدِ regime |
| `lib/Util.php` | `jsonEncode` مقاوم، `utf8()`، HMAC |
| `config.php` | کلیدهای جدیدِ تنظیمی این‌جا متولد می‌شوند (مثلاً `tune.minLedgerNodesForFailShare`) |
| `.htaccess`، `admin/.htaccess` | قواعدِ دسترسی |
| `index.php`، `status.php`، `admin/index.php` | روتر و پنل |

`config.local.php`، `data/vip_raw.txt`، `data/*.json`، `data/cache/*`، `apk/*` را **دست نزن** —
آن‌ها داده‌ی توست، نه کد. (`data/cache/` را می‌توانی پاک کنی؛ بازسازی می‌شود.)

---

## ۲) PHP را انتخاب کن

cPanel → **Select PHP Version / MultiPHP Manager** → `8.1` یا `8.2` (کد روی `7.4` هم در CI تست می‌شود؛
روی `8.4` هم خودت اجرا کردی ✓). اگر همان‌جا extension قابل انتخاب است:

| extension | اگر نباشد |
|---|---|
| `json` | پایان کار؛ فید ساخته نمی‌شود |
| `zlib` | `vip.json.gz` ساخته نمی‌شود → ترافیکِ بیشتر، ولی سالم |
| `curl` | fallback به `file_get_contents` (آهسته‌تر، بدون `curl_multi`) |
| `mbstring` | تشخیص نام/کشور در متن‌های فشرده ضعیف‌تر |
| `openssl` | `password_verify` برای پنل و HMAC امضای نسخه می‌میرد |

دو تنظیم که واقعاً به آن‌ها کار می‌افتد (`Select PHP Version → Options`):

- `memory_limit ≥ 128M` — بیلدِ free با ۹۰۰ کاندید (~`free.maxCandidates`، `config.php:70`).
- `max_execution_time ≥ 30` — بدترین حالتِ یک بیلد ~۲۵ ثانیه است، چون ۱۸ ثانیه‌اش بودجهٔ پروب است
  (`free.probe.budgetMs`، `config.php:83`). با ۲۰ ثانیه، بیلد **وسط کار** کشته می‌شود و کاربر
  کشِ کهنه می‌گیرد (`cache.serveStaleWhileRebuild`، `config.php:43`) — یعنی اپ چیزی نمی‌فهمد، فقط
  کهنه می‌بیند. اگر هاستت سقفِ سختِ ۲۰ ثانیه دارد، بودجه را پایین بیاور:
  ```php
  'free' => ['probe' => ['budgetMs' => 12000, 'concurrency' => 24]],
  ```

---

## ۳) `config.local.php` — تنها فایلی که تو می‌سازي/ویرایش می‌کنی

`config.php` هرگز ویرایش نمی‌شود (با هر آپدیت بازنویسی می‌شود) و در آخرِ کارش `config.local.php` را
deep-merge می‌کند (`config.php:202`). پس فقط یک فایل بساز: `public_html/v/config.local.php`.

راحت‌ترین راه: به `/v/admin/hash.php` برو، رمزِ پنل را در **فرم** بزن (POST، نه نوارِ آدرس — URL در
لاگِ هاست می‌ماند) و کلِ خروجی را به‌عنوان `config.local.php` ذخیره کن. همان صفحه سه کلید تصادفی
(`secret`، `feedKey`، `toolKey`) و هشِ bcryptِ رمز را می‌سازد و بهت می‌گوید کدام خط تا بیلدِ بعدی
کامنت بماند.

### نمونهٔ کامل، با معنیِ هر کلید

```php
<?php return [
    // امضایِ پاسخِ ?action=version (دفاع در عمق در برابر CDN/هاستِ دست‌خورده).
    // اپ باید با همین مقدار بیلد شده باشد:  -PMEELANO_FEED_SECRET=<64 هگز>
    // ⚠ تا وقتی بیلدی با آن کلید نداری، این خط را کامنت نگه دار — وگرنه اپهای نصب‌شده
    //   پاسخِ امضاشده را راستی‌آزمایی نمی‌کنند و می‌مانند روی bad_signature (بی‌صدا، بدون دیالوگ).
    // 'secret' => '…۶۴ هگز…',

    'access' => [
        // 'feedKey' => '…',   // ⚠ §۶ را بخوان: به‌محضِ ریختنِ کانفیگِ واقعیِ VIP، این لازم است
        'toolKey'         => '…',   // فقط ابزارها: ?action=selftest|refresh|stats
        'adminPassHash'   => '$2y$10$…',
        'adminSessionTtl' => 7200,
    ],

    'update' => [
        'publicBase'     => 'https://ainetmee.ir/v/apk',   // بدون اسلش آخر
        'channel'        => 'stable',
        'mandatoryBelow' => 0,
    ],
];
```

### کلیدها همیشه دوطرفه‌اند

| کلیدِ سمتِ سرور | سمتِ اپ | اگر یکی خالی/غلط باشد |
|---|---|---|
| `secret` (۶۴ هگز) | `MEELANO_FEED_SECRET` (BuildConfig) | هر دو واقعی = امضا چک می‌شود؛ هر دو خالی = پاسخِ بدون‌امضا که اپ **می‌پذیرد**؛ یک‌طرف = `bad_signature` و آپدیتِ بی‌صدا |
| `access.feedKey` | `MEELANO_FEED_KEY`، هدر `X-Feed-Key` | سرور کلید می‌خواهد و اپ ندارد → `403 feed_key_required` روی `vip/free/feedback/advice` (`index.php:64`) |
| `access.toolKey` | — | هیچ‌وقت در اپ نمی‌رود؛ فقط `&key=` روی `selftest|refresh|stats` |
| `access.adminPassHash` | — | خالی = پنل قفل است («رمز پنل تنظیم نشده») |

`android/app/build.gradle.kts:30-36` مقادیرِ پیش‌فرضِ build را نگه می‌دارد:
`MEELANO_FEED_BASE=https://ainetmee.ir/v`، `MEELANO_FEED_KEY=""`، `MEELANO_FEED_SECRET=""`.
یعنی APKهای منتشرشده تا امروز **بدون کلید** بیلد شده‌اند — که تا وقتی `feedKey` هاست خالی است درست است،
و از لحظه‌ای که VIPها واقعی شوند، خطرناک (§۶، بند آخر).

---

## ۴) پنل ادمین (`/v/admin/`)

ورود با رمزی که هشِش را در `adminPassHash` گذاشتی. بعد از ورود، پنج کارت:

| کارت | چه می‌کند | معادلِ دستی‌اش |
|---|---|---|
| **وضعیت** | سنِ کش، تعداد نودها، ledger (چند نود پیگیری/بن‌شده) | `?action=stats&key=<toolKey>` (`Builder::stats()`، `lib/Builder.php:597`) |
| **خودآزمون استقرار** | همان ۲۲ بررسیِ §۵، رندر‌شده | `?action=selftest&key=<toolKey>&html` |
| **لیست VIP** | ذخیره در `data/vip_raw.txt`، با تیکِ «بازسازی» | ویرایش فایل با FTP |
| **منابع استخر رایگان** | روشن/خاموش‌کردنِ هر منبع → `data/sources_state.json` | حذف از `sources.php` |
| **بروزرسانی اپ** | آپلود APK و انتشار `data/version.json` (`versionName/versionCode/changelogFa/mandatory/mandatoryBelow`) | FTP + نام‌گذاریِ درست |

پنل تک‌فایل است، بدون فریم‌ورک، با `session` + توکن CSRF؛ قاعدهٔ دسترسی‌اش در
`admin/.htaccess` نوشته شده: همه‌چیز `denied`، فقط `index.php` و `hash.php` `granted`.
اگر **قبل از** این اصلاح فایل‌ها را آپلود کرده‌ای، `/v/admin/` روی همه‌ی هاست‌هایی که mod_rewrite
فعال دارند `403` می‌دهد — نشانه‌اش همین است که «پنل وجود ندارد، ولی `/v/?action=health` سالم است».
راه‌حل: `backend/v/.htaccess` و `backend/v/admin/.htaccess` را دوباره آپلود کن
(قاعدهٔ قدیمی `^(lib|admin)/ → [F,L]` بود؛ حالا فقط `^lib/` بسته است).

یک تستِ CI هم روی همین گذاشته‌ام (`backend/v/tests/run.php`، «the rewrite rules do not brick the admin
panel») تا دیگر ممکن نباشد ابزارِ استقرار را با یک خطِ آپاچی خراب کنیم.

---

## ۵) خودآزمون — این مرحله را رد نکن

```
https://ainetmee.ir/v/?action=selftest&key=<toolKey>&html      (نسخهٔ آدم‌خوان)
https://ainetmee.ir/v/?action=selftest&key=<toolKey>           (JSON، برای اسکریپت)
```

دسته‌ها و معنیِ هر ردیف (`lib/SelfTest.php:11`):

| گروه | ردیف‌ها | اگر `bad`/`warn` بود |
|---|---|---|
| `php` | `version`، `ext:json|zlib|mbstring|curl|openssl`، `allow_url_fopen`، `memory_limit`، `max_execution_time`، `disable_functions` | برای `disable_functions` چارّه‌ای نیست: `stream_socket_client/flock/file_get_contents/curl_multi_exec` باید باشند، وگرنه نه پروب ممکن است نه قفلِ کش |
| `fs` | `data dir`، `atomic write` (tmp+rename)، `flock`، `open_basedir`، `disk free` | `atomic write = bad` یعنی `data/` نوشتنی نیست یا rename مجاز نیست → کش ساخته نمی‌شود و هر درخواست یک بیلدِ کامل است |
| `http` | `gh` (raw.githubusercontent.com)، `cf` (Cloudflare trace) | `bad` = هاست نمی‌تواند لیست‌های عمومی را بخواند؛ استخر رایگان تا ابد خالی می‌ماند (§۷، بند آخر) |
| `egress` | `github.com:443`، `1.1.1.1:443`، `8.8.8.8:53`، `odd_port_8080` | `warn/bad` روی `odd_port_8080` یعنی پورت غیراستاندارد outbound ممنوع؛ `free.probe.autoDisableOnBlocked` خودش پروب را خاموش می‌کند و رتبه‌بندی فقط از بازخوردِ کاربران می‌شود — این حالت **طراحی‌شده** است، نه شکست |
| `build` | `free`، `vip`، `name masking`، `country/flag detection`، `cache write` | `name masking = bad` یعنی نامِ سرویس‌دهنده به اپ نشت کرده؛ فوری `brand.vip`/`vip.maskNames` را چک کن |

خروجی دو حالت دارد: `mode: full: feeds + probes + client feedback` یا
`degraded: check the bad rows above`. «degraded» فاجعه نیست؛ یعنی یک پا لنگ است و بقیه کار می‌کند.

---

## ۶) لیست VIP

سه راه، هر سه به یک‌جا (`Parser::parseBlob`، `lib/Parser.php:41`):

1. پنل → «لیست VIP» → paste → Save + rebuild.
2. فایل `v/data/vip_raw.txt` (`vip.sourceFile`، `config.php:60`).
3. `vip.url` به یک فایل/سایبکریپشنِ http(s) (`config.php:61`) — اگر پنلِ سرورِ خصوصی بهت
   «Subscription URL» می‌دهد، این تمیزترین راه است: هر ساعت دوباره خوانده می‌شود، بدونِ دستی‌کاری.

ورودی‌های پذیرفته‌شده (قاطی‌شان هم اشکالی ندارد): `vless://` `vmess://` `trojan://` `ss://` `hy2://`
`tuic://`، اشتراک base64، صفحهٔ HTML/خروجی تلگرام، JSONِ آرایه‌ای، یا خط‌های `ip:port:user:pass`.

```
vless://<uuid>@185.143.233.10:443?encryption=none&security=reality&sni=www.microsoft.com&pbk=<pbk>&sid=<sid>&fp=chrome&flow=xtls-rprx-vision#هر-نامی
trojan://<pass>@162.159.200.5:443?sni=ny.example.com&allowInsecure=0#…
ss://<base64("aes-256-gcm:پسورد")>@51.15.2.2:8388#…
ss://aes-256-gcm:پسورد@51.15.2.2:8388#…        ← این هم پذیرفته می‌شود
```

- **نام‌ها دور ریخته می‌شوند**: به اپ فقط `name = "Vip M•A"` (`config.php:13`) + `cc` برای پرچم می‌رسد؛
  هیچ اسمِ سرویس‌دهنده‌ای در payload نیست. اگر روزی دیدی نامِ upstream در اپ ظاهر می‌شود، یعنی
  `vip.maskNames=false` شده یا نسخه‌ی `lib/Builder.php` قدیمی است.
- **خطای واقعیِ امروز**: اگر محتوای `vip_raw.example.txt` را کپی-پیست کرده باشی، فید شش نودِ جعلی
  می‌دهد که همه `alive:false` و رتبه D اند. اپ آن‌ها را «VIP» نشان می‌دهد و کاربر فقط ناامید می‌شود.
  کانفیگِ واقعی نداری؟ فایل را **خالی** بگذار — سرور `meta.notes` را با «vip list is empty …» پر می‌کند
  و لیست درستِ خالی، صادقانه‌تر از شش دروغ است.
- روزِ اول همه «بدون تأخیر» یا D به‌نظر می‌رسند، چون پروب از فرانکفورت/آپلودفریم انجام می‌شود و
  بازخوردِ داخل کشور هنوز نیست. `vip.probe=true` فقط می‌سنجد و حذف نمی‌کند
  (`vip.dropIfProbeFails=false`، `config.php:65`) — عمداً، چون یک شکستِ سرور نباید کاربر خصوصی را از
  لیستش محروم کند.
- **هشدار امنیتی که اگر نادیده بگیری، لیستِ پولی تو عمومی است**: `?action=vip` بدنه‌ای می‌دهد که
  `pbk`/`sid`/`userId`/پسوردِ trojan/PSK داخلش است — یعنی عملاً کلیدِ ورود به سرورهای تو. تنها چیزی که
  جلوی هر کسی را می‌گیرد `access.feedKey` است. پس **به‌محضِ ریختنِ کانفیگ واقعی**:
  1. در `config.local.php` مقدار `feedKey` را ست کن،
  2. بیلدِ بعدی را با `-PMEELANO_FEED_KEY=<همان>` بده و منتشر کن،
  3. تا قبل از آن بیلد، اپ قدیمی `403` می‌گیرد — اگر باز کردنِ لیست روی دستگاه‌های فعلی برایت مهم است،
     دو مرحله را هم‌زمان انجام بده (اول بیلد، بعد ست‌کردنِ کلید روی هاست، در همان پنجره‌ی زمانی).

---

## ۷) استخر رایگان — خودکار است؛ این‌ها پیچ‌هایش‌اند

مسیرِ هر بیلد (`Builder::freeNodes`، `lib/Builder.php:247`):
`sources.php` ← fetch با بودجه (`http.maxBytes = 4 MB` برای هر منبع، فقط سرِ فهرست چون لیست‌ها
«بهترین‌ها اول» مرتب‌اند) ← `Parser::parseBlob` ← پیش‌فیلتر (`allowPorts`، dedup، بن‌ها، سقف
`maxCandidates = 900`) ← `Probe` (TCP/CONNECT/SOCKS، `concurrency 40`، `budgetMs 18000`) ←
gate (`www.google.com:443`، `1.1.1.1:443`، `connectivitycheck.gstatic.com:443`) ←
Score (A ≤ 400ms، B ≤ 1000، C ≤ 2200، D) با `reliability = (ok+1)/(ok+fail+2)` ← رتبه‌بندی ←
`free.json` (`maxNodes = 80`، و اگر زیر `minNodes = 6` برود **بیلدِ قبلی نگه داشته می‌شود**).

پیچ‌هایی که ارزش چرخاندن دارند (همه در `config.local.php`، بدونِ دست‌زدن به `config.php`):

| کلید | پیش‌فرض | چه می‌کند |
|---|---|---|
| `free.maxNodes` | 80 | سقفِ فهرستِ منتشرشده؛ پایین‌اش کن اگر مودمِ هاست CPU می‌سوزاند |
| `free.minNodes` | 6 | آستانهٔ «بیلد را دور نریز» |
| `free.allowPorts` | ۲۶ پورت | هر پورتی که در این لیست نباشد اصلاً پروب نمی‌شود؛ برای نودهای خانگی اضافه‌اش کن |
| `free.probe.deepGate` | false | تستِ TLS از تونل؛ روی هاستِ قوی `true` + `deepTopN=12` کیفیتِ رتبه‌بندی را واقعاً بالا می‌برد |
| `free.gate.requireOf` / `probeAll` | 1 / false | «در لیست سیاهِ گوگل/کلادفلر نباشد» — بالا بردن‌شان هزینه را چند برابر می‌کند |
| `free.bans.failLimit` / `banSeconds` | 3 / 21600 | سه شکست → شش ساعت cool-off، بعد می‌تواند برگردد |
| `free.feedback.weight` | 0.55 | وزنِ نظرِ کاربرانِ داخل کشور نسبت به پروبِ سرور |
| `free.sourcesOverride` | — | لیست را کامل عوض کن (کلِ `sources.php` نادیده گرفته می‌شود) |
| `sources.local.php` | — | کنار `sources.php` بسازش؛ فقط **چند منبع اضافه** می‌کند (نمونهٔ پایین همین بند) |

```php
<?php return ['sources' => [
  'my-telegram-ch' => ['kind' => 'config', 'take' => 200, 'url' => 'https://…/list.txt'],
]];
```

دو نکته که اغلب گمراه‌کننده‌اند:
- **فیلترِ نودِ خراب** (`Parser.php:544-576`): اگر خطی `ss://<چیزی>@host:port` باشد که آن `<چیزی>`
  base64ِ `method:password` نباشد، cipherِ بی‌معنی از آب درمی‌آید؛ پروبِ TCP آن را «۴ms، زنده، رتبه B»
  می‌کند چون فقط بازبودنِ پورت را سنجیده. حالا چنین نودی منتشر **نمی‌شود**. پس اگر تعداد free نسبت به
  قبل افت کرد، علتش این است که نودهای مُرده‌ی بیشتری فیلتر شده‌اند، نه اینکه هاست خراب شده.
- **`meta.regime` دیگر از مرگِ آینه‌های عمومی نتیجه نمی‌گیرد** (`lib/Builder.php:449` +
  `tune.minLedgerNodesForFailShare = 5`، `config.php:146`): سهمِ شکستِ پروب فقط از نودهای **VIP**
  محاسبه می‌شود. قبلاً `tcpFail: 0.991` از استخر free باعث می‌شد کل ناوگان blackout شود.

اگر `http gh = bad` و `egress` هم بسته است: استخر رایگان عملاً خالی می‌ماند. **این را بپذیر و رد کن** —
یعنی `free.enabled => false` بگذار تا هر بیلدِ رایگان CPUِ هاست را برای صفر نتیجه نسوزاند؛ لیست VIP
و اپ دست‌نخورده‌اند.

---

## ۸) بروزرسانی اپ (خودِ مسیرِ نصب)

1. APK را با **همان release key همیشگی** امضا کن (اندروید APK با امضای متفاوت را نصب نمی‌کند؛ لایهٔ
   اصلیِ امنیت همین است، نه HMAC).
2. فایل را با نامِ الگویی بگذار: `meelano-<versionName>-<versionCode>.apk` — مثلاً
   `meelano-2.3.1-203001.apk`. `Version.php:96` همین الگو را با regex می‌خواند؛ اگر فایلِ CI را
   دست‌کاری و تغییرنام کنی، `?action=version` می‌گوید `no_apk_published`.
3. `….sha256` را **کنارش** بگذار (asset خودش در Release است): `Version.php:166-170` با
   `filemtime(sidecar) >= filemtime(apk)` می‌فهمد هش تازه است و دیگر ۱۳ مگابایت را هشر نمی‌کند؛
   رشتهٔ داخل فایل هم می‌تواند خامِ ۶۴ هگز باشد یا خروجی `sha256sum`.
4. پنل → «بروزرسانی اپ» → `versionName`، `versionCode` (حتماً بیشتر از قبل)، توضیح فارسی،
   آپلود یا انتخاب از فهرست → انتشار.
5. چک کن:
   ```
   https://ainetmee.ir/v/?action=version&vc=203000
   ```
   و ببین `updateAvailable: true`، `apkUrl` با `https://`، `sha256` پر، و `sig` (اگر `secret` ست
   کرده‌ای). `policy.requireHttps: true` یعنی `apkUrl` روی http = اپ **هیچ‌وقت** دانلود را شروع نمی‌کند.
6. اپ یک تپ می‌خواهد برای نصب؛ بی‌صدا ممکن نیست (Device Owner لازم است و در Play هم ممنوع).
7. اگر روزی در Play بودی: مسیر نصبِ خودکار را برای بیلدِ پلی خاموش کن (`CHANNEL=play`) و Play In-App
   Updates را به‌کار ببر — سیاستِ پلی نصب APK از بیرون را نمی‌پذیرد.

`mandatoryBelow` را فقط برای «این نسخه دیگر امن نیست» به‌کار ببر: شیتِ اجباری، دکمهٔ رد ندارد.

---

## ۹) کش، سرعت، و cron

- پاسخ‌ها `ETag` + `Cache-Control: public, max-age=300, stale-while-revalidate=60` می‌گیرند
  (`cache.httpMaxAge`، `config.php:40`)؛ پولینگِ معمولیِ اپ `304` صفر بایت است.
- `vip.json.gz` از همان لحظهٔ بیلد ساخته می‌شود (`cache.gzipPrebuild`) → PHP هنگامِ سرو کردن CPU
  مصرف نمی‌کند و با `Accept-Encoding: gzip`ِ اپ جور است.
- TTLها: `vipTtl=600` (۱۰ دقیقه)، `freeTtl=3600` (۱ ساعت)، `versionTtl=300` — و بیلد **هیچ‌وقت** جلوی
  کاربر را نمی‌گیرد: اگر کش کهنه باشد همان فوراً داده می‌شود و یک نفر با `flock` بازسازی می‌کند
  (`serveStaleWhileRebuild`، `staleGrace = 7 روز`).
- cron لازم نیست. اگر تازه‌تریِ تضمینی خواستی — cPanel → Cron Jobs:
  ```
  */30 * * * *  curl -s "https://ainetmee.ir/v/index.php?action=refresh&key=<toolKey>" -o /dev/null
  ```
  اگر `curl` نداشتی: `wget -q -O /dev/null "…"` همان کار را می‌کند. خروجیِ JSONِ اکشنِ `refresh`
  برای هر دو لیست `{nodes, ms, notes}` می‌دهد — اگر خواستی لاگش کنی، `>> /home/USER/v-refresh.log 2>&1`.

---

## ۱۰) امنیت — هرچه اگر رعایت نشد، لیست VIP تو عمومی است

1. **`data/` از وب بسته باشد.** `.htaccess` و `data/.htaccess` این کار را می‌کنند
   (`vip_raw.txt`، `ledger.json`، `version.json`، `config.local.php`). اگر هاست nginx است و
   `.htaccess` نادیده گرفته می‌شود، چک کن:
   ```
   https://ainetmee.ir/v/data/vip_raw.txt      باید 403/404 بدهد
   https://ainetmee.ir/v/lib/Util.php          باید 403/404 بدهد
   https://ainetmee.ir/v/config.local.php      باید 403/404 بدهد
   ```
   اگر `200` گرفتی: یا `vip_raw.txt` را به بیرون از `public_html` ببر و `dataDir` را در
   `config.local.php` به مسیر مطلق بده، یا `location ^~ /v/data/ { deny all; }` اضافه کن.
2. **`access.feedKey`** — §۶. بدونِش `?action=vip` یک فایل عمومیِ با CORSِ `*` است.
3. **فقط `https://`**: `FEED_BASE` در اپ باید https باشد. نکته: بازکردن آدرس در مرورگرِ دسکتاپ،
   اعتمادِ **اندروید** را ثابت نمی‌کند. برای اطمینان: همان `https://ainetmee.ir/v/?action=health` را با
   **کرومِ گوشی** باز کن؛ اگر صفحه آمد، زنجیرهٔ گواهی را اندروید قبول کرده و اپ هم قبول می‌کند
   (اپ روی همان trust store است، با یک استثنا: `network_security_config.xml` فقط برای `ainetmee.ir`
   cleartext را باز گذاشته — پس http در *اپ* هم کار می‌کند، ولی برای APK هرگز؛ `requireHttpsForApk`
   دانلودِ http را می‌بندد).
4. رمزِ پنل را با URLِ GET نساز (در لاگ می‌ماند)؛ از فرم استفاده کن. اگر کسی آن URL را دید، رمز را
   عوض کن و `adminPassHash` را تازه‌کن — نشست‌های جاری با `adminSessionTtl` می‌میرند.
5. `data/log/feed.log` فقط با `limits.logRequests=true` نوشته می‌شود و خودش را روی ۵۱۲KB می‌چرخاند؛
   برای عیب‌یابیِ موقت روشنش کن، بعد خاموش کن.

---

## ۱۱) عیب‌یابی

| علامت | علت احتمالی | کار |
|---|---|---|
| `/v/admin/` → `403` | `.htaccess` قدیمی که `admin/` را blanket-deny می‌کرد | `backend/v/.htaccess` + `admin/.htaccess` را آپلود کن |
| `500` روی همه‌ی اکشن‌ها | `data/` نوشتنی نیست / PHP 5.x | chmod 775، Select PHP Version |
| `503 tool_key_not_configured` | `config.local.php` نیست | §۳ |
| `403 feed_key_required` | هاست کلید می‌خواهد، APK ندارد | `-PMEELANO_FEED_KEY` یا خالی‌کردن `feedKey` (`index.php:64`) |
| `json_encode_failed: Malformed UTF-8` روی `?action=free` | نسخهٔ قدیمی `lib/Util.php`/`lib/Parser.php` | آن دو را دوباره آپلود کن |
| `?action=version` → `no_apk_published` | نامِ فایل APK با regex نمی‌خواند | `meelano-<name>-<code>.apk` (`Version.php:96`) |
| آپدیت به کاربر نمی‌رسد، ولی فایل روی هاست هست | `secret` یک‌طرف ست شده (`bad_signature`)، یا `apkUrl` روی http، یا `versionCode` بالا نرفته | §۳ و §۸ |
| `?action=vip` لیست خالی | `data/vip_raw.txt` خالی/فقط-کامنت است | paste در پنل؛ `meta.notes` را ببین |
| لیست VIP پر ولی همه D | پروب از اروپا + نبودِ بازخوردِ داخل کشور | طبیعی؛ با مصرف‌کننده‌های واقعی و `feedback.weight=0.55` درست می‌شود |
| گزارشِ گوشی «فید: ۰ گره» و `?action=free` روی هاست `count` دارد | مسابقهٔ syncِ اول: بیلدِ free ~۱۸s طول می‌کشد و مهلتِ خواندنِ اپ ۶s بود | روی نسخه‌های قدیمی یک بار pull-to-refresh؛ از ۲.۳.۱ مهلتِ syncِ سرد ۳۰s است (`data/ServerFeedRepository.kt:66`) |
| `meta.regime: blackout` روی شبکهٔ سالم | سهمِ شکستِ آینه‌های عمومی به‌عنوان شاهدِ فیلترینگ خوانده می‌شد | آپدیتِ `lib/Builder.php` + `lib/Score.php` + `config.php` |
| free همیشه خالی | outbound بلاک | `free.probe.enabled=false` و اگر فایده‌ای نداشت `free.enabled=false` |
| `429 rate_limited` | بیشتر از `limits.reqPerMinPerIp=60` از یک IP | طبیعی پشتِ NAT مشترک؛ اپ کش دارد و اصرار نمی‌کند |

### `meta.notes` — فرهنگِ اصطلاحاتِ خودتوضیح

فید در `meta.notes` می‌گوید چرا آن‌طور است؛ این‌ها را جدی بگیر:

| نکته | معنی |
|---|---|
| `vip list is empty -> edit … from the admin panel` | `vip_raw.txt` خالی است — نه باگ، نه قطعی |
| `outbound_tcp_blocked_on_this_host -> ranking from client feedback only` | پروب خودکار خاموش شد؛ رتبه‌ها از نظرِ کاربران است |
| `probing disabled (free.probe.enabled=false) -> ranking from ledger + client feedback only` | دستی خاموشش کرده‌ای |

---

## ۱۲) QA پیش از دادنِ APK به آدم‌ها

1. **راستی‌آزمایی فایل:** `sha256sum meelano-*.apk` باید با `.sha256` کنارش یکی باشد؛ اگر کاربر
   از هاست دانلود می‌کند، همین مقایسه را در `/v/?action=version` هم می‌تواند ببیند.
2. **گوشیِ تست را روی حالت پروازِ داده بگذار و بعد بردار** — اتصال باید خودش برگردد (حالت
   `smartReconnect`). بعد اپ را از لیستِ اخیر بکش (swipe-kill)؛ اگر تونل زنده بود، نوتیفیکیشن
   ترافیک باید بی‌وقفه شمارش کند.
3. **صفره‌ی نشت DNS:** در مرورگر گوشی `https://dnsleaktest.com` → باید IPِ تونل دیده شود، نه IPِ
   اپراتور. با `killSwitch=true`، درِ قطع‌شدنِ تونل هم باید کل ترافیک را بخواباند.
4. **شبکه‌های واقعی:** هر سه اپراتور × سه حالتِ `regime` (calm/tight/blackout) × (وای‌فای خانگی
   با kill-switch روشن). خروجیِ هر تست را با «گزارش عیب‌یابی» گوشی کپی کن و نگه دار — آن گزارش
   آدرس/IP/کانفیگ ندارد و برای فرستادن ساخته شده.
5. **متنِ «۰ گره» را جدی بگیر، ولی اول از سرور بپرس:** `?action=free` در مرورگر اگر `count` داشت،
   مشکل سمتِ اپ است (sync)، اگر `count: 0` داشت، سمتِ سرور (§۱۱).
6. برای اتصال/قطعِ خودکار در تست‌های انبوه: `adb shell appops set ir.meelano.vpn ACTIVATE_VPN allow`
   (دیالوگِ سیستمی را رد می‌کند). این فقط برای QA است؛ کاربرِ واقعی یک بار باید آن دیالوگ را ببیند.
7. اگر نسخه‌ی `versionCode` را بالا بردی، `data/version.json` هم تازه شده باشد (وگرنه آپدیت بی‌صدا
   no-op است) — پنل این را برای تو می‌سازد.

---

## ۱۳) پشتیبان‌گیری، بازگشت، و «چه چیزی نباید پاک شود»

**پشتیبان، همین چهار فایل** (هر بار که لیست VIP را عوض کردی، یک نسخه با تاریخ بردار):

```
v/config.local.php            ← تنها تنظیماتِ تو
v/data/vip_raw.txt            ← لیست VIP خام
v/data/sources_state.json     ← منبع‌های روشن/خاموش
v/apk/ + v/data/version.json  ← آخرین نسخهٔ منتشرشده
```

- `data/ledger.json` و `data/cache/*` قابل بازسازی‌اند؛ پاک‌کردنشان فقط یک بیلدِ کاملِ پرهزینه است.
  تنها دلیلِ مشروعِ پاک‌کردنِ کش: «فیدِ من اطلاعات غلطِ چسبنده نشان می‌دهد» → پنل → rebuild.
- بازگشتِ نسخه: فایل APKِ قبلی را در `v/apk/` نگه دار و `versionCode` کوچک‌تر را منتشر نکن
  (اپ فقط وقتی `vc < published` پیشنهاد می‌دهد). برای عقب‌نشینی واقعی، `version.json` را با
  `versionCode`ِ پایین‌ترِ قبلی بنویس و `mandatoryBelow` را صفر کن.
- کلِ `v/` را روی نسخهٔ سالمِ خودِ Release برگرداندن امن است: داده در `config.local.php` و `data/`
  است و `git`/ZIP این‌ها را ندارد.

---

**قراردادِ تماس‌ها**: [`SERVER-CALLS.md`](SERVER-CALLS.md) ·
**هستهٔ VPN و اینکه چرا هنوز وصل نمی‌شود**: [`CORE-INTEGRATION.md`](CORE-INTEGRATION.md) §۳/§۵ ·
**بیلد گرفتن**: [`ANDROID-BUILD.md`](ANDROID-BUILD.md)
