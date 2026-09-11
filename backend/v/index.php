<?php
/**
 * /v/  —  the single entry point the app talks to.
 *
 *   GET  /v/?action=vip        VIP list (vendor names stripped: only "Vip Meelano" + country)
 *   GET  /v/?action=free       tested free pool, identical shape
 *   GET  /v/?action=version    update index (?vc=<currentVersionCode> -> verdict)
 *   GET  /v/?action=health     tiny liveness reply
 *   POST /v/?action=feedback   app reports "this node worked / didn't" -> feeds the ranking
 *   GET  /v/?action=stats      admin only
 *   GET  /v/?action=selftest   admin only, deployment diagnostics (?html=1 for a page)
 *   GET  /v/?action=refresh    admin only, force rebuild now
 *
 * Flat aliases (vip.php / free.php / version.php / health.php) exist for hosts without mod_rewrite.
 */

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');

require_once __DIR__ . '/lib/Util.php';
require_once __DIR__ . '/lib/Country.php';
require_once __DIR__ . '/lib/Parser.php';
require_once __DIR__ . '/lib/Probe.php';
require_once __DIR__ . '/lib/Score.php';
require_once __DIR__ . '/lib/Builder.php';
require_once __DIR__ . '/lib/Version.php';
require_once __DIR__ . '/lib/SelfTest.php';

date_default_timezone_set((string) Util::cfg('timezone', 'UTC'));

/**
 * Handlers live in the block below. The function_exists() guard lets the test suite
 * include this file more than once inside a single PHP process.
 */
