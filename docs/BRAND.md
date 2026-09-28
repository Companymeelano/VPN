# هویت بصری M•A — نشان، آیکن، پرچم‌ها

> **به‌روزرسانی ۱۴۰۵ (نسخه‌ی ۲٫۲):** نامِ محصول از «Meelano VPN» به **`M•A VPN`** تغییر کرد و نشان از
> `M` تنها به **`M•A`** رسید (§۱٫۵ پایین را ببینید). پکیج اندروید عمداً `ir.meelano.vpn` مانده است:
> عوض‌کردن applicationId یعنی هر نصبِ موجود «برنامه‌ی دیگری» شود و کاربر آپدیتِ خودکار را از دست بدهد.
> همان‌طور که در §۶ گفته شد، «نامِ نمایش» یک کلید در `strings.xml` است، نه یک شناسه‌ی فنی.


## ۱. نشان (mark)
**یک حرف، یک تصویر ذهنی: `M` که میانش دهانه‌ی تونل است.**
دو خوانش در یک شکل: مونوگرام برند (M = Meelano) + دروازه‌ای که ترافیک از آن رد می‌شود.
عمداً **هیچ قفلی** در آیکن نیست: قفل یعنی بسته‌شدن، وعده‌ی این سرویس «رد شدن» است. همین یک تصمیم،
آیکن را از ۹۰٪ بازار جدا می‌کند (آن‌ها قفل/سپر/کره‌ی زمین را روی هم چیده‌اند).

```
گرید طراحی   : ۲۴ واحد (پاها y=۱۸، رأس وسط y=۱۱.۹، شعاع دهانه ۲.۱)
تبلیغ روی ۱۰ : ×۳ + انتقال ۱۸ ⇒ مونوگرام داخل safe zone با ~۱۴٪ حاشیه، در هر ماسک
ضخامت خط     : ۱.۹ واحد از ۲۴ (یک‌نواخت؛ هیچ پر و خالی در خط نیست)
نسخه‌ی سه‌بعدی: یک مکعب مات با برش دهانه + یک حلقه نور mint **فقط** داخل تونل
                 — نور تنها عنصر رنگی است، پس در ۴۸dp هم خوانا می‌ماند
```

| فایل | نقش |
|---|---|
| `mipmap-*/ic_launcher_foreground.png` | رندر سه‌بعدی، از پیش داخل safe zone (۶۲٪ بوم) برای آیکون adaptive |
| `drawable/ic_launcher_monochrome.xml` | همان مونوگرام، برداریِ آلفا‌خالص ⇒ Themed Icons در Android 13+ |
| `drawable/ic_launcher_foreground.xml` | نسخه‌ی مسطحِ رنگی (اسپلش، حالت خالی، بازاریابی) |
| `drawable/ic_stat_vpn.xml` | نوار وضعیت؛ **ساده‌شده** (دهانه بدون لبه‌ی داخلی — دو خط این‌قدر نازک در ۱۸dp به لکه تبدیل می‌شوند) |
| `drawable/ic_qs_vpn.xml` | کاشی Quick Settings؛ مونوگرام کامل |
| `mipmap-anydpi-v26/ic_launcher.xml` | adaptive: `@color/bg` + fg سه‌بعدی + monochrome برداری |
| `mipmap-*/ic_launcher.png` + `_round.png` | فالبک API 24/25 |
| `design/icons/m-*.png` | منبع رندر (۱۲۴px) و گزینه‌های دوم/سوم |

### اشتباهات رایجی که این‌جا رخ نمی‌دهد
- foreground را **بزرگ‌تر** از safe zone نمی‌کنیم؛ لانچر ۶۶٪ نگه می‌دارد و ۳۴٪ را می‌بُرد.
- برای لایه‌ی background سایه/گرادیان نمی‌گذاریم؛ بعد از کروپ ۶۶٪، بانْدینگ رنگی دیده می‌شود.
- مونوگرام را وسطِ *بوم* نمی‌چسبانیم بدون شبیه‌سازی ماسک؛ دایره و رانداسکوار گوشه‌ها را می‌برند.
- آیکون نوار وضعیت را با «خط مشکی روی سپر سفید» برش نمی‌دهیم: سیستم فقط **آلفا** را نگه می‌دارد،
  پس خط مشکیِ opaque یک سوراخ نمی‌سازد. یا توپُر با fillType، یا فقط stroke.

## ۲. رنگ و تایپ در برند
`#4ADE9B` تنها رنگ برند است؛ در متون تبلیغاتی هم همین. تایپ: Vazirmatn (OFL، `res/font/`) —
رسانه‌ی فارسی‌زبان نباید با فونت پیش‌فرض اندروید تست شود، چون شکل «گ/ک» و فاصله‌گذاری را می‌بازد.

