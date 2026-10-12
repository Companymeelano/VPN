# گزارش موشکافانه‌ی بررسی پروژه — M•A VPN

**دامنه‌ی بررسی:** تگ `v2.4.0-beta.1` (آخرین تگ مخزن) به‌علاوه‌ی ۹ اصلاحیه‌ی تازه‌ی شاخه‌ی `arena/01a08ba7-vpn` (کامیت `b18e58b` که تازه‌ترین وضعیت کد است). همه‌ی فایل‌های اندروید (۳۴ Kotlin + منابع XML + Gradle) و همه‌ی فایل‌های بک‌اند (PHP، htaccess، ابزارها) و ۱۲ سند خوانده شدند.

**روش:** علاوه بر مرور ایستا، برای ادعاهای کلیدی اجرای واقعی انجام شد:
- سوئیت آزمون بک‌اند روی PHP 8.3.33 (از طریق `@php-wasm`): **۱۰۴ تست سبز، ۰ شکست**
- بازآفرینی اجرایی باگ به‌روزرسانی (خروجی واقعی `Version::canonical()` در برابر رشته‌ی سازنده‌ی کلاینت → عدم تطابق)
- اجرای واقعی `Parser::parseUri` روی vmess با `"tls":"tls"` برای اثبات باگ پارسر
- بررسی تطابق رشته‌ها/دراوبل‌ها با تمام ارجاع‌های `R.string`/`R.drawable` (مورد ازدست‌رفته‌ای وجود ندارد)

---

## 🔴 بحرانی

### ۱) سرویس VPN روی اندروید ۱۴ به‌بالا در هنگام شروع کرش می‌کند و تونل هرگز بالا نمی‌آید
**فایل:** `android/app/src/main/java/ir/meelano/vpn/vpn/MeelanoVpnService.kt:119`

```kotlin
registerReceiver(stopReceiver, IntentFilter(ACTION_DISCONNECT))
```

اپ با `targetSdk = 35` بیلد می‌شود. از اندروید ۱۳ (API 33+) ثبت برودکستِ non-system بدون یکی از پرچم‌های `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` بلافاصله `SecurityException` می‌اندازد. این خط در `onCreate` سرویس است؛ یعنی روی اندروید ۱۴+ هر تلاش اتصال، سرویس را در همان لحظه‌ی تولد می‌کشد و کاربر فقط «اتصال برقرار نشد» را می‌بیند.

**رفع پیشنهادی:**
```kotlin
ContextCompat.registerReceiver(this, stopReceiver, IntentFilter(ACTION_DISCONNECT), ContextCompat.RECEIVER_NOT_EXPORTED)
```

### ۲) به‌روزرسانی خودکارِ امضاشده از آرشیو مرده است و هیچ‌وقت کار نمی‌کند
**فایل:** `android/app/src/main/java/ir/meelano/vpn/update/UpdateManager.kt:206-207`

```kotlin
= "v$versionCode|$versionName|$url|${'$'}{sha256.lowercase()}|$sizeBytes|" +
  "${'$'}{if (mandatory) 1 else 0}|$mandatoryBelow|$channel"
```

دو `{...}` بعد از `'$'` **به‌صورت متن خام** در خروجی می‌نشینند (`{sha256.lowercase()}` و `{if (mandatory) 1 else 0}`، به‌جای مقدار واقعی). در نتیجه رشته‌ی canonical سمت کلاینت با `sigInput` سرور (`backend/v/lib/Version.php`) یکی نیست و `parse()` در خط ۱۱۷ (`if (sig.isNotBlank() && canonical != o.optString("sigInput")) return null`) پاسخ نسخه را بی‌صدا رد می‌کند.

