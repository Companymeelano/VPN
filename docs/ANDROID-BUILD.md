# ساخت APK (و چیزی که هر بیلد واقعاً می‌کند)

این پروژه در محیط توسعه‌ی ما قابل کامپایل **نبود** (Android SDK و Maven Central در دسترس نیستند)،
بنابراین build روی GitHub Actions اجرا می‌شود: `.github/workflows/apk.yml`.
**وضعیت فعلی: سبز.** حلقه‌ی CI این مسیر را طی کرد — ۳۵ خطا → ۳ → ۱ → **کامپایل موفق**، و
اولین APK واقعی با تگ `v2.0.0-beta.1` منتشر شد (`meelano-2.0.0-200000-debug.apk`، ۱۲٬۲۸۷٬۹۷۸ بایت،
sha256 `819a69be…3b5b1f80`). پس کد واقعاً با AGP 8.6.1 + Kotlin 2.0.21 + Compose BOM 2024.09.03
کامپایل می‌شود؛ «کامپایل‌نشده بودن» دیگر یک فرض نیست.

از این نسخه به بعد CI **پیش از** ساخت APK، `testDebugUnitTest` را اجرا می‌کند؛ پس کامپایلِ `vpn/**` و
منطقِ کانفیگ‌ساز همان‌جا می‌سوزد، نه بعد از دوازده دقیقه بیلد. چهار خطایی که فقط کامپایلور واقعی می‌گیرد
و بازبینی متنی نمی‌گیرد، برای تجربه:
`VpnService.protect()` اورلود `FileDescriptor`/`ParcelFileDescriptor` **ندارد** (فقط `int`، `Socket`،
`DatagramSocket`)، و `Builder` هیچ `addExcludedRoute` ندارد (مسیر خارج از تونل = `protect()` یا
`addDisallowedApplication`). دو موردِ تازه که مستقیماً از لاگِ بیلدِ قرمز آمد:

- **کامنت بلوکی در Kotlin تودرتو است.** نوشتن `app/libs/*.aar` داخل یک KDoc یک کامنتِ دوم باز می‌کند؛
  نتیجه «Unclosed comment» در آخرین خط فایل است و بقیه‌ی فایل‌ها سیل `Unresolved reference` جعلی.
- **`LinkedHashMap<K,V>(pair, pair)` وجود ندارد** (سازنده‌اش `initialCapacity, loadFactor` است و
  کامپایلور می‌گوید «Int/Float expected»). `linkedMapOf<K,V>(pair, …)` همان چیزی است که می‌خواستی.

هر دو با یک اسکنِ تودرتو روی `/**/*.kt` قبل از پوش گرفتن گرفته می‌شوند.

## ۱) APK گرفتن — دو راه
**راه اول: تگ بزن (ریلیز خودکار، لینک ثابت).** هر `v*` که پوش کنی، بیلد گرفته و یک Release با
سه asset ساخته می‌شود: APK، `.sha256` کنارش، و آیکون ۵۱. تگ‌های `alpha/beta/rc` خودکار
`--prerelease` می‌شوند.
```bash
git tag -a v2.0.0-beta.2 -m "…" && git push origin v2.0.0-beta.2
gh release view v2.0.0-beta.2 --web        # یا: curl -L -o app.apk <download-url>
adb install -r app.apk
```
**راه دوم: Artifact از همان Run** (بدون ریلیز؛ برای تست‌های زودگذر):
```bash
gh run list --limit 1 && gh run download <run-id> -n meelano-apk-debug
```
`gh workflow run "build apk" --ref <branch> -f variant=debug -f core_linked=false` هم هست، ولی
dispatch کردن به اجازه‌ی `actions: write` نیاز دارد که توکن ربات‌های محیط توسعه معمولاً ندارند؛
push تگ همان کار را با اجازه‌ی push معمولی می‌کند — برای همین ماشه‌ی اصلی تگ است.

خطاهای build را workflow به‌صورت **کامنت روی PR** برمی‌گرداند (با لیست نامزدهای اورلود، چون لاگ
Actions از همه‌جا قابل دانلود نیست). اگر فقط workflow را ویرایش می‌کنی: **کلید تکراری در YAML**
(مثلاً دو `push:` زیر `on:`) را PyYAML بی‌صدا تحمل می‌کند ولی GitHub کل workflow را «not valid»
می‌کند و اصلاً job نمی‌سازد — قبل از پوش، تکراری‌بودن کلیدها را چک کن.