## ۳. رندر دوباره‌ی PNGها (کپی-پیست؛ همان دستوری که فایل‌های repo را ساخته)
`-trim` اول می‌خورد چون رندر منبع حاشیه‌ی خودش را دارد؛ بدون آن، «۶۲٪ بوم» یعنی ۶۲٪ *تصویر+حاشیه*
و مونوگرام کوچک‌تر از حد لازم می‌افتد. `SAFE` را با چشم تنظیم نکن: ماسک را شبیه‌سازی کن (گام ۴).

```bash
SRC=design/icons/m-tunnel-3d.png
INK='#0B0F14'; SAFE=0.62                      # سهم مونوگرام از بومِ ۱۰۸dp

# ۱) برش artwork (فقط یک‌بار؛ بعد همان را در همه‌ی ابعاد استفاده کن)
convert "$SRC" -fuzz 15% -trim +repage /tmp/ico-m.png

# ۲) legacy: آیکون کامل + نسخه‌ی دایره‌ای (ماسک با DstIn، نه CopyOpacity — CopyOpacity
#    بعد از `-alpha off` آلفای مقصد را از صفر می‌سازد و دیسک توخالی/توپُر می‌دهد)
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  m=${d%%:*}; px=${d##*:}; c=$((px/2))
  convert "$SRC" -resize ${px}x${px} -unsharp 0x0.4 -background "$INK" -flatten \
      android/app/src/main/res/mipmap-$m/ic_launcher.png
  convert android/app/src/main/res/mipmap-$m/ic_launcher.png \
      \( -size ${px}x${px} xc:none -fill white -draw "circle $c,$c $c,0" \) \
      -compose DstIn -composite android/app/src/main/res/mipmap-$m/ic_launcher_round.png
done

# ۳) لایه‌ی foreground adaptive: بوم جوهری، artwork به اندازه‌ی SAFE در مرکز
for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  m=${d%%:*}; px=${d##*:}; in=$(python3 -c "print(int($px*$SAFE))")
  convert -size ${px}x${px} xc:"$INK" /tmp/ico-m.png -resize ${in}x${in} \
      -gravity center -composite android/app/src/main/res/mipmap-$m/ic_launcher_foreground.png
done

# ۴) اعتبارسنجی: ماسک لانچر را قلبد می‌زنیم — ۶۶٪ وسط + دایره و رانداسکوار
for px in 48 72 96 144 192; do
  crop=$((px*66/100))
  convert android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png -resize ${px}x${px} \
      -gravity center -crop ${crop}x${crop}+0+0 +repage \
      \( -size ${crop}x${crop} xc:none -fill white -draw "circle $((crop/2)),$((crop/2)) $((crop/2)),0" \) \
      -compose DstIn -composite -background "$INK" -flatten /tmp/mask-$px.png
done
montage /tmp/mask-48.png /tmp/mask-72.png /tmp/mask-96.png /tmp/mask-144.png /tmp/mask-192.png \
        -tile 5x1 -geometry +6+6 -background '#0e1520' /tmp/montage.png   # این را چشمی ببین

# ۵) برگشت به پروتوتایپ/فروشگاه
convert android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png -resize 320x320 design/preview/assets/ic-launcher.png
convert /tmp/ico-m.png -resize 320x320 -background "$INK" -gravity center -extent 360x360 -flatten design/preview/assets/ic-glyph.png
convert "$SRC" -resize 512x512 -background "$INK" -flatten design/preview/assets/ic-store-512.png
```

بردارها (`ic_launcher_monochrome.xml`، `ic_launcher_foreground.xml`، `ic_stat_vpn.xml`، `ic_qs_vpn.xml`)
از همین هندسه‌ی ۲۴ واحدی دست‌نویس‌اند؛ اگر هندسه عوض شد، هر چهار فایل + این PNGها با هم عوض می‌شوند —
«آیکن توی گوشی با آیکن نوار وضعیت فرق دارد» از همان نیامده به‌وجود می‌آید.

`design/icons/launcher-sheet.png` خروجیِ این اسکریپت است (پنج دِینسیتی + foreground + دایره + مونولاین)؛
بعد از هر تغییر، sheet را هم تازه کن و به چک‌لیست §۶ پیوست کن.

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

## ۱٫۵ نشانِ جدید: `M•A` — دو دروازه، یک نقطه‌ی در حال عبور

**ایده.** مونوگرام از یک خط‌پیوسته‌ی تک‌ضخامت ساخته می‌شود که اول `M` را می‌تراشد، بعد یک نقطه، بعد `A`
را. نقطه **بین** دو حرف است، نه کنارشان: ترافیک وارد دهانه‌ی `M` می‌شود و از طاقِ `A` بیرون می‌آید و
هیچ‌کس وسطش را نمی‌بیند. `A` عمداً به‌جای میله‌ی افقی، **طاق** دارد — همان شکلِ دهانه — تا دو حرف
هم‌معنا شوند (ورودی/خروجی). هیچ قفلی، هیچ سپری، هیچ کره‌ی زمین.

