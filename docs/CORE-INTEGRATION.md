# وصل‌کردنِ هسته‌ی تونل (از «CORE_LINKED=false» تا بیلدی که واقعاً ترافیک می‌برد)

> **وضعیت از v2.4.0-beta.5:** انجام شد. CI هسته را با **XTLS/libXray @ v26.9.9** (MIT؛ حامل
> Xray-core تحت MPL-2.0 — هر دو مجاز در اپ بسته‌مصدر) می‌سازد، AAR را در `app/libs/core.aar`
> می‌گذارد، و با `javap` قرارداد را تأیید می‌کند (`invoke` + `registerDialerController` +
> `setDNS` + `resetDNS`)، بعد gradle با `MEELANO_CORE_LINKED=true` و کلاسِ استخراج‌شده بیلد
> می‌گیرد. `XrayBridge` روی API واحدِ `Invoke(requestJSON)` پیاده‌سازی شده — شروع دو‌مرحله‌ای:
> `start()` کنترلرِ protect و DNS را می‌نشاند، `bind()` عددِ fd را در `env."xray.tun.fd"` داخل
> کانفیگ می‌نویسد و `runXray` را صدا می‌زند. اینبaund کانفیگ `protocol=tun` است (خواندنِ مستقیم
> از fd؛ درگاهِ سوکَس 10808 فقط برای لهجه‌ی sing-box مانده).
>
> بقیه‌ی سند — قراردادها، ردیابی باگ، ملاک‌های پذیرش — به‌روز است و حفظ می‌شود.

این تنها سندِ فازِ بعدی بود: همه‌چیزِ این repo — فید، تست، رتبه‌بندی، رژیم‌ها، آپدیت، کاشی، UI —
بدونِ هسته هم کار می‌کرد و **عمداً** ادعای اتصال نمی‌کرد (و در بیلدهای coreless هنوز نمی‌کند).

---

## ۰) قانونِ پایه

تا لحظه‌ای که job هسته در CI واقعاً AAR بسازد و javap قرارداد را تأیید کند، **هیچ** مسیری نباید
`CORE_LINKED=true` بگیرد. دلیلش تجربه‌ی همین پروژه است: یک حلقه‌ی سبزِ دروغین، از حلقه‌ی قرمز
بدتر است، چون کاربر تنظیمات را دست می‌زند و مشکل را پیدا نمی‌کند. در بیلدهای بدون AAR،
`CoreApi.startProxy` عمداً `CoreNotLinked` می‌اندازد و `build.gradle.kts` عمداً `assembleRelease`
را رد می‌کند.

## ۱) انتخاب موتور (این تصمیم برگشت‌ناپذیر است)

| | Xray-core (`XTLS/libXray`) | sing-box / SagerNet |
|---|---|---|
| لایسنس | **MPL-2.0** — در اپ بسته‌مصدر مجاز است | **GPL‑3.0+** — لینک‌کردن، کل اپ را GPL می‌کند |
| Reality + Vision | بومی (همینجا بهترین است) | دارد (Reality) |
| تکه‌تکه‌سازی ترنسپورت | ندارد (باید fork یا حذف) | دارد (`fragment`) |
| شکل کانفیگ | `streamSettings` / `realitySettings` | `transport` / `tls.reality` / `fragment` |
| انتخاب پروژه | **پیش‌فرض** (`MEELANO_CORE_ENGINE=xray`) | `=sing-box` |

نکته‌ای که در کد هم نوشته شده: `CoreProfiles` هر دو لهجه را می‌سازد و از
`BuildConfig.CORE_ENGINE` می‌خواند؛ پس انتخاب موتور یک فلگِ زمانِ بیلد است، نه یک `if` زمانِ اجرا.
اگر موتور را وسطِ آپدیت عوض کنید، باگ‌های کانفیگ بازتولیدناپذیر می‌شوند.

## ۲) ساختنِ AAR و پیدا‌کردنِ نامِ کلاس

روی یک لپ‌تاپ (نه روی هاست؛ هاست شما PHP است و ابزار ساخت ندارد):

```bash
git clone --depth 1 https://github.com/XTLS/libXray
cd libXray
python3 build/main.py android        # خروجی: AAR با libxray.so برای arm64-v8a
```

سپس نامِ کلاسِ واگرفکننده را **استخراج** کنید، نه حدس:

```bash
cp XrayCore-*.aar android/app/libs/core.aar
cd android/app/libs && unzip -o -q core.aar classes.jar
javap -classpath classes.jar -public | grep -nE "class .*(Xray|Box)"
# سپس برای همان کلاس:
javap -classpath classes.jar -public life.xtls.<...>.Xray | grep -E "startLocal|stopLocal|ktBindTun|requireVersion"
```

