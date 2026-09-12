<?php
/**
 * Dev tool: render the public service page against the local fixtures, into design/preview/status.html.
 *
 * Why a tool and not a screenshot: the page is generated markup from the same function the endpoint uses,
 * so this is the only way to *look* at it before deploying anything. It never touches the network (sources
 * are the fixtures under backend/v/data/fixtures, VIP comes from data/vip_raw.example.txt) and it writes
 * outside the repo's runtime dirs, so running it cannot poison a real cache.
 *
 *   php tools/status-snapshot.php [outfile]
 *   PHPVER=8.2 node /path/to/phprun/run.mjs tools/status-snapshot.php     # in this sandbox
 *
 * The committed design/preview/status.html is a snapshot with a banner saying so; regenerate rather than
 * editing it (an edit there is a design that no code produces).
 */

$repo = dirname(__DIR__);
$root = $repo . '/backend/v';
$out = isset($argv[1]) ? $argv[1] : $repo . '/design/preview/status.html';

require $root . '/lib/Util.php';
require $root . '/lib/Country.php';
require $root . '/lib/Parser.php';
require $root . '/lib/Probe.php';
require $root . '/lib/Score.php';
require $root . '/lib/Builder.php';
require $root . '/lib/Version.php';
require $root . '/lib/Ai.php';
require $root . '/lib/AiTune.php';
require $root . '/lib/Status.php';

$tmp = sys_get_temp_dir() . '/meelano-snapshot-' . getmypid();
$rr = function ($dir) use (&$rr) {
    foreach (glob($dir . '/*') ?: [] as $f) {
        is_dir($f) ? $rr($f) : @unlink($f);
    }
    @rmdir($dir);
};
$rr($tmp);
@mkdir($tmp . '/fixtures', 0777, true);
foreach (['configs', 'proxies', 'monosans'] as $f) {
    $src = $root . '/data/fixtures/' . $f . ($f === 'monosans' ? '.json' : '.txt');
    if (is_file($src)) {
        @copy($src, $tmp . '/fixtures/' . basename($src));
    }
}
if (is_file($root . '/data/vip_raw.example.txt')) {
    Util::writeAtomic($tmp . '/vip_raw.txt', (string) file_get_contents($root . '/data/vip_raw.example.txt'));
}

$cfg = require $root . '/config.php';
$cfg['dataDir'] = $tmp;
$cfg['vip']['url'] = '';
$cfg['free']['enabled'] = true;
$cfg['free']['url'] = '';
$cfg['free']['sourcesOverride'] = [
    'fixture-configs' => ['kind' => 'config', 'take' => 50, 'url' => 'fixture://configs'],
    'fixture-proxies' => ['kind' => 'proxy',  'take' => 50, 'url' => 'fixture://proxies'],
    'fixture-json'    => ['kind' => 'json',   'take' => 50, 'url' => 'fixture://monosans.json'],
];
$cfg['free']['probe']['enabled'] = false;
$cfg['free']['minNodes'] = 1;
$cfg['secret'] = str_repeat('ab', 32);
Util::setConfig($cfg);      // the same hook the test suite uses - there is no other way in

$vip = Builder::doBuild('vip', 600);
$free = Builder::doBuild('free', 600);
Util::cacheWrite('vip', $vip);
Util::cacheWrite('free', $free);

$html = Status::renderHtml(Status::summary(true));
// the banner goes before </body> so it cannot land inside a <style> block
$note = '<p style="color:#8FA1AD;font-size:12px;margin-top:18px;text-align:center">'
    . 'نمونه‌ی توسعه، تولیدشده از fixtureها — منبع: backend/v/lib/Status.php · بازتولید: php tools/status-snapshot.php</p>';
$html = str_replace('</body>', $note . '</body>', $html);

if (!is_dir(dirname($out))) {
    @mkdir(dirname($out), 0777, true);
}
file_put_contents($out, $html);
$rr($tmp);
// no STDERR constant: php-cli has it, the wasm/other SAPIs do not, and a tool that dies on its last line
// after writing the file is the worst kind of tool
fwrite(fopen('php://stderr', 'w'), "wrote $out (" . strlen($html) . " bytes)\n");
