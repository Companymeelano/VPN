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
سه asset ساخته می‌شود: APK، `.sha256` کنارش، و آیکون ۵۱۲. **variant از خودِ نام تگ استنتاج می‌شود**
(چون dispatch به اجازه‌ی `actions:write` نیاز دارد و push تگ نه):

| تگ | variant | امضا | ریلیز |
|---|---|---|---|
| `v2.5.0-beta.1` / `-rc.1` / `-alpha` | `debug` | کلید debug (applicationId با پسوند `.debug`) | `--prerelease` |
| `v2.5.0` (تمیز، بدون پسوند) | `release` | **keystore پروژه** (secretهای `MEELANO_*`) | ریلیز عمومی، `Latest` |

یعنی «تگ تمیز = انتشار نهایی» و «تگ با پسوند = پیش‌انتشار آزمایشی». برای همین اگر
`MEELANO_KEYSTORE_B64` ست نشده باشد، push یک تگ تمیز **می‌شکند** (با پیامِ روشن) — بهتر از این‌که
بی‌صدا یک APK دیباگ را به‌جای نسخه‌ی نهایی منتشر کند. ساخت keystore در §۳-ب.

```bash
git tag -a v2.5.0 -m "M•A VPN v2.5.0" && git push origin v2.5.0
gh release view v2.5.0 --web               # یا: curl -L -o app.apk <download-url>
adb install -r app.apk
```
**راه دوم: Artifact از همان Run** (بدون ریلیز؛ برای تست‌های زودگذر):
```bash
gh run list --limit 1 && gh run download <run-id> -n meelano-apk-debug
```

**تمرینِ release بدون انتشار.** قبل از سوزاندنِ یک تگ، برچسب `release-dry-run` را روی PR بگذار:
همان مسیر کامل release اجرا می‌شود (R8/minify + امضا + `apksigner verify`) و APK **امضاشده** را
به‌عنوان artifact می‌دهد، ولی از PR هیچ‌وقت ریلیز منتشر نمی‌شود:
```bash
gh label create release-dry-run                 # یک‌بار
gh pr edit <pr> --add-label release-dry-run     # همین لحظه یک ران می‌سازد (types شامل labeled است)
gh run watch && gh run download <run-id> -n meelano-apk-release
```
اگر برچسب را بعد از شروعِ ران گذاشتی، push بعدی روی همان شاخه (رویداد `synchronize`) کافی است.
وضعیت `variant/core/dry-run` تصمیم‌گیری‌شده در اولین step، در summary همان run چاپ می‌شود.

`gh workflow run "build apk" --ref <branch> -f variant=debug -f core_linked=false` هم هست، ولی
dispatch کردن به اجازه‌ی `actions: write` نیاز دارد که توکن ربات‌های محیط توسعه معمولاً ندارند؛
push تگ همان کار را با اجازه‌ی push معمولی می‌کند — برای همین ماشه‌ی اصلی تگ است. (اگر dispatch
کنی، ورودیِ دستی همیشه برنده است؛ `variant=release` با `core_linked=false` عمداً رد می‌شود، چون
بیلد بی‌هسته VPN نیست.)

خطاهای build را workflow به‌صورت **کامنت روی PR** برمی‌گرداند (با لیست نامزدهای اورلود، چون لاگ
Actions از همه‌جا قابل دانلود نیست). اگر فقط workflow را ویرایش می‌کنی: **کلید تکراری در YAML**
(مثلاً دو `push:` زیر `on:`) را PyYAML بی‌صدا تحمل می‌کند ولی GitHub کل workflow را «not valid»
می‌کند و اصلاً job نمی‌سازد — قبل از پوش، تکراری‌بودن کلیدها را چک کن.

## ۲) ورودی‌های workflow
| ورودی | پیش‌فرض | معنا |
|---|---|---|
| `variant` | خالی | خالی = از نام تگ استنتاج شود (جدول §۱). `release` به `MEELANO_KEYSTORE_B64` و سه secret دیگر نیاز دارد |
| `feed_base` | `https://ainetmee.ir/v` | همان `MEELANO_FEED_BASE` |
| `feed_key` | خالی | هدر `X-Meelano-Key` برای فید VIP |
| `core_linked` | `false` | **مهم‌ترین**: آیا هسته‌ی تونل به این بیلد وصل شده؟ (روی تگ همیشه وصل است) |
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