آن نام را به‌عنوان ورودیِ بیلد بدهید (هیچ‌جا در کد هاردکد نشده، چون هر fork می‌تواند عوضش کند):

```
MEELANO_CORE_BRIDGE_CLASS=life.xtls.<...>.Xray
```

`android/app/build.gradle.kts` آن را به `BuildConfig.CORE_BRIDGE_CLASS` می‌ریزد و `XrayBridge` فقط همان
را با `Class.forName` بار می‌کند. اگر تنظیم نکنید، `XrayBridge.describe()` دقیقاً می‌گوید چه چیزی کم است.

## ۳) ده خطی که همه‌چیز را وصل می‌کند — **پیاده‌شده**

`XrayBridge` با reflection روی API «Invoke» پیاده شد (`libXray v26.9.9`؛ گوموبایل همه‌چیز را در یک
کلاس `libxray.LibXray` می‌گذارد که CI آن را با javap از داخل جار استخراج می‌کند):

- `start()` — `registerDialerController` + `registerListenerController` با یک Proxy جاوا که
  `VpnService.protect(fd)` را برمی‌گرداند (پاد‌حلقه)، سپس `setDNS(controller, "1.1.1.1:53")`.
- `bind()` — fd خام را می‌خواند، در `env."xray.tun.fd"` ریشه‌ی کانفیگ می‌نویسد، و
  `{"apiVersion":3,"method":"runXray","payload":{"xrayJson":…}}` را invoke می‌کند.
  (این جایگزینِ رسمیِ `SetTunFd` حذف‌شده است؛ ترتیبِ config ← establish ← bind حفظ شده چون
  خودِ موتور همین‌جا شروع می‌شود، نه قبل‌تر.)
- `stop()` — `stopXray` + `resetDNS()`؛ نخِ اصلی هرگز (ارکستراتور تضمین می‌کند).
- خطاها همه به‌صورت `Missing(why)` بالا می‌آیند تا AdviceCard دلیل بگوید، نه «وصل شدم ولی نه».

در CI بیلد هسته (تگ‌ها و dispatch با `core_linked=true`):

```bash
git clone --depth 1 --branch "$LIBXRAY_TAG" https://github.com/XTLS/libXray
cd libXray && python3 build/main.py android       # خروجی: libXray.aar (16KB-page ready)
cp libXray.aar android/app/libs/core.aar
# سپس در همان workflow: استخراج کلاس + javap-gate + -PMEELANO_CORE_LINKED=true
```

بیلد محلی بدون کلونِ CI هم کار می‌کند (همان مراحل دستی + دو پِراپرتی `-PMEELANO_...`).

## ۴) پیمانِ فراخوانی‌ها (جایی که بیشترِ باگ‌ها هست)

- **ترتیب**: کانفیگ ← `establish()` ← اتصالِ fd ← `onTunnelUp()`. دلیلش در `CoreApi` کامنت شده؛
  برعکسِ آن یعنی فیلدی که هنوز وجود ندارد.
- **`protect()`**: هسته **قبل از connect** باید سوکتِ خروجی‌اش را protect کند
  (`VpnOrchestrator.protectOutbound` → `VpnService.protect`)؛ فقط سه اورلود دارد
  (`int`, `Socket`, `DatagramSocket`). اگر این را از قلم بیندازی، تونل به خودش زنگ می‌زند:
  اتصال برقرار می‌شود، ترافیک هرگز.
- **بستنِ fd**: بعد از `bind0` مالکیت فیلد با موتور است. `close()` دوباره روی fd بازیافت‌شده، یک باگِ
  سه‌صبح‌است نیست، یک باگِ «اتاقِ خوابِ کاربر» است.
- **MTU/MSS**: `mtu` از `AppSettings.tuneFor(node)` به `VpnService.Builder.setMtu` می‌رود (از نسخه‌ی ۲٫۲
  واقعی است، قبل‌تر یک ثابت ۱۴۲۰ بود). **هیچ‌جا** `tcpMss` به کانفیگ Xray اضافه نکنید: فیلدِ sing-box است و
  در Xray بی‌صدا نادیده گرفته می‌شود. عددِ MSS فقط در `meelanoTune` می‌نشیند تا در اسکرین‌شات پشتیبانی
  قابل‌دیدن باشد.
- **Wi‑Fi → سیم‌کارت**: بعد از تغییرِ شبکه، سوکت‌های قدیمی داخل تونل می‌مانند. `CoreApi.rebindUnderlying`
  جایِ `reprotect()` است؛ اگر این را نزنید، علامتِ «چند ثانیه قفل می‌شود» برمی‌گردد.
- **نخِ اصلی**: هیچ‌چیزِ این لایه نباید روی main thread اجرا شود؛ `orchestrator` این را تضمین می‌کند،
  در `start0` هم بشکنید نه اینکه `runBlocking` بگذارید.

