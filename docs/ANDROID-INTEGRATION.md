# اتصال کد اندروید (drop-in references)

هشدار صادقانه (به‌روز): `android/**` در CI **کامپایل می‌شود** و تست‌های JVM (`CoreProfilesTest`،
`TrafficTraceTest`) هم اجرا می‌شوند؛ اما روی **دستگاه** آزمایش نشده — رفتار واقعیِ TUN، مصرفِ باتری و foreground
service فقط روی گوشی سنجیده می‌شود. کتب‌خانه‌ها و ترتیب APIها عمداً استاندارد انتخاب شده‌اند؛ مقادیر `R.*`، نام بسته، و بدنه‌ی
`CoreApi` را باید با پروژه‌ات ست کنی. منطق (تردّی‌ها، ترتیب مراحل، کش، ETag، ترتیب اعمال پروفایل)
همان چیزی است که باید منتقل شود.

## نقشه‌ی فایل‌ها
| فایل | مسئولیت | کجا در پروژه‌ات |
|---|---|---|
| `vpn/VpnOrchestrator.kt` | ماشین‌حالت اتصال، همه‌ی کارهای سنگین خارج از ترد اصلی، درصد پیشرفت واقعی | جانشین بدنه‌ی `connect()` فعلی‌ات |
| `vpn/MeelanoVpnService.kt` | سرویس foreground، keep-alive، نوتیفیکیشن با سرعت زنده، coalescing | VpnService فعلی |
| `vpn/CoreApi.kt` + `CoreProfiles` | تنها جایی که «هسته» (tProxy/sing-box/…) را می‌شناسد | adapter |
| `data/ServerFeedRepository.kt` + `data/FeedJson.kt` | خواندن `/v/?action=vip|free`، کش، ETag، feedback، `applyNode` | Repository |
| `ui/VpnControlPanel.kt` | کنترل اتصال بالا صفحه + سرعت + ردیف سرورها | صفحه‌ی اصلی |
| `qs/MeelanoTileService.kt` | کاشی Quick Settings (همان کنترل «بالای گوشی») | سرویس جدید |
| `update/UpdateManager.kt` | چک/دانلود/وریفای/نصب | سرویس آپدیت |
| `keepalive/KeepAlive.kt` | BootReceiver، WorkManager watchdog، باتری، backoff | جدید |
| `MainActivity.kt` + `MeelanoApp.kt` | یک Activity/یک صفحه؛ sheets به‌جای navigation؛ چک نسخه فقط روی Wi‑Fi و فقط با `BuildConfig.SELF_UPDATE=true` | entry point |
| `ui/HomeScreen.kt` | چیدمان واقعی صفحه: هاله‌ی محیطی + کنترل + چیپ نود + بنر آپدیت + ردیف پایین | صفحه‌ی اصلی |
| `ui/ServerListSheet.kt` | لیست Vip/free با آناتومی یکسان، segment، فیلترها، اسکلتون، حالت تهی | sheet سرور |
| `ui/SettingsSheet.kt` · `ui/UpdateSheet.kt` · `ui/OnboardingScreen.kt` | تنظیمات (۵ سوئیچ واقعی) · جریان آپدیت (ماشین‌حالت State) · دو مرحله‌ی اجازه | sheetها |
| `ui/VpnViewModel.kt` · `data/AppSettings.kt` | خواننده‌ی StateFlowهای سرویس؛ تنظیمات هم‌زمان برای tile/receiver | جدید |
| `ui/theme/{Color,Type,Shape,Motion,Theme}.kt` | توکن‌های طراحی به‌عنوان کد (همان اعداد `design/preview/index.html`) | جدید |
| `res/values/*.xml` · `res/drawable/*.xml` · `res/mipmap-*/` · `res/font/` | رنگ/سایز/متن فارسی، ۵۲ پرچم برداری، آیکون adaptive + monochrome، Vazirmatn | منابع |
| `app/build.gradle.kts` · `proguard-rules.pro` · `gradle/libs.versions.toml` | نسخه‌های pinnشده، `buildConfigField`های فید، قوانین R8 | build |
| `app/src/main/AndroidManifest.xml` | manifest کامل و معتبر: permissionها، FGS `specialUse`، tile، FileProvider، BootReceiver، `network_security_config` | merge/جایگزین |

## ۱) «هنگی بعد از اتصال» — چک‌لیست اصلاح روی سورس خودت
- [ ] `onStartCommand` هیچ I/O ندارد: فقط `scope.launch(Dispatchers.Default)` و `return START_STICKY`.
- [ ] `startForeground()` **اول** صدا زده می‌شود (قبل از هر کار)، نه بعد از ساخت پروفایل.
- [ ] هیچ `InetAddress.getByName/getAllByName` روی ترد اصلی/سرویس؛ هر DNS با `withTimeoutOrNull` + `Dispatchers.IO`.
- [ ] هیچ `Gson().fromJson()` روی لیست بزرگ در لحظه‌ی اتصال (این کار را سرور می‌کند؛ اپ یک JSON کوچک می‌خواند).
- [ ] `SharedPreferences.commit()` → `apply()` (یا DataStore).
- [ ] `notify()` حداکثر ۱ بار در ثانیه و فقط وقتی متن واقعاً عوض شده (این مورد روی «کل گوشی» اثر دارد).
- [ ] خواندن fd تونل روی ترد خود هسته؛ `pfd.autoClose = false` + `detachFd()`؛ هیچ `runBlocking` در مسیر UI.
- [ ] `addDnsServer/addRoute` **بعد** از بالا آمدن تونل، با host-route برای IP سرور.
- [ ] بعد از هر تغییر: `dumpsys gfxinfo <pkg> framestats` باید «Jank: 0» بدهد و logcat هیچ `Skipped 30 frames` نداشته باشد.

