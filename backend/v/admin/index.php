<?php
/**
 * Meelano feed control panel — one file, no framework, no node, no ssh.
 * URL: https://ainetmee.ir/v/admin/
 * Login: access.adminPassHash (generate with ../admin/hash.php?pass=...)
 *
 * What lives here:
 *   - paste / edit the VIP list            -> data/vip_raw.txt
 *   - rebuild vip.json + free.json          -> data/cache/*.json  (served with ETag)
 *   - enable/disable each free source       -> data/sources_state.json
 *   - upload an APK + publish version.json  -> apk/ + data/version.json
 *   - run the deployment selftest
 */

error_reporting(E_ALL);
ini_set('display_errors', '0');

require_once dirname(__DIR__) . '/lib/Util.php';
require_once dirname(__DIR__) . '/lib/Country.php';
require_once dirname(__DIR__) . '/lib/Parser.php';
require_once dirname(__DIR__) . '/lib/Probe.php';
require_once dirname(__DIR__) . '/lib/Score.php';
require_once dirname(__DIR__) . '/lib/Builder.php';
require_once dirname(__DIR__) . '/lib/Version.php';
require_once dirname(__DIR__) . '/lib/SelfTest.php';
date_default_timezone_set((string) Util::cfg('timezone', 'UTC'));

@session_set_cookie_params([
    'lifetime' => 0, 'path' => '/', 'domain' => '', 'secure' => !empty($_SERVER['HTTPS']),
    'httponly' => true, 'samesite' => 'Lax',
]);
@session_start();

$passHash = (string) Util::cfg('access.adminPassHash', '');
$ttl = (int) Util::cfg('access.adminSessionTtl', 7200);
$authed = isset($_SESSION['meelano_exp']) && (int) $_SESSION['meelano_exp'] > time();

if (!function_exists('h')) {
function h($s)
{
    return htmlspecialchars((string) $s, ENT_QUOTES, 'UTF-8');
}

function csrf_token()
{
    if (empty($_SESSION['meelano_csrf'])) {
        $_SESSION['meelano_csrf'] = bin2hex(random_bytes(16));
    }
    return $_SESSION['meelano_csrf'];
}

function flash(&$x, $msg, $kind = 'ok')
{
    $x = ['msg' => $msg, 'kind' => $kind];
}
}

$flash = null;
$post = $_SERVER['REQUEST_METHOD'] === 'POST';
if ($post && $authed && !hash_equals(csrf_token(), isset($_POST['csrf']) ? (string) $_POST['csrf'] : '')) {
    $post = false;
    flash($flash, 'نشست امنیتی نامعتبر بود (CSRF) — دوباره وارد شوید', 'bad');
    unset($_SESSION['meelano_exp']);
}

/* ---------------------------------------------------------------- login */
if ($post && isset($_POST['do']) && $_POST['do'] === 'login') {
    if ($passHash === '') {
        flash($flash, 'رمز پنل تنظیم نشده است. در config.local.php مقدار access.adminPassHash را بگذارید (با hash.php ساخته می‌شود).', 'bad');
    } elseif (!Util::rateLimit('admin-login', 6, 300)) {
        flash($flash, 'چند بار اشتباه — ۵ دقیقه صبر کنید.', 'bad');
    } elseif (password_verify((string) (isset($_POST['pass']) ? $_POST['pass'] : ''), $passHash)) {
        // only rotate when a session really exists: some hosts disable session persistence
        // (or a prior echo already sent headers), and regenerating blindly is a fatal-free warning
        if (session_status() === PHP_SESSION_ACTIVE) {
            @session_regenerate_id(true);
        }
        $_SESSION['meelano_exp'] = time() + $ttl;
        $authed = true;
        flash($flash, 'خوش آمدید.', 'ok');
    } else {
        flash($flash, 'رمز نادرست.', 'bad');
    }
}
if ($post && isset($_GET['logout'])) {
    unset($_SESSION['meelano_exp']);
    $authed = false;
}