## ۵) معیارهای پذیرش (تا این‌ها سبز نشده، «نسخه‌ی نهایی» نیست)

1. `XrayBridge.probe()` → `startLocal/stopLocal/requireVersion` هر سه true.
2. `./gradlew :app:testDebugUnitTest` سبز (قراردادِ کانفیگ، `CoreProfilesTest`).
3. اتصال روی **MCI + Irancell + همراه‌اول**، هر سه در رژیم `TIGHT`، ≥ ۳۰ دقیقه بدون افت.
4. تستِ نشتی: `dnsleaktest` و `browserleaks/webrtc` از داخلِ تونل تمیز؛ DNS داخلِ تونل می‌رود (قاعده‌ی پورت ۵۳).
5. kill-switch: با خاموش‌کردنِ سرویس از Settings، ترافیک **قطع** شود نه اینکه مستقیم برود.
6. کشیدنِ نوتیفیکیشن (swipe-kill) → تونل زنده، reconnect < ۳ ثانیه (با `adb shell appops set ir.meelano.vpn ACTIVATE_VPN allow` قابل خودکارسازی است).
7. `bundleRelease` روی دستگاه ۱۶KB-page (Pixel 9 / Android 15+) بدون `dlopen failed`؛ یعنی `.so` با
   `PAGE_SIZE=16384` ساخته شده باشد.
8. صفحه‌ی «تنظیمات پیشرفته» — همان کلیدهای رژیم — بعد از اتصالِ واقعی هم کار کند: تغییرِ mid-session
   باید با یک rebindِ بی‌سروصدا اعمال شود، نه با قطع‌وصل.

## ۶) اگر بعد از وصل‌کردن، «وصل می‌شود ولی ترافیک نمی‌رود»

به همین ترتیب چک کن (هر مورد در همین repo یک متهمِ مشخص دارد):

| علامت | اول کجا را ببین |
|---|---|
| حلقه سبز، هیچ اپی اینترنت ندارد | `builderFor` — آیا `addRoute("0.0.0.0", 0)` و `addAddress/PDNS` هر سه هستند؟ |
| فقط اپ‌هایی که exclude شده‌اند کار می‌کنند | `addDisallowedApplication` برای خودِ اپِ ما نباید زده شود |
| DNS کار می‌کند، بقیه نه | قاعده‌ی پورت ۵۳ → `proxy`؛ و `dns` در `log` خالی باشد |
| WS کار می‌کند، Reality نه | `flow` باید روی ws حذف و روی tcp اضافه شود (تستِ ۲ تا دارد) |
| ۴۰٪ اتصال‌ها Timeout | `mux` زیر Reality/grpc: `CoreProfiles.specFor` باید false کند |
| بعد از ۶۰ ثانیه می‌میرد | `grpcSettings.idle_timeout` و `keepAliveSec`؛ نردبان در `net/Regime.kt` |
| فقط روی دیتا، نه Wi‑Fi | `protect()` صدا زده نمی‌شود |

## ۷) نسخه‌ی Play

- VpnService مجاز است چون VPN کارکردِ اصلی است، ولی باید در **لیستینگ** اعلام شود و ترافیک تا endpoint
  رمزنگاری شود (سیاستِ «Permissions and APIs that access sensitive information»).
- مسیرِ «دانلود APK و نصب خودکار» در نسخه‌ی Play باید خاموش شود: `CHANNEL=play` و
  `-PMEELANO_SELF_UPDATE=false` (برای سایدلود روی هاست، همان جریانِ فعلی می‌ماند).
- `targetSdk` باید ۳۶ باشد (از ۳۱ اوت ۲۰۲۶ برای هر اپِ جدید و هر آپدیت اجباری است)؛ یعنی edge-to-edge
  اجباری و `WindowInsets` در `VpnControlPanel`/شیت‌ها باید تنظیم شود.
- AAB با `abi` splits + mapping.txt به‌عنوان artifact نگه داشته شود؛ وگرنه کرش‌های نسخه‌ی minify‌شده
  در دست شما قابل‌خواندن نیست.

## ۸) بعد از سبزشدنِ ۵

هیچ رشته‌ای از «هسته‌ی تونل در این بیلد وصل نشده است» را پاک نکن — شرطی است و روی بیلدهای بدون هسته
همچنان باید دیده شود. تنها چیزی که عوض می‌شود: متنِ اخطارِ onboarding در `OnboardingScreen.kt`
(«این نسخه تستی است») و پرچمِ `BuildConfig` است. یک راندِ QA روی سه اپراتور هم به `docs/QA.md` اضافه شود،
چون از نسخه‌ی ۲٫۲ به بعد پاسخِ «چرا کند شد» در خودِ اپ هست (`AdviceCard`) و باید راست گفته شود.
