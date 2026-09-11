# VPN — فیلترشکن اختصاصی Meelano

**وضعیت این ریپازیتوری:** در `main` فقط `README.md` وجود داشت (بدون حتی یک فایل سورس).
این شاخه (`arena/01a08ba7-vpn`) حالا دو چیز دارد:

1. **بک‌اند قابل‌استقرار روی هاست اشتراکی** — `backend/v/` (خیره‌ی `https://ainetmee.ir/v/`)
2. **کد مرجع اندروید + اسناد فنی/طراحی** — `android/` و `docs/`

سورس اپ هنوز در این ریپو نیست؛ تا اضافه نشود، اتصال نهایی (wire-up) روی دستگاه شما تست نشده است.

## چرا بک‌اند؟
سه مشکلی که گزارش شده بود (هنگی چند ثانیه‌ای بعد از اتصال، اعمال‌نشدن پروکسی رایگان، تست بیش‌ازحد
سخت‌گیرانه) یک ریشه‌ی مشترک دارند: **کار سنگین در لحظه‌ی اتصال روی دستگاه کاربر انجام می‌شود**.
راه‌حل: لیست‌سازی، پارس، پروب، رتبه‌بندی و حذف نام فروشنده به `/_/v/` روی هاست منتقل شد و اپ فقط
یک JSON کوچک با ETag می‌خواند. تشخیص و چک‌لیست کامل: [`docs/FIX-PLAN.md`](docs/FIX-PLAN.md).

```
backend/v/
├── index.php            entry point: ?action=vip|free|version|feedback|health|stats|selftest|refresh
├── vip.php free.php version.php health.php     تخت، برای هاست بدون mod_rewrite
├── config.php           همه‌ی تنظیمات (override: config.local.php)
├── sources.php          منابع استخر رایگان (override: sources.local.php)
├── lib/
│   ├── Util.php         cache اتمی + flock + gzip + ETag + fetch چندتایی + rate-limit + HMAC
│   ├── Parser.php       هر input (txt/html/base64/json) → نود نرمال‌شده
│   ├── Country.php      کشور/پرچم از remark، هاست، یا geo-upstream
│   ├── Probe.php        پروب async (TCP / HTTP CONNECT / SOCKS) با بودجه‌ی زمانی
│   ├── Score.php        ledger + گیت Google/Cloudflare + رتبه‌ی A–D + ban-box
│   ├── Builder.php      خط لوله‌ی vip/free، ماسک‌کردن نام‌ها، کش، degrade-mode
│   ├── Version.php      ایندکس APK + version.json امضاشده
│   └── SelfTest.php     عیب‌یابی استقرار (خروجی json/html)
├── admin/index.php      پنل: paste لیست VIP، منابع، rebuild، آپلود APK، selftest
├── data/                کش و داده‌ها (با .htaccess از وب بسته)
└── apk/                 فایل نصب (عمداً بیرون data/ تا قابل‌دانلود بماند)
```

## اجرا و تست
```bash
# تست‌ها: ۶۵ assertion، بدون نیاز به شبکه (fixture + stub)
php backend/v/tests/run.php

# دستی
curl 'http://localhost/v/?action=health'
curl 'http://localhost/v/?action=vip'
curl 'http://localhost/v/?action=selftest&key=<toolKey>'
```
نیاز: PHP 7.4+ (روی 8.5 هم تست شده). بدون composer، بدون exec، بدون Node، بدون SSH، بدون cron
(بیلد تنبل است؛ cron در cPanel فقط برای تازگی تضمینی اختیاری است).
قدم‌های استقرار روی cPanel: [`docs/BACKEND-DEPLOY.md`](docs/BACKEND-DEPLOY.md) — بعد از آپلود،
**حتماً** `?action=selftest` را اجرا کن: مشخص می‌کند هاست تو اتصال outbound به پورت‌های غیراستاندارد
را باز می‌گذارد یا نه (این تنها چیزی است که تعیین می‌کند تست پروکسی سمت سرور ممکن است یا نه).

## قرارداد
[`docs/API-CONTRACT.md`](docs/API-CONTRACT.md) — شکل پاسخ `vip.json`/`free.json` (یک schema برای هر دو)،
`version.json`، و `feedback`. نکته‌ی کلیدیِ خواسته‌شده: **نام سرویس‌ها در پاسخ وجود ندارد**؛
فقط `"name": "Vip Meelano"` + `"cc": "DE"` که اپ پرچم خودش را از آن می‌سازد.

## اندروید
`android/app/src/main/java/ir/meelano/vpn/**` — یک ماژول Gradle واقعی (نه اسنیپت):

```
android/
  settings.gradle.kts · build.gradle.kts · gradle.properties · gradle/libs.versions.toml
  app/build.gradle.kts            ← FEED_BASE_URL / MEELANO_FEED_KEY / _SECRET / SELF_UPDATE (از gradle -P یا local.properties)
  app/proguard-rules.pro
  app/src/main/AndroidManifest.xml
  app/src/main/java/ir/meelano/vpn/
    MeelanoApp.kt  MainActivity.kt
    vpn/      MeelanoVpnService · VpnOrchestrator · CoreApi (TunnelEngine = نقطه‌ی اتصال به هسته)
    data/     ServerFeedRepository · FeedJson · FeedHolder · TunnelSpec · AppSettings
    ui/       HomeScreen · VpnControlPanel · ServerListSheet · SettingsSheet · UpdateSheet · OnboardingScreen
    ui/theme/ Color (اشیاء Meelano) · Type · Shape · Motion · Theme
    keepalive/KeepAlive · qs/MeelanoTileService · update/UpdateManager
  app/src/main/res/
    values/{colors,dimens,strings,themes}.xml · anim/ · drawable/*.xml (۵۲ پرچم + آیکون‌ها)
    mipmap-anydpi-v26/ic_launcher.xml · mipmap-{m..xxx}hdpi/*.png · font/vazirmatn_*.ttf
    xml/{file_paths,network_security_config,backup_rules,data_extraction_rules,shortcuts}.xml
```

این فایل‌ها اینجا **کامپایل نشده‌اند** (Android SDK در این محیط نیست)؛ راهنمای وصل‌کردن هسته و
چک‌لیست رفع باگ‌ها: [`docs/ANDROID-INTEGRATION.md`](docs/ANDROID-INTEGRATION.md).
توکن‌ها/حرکت/دسترس‌پذیری: [`docs/DESIGN-SYSTEM.md`](docs/DESIGN-SYSTEM.md) — و همان توکن‌ها در
پروتوتایپ زنده: [`docs/PROTOTYPE.md`](docs/PROTOTYPE.md). برند و آیکون: [`docs/BRAND.md`](docs/BRAND.md).

## ساخت APK
APK واقعی در CI ساخته می‌شود (این محیط Android SDK ندارد): `.github/workflows/apk.yml` → Artifact.
توضیح کامل، ورودی‌ها، و معنی `core_linked`: [`docs/ANDROID-BUILD.md`](docs/ANDROID-BUILD.md).

## برای ادامه به چه نیاز دارم
1. سورس اپ (Kotlin/Flutter/…) — پوشه‌ی پروژه، یا دست‌کم: `VpnService`، سازنده‌ی کانفیگ، بخش تست، صفحه‌ی اتصال.
2. مشخص بودن هسته‌ی VPN (tProxy / sing-box / v2rayNG-lib / …) و نسخه‌اش.
3. `minSdk/targetSdk` و اینکه توزیع از Google Play است یا مستقیم/بازار (تصمیم آپدیت خودکار به آن وابسته است).
