# ساخت نسخهٔ iOS (آیفون) — M•A VPN

> **وضعیت:** فاز ۱ کامل است: اپ SwiftUI، افزونهٔ Packet Tunnel، هستهٔ Xray با همان تگ پین‌شدهٔ اندروید
> (XTLS/libXray)، همان فید `/v/` و همان منطق رتبه‌بندی/Failover. خروجی CI یک **IPA‌ی امضا‌نشده** است.
> برای نصب روی آیفون/TestFlight حساب **Apple Developer** لازم است (§۵). برخلاف APK، هیچ راهی برای
> «نصب آزاد بدون امضا» روی iOS وجود ندارد — این محدودیت پلتفرم است، نه کم‌کاری پروژه.

## ۱. معماری

```
ios/
├── project.yml              ← تنها منبع حقیقت پروژه (XcodeGen آن را به .xcodeproj ترجمه می‌کند)
├── MeelanoVPN/              ← اپ SwiftUI؛ فارسی/راست‌چین؛ Home/سرورها/تنظیمات/عیب‌یابی
│   ├── App/                 ← AppModel + TunnelManager (NETunnelProviderManager)
│   ├── UI/                  ← Theme.swift (آینهٔ چهارم پالت ۱۷ توکنی) + ویوها
│   └── Assets.xcassets/     ← آیکون اپ (از design/icons/ma-vpn-3d.jpg)
├── MeelanoVPNTunnel/        ← PacketTunnelProvider: نصب VPN واقعی + مسیردهی به هسته
├── Shared/                  ← منطق مشترک (هم در اپ، هم در افزونه کامپایل می‌شود)
│   ├── Models/              ← FeedNode + Regime/Tune/Ladder (دوقلوی دقیق net/Regime.kt)
│   ├── Net/                 ← IpFamily، ProbePolicy، TunnelProbe (verdict دومرحله‌ای)
│   ├── Data/                ← NodeUri، NodeRanking، FeedClient، SharedStore (App Group)
│   └── Vpn/                 ← CoreProfiles (سازندهٔ JSON همان دیالکت Xray) + XrayEngine + TunnelSession
└── Tests/                   ← تست‌های منطقی (معادل تست‌های JVM اندروید)
```

- **هسته:** `XTLS/libXray` با همان تگ CalVer اندروید (`LIBXRAY_TAG` در `ios.yml`)، طعم `apple go`
  (cgo). خروجی `LibXray.xcframework` با `libXray.h` + `module.modulemap`؛ سوییفت `import LibXray`
  می‌کند و از `CGoInvoke/CGoFree` همان پاکت JSON استفاده می‌کند:
  `{"apiVersion":3,"method":"runXray","payload":{"xrayJson":"..."}}` — عین پل اندروید.
- **پروفایل Xray:** `CoreProfiles.swift` دوقلوی `CoreApi.kt` است با یک تفاوت پلتفرمیِ صریح:
  inbound به‌جای `tun` اندرویدی، یک `mixed` روی `127.0.0.1:10808` است. فرگمنتِ واقعیِ freedom
  (`packets: tlshello` + `dialerProxy`) فقط برای TLS ساده؛ هرگز Reality. DNS مثل اندروید سه‌تاییِ DoH
  (Cloudflare/Google/AliDNS، ۴ ثانیه‌ای).
- **فید:** همان قرارداد `docs/API-CONTRACT.md` (schema 2): `?action=vip|free`، ETag/304، هدر
  `X-Feed-Key`، 429 با retryAfter، و `?action=feedback` برای بازخورد کیفیت.
- **رتبه‌بندی:** `NodeRanking.bucket` = نردبان ترنسپورت×۱۰۰۰ + جغرافیا×۱۰۰ + پورت×۱۰ — همان
  تابع `Score::iranRank` در بک‌اند و همان `NodeRanking.kt`. بین دو پلتفرم هیچ اختلاف ترتیبی نیست.
- **Failover:** ضربان سلامتی اندروید (شروع ۸ثانیه، سالم ۱۲ثانیه، بازبینی ۳ثانیه پس از اولین شکست،
  دو شکست = Failover). verdict دومرحله‌ای `clean`/`usable` با سه endpoint گوگل/کلودفلر/gstatic
  **از داخل خود تونل** (پروکسی محلی)، نه فقط بازشدن سوکت.

## ۲. مدل ترافیک — چه چیزی واقعاً کار می‌کند

iOS در `NEPacketTunnelProvider` دو راه دارد: یا بسته‌های `packetFlow` را با یک TCP/IP کاربر-فضایی
(tun2socks) ببلعی، یا از `NEProxySettings` استفاده کنی. فاز ۱ **پروکسی-مود** است:

- سیستم‌عامل همهٔ اپ‌هایی را که از پروکسی سیستم اطاعت می‌کنند (سافاری، اکثر اپ‌ها) به
  `127.0.0.1:10808` می‌فرستد؛ **hostname سمت سرور resolve می‌شود** (DNS ریموت) → نشت DNS نداریم.
- مسیر default داخل تونل نصب **نمی‌شود** (عمدی): مسیر بدون خوانندهٔ `packetFlow` یعنی بلک‌هول کردن گوشی.
- **صادقانه:** بازی‌ها/اپ‌های UDP-خام که پروکسی سیستم را نادیده می‌گیرند از تونل رد نمی‌شوند.
  دروازهٔ تمیز برای فاز ۲ (tun2socks روی `packetFlow`) باز گذاشته شده است.

## ۳. ساخت محلی (macOS)

