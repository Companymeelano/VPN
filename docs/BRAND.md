# هویت بصری میلانو — لوگو، آیکون، پرچم‌ها

## ۱. نشان (mark)
یک شکل با دو خوانش: **سپر** (امنیت) و **تونل** (عبور). چیزی جز این دو خوانش در لوگو نیست —
هیچ کلید، هیچ قفل، هیچ کره‌ی زمین. نشان‌هایی که هر سه را با هم دارند در ۴۸dp به لکه تبدیل می‌شوند.

```
گرید ۲۴ واحدی → روی بوم ۱۰۸dp با ضریب ۳ و حاشیه‌ی ۱۸dp (safe zone = ۷۲dp = ۶۶٪)
قوس‌ها        : ۱۲۰° بازشدگی، y=۱۵.۴ (زیر مرکز؛ چشم نقطه‌ی مرکزی را بالاتر از هندسی می‌بیند)
ضخامت خط     : ۱.۸ واحد از ۲۴ ≈ ۵.۴dp در ۱۰۸ ≈ ۵٪ ⇒ در ۲۴dp هنوز ~۱.۸dp است و پخش نمی‌شود
نقطه          : شعاع ۱.۰ — در ۲۴dp دقیقاً ۲dp؛ کوچک‌تر از این روی OLED‌های ارگانیک پایین‌رونده «نقطه» را می‌بلعد
رنگ            : #4ADE9B روی #0B0F14؛ تک‌رنگ سفید برای monochrome
```

فایل‌های مرجع (همه برداری و دست‌نویس، نه خروجی اتوماتیک):

| فایل | نقش |
|---|---|
| `res/drawable/ic_launcher_foreground.xml` | لایه‌ی foreground آیکون (سپر + قوس‌ها + نقطه، رنگ برند) |
| `res/drawable/ic_launcher_monochrome.xml` | همان هندسه با آلفای خالص — Themed Icons در Android 13+ |
| `res/drawable/ic_stat_vpn.xml` | نوار وضعیت؛ **ساده‌شده**: فقط سپر و یک شکاف، بدون قوس داخلی |
| `res/drawable/ic_qs_vpn.xml` | کاشی Quick Settings؛ ۳۲dp، همان نشان کامل |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive: bg=`@color/bg` + fg=آیکون + monochrome |
| `res/mipmap-*/ic_launcher*.png` | فالبک API 24/25 (از همان منبع رندر شده) |
| `design/preview/assets/ic-launcher.png` | نمونه‌ی ۱۱۰۸px برای کاور/بازار/اسکرین‌شات |

### اشتباهات رایجی که این‌جا رخ نمی‌دهد
- foreground را **بزرگ‌تر** از safe zone نمی‌کنیم؛ لانچر ۶۶٪ نگه می‌دارد و ۳۴٪ را می‌بُرد.
- برای آیکون سایه/گرادیان نمی‌گذاریم؛ بعد از کروپ، بانْدینگ رنگی دیده می‌شود.
- آیکون نوار وضعیت را با «خط مشکی روی سپر سفید» برش نمی‌دهیم: سیستم فقط **آلفا** را نگه می‌دارد،
  پس خط مشکیِ opaque یک سوراخ نمی‌سازد. یا توپُر با fillType، یا فقط stroke.

## ۲. رنگ و تایپ در برند
`#4ADE9B` تنها رنگ برند است؛ در متون تبلیغاتی هم همین. تایپ: Vazirmatn (OFL، `res/font/`) —
رسانه‌ی فارسی‌زبان نباید با فونت پیش‌فرض اندروید تست شود، چون شکل «گ/ک» و فاصله‌گذاری را می‌بازد.

## ۳. رندر دوباره‌ی PNGها
```bash
# پس از ویرایش هندسه‌ی بردار (یا منبع طراح):
SRC=design/preview/assets/ic-launcher.png
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  m=${d%%:*}; px=${d##*:}
  convert "$SRC" -resize ${px}x${px} -unsharp 0x0.6 \
      android/app/src/main/res/mipmap-$m/ic_launcher.png
  convert android/app/src/main/res/mipmap-$m/ic_launcher.png \
      \( +clone -alpha extract -negate \) -alpha off -compose CopyOpacity -composite \
      android/app/src/main/res/mipmap-$m/ic_launcher_round.png
done
```

## ۴. پرچم‌ها
پرچم‌ها **نقاشیِ خودمان** در تایل ۱۹×۱۴ هستند، با فرمت برداری — نه ایموجی (روی OneUI/اندروید ۷ تا ۹ و
روی دستگاهی که فونت سیستمش عوض شده ایموجی به‌شکل «DE» در جعبه می‌افتد) و نه لوگوی سرویس‌دهنده.
کد کشور از فید می‌آید (`cc`) و اپ نگاشت را می‌سازد؛ اگر کدی در مجموعه نبود، **تایل خنثی** با حرف کشور
نشان داده می‌شود و هیچ‌وقت پرچم اشتباه.

```bash
python3 design/flags/gen-flags.py           # ۵۲ تایل → res/drawable/flag_*.xml
python3 design/flags/gen-flags.py && ./gradlew :app:processDebugResources
```
ساخت: باند آخر به لبه‌ی تایل می‌چسبد (درز نیم‌پیکسلی بین دو باند در ۱۹dp دیده می‌شود)، حداکثر سه رنگ،
و دایره فقط آن‌جا که هویت پرچم است. نگاشت `cc → R.drawable` در `ui/VpnControlPanel.kt` داخل `object Flags`
**ایستا** است: `getIdentifier` در هر ردیف هم هزینه دارد و هم R8 می‌تواند منبع را حذف کند (باگ «در ریلیز
پرچم‌ها نیفتاد»); `res/raw/keep.xml` لایه‌ی احتیاط است.

## ۵. چک‌لیست برند قبل از هر نسخه
- [ ] رنگ برند فقط `#4ADE9B` است؛ رنگ نودهای VIP از گرید می‌آید، نه از رنگ کشور/سرویس.
- [ ] هیچ نام سرویسی در UI نیست؛ فقط `Vip Meelano` + پرچم (قرارداد `namePolicy: masked:brand+cc`).
- [ ] آیکون‌ها در ماسک دایره‌ای و مربع‌گرد همان‌اند که در `design/preview/` تست شده‌اند.
- [ ] monochrome آیکون در Themed Icons قابل‌دیدن است (کنتراست با tint سیستم).
- [ ] ۵۲ پرچم + تایل خنثی؛ بدون fallback به ایموجی.
