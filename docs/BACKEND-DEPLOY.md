# استقرار بک‌اند روی هاست اشتراکی (cPanel، بدون Node، بدون SSH)

فایل‌ها: `backend/v/` → همان آدرسی که خودت گفتی: **`https://ainetmee.ir/v/`**
(امروز `http://ainetmee.ir/v` را چک کردم: `404 Not Found` — یعنی فایلش هنوز وجود ندارد؛ با این آپلود درست می‌شود.)

---

## ۱) آپلود
1. cPanel → **File Manager** (یا FTP با FileZilla) → `public_html/`.
2. کل پوشه‌ی `v` را داخل `public_html/` کپی کن. نتیجه:
   ```
   public_html/v/index.php
   public_html/v/config.php
   public_html/v/sources.php
   public_html/v/lib/*.php
   public_html/v/data/          ← باید writable باشد
   public_html/v/apk/           ← برای فایل نصب اپ
   public_html/v/admin/index.php
   ```
3. روی `v/data` راست‌کلیک → Permissions → `775` (اگر host اجازه نداد `755` و owner را چک کن).

## ۲) PHP را انتخاب کن
cPanel → **Select PHP Version / MultiPHP Manager** → نسخه را روی `8.1` یا `8.2` بگذار
(کد روی `7.4` هم تست شده). اگر همان‌جا extension قابل انتخاب است، تیک‌های **curl**، **zlib**،
**mbstring**، **openssl** را روشن کن؛ بدون curl هم کار می‌کند (fallback به `file_get_contents`).

## ۳) ساختن `config.local.php` (تنها فایلی که تو ویرایش می‌کنی)
`v/config.local.php`:
```php
<?php return [
    'secret' => 'یک رشته‌ی ۶۴ کاراکتر هگز — همان مقدار در BuildConfig',
    'access' => [
        'adminPassHash' => '$2y$10$...',   // از مرحله ۴
        'toolKey'       => '...',          // از مرحله ۴
        'feedKey'       => '...',          // اختیاری ولی توصیه‌شده برای محافظت لیست VIP
    ],
    'update' => [
        'publicBase'  => 'https://ainetmee.ir/v/apk',
        'mandatoryBelow' => 0,
    ],
];
```
**چرا `config.local.php`؟** چون وقتی کد را آپدیت می‌کنی، تنظیمات تو پاک نمی‌شود
(`config.php` فقط مقادیر پیش‌فرض را دارد و `array_replace_recursive` این فایل را رویش سوار می‌کند).

## ۴) رمز پنل
مرورگر: `https://ainetmee.ir/v/admin/hash.php?pass=رمز-مناسب`
خروجی دو خط است (`adminPassHash` و یک `toolKey` تصادفی) → در `config.local.php` بگذار.
پنل: `https://ainetmee.ir/v/admin/`.

## ۵) خودآزمون — این مرحله را رد نکن
`https://ainetmee.ir/v/?action=selftest&key=<toolKey>`

سه ردیف مهم:
- `fs data dir = ok` → کش و لیست VIP نوشته می‌شود.
- `http gh = ok` → لیست‌های عمومی از هاست تو قابل خواندن است (برای استخر رایگان لازم است).
- `egress odd_port_8080` → **اینجا معمولاً مشکل است.** بسیاری از هاست‌های اشتراکی ایران اتصال
  outbound به پورت‌های غیراستاندارد را بلاک می‌کنند، یعنی «تست پروکسی سمت سرور» ممکن نیست.
  اگر `warn/bad` دیدی: در `config.local.php` بگذار
  ```php
  'free' => ['probe' => ['enabled' => false]],
  ```
  و کل سیستم همچنان کار می‌کند، با این تفاوت که رتبه‌بندی از **بازخورد واقعی کاربران**
  (`?action=feedback`) ساخته می‌شود نه از پروب سرور. این حالت عمداً طراحی شده و در تست‌ها پوشش داده شده.

## ۶) لیست VIP
پنل → «لیست VIP» → متن/لینک‌ها را paste کن → Save + rebuild.
یا مستقیم فایل `v/data/vip_raw.txt`. ورودی‌های پذیرفته‌شده: خط‌های `vless://`/`vmess://`/`trojan://`/`ss://`،
اشتراک base64، HTML/خروجی تلگرام، یا JSON. **نام سرویس‌ها دور ریخته می‌شود**؛ به اپ فقط
`name="Vip Meelano"` + `cc` (برای پرچم) می‌رسد.