پیش‌نیاز: Xcode 15+، `brew install go xcodegen`، python3.

```bash
# ۱) هسته + پروژه (همه‌کاره)
tools/build-libxray-ios.sh

# ۲) باز کردن در Xcode
open ios/MeelanoVPN.xcodeproj
```

- اجرا روی Simulator بدون امضا ممکن است؛ نصب روی دستگاه واقعی به Development Team و پروفایل
  با قابلیت **Network Extension + App Groups** نیاز دارد (§۵).
- تست‌ها: `xcodebuild test -project ios/MeelanoVPN.xcodeproj -scheme MeelanoVPN -destination 'platform=iOS Simulator,name=iPhone 16'`
- بدون هسته هم پروژه کامپایل می‌شود ولی `XrayEngine.linked == false` است و اتصال با خطای
  نام‌دارِ `core_not_linked` رد می‌شود — «حلقهٔ سبز جعلی» ممنوع، عین سیاست اندروید.

## ۴. CI (`build ios`)

- `structure` (اوبونتو، ۵ ثانیه): `tools/check-swift.py` + آینه‌های پالت (`check-theme.py` حالا
  `Theme.swift` را هم می‌خواند؛ ۱۷ توکن × ۴ آینه).
- `ios` (macos-15): ساخت هسته (کش‌شده با کلید تگ)، تولید پروژه، `xcodebuild test` روی Simulator،
  آرشیو بدون امضا، و بسته‌بندی `meelano-ios-2.6.0-206000-unsigned.ipa` به‌صورت Artifact.
- روی تگ `v*`: اگر Release وجود داشته باشد، همان IPA به Release پیوست می‌شود.

## ۵. امضا، TestFlight و نصب روی آیفون

بدون این مرحله، IPA فقط برای Simulator/آزمایشگاه است. iOS فقط بسته‌های امضاشده با اپل را نصب می‌کند.

1. **حساب Apple Developer** (سالانه، Individual یا Organization).
2. در [developer.apple.com](https://developer.apple.com):
   - App ID با `ir.meelano.vpn` + قابلیت‌های **Network Extension** و **App Groups**؛
   - App ID افزونه `ir.meelano.vpn.tunnel` با همان قابلیت‌ها؛
   - App Group `group.ir.meelano.vpn` برای هر دو؛
   - گواهی Apple Distribution.
3. در [App Store Connect](https://appstoreconnect.apple.com): اپ جدید با Bundle ID بالا.
4. **کلید API اپ‌استور** (App Store Connect → Users and Access → Integrations → App Store Connect API):
   فایل `.p8` را فقط در GitHub Secrets بگذارید، **هرگز در چت/لاگ**:
   - `ASC_KEY_ID` (شناسهٔ کلید)
   - `ASC_ISSUER_ID` (شناسهٔ صادرکننده)
   - `ASC_KEY_P8` (محتوای کامل فایل .p8)
5. ساخت و آپلود (روی مک یا CI):
   ```bash
   xcodebuild archive -project ios/MeelanoVPN.xcodeproj -scheme MeelanoVPN \
     -destination 'generic/platform=iOS' -archivePath build/app.xcarchive \
     -allowProvisioningUpdates
   xcodebuild -exportArchive -archivePath build/app.xcarchive \
     -exportOptionsPlist ExportOptions.plist -exportPath build/export \
     -allowProvisioningUpdates
   xcrun altool --upload-app -f build/export/MeelanoVPN.ipa \
     --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
   ```
   بعد از پذیرش در App Store Connect، نسخه در **TestFlight** ظاهر می‌شود و با لینک دعوت روی هر
   آیفون نصب می‌شود.
6. **آزمون میدانی (اجباری):** روی سیم‌کارت Irancell و MCI — اتصال پایدار ۳۰ دقیقه‌ای، تغییر
   سلول/جابه‌جایی، تست نشت DNS/WebRTC. تا این آزمون انجام نشود، هیچ ادعای «پایداری اثبات‌شده»
   معتبر نیست (همان قاعدهٔ `docs/CORE-INTEGRATION.md` §۵).

## ۶. تفاوت‌های عمدی با اندروید

| | اندروید | iOS فاز ۱ |
|---|---|---|
| مسیر ترافیک | TUN (fd به هسته) | پروکسی سیستمی (`NEProxySettings`) |
| inbound هسته | `tun` | `mixed` روی 127.0.0.1:10808 |
| DNS | DoH سه‌تایی داخل تونل | resolve ریموت در سرور + DoH برای خود هسته |
| هسته | libXray AAR (gomobile) | libXray xcframework (cgo) |
| امضا | keystore اندروید (CI/بوت‌استرپ) | گواهی اپل (TestFlight/App Store) |
| بقیه (فید، رتبه‌بندی، رژیم، فرگمنت، Failover) | یکسان | یکسان |

## ۷. عیب‌یابی سریع

- «اتصال می‌زند ولی چیزی باز نمی‌شود» → صفحهٔ عیب‌یابی را ببین؛ verdict باید `clean` یا `partial`
  باشد. `dead` یعنی زنجیره (هسته/نود/خروجی) کار نمی‌کند — Failover باید خودش نود بعدی را امتحان کند.
- `core_not_linked` → `tools/build-libxray-ios.sh` اجرا نشده یا xcframework در پروژه لینک نشده.
- خطای نصب پروفایل VPN → قابلیت Network Extension در App ID/پروفایل provisioning نیست.
- سوال امضا/Release → هیچ رمز/کلیدی در چت نفرست؛ فقط Secrets مرحلهٔ ۵.