/* ---------------------------------------------------------------- actions */
if ($post && $authed) {
    $do = isset($_POST['do']) ? (string) $_POST['do'] : '';
    if ($do === 'save_vip') {
        $body = (string) (isset($_POST['vip']) ? $_POST['vip'] : '');
        $file = Util::dataDir() . '/' . basename((string) Util::cfg('vip.sourceFile', 'vip_raw.txt'));
        if (!Util::writeAtomic($file, $body)) {
            flash($flash, 'نوشتن فایل ناموفق بود — مجوز data/ را بررسی کنید.', 'bad');
        } else {
            $n = Parser::parseBlob($body);
            flash($flash, 'ذخیره شد: ' . count($n) . ' سرویس سالم شناسایی شد'
                . ($body === '' ? ' (فایل خالی است)' : '') . '.', 'ok');
            if (isset($_POST['rebuild']) && $_POST['rebuild'] !== '') {
                $p = Builder::doBuild('vip', (int) Util::cfg('cache.vipTtl', 600));
                Util::cacheWrite('vip', $p);
                flash($flash, 'vip.json بازسازی شد: ' . $p['count'] . ' سرور در ' . $p['meta']['buildMs'] . 'ms', 'ok');
            }
        }
    } elseif ($do === 'rebuild') {
        $which = in_array(isset($_POST['kind']) ? $_POST['kind'] : '', ['vip', 'free'], true) ? $_POST['kind'] : 'vip';
        $p = Builder::doBuild($which, (int) Util::cfg('cache.' . $which . 'Ttl', 900));
        Util::cacheWrite($which, $p);
        flash($flash, $which . ' بازسازی شد — ' . $p['count'] . ' نود، ' . $p['meta']['buildMs'] . 'ms'
            . (empty($p['servers']) ? ' (لیست خالی شد؛ علت را در Notes ببینید)' : ''), empty($p['servers']) ? 'warn' : 'ok');
    } elseif ($do === 'sources') {
        $on = isset($_POST['src']) && is_array($_POST['src']) ? $_POST['src'] : [];
        $conf = require dirname(__DIR__) . '/sources.php';
        $state = [];
        foreach ($conf['sources'] as $id => $src) {
            $state[$id] = isset($on[$id]);
        }
        Util::writeAtomic(Util::dataDir() . '/sources_state.json', Util::jsonEncode($state));
        flash($flash, 'وضعیت منابع ذخیره شد (' . count(array_filter($state)) . ' روشن).', 'ok');
    } elseif ($do === 'publish') {
        $row = [
            'versionName' => trim((string) (isset($_POST['versionName']) ? $_POST['versionName'] : '')),
            'versionCode' => (int) (isset($_POST['versionCode']) ? $_POST['versionCode'] : 0),
            'changelogFa' => trim((string) (isset($_POST['changelogFa']) ? $_POST['changelogFa'] : '')),
            'mandatory'   => !empty($_POST['mandatory']),
            'mandatoryBelow' => (int) (isset($_POST['mandatoryBelow']) && $_POST['mandatoryBelow'] !== '' ? $_POST['mandatoryBelow'] : Util::cfg('update.mandatoryBelow', 0)),
            'channel'     => Util::cfg('update.channel', 'stable'),
        ];
        $dir = Version::apkDir();
        if (!is_dir($dir)) {
            @mkdir($dir, 0775, true);
        }
        $err = '';
        if (!empty($_FILES['apk']['tmp_name']) && is_uploaded_file($_FILES['apk']['tmp_name'])) {
            $orig = isset($_FILES['apk']['name']) ? (string) $_FILES['apk']['name'] : 'app.apk';
            $safe = preg_replace('~[^A-Za-z0-9._\-]~', '-', $orig);
            if (!preg_match('~\.apk$~i', $safe)) {
                $safe = 'meelano.apk';
            }
            if ($_FILES['apk']['size'] > 220 * 1048576) {
                $err = 'فایل بزرگ‌تر از ۲۲۰ مگابایت است (محدودیت upload_max_filesize/حافظه هاست را هم در نظر بگیرید).';
            } else {
                $name = 'meelano-' . ($row['versionName'] !== '' ? $row['versionName'] : '0.0.0') . '-' . $row['versionCode'] . '.apk';
                if (!@move_uploaded_file($_FILES['apk']['tmp_name'], $dir . '/' . $name)) {
                    $err = 'انتقال فایل آپلودی ناموفق بود (مجوز پوشه apk/).';
                } else {
                    $row['file'] = $name;
                }
            }
        } elseif (!empty($_POST['existing'])) {
            $row['file'] = (string) $_POST['existing'];
        }
        if ($err !== '') {
            flash($flash, $err, 'bad');
        } elseif ($row['versionCode'] < 1) {
            flash($flash, 'versionCode باید یک عدد صحيح بزرگ‌تر از نسخه‌ی فعلی بازار/سایت باشد.', 'bad');
        } else {
            $data = Version::publish($row);
            flash($flash, 'version.json منتشر شد — نسخه ' . (isset($data['versionName']) ? $data['versionName'] : '?')
                . ' (کد ' . $data['versionCode'] . ')، sha256 ' . (empty($data['sha256']) ? 'محاسبه نشد' : substr($data['sha256'], 0, 12) . '…'), 'ok');
        }
    } elseif ($do === 'selftest') {
        $GLOBALS['__selftest'] = SelfTest::run();
        flash($flash, 'خودآزمون اجرا شد.', 'ok');
    }
}