**دو خوانش، هر دو واقعی:** مونوگرام برند، و نمودارِ مسیرِ ترافیک. نشانِ خوب همین است: اگر توضیح
بخواهد و فقط یک خوانش داشته باشد، تزئین است.

**گرید و نسبت‌ها (فایل‌های برداری همین‌ها را اجرا می‌کنند):**

```
گرید          : ۲۴ واحد؛ پاها y=۱۷٫۸، رأس‌ها y=۷٫۲، نقطه روی خطِ y=۱۵٫۴ (روی خطِ ground می‌نشیند، نه معلق)
M             : 2.8→11.8  (عرض ۹)؛  V میانی به y=۱۱٫۶ فرود می‌آید = دهانه‌ی ورودی
A             : 15.8→21.8 (عرض ۶)؛  طاق: y=۱۳٫۸، شعاع ۲٫۰ = دهانه‌ی خروجی
نقطه          : مرکز (14.6,15.4) شعاع ۱٫۱۵؛ دو پرتو کوتاه به دو حرف (۱۳٫۴ و طاقِ A) تا مسیر پیوسته بماند
ضخامت خط      : ۲٫۰ از ۲۴ (یک‌نواخت؛ هیچ پر و خالی در خط نیست)
حاشیه‌ی چپ‌راست : ۲٫۸ / ۲٫۲ — کادر بصری، نه عددِ تقارنِ ریاضی
```

**وکتورِ برند:** `design/icon.svg` همان پنج مسیرِ `ic_launcher_monochrome.xml` است روی اسلبِ ۱۰۸۰ (برای اسلاید، مارکت و بازبینیِ هندسه). اگر عددی از جدولِ بالا عوض شد، این فایل و فایل‌های `res/` **هم‌زمان** باید عوض شوند؛ وگرنه نشانِ روی کاغذ با نشانِ روی گوشی فرق می‌کند و این تنها چیزی است که مخاطب می‌بیند.

**لانچر (تصمیمِ مهمِ امسال):** کاشیِ لانچر **در تم روشن هم تیره می‌ماند.** اسلب گرافیت با نورِ مینت از
درونِ دهانه‌ها؛ `mipmap-anydpi-v26` به `@color/icon_field` (#13171C در **هر دو** qualifier) وصل است.
دو دلیل: ۱) برندهایی که رنگِ آیکون‌شان با تم عوض می‌شود، روی صفحه‌ی اصلی هویتشان را می‌بازند؛
۲) مسیرِ رسمیِ تطبیق‌شدن، `<monochrome>` است — همان‌که SystemUI در «themed icons» و کاشی‌های QS رنگش
را از تم می‌گیرد. پس تطبیق را همان‌جا گذاشته‌ایم (`ic_launcher_monochrome.xml`)، نه در کاشی.

**آیکن‌های درون‌برنامه** (نوتیفیکیشن، QS tile، صفحه‌ی onboarding) **باید** رنگ عوض کنند: نوارِ
نوتیفیکیشن روی تم روشن، سفیدِ مطلق را محو می‌کند. `ic_qs_vpn.xml` به همین دلیل `@color/tile_icon`
می‌خواند (تاریک: #E6EDF3 / روشن: #0D151C)؛ `ic_stat_vpn.xml` عمداً `#FFFFFF` می‌ماند چون سیستم آن را
به‌عنوان ماسکِ آلفا tint می‌کند.

**پایپ‌لاینِ بازتولید رسترها** (از رندر تا mipmap، بدون فتوشاپ):

```bash
# ۱) رندر master (AI-generated، ۱۰۲) در design/icons/ma-vpn-3d.jpg
# ۲) برشِ بازتاب + trim + فیلدِ تخت، بعد ترکیب با نسبت ۶۶٪ از بومِ ۱۰۸dp
convert master.jpg -gravity north -crop 100%x84%+0+0 +repage /tmp/n.png
convert /tmp/n.png -fuzz 3% -trim +repage -resize 1024x1024! /tmp/slab.png
# ۳) foregroundها (۵ تراکم) روی فیلد #13171C؛ legacy روی #0B0F14 با ۹۲٪؛ round با ماسکِ دایره
convert -size 108x108 xc:"#13171C" /tmp/slab.png -resize 71x71 -gravity center -composite \
        android/app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png
```

دو تله که در این پایپ‌لاین زنده گرفتیم و در مستندات ماند: `-crop 100x82` **پیکسل** است نه درصد (یک‌بار
کل خروجی‌ها سیاه شد)؛ و `-fuzz 8% -trim` روی زمینه‌ی #000 با اسلبِ #17191C کل نقاشی را می‌بُرد
(با ۳٪ درست می‌شود). `design/icons/launcher-sheet.png` خروجیِ بصریِ همین پایپ‌لاین است: پنج تراکم
لانچر + ماسک‌های دایره/مربع/لوزی — چیزی که باید قبل از هر تغییرِ آیکن **دستی** نگاه شود.