## ۲) ورودی‌های workflow
| ورودی | پیش‌فرض | معنا |
|---|---|---|
| `variant` | `debug` | `debug` با کلید دیباگ امضا می‌شود (روی گوشی تست نصب می‌شود)؛ `release` به `MEELANO_KEYSTORE_B64` و سه secret دیگر نیاز دارد |
| `feed_base` | `https://ainetmee.ir/v` | همان `MEELANO_FEED_BASE` |
| `feed_key` | خالی | هدر `X-Meelano-Key` برای فید VIP |
| `core_linked` | `false` | **مهم‌ترین**: آیا هسته‌ی تونل به این بیلد وصل شده؟ |
| `self_update` | `true` | آپدیت خودکار در این بیلد فعال باشد؟ |

### `core_linked=false` یعنی چه (و چرا عمداً ساخته شده)
`CoreApi` تنها فایلیداننده‌ی «هسته» است (tProxy / sing-box / …). تا وقتی آن را وصل نکرده‌ای،
بیلد ** وانمود نمی‌کند **: `startProxy()` استثنا می‌دهد و صفحه به‌جای حلقه‌ی سبز می‌نویسد
«هسته‌ی تونل در این بیلد وصل نشده است». پس APK دیباگ برای این‌ها کاملاً واقعی و قابل‌آزمایش است:
فید VIP/free، تست و رتبه‌بندی محلی، انتخاب/سنجاق نود، keep-alive و کاشی QS، نوتیفیکیشن با سرعت زنده،
جریان آپدیت (دانلود + HMAC + پرامپت نصب)، صفحه‌ی شروع و تنظیمات — و فقط «تونل» خالی است.
`assembleRelease` با همین flag در gradle **رد می‌شود** تا کسی از روی عادت APK ناکارآمد منتشر نکند.

## ۳) وصل‌کردن هسته (تنها کاری که بین «UI آماده» و «VPN واقعی» فاصله است)
1. در `android/app/build.gradle.kts` خط وابستگی را باز کن (tProxy یا sing-box؛ نمونه در همان‌جا).
2. `vpn/CoreApi.kt` را پر کن: `startProxy` (سازنده‌ی core + `setVpnConfigureConfig(profileJson)`)،
   `attachFd` (دادن fd تونل)، `tunnelUp` (قاعده‌های routing/DoH)، `stop`. فقط همین ۵ نقطه.
3. `CoreProfiles.toJson()` پروفایل sing-box را می‌سازد؛ اگر هسته‌ات فرمت دیگری می‌خواهد، همان‌جا عوض شود.
4. `protectOutbound(fd)` را روی سوکتِ خروجیِ خودِ هسته صدا بزن (نه `addRoute` برای IP سرور —
   توضیح کامل در `vpn/VpnOrchestrator.kt`؛ این همان اشتباهی است که «حلقه‌ی تونل» و یخ‌زدگی می‌سازد).
5. بیلد با `-PMEELANO_CORE_LINKED=true`.

## ۴) ساخت محلی (اگر Android Studio داری)
```bash
cd android
gradle wrapper --gradle-version 8.9        # مخزن عمداً gradle-wrapper.jar را نگه نمی‌دارد
./gradlew :app:assembleDebug --stacktrace
# کلید/سِرِت‌ها (اختیاری) در android/local.properties:
#   MEELANO_FEED_KEY=...   MEELANO_FEED_SECRET=...   (git-ignore شده)
```
SDK لازم: `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`.

## ۵) انتشار روی هاست اشتراکی (بدون Google Play)
1. نام فایل باید الگوی `meelano-<versionName>-<versionCode>.apk` باشد؛ `backend/v/lib/Version.php`
   از روی همین نام version.json را می‌سازد (هیچ فایل دستی لازم نیست).