if (!function_exists('resolveAction')) {

function resolveAction()
{
    $raw = isset($_GET['action']) ? (string) $_GET['action'] : '';
    if ($raw === '' && isset($_SERVER['REQUEST_METHOD']) && $_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
        return 'options';
    }
    if ($raw === '' && isset($_SERVER['REQUEST_URI'])) {
        // /v/vip.json  ->  vip   (mod_rewrite normally does this; this is the fallback)
        $path = (string) parse_url((string) $_SERVER['REQUEST_URI'], PHP_URL_PATH);
        $base = strtolower((string) basename($path));
        $base = preg_replace('~\.(json|txt|php)$~', '', $base);
        $raw = ($base === 'index' || $base === '' || $base === 'v') ? '' : $base;
    }
    return preg_replace('~[^a-z_]~', '', (string) $raw);
}

function feed($action)
{
    $need = (string) Util::cfg('access.feedKey', '');
    if ($need !== '') {
        $got = isset($_SERVER['HTTP_X_FEED_KEY']) ? (string) $_SERVER['HTTP_X_FEED_KEY'] : (isset($_GET['key']) ? (string) $_GET['key'] : '');
        if (!hash_equals($need, $got)) {
            Util::fail(403, 'feed_key_required', ['howto' => 'send header X-Feed-Key']);
        }
    }
    if (!Util::rateLimit('feed:' . $action, (int) Util::cfg('limits.reqPerMinPerIp', 60))) {
        Util::respond(['error' => 'slow_down', 'servers' => [], 'retryAfter' => 60], ['status' => 429, 'maxAge' => 0]);
        return;
    }

    $payload = $action === 'vip' ? Builder::vip() : Builder::free();
    if (!isset($payload['etag']) || !is_string($payload['etag'])) {
        $payload['etag'] = substr(sha1(Util::jsonEncode($payload)), 0, 16);
    }
    $etag = $payload['etag'];
    if (Util::ifNoneMatch() === $etag && empty($_GET['fresh'])) {
        Util::respond([], ['status' => 304, 'etag' => $etag]);
        return;
    }
    $meta = ['etag' => $etag, 'maxAge' => (int) Util::cfg('cache.httpMaxAge', 300)];
    // the pre-gzipped build on disk only matches when we served the cache untouched
    if (isset($payload['cache']['state']) && $payload['cache']['state'] === 'fresh') {
        $meta['gzPath'] = Util::dataDir('cache') . '/' . $action . '.json.gz';
    }
    Util::log('feed', ['a' => $action, 'n' => isset($payload['count']) ? $payload['count'] : 0]);
    Util::respond($payload, $meta);
}

function feedback()
{
    $body = '';
    if (isset($_SERVER['REQUEST_METHOD']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
        $body = (string) @file_get_contents('php://input');
    }
    $d = json_decode($body, true);
    if (!is_array($d)) {
        $d = $_GET;
    }
    if (!Util::rateLimit('fb', (int) Util::cfg('free.feedback.maxPerIpPerMin', 12))) {
        Util::fail(429, 'too_many_reports');
    }
    $items = isset($d['reports']) && is_array($d['reports']) ? $d['reports'] : [$d];
    $n = 0;
    foreach ($items as $it) {
        if (!is_array($it)) {
            continue;
        }
        $sid = isset($it['sid']) ? $it['sid'] : (isset($it['id']) ? $it['id'] : '');
        $ok = isset($it['ok']) && !in_array(strtolower((string) $it['ok']), ['0', 'false', ''], true);
        $ms = isset($it['latencyMs']) ? (int) $it['latencyMs'] : (isset($it['ms']) ? (int) $it['ms'] : 0);
        if (Builder::ingestFeedback($sid, $ok, $ms)) {
            $n++;
        }
    }
    header('Content-Type: application/json; charset=utf-8');
    echo Util::jsonEncode(['ok' => true, 'accepted' => $n]);
}

function requireAdmin()
{
    $key = (string) Util::cfg('access.toolKey', '');
    if ($key === '') {
        Util::fail(503, 'tool_key_not_configured', ['howto' => 'set access.toolKey in config.local.php']);
    }
    $got = isset($_GET['key']) ? (string) $_GET['key'] : '';
    if ($got === '' || !hash_equals($key, $got)) {
        Util::fail(401, 'bad_tool_key');
    }
}

} // end guarded function block

/* ------------------------------------------------------------------ router */

$action = resolveAction();

if ($action === 'options') {
    header('Allow: GET, POST, OPTIONS');
    http_response_code(204);
    exit;
}

switch ($action) {
    case 'vip':
    case 'free':
        feed($action);
        break;

    case 'version':
        $p = Version::serve();
        $etag = substr(sha1(Util::jsonEncode($p)), 0, 16);
        if (Util::ifNoneMatch() === $etag) {
            Util::respond([], ['status' => 304, 'etag' => $etag]);
            break;
        }
        Util::respond($p, ['etag' => $etag, 'maxAge' => (int) Util::cfg('cache.versionTtl', 300)]);
        break;

    case 'feedback':
        feedback();
        break;

    case 'health':
        header('Content-Type: application/json; charset=utf-8');
        echo Util::jsonEncode([
            'ok'       => true,
            'php'      => PHP_VERSION,
            'now'      => time(),
            'time'     => date('c'),
            'writable' => is_writable(Util::dataDir()),
        ]);
        break;

    case 'refresh':
        requireAdmin();
        $r = [];
        foreach (['vip', 'free'] as $k) {
            $t0 = microtime(true);
            $p = Builder::doBuild($k, (int) Util::cfg('cache.' . $k . 'Ttl', 900));
            Util::cacheWrite($k, $p);
            $r[$k] = [
                'nodes' => $p['count'],
                'ms'    => (int) round((microtime(true) - $t0) * 1000),
                'notes' => isset($p['meta']['notes']) ? $p['meta']['notes'] : [],
            ];
        }
        header('Content-Type: application/json; charset=utf-8');
        echo Util::jsonEncode(['ok' => true, 'result' => $r]);
        break;

    case 'selftest':
        requireAdmin();
        $report = SelfTest::run();
        if (isset($_GET['html'])) {
            header('Content-Type: text/html; charset=utf-8');
            echo '<!doctype html><meta charset="utf-8"><title>Meelano feed selftest</title>'
               . '<style>body{background:#0B0F14;color:#E6EDF3;font:14px/1.6 Vazirmatn,system-ui,sans-serif;padding:24px;direction:rtl}'
               . 'table{width:100%;border-collapse:collapse}td{padding:6px 8px;border-bottom:1px solid #1c2630;text-align:right}'
               . '.gh td{color:#8FA3B8;text-transform:uppercase;font-size:11px;letter-spacing:.08em;padding-top:16px}'
               . '.mut{color:#8FA3B8;font-size:12px}</style>';
            echo SelfTest::html($report);
        } else {
            header('Content-Type: application/json; charset=utf-8');
            echo Util::jsonEncode($report);
        }
        break;

    case 'stats':
        requireAdmin();
        header('Content-Type: application/json; charset=utf-8');
        echo Util::jsonEncode(Builder::stats());
        break;

    default:
        header('Content-Type: application/json; charset=utf-8');
        echo Util::jsonEncode([
            'app'       => Util::cfg('appName'),
            'endpoints' => [
                'vip'      => 'index.php?action=vip',
                'free'     => 'index.php?action=free',
                'version'  => 'index.php?action=version&vc=<versionCode>',
                'feedback' => 'POST index.php?action=feedback  {"reports":[{"sid":"..","ok":true,"latencyMs":380}]}',
                'health'   => 'index.php?action=health',
            ],
            'hint' => 'stats|selftest|refresh need ?key=<access.toolKey>; run ?action=selftest once after deploy',
        ]);
}