### ۳-ب) keystore برای نسخه‌ی نهایی (یک‌بار برای همیشه)
بدون این چهار secret، تگ تمیز (`v2.5.0`) عمداً می‌شکند؛ با آن‌ها همان تگ یک ریلیز **امضاشده**
می‌سازد. کلید را روی همان ماشینی بساز که به آن دسترسی داری و **فایلش را برای همیشه نگه دار**
(اگر گم شود یا عوض شود، هر نصبِ قبلی فقط با حذف/نصب دوباره آپدیت می‌شود):

**راه کوتاه (اسکریپت، پیشنهادی):** کلید و رمزها را یک‌جا برایت می‌سازد، در پوشه‌ای **بیرون از
مخزن** می‌گذارد (تا اشتباهی کامیت نشوند)، فایل اعاده‌ی رمز می‌نویسد و چهار secret را ست می‌کند:
```bash
./tools/make-signing-key.sh
# اختیاری: MEELANO_FEED_SECRET='…' ./tools/make-signing-key.sh   # مرحله‌ی ۴
```
اگر کلیدِ موجودی داری (JKS یا PKCS12)، فقط مرحله‌ی ۳ را دستی بزن: CI نوع keystore را از جادوی
فایل تشخیص می‌دهد، پس `.jks` قدیمی هم کار می‌کند.

**راه دستی** (همان کارها، اگر ترجیح می‌دهی خودت کنترل کنی):
```bash
STORE_PASS='یک-رمز-قوی'          # و KEY_PASS و FEED_SECRET هم همین‌جا تعریف کن
KEY_PASS='یک-رمز-قوی-دیگر'
# ۱) کلید (JDK 17؛ خروجی پیش‌فرضِ keytool در نسخه‌های جدید PKCS12 است و Gradle همان را می‌خواند)
keytool -genkeypair -v -keystore meelano.keystore -alias meelano \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=M•A VPN, O=Meelano, C=IR"
# ۲) چهار secret (رمز از stdin می‌رود، نه در argv — در `ps` دیده نمی‌شود)
openssl base64 -A -in meelano.keystore > ks.b64 && gh secret set MEELANO_KEYSTORE_B64 < ks.b64 && rm ks.b64
printf '%s' "$STORE_PASS" | gh secret set MEELANO_STORE_PASS
printf '%s' "$KEY_PASS"   | gh secret set MEELANO_KEY_PASS
printf '%s' meelano       | gh secret set MEELANO_KEY_ALIAS
# ۳) (اختیاری ولی توصیه‌شده) کلید HMAC برای امضای version.json
printf '%s' "$FEED_SECRET" | gh secret set MEELANO_FEED_SECRET
```
> نکته‌ی دسترسی: ست‌کردن secret از API دسترسیِ **admin** روی مخزن می‌خواهد. توکن‌های رباتِ محیط
> توسعه‌ی معمول `actions:secrets` ندارند (۴۰۳) — این کار باید با حسابِ خودت انجام شود.
چند نکته‌ی عملی:
- `-validity 10950` ≈ ۳۰ سال: Google Play نمی‌خواهد ولی توزیع مستقیم نباید وسط راه منقضی شود.
- رمزها را در `android/local.properties` یا password manager بگذار — **نه** در گیت (فایل
  `.gitignore` مخزن دقیقاً برای همین گذاشته شده).
- اثر گواهی (SHA-256) در یادداشت هر ریلیز چاپ می‌شود؛ ریلیز بعدی باید همان اثر را بدهد، وگرنه
  آپدیت روی نصب قبلی رد می‌شود. برای تبدیل keystore قدیمی `.jks` به PKCS12:
  `keytool -importkeystore -srckeystore old.jks -destkeystore meelano.keystore -deststoretype pkcs12`.