2. فایل را در `public_html/v/apk/` بگذار (روی همان هاست، `.htaccess` پوشه اجازه‌ی دانلود می‌دهد).
3. در پنل ادمین `/v/admin/` می‌توانی `mandatory`، `changelogFa` و `signature` را تنظیم کنی؛
   اگر `MEELANO_FEED_SECRET` را در بیلد گذاشته باشی، کلاینت HMAC فایل را چک می‌کند و فایل دست‌کاری‌شده نصب نمی‌شود.
4. نصب بی‌صدا ممکن نیست (Device Owner لازم است و در Play ممنوع)؛ اپ فایل را می‌گیرد، سالم‌اش را تأیید
   می‌کند و **یک** پرامپت نصب نشان می‌دهد. متن UI همین را promise می‌کند، نه «خودکار نصب می‌شود».

## ۶) چک‌لیست قبل از اعلام «نسخه‌ی نهایی»
- [ ] `core_linked=true` و تونل روی یک دستگاه واقعی با LTE + Wi‑Fi تست شده.
- [ ] بستن اپ از Recent Apps → اتصال بماند (هدف اصلی رفع باگ).
- [ ] `adb shell dumpsys gfxinfo ir.meelano.vpn debuginfo` بعد از اتصال: بدون `Skipped frames`.
- [ ] `?action=selftest` روی هاست سبز، و `vip.json`/`free.json` هر دو با `name: "Vip Meelano"`.
- [ ] `adb logcat -s MeelanoFeed MeelanoVpn` بدون `notify()` بیش از ۱ بار در ثانیه.
- [ ] اگر `versionCode` را بالا بردی، `version.json` هم تازه شده باشد (وگرنه آپدیت بی‌صدا no-op است).

## ۷) لینک‌های نسخه‌ی فعلی
| مورد | مقدار |
|---|---|
| Release | `https://github.com/Companymeelano/VPN/releases/tag/v2.3.0-beta.2` |
| APK (asset) | `https://github.com/Companymeelano/VPN/releases/download/v2.3.0-beta.2/meelano-2.3.0-203000.apk` |
| حجم / sha256 | ۱۲٬۹۶۷٬۹۷۴ بایت · `e2082837ee3dfe00b101f93d2210e09c76114ee6dc77d1991ceb50e94f6df7eb` |
| کنارش | `…meelano-2.3.0-203000.apk.sha256` (۶۴ هگز خام — همان چیزی که `Version.php::checksum()` می‌خواند) |
| نام روی هاست | همان فایل، بدون تغییرنام: `public_html/v/apk/meelano-2.3.0-203000.apk` |
| در این نسخه | نمودارِ سرعت با نشانگرِ رویداد، گزارشِ عیب‌یابیِ قابل‌ارسال، کانفیگ‌سازِ موتور-آگاه با ۲۴ تست JVM، صفحهٔ وضعیتِ عمومی، آیکون `M•A` و تمِ روشن، پیش‌فرض‌های ضدفیلترِ ایران |
| وضعیت هسته | `CORE_LINKED=false` — تونل وصل نیست؛ برای ریلیز کاربران `core_linked=true` لازم است (`docs/CORE-INTEGRATION.md` §۵) |

نسخه‌های قبلی (برای QA مقایسه‌ای): `v2.2.0-beta.1` → `meelano-2.2.0-202000-debug.apk`
(۱۲٬۹۳۳٬۸۶۶ بایت · `2345c00c47df926fcfcc75e268887757886caf5485b87f7b243a1c1dafc840df`) و
`v2.0.0-beta.1` → `meelano-2.0.0-200000-debug.apk` (۱۲٬۲۸۷٬۹۷۸ بایت ·
`819a69be5cafc9317b994e98de66d07744c0777316fe63be37fb6e1a3b5b1f80`). آن دو asset را با پسوند `-debug`
ساخته بودند و `Version.php` آن نام را رد می‌کرد؛ از این نسخه پسوندِ variant در نام فایل نیست.

REPO خصوصی است، پس این لینک‌ها با لاگین GitHub باز می‌شوند. برای اینکه لینک «عمومی» باشد یا
`version.json` همان لینک را نشان دهد، فایل را روی `ainetmee.ir` بگذار (این بخش §۵) — لینک GitHub
برای تست داخلی و QA ساخته شده، نه برای توزیع انبوه.