**اثبات با اجرا:** بک‌اند واقعی با php-wasm رشته‌ی `…|a1a1…|42|0|90|stable` تولید می‌کند ولی رشته‌ی کلاینت `…|{sha256.lowercase()}|42|{if (mandatory) 1 else 0}|90|stable` است → هرجوابی با HMAC فعال به `bad_signature` می‌خورد. با `version.hmacOff=false` (حالت توصیه‌شده‌ی اسناد) هیچ به‌روزرسانی‌ای هیچ‌وقت نصب نمی‌شود و هیچ خطایی به کاربر نشان داده نمی‌شود.

**رفع:** دو عبارت را به `${sha256.lowercase()}` و `${if (mandatory) 1 else 0}` (با `$` واقعی برای اینترپولیشن) برگردانید.

### ۳) هسته‌ی تونل لینک نیست — در این نسخه هیچ ترافیکی واقعاً از تونل عبور نمی‌کند
**فایل‌ها:** `android/app/src/main/java/ir/meelano/vpn/vpn/CoreApi.kt` (ثابت `CORE_LINKED = false` و `startProxy` که `CoreNotLinked` پرتاب می‌کند)، `XrayBridge.kt` (بدنه‌ی پرتاب‌کننده‌ی `start0/bind0`)، `android/app/build.gradle.kts` (گارد Release که خروجی Play را با CoreLinked=false می‌بندد)

این مورد «باگ» نیست بلکه وضعیت عمدی طراحی است، اما از دید کاربر بزرگ‌ترین شکاف است: همه‌ی زنجیره‌ی UI و ارکستراتور کامل است و دقیقاً در نقطه‌ی اتصال به CoreEngine با خطای «هسته‌ی تونل در این بیلد وصل نشده است» تمام می‌شود (UI این حالت را شناسایی هم می‌کند). تا اتصال واقعی Xray/sing-box انجام نشود، اپ عملاً VPN نیست — و در هر برنامه‌ی انتشار باید صادقانه اعلام بماند.

---

## 🟠 مهم

### ۴) بک‌اند پرچم TLS را برای vmess کلاسیک حذف می‌کند (تنزل سکوت‌گرانه از TLS به plaintext)
**فایل:** `backend/v/lib/Parser.php` — `parseVmess` (خط ۱۹۹) → `tlsOf` (خط ۴۶۷) → `boolParam` (خط ۴۸۵)

کانفیگ vmess کلاسیک در JSON مقدار `"tls":"tls"` دارد؛ ولی `tlsOf()` از طریق `boolParam()` فقط `1/true/yes/on` را «صادق» می‌داند و `'tls'` را نه. نتیجه: `tls=''` برای هر نودِ vmess. چنین نودی با `security=none` در فید منتشر می‌شود: handshake TLS از کار می‌افتد و بسته به پنل مقصد، ترافیک حتی بدون رمزنگاری TLS رد می‌شود (بدتر از شکست ساده‌ی اتصال).

**اثبات با اجرا:** توکن `vmess://...{"tls":"tls","net":"ws",...}` به `Parser::parseUri` واقعی داده شد و نتیجه `'tls' => ''` برگشت.

نکته‌ی تقابلی مهم: کلاینت (`NodeUri.kt`) همین لینک را درست می‌خواند؛ یعنی یک کانفیگ vmess اگر مستقیم در اپ paste شود TLS دارد و اگر از سرور عبور کند ندارد — رفتار متناقض دو راه ورود.

**رفع:** در `tlsOf` مقدار رشته‌ای `'tls'` (و `'reality'`) را هم برای کلید `tls` بپذیرید، یا در `parseVmess` مقدار را صریحاً ست کنید.

### ۵) endpoint بازخورد بدون گیت کلید — بازی‌دادن رتبه‌بندی و بن از بیرون ممکن است
**فایل:** `backend/v/index.php:90` (تابع `feedback()`)