/* ---------------------------------------------------------------- data for view */
$vipFile = Util::dataDir() . '/' . basename((string) Util::cfg('vip.sourceFile', 'vip_raw.txt'));
$vipRaw = Util::readText($vipFile, '');
if ($vipRaw === null && is_file($vipFile . '.example.txt')) {
    $vipRaw = '';
}
$vipRaw = (string) $vipRaw;
$stats = $authed ? Builder::stats() : [];
$conf = require dirname(__DIR__) . '/sources.php';
$srcState = json_decode((string) Util::readText(Util::dataDir() . '/sources_state.json', '{}'), true);
$srcState = is_array($srcState) ? $srcState : [];
$lastFree = Util::cacheRead('free');
$verIdx = Version::readIndex();
$apks = [];
if (is_dir(Version::apkDir())) {
    foreach (scandir(Version::apkDir()) ?: [] as $f) {
        if (preg_match('~\.apk$~i', $f)) {
            $apks[] = $f;
        }
    }
}
$selftestHtml = isset($GLOBALS['__selftest']) && is_array($GLOBALS['__selftest']) ? SelfTest::html($GLOBALS['__selftest']) : '';
$feedKey = (string) Util::cfg('access.feedKey', '');
$toolKey = (string) Util::cfg('access.toolKey', '');
// /v/admin/index.php -> /v  (used for the "open json" links)
$script = isset($_SERVER['SCRIPT_NAME']) ? (string) $_SERVER['SCRIPT_NAME'] : '/v/admin/index.php';
$pub = rtrim(str_replace('\\', '/', dirname(dirname($script))), '/');

