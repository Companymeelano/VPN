# iOS — M•A VPN

راهنمای کامل: [`docs/IOS-BUILD.md`](../docs/IOS-BUILD.md) (معماری، ساخت، امضا/TestFlight، آزمون میدانی).

**ساخت سریع روی مک:**

```bash
tools/build-libxray-ios.sh     # هستهٔ libXray + تولید MeelanoVPN.xcodeproj
open ios/MeelanoVPN.xcodeproj
```

نکته‌ها:

- پروژه از `project.yml` تولید می‌شود (XcodeGen)؛ `*.xcodeproj` و `Info.plist`/`entitlements`
  تولیدشده در git نیستند (`.gitignore`).
- `LibXray.xcframework` هم در git نیست — همان قاعدهٔ `core.aar` اندروید؛ در همان تگ پین‌شده ساخته می‌شود.
- خروجی CI یک IPA‌ی **امضا‌نشده** است؛ نصب واقعی/TestFlight نیازمند گواهی اپل است (§۵ سند بالا).
- رنگ‌ها/توکن‌ها در `MeelanoVPN/UI/Theme.swift` آینهٔ چهارم پالت‌اند و `tools/check-theme.py`
  هرگونه ناهماهنگی بین آینه‌ها را در بیلد قرمز می‌کند.