اپ همیشه هدر `X-Feed-Key` می‌فرستد و `feed()` هم `access.feedKey` را اجباری می‌کند، اما `feedback()` (و `advice()`) این بررسی را ندارند — تنها دفاع rate-limit ۱۲/دقیقه/IP است. نتیجه: هر عامل خارجی می‌تواند برای هر `sid` گزارش‌های ok/fail جعلی ثبت کند؛ `Ledger::bump` با آستانه‌ی `(ok+fail)>=3` و ۶ شکست متوالی به `bannedUntil` می‌رسد و همان ورودی‌ها وارد `foldBlockEvidence` (تصمیم‌گیری regime فلیت) هم می‌شود. سناریوی «بن‌کردن همه‌ی سرورهای VIP یک عملگر» با چند صد درخواست در دقیقه عملی است.

**رفع:** همان گیت `requireFeedKey()` را در ابتدای `feedback()` (و ترجیحاً `advice()`) صدا بزنید.

---

## 🟡 متوسط

### ۶) گیت اتصال SOCKS روی کشِ DNS سرد همیشه از کار می‌افتد
**فایل:** `backend/v/lib/Builder.php:385`

در ساخت تسک‌های `SocksTask` خط `Dns::lookup($gateHost)` **پیش از** فراخوانی `Probe::run` اجرا می‌شود، در حالی‌که بودجه‌ی DNS (`Dns::setBudget`) داخل خودِ `Probe::run` مقداردهی می‌شود. پس در اولین ساخت روز (کش خالی) `lookup('www.google.com')` با بودجه‌ی صفر ← `null` برمی‌گردد ← هیچ `SocksTask`ای ساخته نمی‌شود ← نودهای socks بی‌گیت (`gateOk=null`) وارد رتبه‌بندی می‌شوند. سکوت‌گرانه و بدون لاگ، و هر بار که TTL شش‌ساعته‌ی dns.json تمام شود تکرار می‌شود.

**رفع:** رسolution گیت‌هاست را به داخل `Probe::run` منتقل کنید یا پیش از فراخوانی `Probe::run` بودجه را ست کنید.

### ۷) کش سرد + قفلِ مشغول ⇒ لیستِ خالی در اپ تا رفرش دستی
**فایل:** `backend/v/lib/Builder.php` (حالت `'building'`) + `android/.../data/ServerFeedRepository.kt`

وقتی قفل build گرفته شده و هنوز اولین خروجی ساخته نشده، سرور `{servers:[], meta:{reason:'building'}}` برمی‌گرداند. کلاینت در حالت HOST برای این پاسخ re-poll خودکار ندارد: اولین اجرای اپ درهم‌زمان با اولین build سرور به کاربر «لیست خالی» نشان می‌دهد تا وقتی خودش «تست دوباره» بزند. دقیقاً بدترین سناریو برای کاربر روز اول.

**رفع:** در نمایش reason=building یک تأخیر ۲–۳ ثانیه‌ای و تلاش مجدد خودکار در repo بگذارید.

### ۸) ناسازگاری متن کارت راهنما با اکشن دکمه‌اش
**فایل:** `android/app/src/main/java/ir/meelano/vpn/ui/AdviceCard.kt`

کارت کنسرو `udp` از سمت سرور می‌گوید «حالت سانسور را «سنگین» کنید» و اکشن `change_regime` می‌فرستد؛ اما وقتی regime فعلی calm/tight است، دکمه «حالت را روی «قطعی» بگذار» را نشان می‌دهد و `blackout` را تنظیم می‌کند — متن یک پله می‌گوید و دکمه دو پله تنظیم می‌کند. یا منطق دکمه باید یک پله بالاتر از وضعیت فعلی برود، یا متن‌کارت باید با وضعیت خواننده سازگار شود.

### ۹) ری‌چک فوریِ اجازه‌ی VPN در Onboarding همیشه false است
**فایل:** `android/app/src/main/java/ir/meelano/vpn/ui/OnboardingScreen.kt`