/* ---------------------------------------------------------------- view */
?><!doctype html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Meelano — کنترل فید</title>
<style>
:root{--bg:#0B0F14;--surf:#131A21;--surf2:#18212A;--line:#232F3A;--tx:#E6EDF3;--mut:#8FA3B8;--acc:#4ADE9B;--warn:#F5A524;--bad:#FF6B6B}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--tx);font:15px/1.75 Vazirmatn,system-ui,-apple-system,"Segoe UI",sans-serif;padding:24px 16px 64px}
.wrap{max-width:1080px;margin:0 auto}
h1{font-size:22px;margin:0 0 4px;letter-spacing:-.01em}
h2{font-size:13px;text-transform:uppercase;letter-spacing:.09em;color:var(--mut);margin:0 0 14px}
.sub{color:var(--mut);font-size:13px;margin:0 0 24px}
.card{background:var(--surf);border:1px solid var(--line);border-radius:16px;padding:18px;margin:0 0 16px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}
.stat{background:var(--surf2);border:1px solid var(--line);border-radius:12px;padding:12px 14px}
.stat b{display:block;font-size:22px;font-variant-numeric:tabular-nums;letter-spacing:-.02em}
.stat span{color:var(--mut);font-size:12px}
textarea,input[type=text],input[type=number],select{width:100%;background:#0E141A;color:var(--tx);border:1px solid var(--line);border-radius:10px;padding:10px 12px;font:13px/1.6 ui-monospace,Menlo,monospace;direction:ltr;text-align:left}
textarea{min-height:220px}
button,.btn{background:var(--acc);color:#04231A;border:0;border-radius:10px;padding:10px 16px;font:inherit;font-weight:700;cursor:pointer;text-decoration:none;display:inline-block}
button.ghost,.btn.ghost{background:transparent;color:var(--tx);border:1px solid var(--line)}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:12px}
label.chk{display:flex;gap:8px;align-items:center;font-size:13px}
table{width:100%;border-collapse:collapse;font-size:13px}
td,th{padding:8px 10px;border-bottom:1px solid var(--line);text-align:right}
th{color:var(--mut);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.07em}
.mut{color:var(--mut)}
.flash{border-radius:12px;padding:12px 14px;margin:0 0 16px;border:1px solid;font-variant-numeric:tabular-nums}
.flash.ok{background:rgba(74,222,155,.08);border-color:rgba(74,222,155,.35)}
.flash.warn{background:rgba(245,165,36,.08);border-color:rgba(245,165,36,.35)}
.flash.bad{background:rgba(255,107,107,.08);border-color:rgba(255,107,107,.35)}
pre{background:#0E141A;border:1px solid var(--line);border-radius:10px;padding:12px;overflow:auto;font-size:12px;direction:ltr;text-align:left;white-space:pre-wrap}
code{font-family:ui-monospace,Menlo,monospace;background:#0E141A;padding:1px 5px;border-radius:5px;direction:ltr;display:inline-block}
.right{margin-inline-start:auto}
.badge{display:inline-block;font-size:11px;padding:2px 8px;border-radius:999px;border:1px solid var(--line);color:var(--mut)}
table.report td{font-size:12px}
.gh td{color:var(--mut);text-transform:uppercase;font-size:11px;letter-spacing:.08em;padding-top:14px}
</style></head><body>
<div class="wrap">
<?php if (!$authed): ?>
  <div class="card" style="max-width:420px;margin:12vh auto 0;text-align:center">
    <h1>Meelano Feed</h1>
    <p class="sub">پنل مدیریت لیست VIP، منابع رایگان و بروزرسانی اپ</p>
    <?php if ($flash): ?><div class="flash <?= h($flash['kind']) ?>"><?= h($flash['msg']) ?></div><?php endif; ?>
    <form method="post">
      <input type="hidden" name="do" value="login">
      <input type="password" name="pass" placeholder="رمز پنل" autofocus
             style="width:100%;background:#0E141A;color:var(--tx);border:1px solid var(--line);border-radius:10px;padding:12px;font:15px inherit">
      <div class="row" style="justify-content:center"><button type="submit">ورود</button></div>
    </form>
    <?php if ($passHash === ''): ?>
      <p class="mut" style="font-size:12px;margin-top:16px">برای فعال‌سازی: <code>?pass=...</code> را روی <code>admin/hash.php</code> اجرا کنید و خروجی را در <code>config.local.php</code> بگذارید.</p>
    <?php endif; ?>
  </div>
</div></body></html>
<?php
    // return (not exit): as an entry script this ends the response, and it stays include-safe
    return;
endif;
?>
<h1>Meelano Feed</h1>
<p class="sub">PHP <?= h(PHP_VERSION) ?> · timezone <?= h(Util::cfg('timezone')) ?> · <span class="right"><a class="btn ghost" href="?logout=1">خروج</a></span></p>
<?php if ($flash): ?><div class="flash <?= h($flash['kind']) ?>"><?= h($flash['msg']) ?></div><?php endif; ?>

<div class="card">
  <h2>وضعیت</h2>
  <div class="grid">
    <?php foreach (['vip' => 'سرور VIP', 'free' => 'استخر رایگان'] as $k => $label): $c = Util::cacheRead($k); ?>
    <div class="stat">
      <b><?= $c ? (int) (isset($c['payload']['count']) ? $c['payload']['count'] : 0) : 0 ?></b>
      <span><?= h($label) ?> · <?= $c ? ($c['fresh'] ? 'تازه' : 'قدیمی') . ' (' . $c['age'] . 's)' : 'بدون کش' ?></span>
    </div>
    <?php endforeach; ?>
    <div class="stat"><b><?= (int) (isset($stats['ledger']['tracked']) ? $stats['ledger']['tracked'] : 0) ?></b><span>نود تحت‌پیگیری · <?= (int) (isset($stats['ledger']['banned']) ? $stats['ledger']['banned'] : 0) ?> بن‌شده</span></div>
    <div class="stat"><b><?= $verIdx ? 'v' . (int) (isset($verIdx['versionCode']) ? $verIdx['versionCode'] : 0) : '—' ?></b><span>نسخه منتشرشده: <?= h(isset($verIdx['versionName']) ? $verIdx['versionName'] : 'هیچ') ?></span></div>
  </div>
  <div class="row">
    <a class="btn ghost" target="_blank" href="<?= h($pub) ?>/index.php?action=vip<?= $feedKey !== '' ? '&key=' . h($feedKey) : '' ?>">vip.json ↗</a>
    <a class="btn ghost" target="_blank" href="<?= h($pub) ?>/index.php?action=free">free.json ↗</a>
    <a class="btn ghost" target="_blank" href="<?= h($pub) ?>/index.php?action=version">version.json ↗</a>
    <a class="btn ghost" target="_blank" href="<?= h($pub) ?>/index.php?action=health">health ↗</a>
    <form method="post" style="display:inline"><input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>"><input type="hidden" name="do" value="selftest"><button>اجرای خودآزمون</button></form>
  </div>
  <?php if ($toolKey === ''): ?><p class="mut" style="font-size:12px;margin:12px 0 0">برای دسترسی از URL هم (بدون لاگین) <code>access.toolKey</code> را تنظیم کنید.</p><?php endif; ?>
</div>

<?php if ($selftestHtml !== ''): ?>
<div class="card"><h2>خودآزمون استقرار</h2><?= $selftestHtml ?></div>
<?php endif; ?>

<div class="card">
  <h2>لیست VIP <span class="badge">نام‌ها در اپ حذف می‌شوند؛ فقط «<?= h(Util::cfg('brand.vip')) ?>» + پرچم کشور</span></h2>
  <p class="sub" style="margin-bottom:10px">فایل <code><?= h(basename($vipFile)) ?></code> — می‌توانید لینک‌ها، خروجی تلگرام، HTML یا یک اشتراک base64 را همین‌جا paste کنید.</p>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
    <input type="hidden" name="do" value="save_vip">
    <textarea name="vip" spellcheck="false" placeholder="vless://...#VIP-DE-01&#10;trojan://...#VIP-US-02"><?= h($vipRaw) ?></textarea>
    <div class="row">
      <button type="submit">ذخیره</button>
      <label class="chk"><input type="checkbox" name="rebuild" value="1" checked> بازسازی vip.json</label>
      <span class="mut" style="font-size:12px"><?= $vipRaw === '' ? 'همه‌ی فایل‌های input پشتیبانی می‌شوند: txt / html / base64 / json' : strlen($vipRaw) . ' بایت' ?></span>
    </div>
  </form>
  <?php
  $pv = Parser::parseBlob($vipRaw);
  if ($pv): ?>
  <table style="margin-top:14px"><thead><tr><th>پروتکل</th><th>هاست:پورت</th><th>کشور</th><th>tls</th><th>network</th><th>id</th></tr></thead><tbody>
  <?php foreach (array_slice($pv, 0, 12) as $n): ?>
    <tr>
      <td><?= h($n['proto']) ?></td>
      <td style="direction:ltr"><?= h($n['host'] . ':' . $n['port']) ?></td>
      <td><?= h($n['cc'] ?: '—') ?> <?= $n['cc'] ? h(Country::flagEmoji($n['cc'])) : '' ?></td>
      <td><?= h(isset($n['tls']) ? ($n['tls'] ?: 'none') : '—') ?></td>
      <td><?= h(isset($n['network']) ? $n['network'] : '—') ?></td>
      <td class="mut"><?= h($n['id']) ?></td>
    </tr>
  <?php endforeach; ?>
  </tbody></table>
  <p class="mut" style="font-size:12px"><?= count($pv) ?> نود نرمال‌شده — متن‌های اصلی (remark) در پاسخ به اپ نخواهند بود.</p>
  <?php endif; ?>
</div>

<div class="card">
  <h2>منابع استخر رایگان</h2>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
    <input type="hidden" name="do" value="sources">
    <table><thead><tr><th style="width:60px">فعال</th><th>منبع</th><th>نوع</th><th>آدرس</th><th style="width:120px">آخرین وضعیت</th></tr></thead><tbody>
    <?php foreach ($conf['sources'] as $id => $src):
        $on = empty($srcState) || !array_key_exists($id, $srcState) || !empty($srcState[$id]);
        $srcMeta = ($lastFree && isset($lastFree['payload']['meta']['sources'][$id]))
            ? $lastFree['payload']['meta']['sources'][$id] : null; ?>
      <tr>
        <td><input type="checkbox" name="src[<?= h($id) ?>]" value="1" <?= $on ? 'checked' : '' ?>></td>
        <td><?= h($id) ?></td>
        <td class="mut"><?= h(isset($src['kind']) ? $src['kind'] : 'proxy') ?> · take <?= (int) (isset($src['take']) ? $src['take'] : 0) ?></td>
        <td class="mut" style="direction:ltr;text-align:left;font-size:11px"><?= h(isset($src['url']) ? $src['url'] : '') ?></td>
        <td class="mut" style="font-size:11px"><?= $srcMeta ? ((int) (isset($srcMeta['nodes']) ? $srcMeta['nodes'] : 0) . ' نود' . (empty($srcMeta['ok']) ? ' ✕' . h(isset($srcMeta['err']) ? $srcMeta['err'] : '') : '')) : 'بدون اجرا' ?></td>
      </tr>
    <?php endforeach; ?>
    </tbody></table>
    <div class="row">
      <button type="submit">ذخیره منابع</button>
      <span style="margin-inline-start:auto;display:inline-flex;gap:10px">
        <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
        <input type="hidden" name="do" value="rebuild"><input type="hidden" name="kind" value="free">
        <button>بازسازی free.json</button>
      </span>
    </div>
  </form>
  <?php if ($lastFree && isset($lastFree['payload']['meta'])): ?>
    <pre><?= h(Util::jsonEncode(['notes' => $lastFree['payload']['meta']['notes'] ?? [], 'sources' => $lastFree['payload']['meta']['sources'] ?? [], 'prefilter' => $lastFree['payload']['meta']['prefilter'] ?? [], 'buildMs' => isset($lastFree['payload']['meta']['buildMs']) ? $lastFree['payload']['meta']['buildMs'] : null])) ?></pre>
  <?php else: ?>
    <p class="mut" style="font-size:12px">هنوز free.json ساخته نشده — با دکمه‌ی «بازسازی free.json» اولین نسخه را بسازید (اگر هاست شما outbound مجاز نباشد، خودکار به رتبه‌بندی مبتنی بر بازخورد کاربر سوییچ می‌شود).</p>
  <?php endif; ?>
</div>

<div class="card">
  <h2>بروزرسانی اپ</h2>
  <form method="post" enctype="multipart/form-data">
    <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
    <input type="hidden" name="do" value="publish">
    <div class="grid">
      <label class="mut" style="font-size:12px">APK (اختیاری — اگر فایل را با FTP در <?= h(Version::apkDir()) ?> گذاشته‌اید، از فهرست پایین انتخاب کنید)
        <input type="file" name="apk" accept=".apk,application/vnd.android.package-archive" style="margin-top:6px"></label>
      <label class="mut" style="font-size:12px">versionName<input type="text" name="versionName" value="<?= h(isset($verIdx['versionName']) ? $verIdx['versionName'] : '1.0.0') ?>"></label>
      <label class="mut" style="font-size:12px">versionCode (باید افزایش یابد)<input type="number" name="versionCode" value="<?= (int) (isset($verIdx['versionCode']) ? $verIdx['versionCode'] + 1 : 1) ?>"></label>
      <label class="mut" style="font-size:12px">اجباری تا زیر این کد<input type="number" name="mandatoryBelow" value="<?= (int) (isset($verIdx['mandatoryBelow']) ? $verIdx['mandatoryBelow'] : Util::cfg('update.mandatoryBelow', 0)) ?>"></label>
    </div>
    <label class="mut" style="font-size:12px;display:block;margin-top:12px">توضیحات بروزرسانی (نمایش در اپ)
      <textarea name="changelogFa" style="min-height:90px" placeholder="• رفع لگ بعد از اتصال&#10;• اعمال خودکار پروکسی VIP"><?= h(isset($verIdx['changelogFa']) ? $verIdx['changelogFa'] : '') ?></textarea>
    </label>
    <div class="row">
      <label class="chk"><input type="checkbox" name="mandatory" value="1" <?= !empty($verIdx['mandatory']) ? 'checked' : '' ?>> بروزرسانی اجباری (مسدود کردن استفاده تا نصب)</label>
      <button type="submit">انتشار version.json</button>
    </div>
    <?php if ($apks): ?>
    <p class="mut" style="font-size:12px">APK های موجود:
      <?php foreach ($apks as $f): ?><label class="chk" style="display:inline-flex;margin-inline-end:12px"><input type="radio" name="existing" value="<?= h($f) ?>"> <?= h($f) ?></label><?php endforeach; ?>
    </p>
    <?php endif; ?>
  </form>
  <?php if ($verIdx): ?><pre><?= h(Util::jsonEncode($verIdx)) ?></pre>
  <?php else: ?><p class="mut" style="font-size:12px">هنوز نسخه‌ای منتشر نشده. تا زمان انتشار، اپ «بروزرسانی موجود نیست» می‌گیرد و هیچ خطایی نمی‌دهد.</p><?php endif; ?>
</div>

<div class="card">
  <h2>چیزی که اپ باید تنظیم کند</h2>
  <pre><?= h(implode("\n", [
    'FEED_BASE   = https://' . (isset($_SERVER['HTTP_HOST']) ? preg_replace('~:\d+$~', '', $_SERVER['HTTP_HOST']) : 'ainetmee.ir') . '/v',
    'GET  /v/?action=vip        If-None-Match: <etag>   -> 304 (بدون ترافیک)',
    'GET  /v/?action=free       same contract, same shape',
    'GET  /v/?action=version?vc=<versionCode>            -> updateAvailable + mandatory',
    'POST /v/?action=feedback   {"sid":"..","ok":true,"latencyMs":380}',
    $feedKey !== '' ? 'header X-Feed-Key: <value from config>' : 'X-Feed-Key is off (set access.feedKey to protect the VIP list)',
  ])) ?></pre>
</div>
<p class="mut" style="font-size:12px">داده‌ها در <code><?= h(Util::dataDir()) ?></code> (کش، ledger، vip_raw.txt) — این پوشه با .htaccess برای وب بسته شده است. APK ها در <code>apk/</code> که عمداً قابل دانلود است.</p>
</div></body></html>