## ۷) بروزرسانی اپ
1. APK را امضا کن با **همان release key همیشگی** (اندروید APK با امضای متفاوت را نصب نمی‌کند؛
   این لایه‌ی اصلی امنیت است، نه HMAC).
2. پنل → «بروزرسانی اپ» → `versionName`، `versionCode` (حتماً بیشتر از قبل)، توضیح فارسی،
   آپلود APK (یا اگر با FTP در `v/apk/` گذاشته‌ای، از فهرست انتخابش کن) → انتشار.
3. خروجی `https://ainetmee.ir/v/?action=version&vc=<versionCode فعلی>` را چک کن (دقت کن `&` است، نه `?` دوم):
   `updateAvailable`, `apkUrl`, `sha256`, `sig`.
   نام فایل را هم الگوی `meelano-<versionName>-<versionCode>.apk` بگذار تا اگر `version.json`
   پاک شد، سرور خودش از روی پوشه بسازدش.
4. **اگر اپ را در Google Play داری:** مسیر نصب خودکار را برای بیلد پلی خاموش کن و از
   Play In-App Updates استفاده کن (سیاست پلی اجازه‌ی نصب APK از بیرون را نمی‌دهد).
   برای «بازار»/توزیع مستقیم، همین روش رایج و مجاز است.

## ۸) کش و سرعت (چون درخواستت «درست‌تر/سریع‌تر» بود)
- پاسخ‌ها با `ETag` + `Cache-Control: max-age=300, stale-while-revalidate=60` سرو می‌شوند؛
  پولینگ معمولی اپ `304` با صفر بایت است.
- `vip.json.gz` از همان لحظه‌ی بیلد ساخته می‌شود → PHP در سرو کردن CPU مصرف نمی‌کند.
- بیلد هیچ‌وقت جلوی کاربر را نمی‌گیرد: اگر کش کهنه باشد، همان را فوراً می‌دهد و یک نفر (whoever)
  قفل `flock` را می‌گیرد و بازسازی می‌کند.
- cron **لازم نیست**؛ اگر خواستی تازه‌تریِ تضمینی: cPanel → Cron Jobs → هر ۳۰ دقیقه:
  ```
  curl -s "https://ainetmee.ir/v/index.php?action=refresh&key=<toolKey>" -o /dev/null
  ```

## ۹) امنیت — سه نکته که اگر رعایت نشد، لیست VIP تو عمومی است
1. `data/` باید از وب بسته باشد. اگر هاست **nginx** است، `.htaccess` نادیده گرفته می‌شود:
   در پنل/کانفیگ nginx هم `location ^~ /v/data/ { deny all; }` اضافه کن (یا `vip_raw.txt` را
   به بیرون از `public_html` منتقل کن و مسیر مطلق در config بده).
2. `access.feedKey` را پر کن تا اپهای دیگر از لیست پولی تو رایگان استفاده نکنند؛ اپ هدر
   `X-Feed-Key` را می‌فرستد.
3. فقط `https://`. سایتت TLS سالم دارد (چک کردم)؛ پس `FEED_BASE` در اپ را https بگذار.
   دانلود APK روی http = هر کسی در مسیر می‌تواند فایل را عوض کند.

## ۱۰) عیب‌یابی سریع
|علامت | علت احتمالی | کار |
|---|---|---|
| `500` روی همه‌ی اکشن‌ها | `data/` نوشتنی نیست / PHP 5.x | chmod 775، Select PHP Version |
| `?action=vip` لیست خالی | `data/vip_raw.txt` خالی است | paste در پنل |
| free همیشه خالی | outbound بلاک (خودآزمون را ببین) | `free.probe.enabled=false` + کاهش `free.minNodes` |
| ۴۰۳ روی `/v/data/...` نمی‌آید | `.htaccess` بی‌اثر (nginx/LiteSpeed بدون AllowOverride) | مسیر `vip_raw.txt` را به بیرون public_html ببر |
| اپ می‌گوید checksum_mismatch | فایل APK روی هاست با `version.json` هم‌خوان نیست | از پنل دوباره Publish کن |

---

قراردادِ هر تماس (پارامترها، هدرها، پاسخ‌ها، و کجا در اپ خوانده می‌شود):
[`SERVER-CALLS.md`](SERVER-CALLS.md).