بعد از `startActivity(VpnService.prepare(ctx))` بلافاصله `vpnGranted = prepare(ctx) == null` اجرا می‌شود؛ کاربر هنوز دیالوگ سیستم را ندیده، پس چک همیشه به false می‌رسد و بعد از برگشت از دیالوگ هم کارت به‌روز نمی‌شود — کاربر باید دوباره دکمهٔ همان مرحله را بزند تا جلو برود (ناقض وعده‌ی «re-checks it; no polling» در کامنت).

**رفع:** نتیجه را با `ActivityResult` در MainActivity به ViewModel برسانید، یا در `onResume` صفحه ری‌چک کنید.

---

## 🔵 جزئی / بهداشتی

| # | محل | مورد |
|---|---|---|
| ۱۰ | `backend/v/lib/AiTune.php` (`cannedAdvice` کلید `tls_reset`) | متن «MTU را روی ۱۲۸ بگذارید» — مقصود **۱۲۸۰** بود (همه‌جای دیگر پروژه ۱۲۸۰ است؛ و کفِ خود clamp در اسکیما ۵۷۶ است). پاسخ مستقیم به کاربر. |
| ۱۱ | `backend/v/lib/Builder.php` | الف) کلید داخلی `cache` وارد JSON عمومی می‌شود (نشت وضعیت کش به کلاینت). ب) بایت‌های gzِ prebuilt با بایت‌های غیر-gz همان ETag متفاوت‌اند (ردهم‌ریختگی کش در سناریوهای میانی). ج) `meta.tuned = count($tuned)` کلید `_regime` را هم می‌شمارد (off-by-one زیبایی‌شناختی). |
| ۱۲ | `android/.../data/NodeUri.kt` | `parseUri` برای `ss` همیشه `network="tcp"` می‌گذارد؛ لینک‌های SIP002 با `type=ws` یا plugin اطلاعات انتقال‌شان را در اپ می‌بازند (سرور همان‌ها را با network واقعی می‌خواند) — لیست DIRECT و لیست سرور برای یک کانفیگ ss متفاوت رفتار می‌کنند. |
| ۱۳ | `backend/v/index.php` و `backend/v/lib/Util.php` | indent نادرست `case 'advice'` (اجرا درست است؛ فقط بهداشت). در `Util::respond` عبارت `$serveGz ? $maxAge : $maxAge` شاخه‌ی بیمعنی است. |
| ۱۴ | `android/.../net/Regime.kt` | کامنت `Ladder.preference` بازه‌ی 0..14 را اعلام می‌کند، امتیاز واقعی 2..16 است (کامنت دروغ). |
| ۱۵ | `android/app/src/main/AndroidManifest.xml` | اکشن‌‌های `MY_PACKAGE_REPLACED` و `QUICKBOOT_POWERON` به فیلتر BootReceiver افزوده نشده‌اند. گیرنده‌ی میراثی `CONNECTIVITY_CHANGE` رویدادی دریافت نمی‌کند (کد مرده در manifest). اسکیم `hy2` برای deep-link در فیلتر نیست، گرچه `NodeUri` آن را می‌خواند. `tools:targetApi="34"` با targetSdk 35 ناهمگام است. |
| ۱۶ | `android/.../keepalive/KeepAlive.kt` (BootReceiver) | فراخوان مستقیم `startService` در مسیر fallback می‌تواند از پس‌زمینه (اندروید ۱۲+) `IllegalStateException` بدهد و wrapنشده است؛ مسیر اصلی WorkManager سالم است ولی fallback باید runCatching شود. |
| ۱۷ | `backend/v/lib/Parser.php` (`parseUri`) | متغیر `$path = ''` مقدارش هیچ‌جا عوض نمی‌شود؛ شرط `$path !== ''` در انتها کد مرده است (نشانه‌ی‌ای که استخراج path برای برخی پروتکل‌ها هرگز انجام نمی‌شود). |
| ۱۸ | `backend/v/lib/Builder.php` (`maskNames`) | مسیر vmess مستقیم از `json_encode` بدون fallback استفاده می‌کند؛ remark با UTF-8 نامعتبر (رایج در لیست‌های عمومی) به `base64_encode(false)` → URI خالی می‌انجامد و نود شکسته منتشر می‌شود (edge). |
| ۱۹ | کامنت `backend/v/data/.htaccess` | عنوان «uploaded APKs» را این‌جا می‌داند، ولی APKها در `backend/v/apk/` می‌نشینند (تضاد مختصر کامنت/واقعیت). |