## ۲) «اعمال نشدن پروکسی» — ترتیب درست
```kotlin
repo.applyNode(ctx, node)          // 1) ذخیره id
    └─ MeelanoVpnService.start()   // 2) stop → بنویس پروفایل → start (همیشه restart، نه hot-patch)
         └─ UI فقط phase را از سرویس می‌خواند   // 3) نه از کلیک کاربر
```
قوانین:
- اگر هسته‌ات APIی مثل `updateVpnConfigure` دارد، فقط وقتی مجاز است که تونل بالاست؛ وگرنه صدا را
  نادیده می‌گیرد و UI «موفق» نشان می‌دهد در حالی که سرور قبلی فعال است (این همان باگ گزارش‌شده است).
- پروفایل را از `node.raw`/`node.configِ` نرمال‌شده بساز، نه از رشته‌ی خام کاربر.
- خطای پارس = `ConnectPhase.Failed` قابل‌دیدن. «سکوت + اتصال سبز» بدترین حالت است.
- `supportsUdp=false` (پروکسی‌های http) ⇒ UDP را از مسیر VPN خارج کن، وگرنه «وصل است ولی هیچ‌چیز باز نمی‌شود».

## ۳) تست را از «آستانه» به «رتبه» تبدیل کن
```kotlin
// بد:  icmp ping، یک تلاش، timeout=۸۰۰ms، باید ۲۰۰ms و ۱۰۰٪ باشد
// خوب: لایه‌لایه + چند endpoint + رأی ۲ از ۳
val r = repo.probe(node, timeoutMs = 3_000)          // TCP connect زمان‌بندی‌شده (بدون ICMP)
val usable = headViaProxy(node, "https://clients3.google.com/generate_204")   // 204
val cf     = headViaProxy(node, "https://1.1.1.1/cdn-cgi/trace")               // "ip="
val grade  = if (usable && cf) scoreGrade(r.latencyMs) else "D"
repo.reportResult(node.id, usable, r.latencyMs, if (usable) null else "gate")
```
- تست **بعد** از اتصال و در بک‌گراند؛ اتصال را بلاک نکند.
- همه‌جا `withTimeoutOrNull` + `launch` روی `Dispatchers.IO`، قابل لغو هنگام disconnect.
- برای کاربر فقط دو حالت نشان بده: «کار می‌کند» و «کُند است» — جزئیات A/B/C در bottom sheet.

## ۴) keep-alive و بسته‌شدن اپ
- سرویس **started** باشد (نه bound-only)، `START_STICKY`، `stopWithTask="false"`،
  `onTaskRemoved` فقط نوتیفیکیشن را نگه دارد.
- `android:process=":vpn"` اختیاری است ولی مؤثرترین ترفند علیه «ببندم، همه بسته شد» است
  (پروسه‌ی UI می‌میرد، تونل می‌ماند). اگر گرفتی، state را از طریق همان `StateFlow` مشترک/`Messenger` بده.
- WorkManager ۱۵ دقیقه‌ای برای «اگر پروسه hard-kill شد»، با `Prefs.activeId` + `ActiveNodeCache`.
- حالت «بهینه‌سازی باتری»: درخواست کن، نه فرض کن. در آنبردینگ یک کارت:
  «اجازه‌ی اجرا در پس‌زمینه» → `KeepAlive.batteryOptimizationIntent()`.
- OEMها (شیائومی/هوآوی/اپو) «auto-start» دارند: با ۳ اسکرین آموزش صادقانه، نه با وعده‌ی فنی.

## ۵) آپدیت خودکار
- `UpdateManager.checkAsync()` در `MainActivity.onCreate` + `WorkManager` (هر ۱۲ ساعت) + `BootReceiver`.
- state‌ها: `Available → Downloading(%) → ReadyToInstall`؛ `BlockedNeedPermission` → پرده‌ی آموزش
  «اجازه‌ی نصب از این منبع» (دکمه‌ی مستقیم به تنظیمات).
- نوتیفیکیشن «دانلود شد — نصب» با `installPendingIntent`؛ یعنی کاربر حتی اگر اپ را بسته باشد،
  یک لمس فاصله دارد.
- `mandatory=true` → پرده‌ی بدون dismiss تا نصب.
- بیلد گوگل‌پلی: این مسیر را `BuildConfig.SELF_UPDATE=false` کن و Play In-App Updates بگذار.

## ۶) BuildConfig مورد نیاز
```gradle
android.buildTypes.each {
    buildConfigField "String", "MEELANO_FEED_BASE", '"https://ainetmee.ir/v"'
    buildConfigField "String", "MEELANO_FEED_KEY",  '"…"'          // == access.feedKey
    buildConfigField "String", "MEELANO_FEED_SECRET", '"…"'        // == config.secret
    buildConfigField "boolean", "SELF_UPDATE", 'true'
}
```
`MEELANO_FEED_KEY` فقط جلوی استفاده‌ی رایگان دیگران از لیست VIP را می‌گیرد (نه امنیت واقعی؛
هر کلیدِ داخل APK قابل استخراج است). برای VIP جدی: کوپن/توکن سرور + انقضا.