- اگر APK نهایی با کلید debug در بیاید (مثلاً `keystore.properties` ساخته نشده)، CI **قرمز
  می‌شود**: `collect + hash` اثر گواهی APK را با کلید debug مقایسه می‌کند. این عمدی است — بهتر
  از این‌که کاربر «به‌روزرسانی نصب نشد» ببیند و ما حدس بزنیم چرا.

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
| Release | `https://github.com/Companymeelano/VPN/releases/tag/v2.5.0-beta.1` |
| APK (asset) | `https://github.com/Companymeelano/VPN/releases/download/v2.5.0-beta.1/meelano-2.5.0-205000.apk` |
| حجم / sha256 | ۶۴٬۰۹۴٬۴۶۶ بایت · `51a5da161865d4966b862b198cdec486a5da75a95fa6a56b4470b979cb34534f` |
| کنارش | `…meelano-2.5.0-205000.apk.sha256` (۶۵ بایت = ۶۴ هگز + newline — همان چیزی که `Version.php::checksum()` می‌خواند) |
| universal (arm64 + v7a) | `…meelano-2.5.0-205000-universal.apk` · ۱۳۰٬۹۵۲٬۰۲۱ بایت · `de1fb48ff1907f3f89a0c0c5a3485f25b5bd069223c016b13ba053a9c796e20a` |
| نام روی هاست | همان فایل، بدون تغییرنام: `public_html/v/apk/meelano-2.5.0-205000.apk` |
| چه چیزی نسبت به ۲٫۳٫۲ فرق کرد | هسته‌ی تونل واقعاً وصل شد (libXray v26.9.9 + تپِ اتصال با نردبان تا برقراری)، پروکسی‌ها end-to-end تأیید و فِیل‌اُور خودکار می‌شوند، تقسیم مسیر برنامه‌ها (اسنپ/دیوار/بانک‌ها بیرون از تونل — `docs/APP-ROUTING.md`)، و بیلدِ نصب‌شده با `BUILD_ID` در دیباگ/تنظیمات شناسایی می‌شود |
| وضعیت هسته | `CORE_LINKED=true` — bridge `libXray.LibXray` · core.aar sha256 `7ac5db44426e1d7c4570d408355aa0a7a93e224e198331cc23af82a7ebfe2911` (`docs/CORE-INTEGRATION.md` §۵) |

بیلد `v2.5.0-beta.1` روی کامیت `443c941` است (`release: v2.5.0 (versionCode 205000)`) و چون تگش
`beta` دارد، خودکار `--prerelease` منتشر شده. نسخه‌ی قبلی هسته‌وصل: `v2.4.5` →
`meelano-2.4.5-204005.apk` (۶۴٬۰۵۶٬۲۳۶ بایت · `d03198925ff3ad57f436fb4670467e261256a84530197f772df46ebcde4e4d08`).

نسخه‌های قبلی (برای QA مقایسه‌ای): `v2.3.1-beta.1` → `meelano-2.3.1-203001.apk`
(۱۲٬۹۶۷٬۹۷۴ بایت · `47530774d7ab1315cbfc3ca5975f6bdc9ae2ca46e233e6ef24a9d6c875683d6b`) —
**دربارهٔ `v2.3.2-beta.1`:** همان `versionCode` و همان نامِ فایل را با محتوایِ دیگر داشت (قبل از دو
اصلاحِ آخر بیلد شده بود)، پس حذف شد تا هیچ‌کس «همان فایل، چیزِ دیگر» را نصب نکند. نسخه‌ها را با
تگِ تازه منتشر کن، نه با بازنویسیِ فایلِ یک تگِ موجود.
`v2.3.0-beta.2` → `meelano-2.3.0-203000.apk`
(۱۲٬۹۶۷٬۹۷۴ بایت · `e2082837ee3dfe00b101f93d2210e09c76114ee6dc77d1991ceb50e94f6df7eb`)،
`v2.2.0-beta.1` → `meelano-2.2.0-202000-debug.apk`
(۱۲٬۹۳۳٬۸۶۶ بایت · `2345c00c47df926fcfcc75e268887757886caf5485b87f7b243a1c1dafc840df`) و
`v2.0.0-beta.1` → `meelano-2.0.0-200000-debug.apk` (۱۲٬۲۸۷٬۹۷۸ بایت ·
`819a69be5cafc9317b994e98de66d07744c0777316fe63be37fb6e1a3b5b1f80`). آن دو asset را با پسوند `-debug`
ساخته بودند و `Version.php` آن نام را رد می‌کرد؛ از این نسخه پسوندِ variant در نام فایل نیست.

REPO **عمومی** است (تأیید بدونِ توکن: صفحه‌ی ریلیز و خودِ asset هر دو بدون لاگین `302/200` می‌دهند)،
پس همین لینک‌های GitHub «قابل دانلود عمومی»اند و می‌شوند مستقیم به کاربر داد. آنچه از این لینک
به‌دست *نمی‌آید* آپدیتِ خودکار داخل اپ است: `version.json` روی `ainetmee.ir` ساخته می‌شود و `apkUrl`
باید به هاست اشاره کند — پس برای جریان آپدیت، فایل را با همان نام در `public_html/v/apk/` بگذار (§۵).
برای توزیع انبوه هم مسیر هاست ترجیح دارد؛ GitHub برای تست داخلی و QA مناسب است.
