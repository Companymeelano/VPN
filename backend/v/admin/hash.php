<?php
/**
 * The setup helper for hosts without SSH — this file is the reason `/v/admin/` must stay reachable
 * (see the note in ../.htaccess).
 *
 *   browser:  POST the password to this page  (or /v/admin/hash.php?pass=YOUR-PASSWORD)
 *   cli:      php hash.php YOUR-PASSWORD
 *
 * It prints a ready-to-paste `config.local.php` with three freshly generated secrets and the admin
 * password hash. Nothing here reads your existing config, and nothing is stored: generate, paste,
 * and (for the password) prefer the POST form — a GET URL is written into the hosting access log.
 */

require_once dirname(__DIR__) . '/lib/Util.php';

$pass = PHP_SAPI === 'cli'
    ? (isset($argv[1]) ? (string) $argv[1] : '')
    : (isset($_POST['pass']) ? (string) $_POST['pass'] : (isset($_GET['pass']) ? (string) $_GET['pass'] : ''));

if (PHP_SAPI !== 'cli' && $pass === '') {
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta charset="utf-8"><title>Meelano — کلیدهای استقرار</title>'
       . '<style>body{background:#0B0F14;color:#E6EDF3;font:15px/1.9 Vazirmatn,system-ui,sans-serif;'
       . 'direction:rtl;max-width:640px;margin:48px auto;padding:0 20px}'
       . 'input,button{font:inherit;padding:10px 12px;border-radius:10px;border:1px solid #24303c;background:#13171C;color:#E6EDF3}'
       . 'button{background:#4ADE9B;color:#08221A;border:0;cursor:pointer}p{color:#8FA3B8}</style>'
       . '<h1>ساختنِ کلیدهای استقرار</h1>'
       . '<p>رمزِ پنلِ ادمین را وارد کن؛ سه کلید تصادفی و هشِ همین رمز ساخته می‌شود. '
       . 'از این فرم استفاده کن، نه نوارِ آدرس — URL در لاگِ هاست می‌ماند.</p>'
       . '<form method="post" action="hash.php"><input type="password" name="pass" placeholder="رمزِ پنل" autofocus>'
       . ' <button type="submit">بساز</button></form>';
    exit;
}

// bcrypt is ~100ms of CPU per call, and this page is world-readable: a scanner must not be able to
// buy a million hashes (shared hosts get suspended for exactly this kind of CPU spike).
if (PHP_SAPI !== 'cli' && !Util::rateLimit('admin-hash', 5, 600)) {
    http_response_code(429);
    header('Content-Type: text/plain; charset=utf-8');
    echo "too many attempts from this IP - wait 10 minutes, or run: php hash.php YOUR-PASSWORD\n";
    exit;
}

$secret  = bin2hex(random_bytes(32));    // 64 hex: HMAC key for ?action=version  (BuildConfig.MEELANO_FEED_SECRET)
$feedKey = bin2hex(random_bytes(16));    // what the app sends as X-Feed-Key      (BuildConfig.MEELANO_FEED_KEY)
$toolKey = bin2hex(random_bytes(24));    // ?action=selftest|refresh|stats only
$hash    = password_hash($pass, defined('PASSWORD_BCRYPT') ? PASSWORD_BCRYPT : PASSWORD_DEFAULT);

$base = '';
if (isset($_SERVER['HTTP_HOST'])) {
    // Always https, whatever the current request looked like: `update.requireHttpsForApk` makes the app
    // refuse a plain-http apkUrl, so handing out an http base here would only create a support ticket.
    $front = rtrim(str_replace("\\", '/', dirname(dirname((string) ($_SERVER['SCRIPT_NAME'] ?? '/v/admin/hash.php')))), '/');
    $base = 'https://' . $_SERVER['HTTP_HOST'] . $front;
}

header('Content-Type: text/plain; charset=utf-8');
echo "# ============================================================\n"
   . "#  public_html/v/config.local.php   —  this is the ONLY file you edit\n"
   . "#  (config.php is overwritten on every update)\n"
   . "# ============================================================\n"
   . "<?php\n"
   . "return [\n"
   . "    // امضایِ پاسخِ ?action=version. اپ باید با همین مقدار بیلد شود:\n"
   . "    //     -PMEELANO_FEED_SECRET={$secret}\n"
   . "    // تا قبل از بیلدِ بعدی، این خط را کامنت کن وگرنه اپهای نصب‌شده می‌گیرند روی bad_signature.\n"
   . "    'secret' => '{$secret}',\n"
   . "\n"
   . "    'access' => [\n"
   . "        // کلیدِ فید: اپ با هدر X-Feed-Key می‌فرستد؛ باید هم‌زمان بیلد شود با\n"
   . "        //     -PMEELANO_FEED_KEY={$feedKey}\n"
   . "        // تا قبل از آن بیلد، این را خالی بگذار (وگرنه 403 feed_key_required).\n"
   . "        'feedKey'       => '',\n"
   . "        // 'feedKey'     => '{$feedKey}',\n"
   . "\n"
   . "        // فقط برای ابزارها: ?action=selftest|refresh|stats  (هرگز در اپ نمی‌آید)\n"
   . "        'toolKey'       => '{$toolKey}',\n"
   . "\n"
   . "        // رمزِ پنل (این صفحه). بعد از ذخیره، /v/?action=status و ?action=vip را در مرورگر ببین.\n"
   . "        'adminPassHash' => '{$hash}',\n"
   . "        'adminSessionTtl' => 7200,\n"
   . "    ],\n"
   . "\n"
   . "    'update' => [\n"
   . "        // پوشه‌ای که APK در آن است، به‌صورت وب‌خواندنی. اگر دامنه‌ات چیز دیگری است، اصلاح کن.\n"
   . "        'publicBase'    => '{$base}/apk',\n"
   . "        'channel'       => 'stable',\n"
   . "        'mandatoryBelow' => 0,\n"
   . "    ],\n"
   . "];\n"
   . "# ============================================================\n"
   . "#  یادآوری: این فایل را آپلود کن و اجازه بده data/.htaccess بماند.\n"
   . "#  رمزِ خام جایی ذخیره نشد؛ اگر پنل قفل شد، همین صفحه را دوباره باز کن و هشِ تازه بگذار.\n"
   . "# ============================================================\n";
