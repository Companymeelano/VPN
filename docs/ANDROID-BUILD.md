# ساخت APK (و چیزی که هر بیلد واقعاً می‌کند)

این پروژه در محیط توسعه‌ی ما قابل کامپایل **نبود** (Android SDK و Maven Central در دسترس نیستند)،
بنابراین build روی GitHub Actions اجرا می‌شود: `.github/workflows/apk.yml`.
نتیجه‌ی واقعی اجرای فعلی: **`compileDebugKotlin` با یک خطای ۳۵تایی شروع شد و به ۳ خطا رسید**؛
پس کد واقعاً با AGP 8.6.1 + Kotlin 2.0.21 + Compose BOM 2024.09.03 کامپایل می‌شود.

## ۱) APK گرفتن (۳ دقیقه، بدون نصب چیزی روی ماشین تو)
```bash
gh workflow run "build apk" --ref <branch> -f variant=debug -f core_linked=false
gh run list --limit 1
gh run download <run-id> -n meelano-apk-debug       # یا Artifact را از صفحه‌ی Run دانلود کن
adb install -r meelano-*-debug.apk
```
خلاصه‌ی همان Run این‌ها را هم می‌نویسد: `sha256`، حجم، و مسیر پیشنهادی روی هاست.
برای دیدن خطاهای build روی PR (چون لاگ Actions همیشه در دسترس نیست) کامنت ربات را ببین.

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