## ✅ بررسی‌شده و سالم تأییدشده (برجسته‌ترین‌ها)

- **سوئیت بک‌اند ۱۰۴/۱۰۴ سبز روی PHP 8.3.33**: پردازش vless/vmess/trojan/ss (هر دو سبک SIP002 و legacy)، خطوط proxy و JSON؛ sanity gate؛ چرخه‌ی نامزدها؛ prefilter/gate؛ قاعده‌ی flow (vision فقط روی tcp+reality)؛ ledger و رأی‌گیری EWMA (فقط tier=vip در شمارش ledger)؛ ماسک کردن نام‌ها؛ ETag/gzip/304؛ HMAC نسخه؛ رتبه‌بندی.
- تطابق کامل `R.string` و `R.drawable` با ارجاع‌ها؛ آرگومان‌های فرمت (`%1$d`/`%1$s`/`%2$d`) هم‌تراز با کد. تعادل کامنت‌های بلوکی با `check-comments.py`.
- روندی‌کت `FeedJson.encode/decode` دوجهته (alterId/username پس از ری‌استارت گم نمی‌شود؛ پیامی که اشاره کرده بودید در کامنت همان‌جاست).
- `TunePatch`، `NetGuard` (DoH + pinnedClient + padAlpn)، `Diagnostics` (قرارداد دقیق پروب)، مسیرهای warm/cold/DoH در repo، `UpdateManager` (دانلود/تأیید/نصب)، کیل‌سوئیچ SELF_UPDATE — سالم.
- htaccess: admin فقط دو entry-point، data/ کاملاً بسته، apk/ فقط دانلودی بدون لیستینگ.

## ⚪ هشدارهای ردشده (برای جلوگیری از تکرار)

1. «regex شکسته‌ی HOSTNAME/Diagnostics در Diagnostics» — سوءتفاهم escaping خروجی ابزار read بود؛ با شبیه‌سازی اجرایی (Python) الگوها درست بودند.
2. `CI-FAILURE.md` — گزارش اتوماتیک فریزشده بود در تگ؛ مشکل واقعی در ab68f6c برطرف و فایل در b18e58b حذف شده است.

---

## خلاصه‌ی اجرایی

| شدت | تعداد | مهم‌ترین اقدام |
|---|---|---|
| بحرانی | ۳ | گذاشتن RECEIVER_NOT_EXPORTED روی ۱۱۹ سرویس + ترمیم رشته‌ی canonical؛ قبل از انتشار رسمی Core را لینک کنید |
| مهم | ۲ | پذیرفتن `'tls'` رشته‌ای در `tlsOf` + افزودن گیت feedKey به `feedback()` |
| متوسط | ۴ | نظم‌دهی به بودجه‌ی DNS پیش از ساخت تسک‌ها؛ re-poll خودکار در حالت HOST؛ سازگارکردن اکشن/متن AdviceCard؛ ری‌چک درست Onboarding |
| جزئی | ۱۰ | MTU=۱۲۸۰، حذف کلید کش از JSON عمومی، اصلاح manifest/کامنت‌ها |

**باگ‌های جدیدی که این بررسی برای اولین‌بار مستند می‌کند:** موارد ۴، ۵، ۶، ۷ (عمیق‌شده)، ۸، ۹، ۱۰، ۱۱، ۱۲ — با اثبات اجرایی برای ۲ و ۴.
