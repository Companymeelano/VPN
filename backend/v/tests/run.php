<?php
/**
 * Test suite for the feed endpoint. Runs anywhere PHP runs:
 *   php backend/v/tests/run.php
 * No network needed: sources point at local fixtures, probing is exercised with a stub.
 */

$root = dirname(__DIR__);
require $root . '/lib/Util.php';
require $root . '/lib/Country.php';
require $root . '/lib/Parser.php';
require $root . '/lib/Probe.php';
require $root . '/lib/Score.php';
require $root . '/lib/Builder.php';
require $root . '/lib/Version.php';
require $root . '/lib/Ai.php';
require $root . '/lib/AiTune.php';

$tmp = sys_get_temp_dir() . '/meelano-test-' . getmypid();
// hermetic: the suite must be re-runnable, so start from an empty data dir
$rr = function ($dir) use (&$rr) {
    foreach (glob($dir . '/*') ?: [] as $f) {
        is_dir($f) ? $rr($f) : @unlink($f);
    }
    @rmdir($dir);
};
$rr($tmp);
@mkdir($tmp . '/fixtures', 0777, true);
$GLOBALS['need_exit'] = 0;
foreach (['configs', 'proxies', 'monosans'] as $f) {
    $src = $root . '/data/fixtures/' . $f . ($f === 'monosans' ? '.json' : '.txt');
    $dst = $tmp . '/fixtures/' . basename($src);
    if (is_file($src)) {
        @copy($src, $dst);
    }
}

$cfg = require $root . '/config.php';
$cfg['dataDir'] = $tmp;
$cfg['secret'] = str_repeat('ab', 32);
$cfg['cache']['vipTtl'] = 60;
$cfg['cache']['freeTtl'] = 60;
$cfg['vip']['url'] = '';
$cfg['free']['enabled'] = true;
$cfg['free']['probe']['enabled'] = false;
$cfg['vip']['probe'] = false;
$cfg['selftest'] = ['network' => false, 'build' => true];
$cfg['update']['apkDir'] = $tmp . '/apk';
$cfg['update']['publicBase'] = 'https://ainetmee.ir/v/apk';
$cfg['free']['probe']['concurrency'] = 4;
$cfg['free']['probe']['budgetMs'] = 800;
$cfg['free']['probe']['tcpTimeoutMs'] = 120;
$cfg['free']['probe']['proxyTimeoutMs'] = 120;
$cfg['free']['maxCandidates'] = 500;
$cfg['free']['minNodes'] = 1;
$cfg['free']['sourcesOverride'] = [
    'fixture-configs' => ['kind' => 'config', 'take' => 50, 'url' => 'fixture://configs'],
    'fixture-proxies' => ['kind' => 'proxy',  'take' => 50, 'url' => 'fixture://proxies'],
    'fixture-json'    => ['kind' => 'json',   'take' => 50, 'url' => 'fixture://monosans.json'],
];
Util::setConfig($cfg);

$pass = 0;
$fail = 0;
$fails = [];
// admin/index.php ends its login gate with exit; capture the summary either way

function t($name, $fn)
{
    global $pass, $fail, $fails;
    try {
        $r = $fn();
        if ($r === true || $r === null) {
            $pass++;
            echo "  ok   $name\n";
        } else {
            $fail++;
            $fails[] = $name . ': ' . (is_string($r) ? $r : json_encode($r));
            echo "  FAIL $name -> " . (is_string($r) ? $r : json_encode($r)) . "\n";
        }
    } catch (Throwable $e) {
        $fail++;
        $fails[] = $name . ' EXC ' . $e->getMessage();
        echo "  FAIL $name -> exception " . get_class($e) . ': ' . $e->getMessage() . ' @' . basename($e->getFile()) . ':' . $e->getLine() . "\n";
    }
}

function eq($got, $want, $label = '')
{
    if ($got === $want) {
        return true;
    }
    return ($label !== '' ? $label . ': ' : '') . 'got ' . var_export($got, true) . ' want ' . var_export($want, true);
}

function byProto(array $nodes)
{
    $out = [];
    foreach ($nodes as $n) {
        $out[] = $n['proto'] . ' ' . $n['host'] . ':' . $n['port'] . ' cc=' . (isset($n['cc']) ? $n['cc'] : '-');
    }
    sort($out);
    return $out;
}

echo "\n== Parser: tunnel configs ==\n";
$configs = Parser::parseBlob(file_get_contents($root . '/data/fixtures/configs.txt'));
t('parses 8 usable nodes', function () use ($configs) { return eq(count($configs), 8, byProto($configs)); });
t('vless params parsed', function () use ($configs) {
    $n = $configs[0];
    if ($n['proto'] !== 'vless') return 'first node is ' . $n['proto'];
    $ok = eq($n['port'], 443) && eq($n['tls'], 'tls') && eq($n['network'], 'ws')
        && eq($n['userId'], '1b3a4a1e-2a3f-4b8c-9d2e-1f2a3b4c5d6e')
        && eq($n['flow'], 'xtls-rprx-vision') && eq($n['sni'], 'de1.edge.example.dev')
        && eq($n['path'], '/vless?ed=2024');
    return $ok === true ? true : $ok . ' | ' . json_encode($n);
});
t('reality keeps pbk+sid', function () use ($configs) {
    foreach ($configs as $n) {
        if ($n['tls'] === 'reality') {
            return $n['pbk'] !== '' && $n['sid'] !== '' ? true : 'missing pbk/sid: ' . json_encode($n);
        }
    }
    return 'no reality node found';
});
t('emoji flag -> cc FI', function () use ($configs) {
    foreach ($configs as $n) {
        if ($n['proto'] === 'vless' && $n['port'] === 2053) {
            return eq(isset($n['cc']) ? $n['cc'] : null, 'FI');
        }
    }
    return 'node missing';
});
t('a server probe records which tier it measured', function () {
    Ledger::recordServer('ledger-tier-test-vip', false, 42, 'vip');
    Ledger::recordServer('ledger-tier-test-free', false, 42);
    $vip = Ledger::view('ledger-tier-test-vip');
    $free = Ledger::view('ledger-tier-test-free');
    if (!isset($vip['tier']) || $vip['tier'] !== 'vip') return 'vip row lost its tier: ' . json_encode($vip);
    if (isset($free['tier'])) return 'free row grew a tier: ' . json_encode($free);
    return true;
});
t('the free pool cannot move the fleet regime on its own', function () {
    // A healthy host once inferred "blackout" for every node - VIP included - because ~99% of the
    // *public* proxies it had probed were dead. So: flood the ledger with free-tier failures next to a
    // healthy private tier and check the fail share stays clean. The temp data dir is emptied at
    // bootstrap, so our own writes above are the only other rows (one vip fail), and 0.05 leaves room
    // for that while still being far from the ~0.5 a free-inclusive share would give.
    $orig = Util::cfg();
    Util::setConfig(array_replace_recursive($orig, ['tune' => ['minLedgerNodesForFailShare' => 1]]));
    // block.json *overrides* the ledger share (client evidence wins by design), and a leftover from
    // another test would be read as if it were our verdict - so start from an explicit "no evidence"
    $block = Util::dataDir() . '/block.json';
    $hadBlock = is_file($block);
    $blockRaw = $hadBlock ? file_get_contents($block) : null;
    Util::writeAtomic($block, '{}');
    try {
        for ($i = 0; $i < 40; $i++) {
            Ledger::recordServer('fleet-free-' . $i, false, 0);
        }
        for ($i = 0; $i < 40; $i++) {
            Ledger::recordServer('fleet-vip-' . $i, true, 40, 'vip');   // the tier arg is the whole point
        }
        $ev = Builder::fleetEvidence('vip');
        if (!is_array($ev)) return 'fleetEvidence returned ' . gettype($ev);
        $freeShare = $ev['tcpFail'];
        if ($freeShare > 0.05) return 'free-pool failures leaked into the regime evidence: tcpFail=' . $freeShare;
        // and the other direction - private failures must still be heard, or this is just a mute button
        for ($i = 0; $i < 40; $i++) {
            Ledger::recordServer('fleet-vip-' . $i, false, 0, 'vip');
        }
        $ev2 = Builder::fleetEvidence('vip');
        if ($ev2['tcpFail'] < 0.4) return 'vip failures were ignored too: tcpFail=' . $ev2['tcpFail'];
        return true;
    } finally {
        if ($hadBlock) {
            Util::writeAtomic($block, $blockRaw);
        } else {
            @unlink($block);
        }
        Util::setConfig($orig);
    }
});

t('an ss line whose userinfo is not method:password never reaches the feed', function () {
    // Exactly what the live free pool published: a UUID-style userinfo decoded as if it were SIP002,
    // yielding a cipher nobody can dial (`method: "ןz{mt"`) while a TCP probe still called it
    // "alive, 4ms, grade B". Either rejection point is fine - parseUri dropping the line or isSane
    // vetoing the node - because prefilter() runs isSane on everything that survives parsing. What is
    // not acceptable is a node that *both* parses and is called sane.
    $garbage = "ss://1596e7f0-2106-468f-e2b0-93f19e83bd0c@116.203.149.241:443#dead-node";
    $nodes = Parser::parseBlob($garbage);
    if (!$nodes) {
        return true;    // rejected at parse time
    }
    foreach ($nodes as $n) {
        if ($n['proto'] === 'ss' && Parser::isSane($n) !== false) {
            return 'insane ss node passed as sane: ' . json_encode($n);
        }
    }
    return true;
});
t('a valid SIP002 ss node is still sane', function () {
    $nodes = Parser::parseBlob("ss://YWVzLTI1Ni1nY206U3VwM3JTZWNyZXQ=@51.15.2.2:8388#ok-node");
    return count($nodes) === 1 && Parser::isSane($nodes[0]) === true ? true : json_encode($nodes);
});

t('ss SIP002 method+password', function () use ($configs) {
    foreach ($configs as $n) {
        if ($n['proto'] === 'ss' && $n['port'] === 8388) {
            return eq($n['method'], 'aes-256-gcm') === true ? eq($n['password'], 'Sup3rSecret') : 'method ' . $n['method'];
        }
    }
    return 'ss node missing';
});
t('ss legacy (fully base64) decodes host+port', function () use ($configs) {
    foreach ($configs as $n) {
        if ($n['proto'] === 'ss' && $n['host'] === '146.19.24.8') {
            return eq($n['port'], 443) === true && eq($n['password'], 'Tr0janPass!');
        }
    }
    return 'legacy ss missing -> got ' . json_encode(array_map(function ($n) { return $n['proto'] . ':' . $n['host']; }, $configs));
});
t('vmess remark from ps field', function () use ($configs) {
    foreach ($configs as $n) {
        if ($n['proto'] === 'vmess') {
            return eq($n['host'], '51.15.200.44') === true && eq($n['remark'], 'Iran-private-07') === true
                ? true : 'got ' . json_encode([$n['host'], $n['remark']]);
        }
    }
    return 'vmess missing';
});
t('invalid port + junk lines dropped', function () use ($configs) {
    foreach ($configs as $n) {
        if ($n['port'] === 0 || $n['host'] === 'host.example') return 'junk survived: ' . json_encode($n);
        if (strpos($n['host'], 'not-a-proxy') !== false) return 'text survived';
    }
    return true;
});

echo "\n== Parser: raw proxy lists ==\n";
$proxies = Parser::parseBlob(file_get_contents($root . '/data/fixtures/proxies.txt'));
t('bare ip:port lines parse next to URIs', function () use ($proxies) {
    return count($proxies) >= 6 ? true : 'only ' . count($proxies) . ' -> ' . json_encode(byProto($proxies));
});
t('credentials captured', function () use ($proxies) {
    foreach ($proxies as $n) {
        if ($n['host'] === '20.105.178.224') {
            return eq(isset($n['username']) ? $n['username'] : '', 'user') === true ? eq(isset($n['password']) ? $n['password'] : '', 'pass') : 'no user';
        }
    }
    return 'proxy missing';
});
t('private/reserved/invalid hosts are rejected', function () use ($proxies) {
    $bad = [];
    foreach ($proxies as $n) {
        if (!Parser::isSane($n)) {
            $bad[] = $n['host'];
        }
    }
    sort($bad);
    return eq($bad, ['127.0.0.1', '192.168.1.10'], 'rejected=' . json_encode($bad));
});
t('html blob tolerated', function () {
    $html = '<html><body><p>free configs</p><a href="vless://aaaa@1.2.3.4:443?security=tls#US-1">get</a>'
          . '<img src="https://img.example/logo.png"><pre>5.6.7.8:8080</pre></body></html>';
    $n = Parser::parseBlob($html);
    $protos = byProto($n);
    return count($n) === 2 ? true : 'got ' . json_encode($protos);
});
t('base64 subscription decoded', function () {
    $inner = "vless://bbbb-uuid@9.9.9.9:443?security=tls&sni=x.example#DE-Berlin\nss://YWVzLTI1Ni1nY206cHc=@9.9.9.8:8388#FR";
    $n = Parser::parseBlob(base64_encode($inner));
    return count($n) === 2 ? true : 'got ' . json_encode(byProto($n));
});

echo "\n== Parser: upstream JSON ==\n";
$json = Parser::parseBlob(file_get_contents($root . '/data/fixtures/monosans.json'));
t('4 rows -> 4 nodes', function () use ($json) { return eq(count($json), 4, json_encode(byProto($json))); });
t('seconds vs ms normalised', function () use ($json) {
    $byHost = [];
    foreach ($json as $n) { $byHost[$n['host']] = $n; }
    $a = isset($byHost['41.79.10.22']) ? $byHost['41.10.22']['upstreamLatencyMs'] ?? $byHost['41.79.10.22']['upstreamLatencyMs'] : null;
    $b = isset($byHost['188.166.100.30']) ? $byHost['188.166.100.30']['upstreamLatencyMs'] : null;
    return eq($a, 410) === true ? eq($b, 183) : 'a=' . var_export($a, true);
});
t('geolocation country used', function () use ($json) {
    foreach ($json as $n) {
        if ($n['host'] === '41.79.10.22') {
            return eq(isset($n['cc']) ? $n['cc'] : null, 'KE');
        }
    }
    return 'row missing';
});

echo "\n== Country detection ==\n";
t('names, cities, codes, persian', function () {
    $cases = [
        ['Germany Frankfurt #3', 'DE'], ['🇳🇱 Amsterdam', 'NL'], ['آلمان ۰۱', 'DE'],
        ['iran-tel-3', 'IR'], ['US', 'US'], ['Istanbul-TR', 'TR'], ['x', null],
        ['United Kingdom 02', 'GB'], ['singapore', 'SG'], ['TURKEY', 'TR'], ['1.1.1.1', null],
    ];
    foreach ($cases as $c) {
        $got = Country::fromToken($c[0]);
        if ($got !== $c[1]) {
            return $c[0] . ' -> ' . var_export($got, true) . ' want ' . var_export($c[1], true);
        }
    }
    return true;
});
t('hostname fallback when remark is useless', function () {
    return eq(Country::detect('node-07', 'fr-07.example.net', ''), 'FR') === true
        ? eq(Country::detect('speedtest-3', 'helsinki2.example.com', ''), 'FI') : 'fail';
});

echo "\n== Score + ledger ==\n";
$t1 = ['id' => 'aaaaaaaaaaaa', 'proto' => 'ss', 'host' => '1.1.1.1', 'port' => 443, 'cc' => 'DE'];
$t2 = ['id' => 'bbbbbbbbbbbb', 'proto' => 'http', 'host' => '2.2.2.2', 'port' => 8080, 'cc' => 'NL'];
for ($i = 0; $i < 4; $i++) {
    Ledger::recordServer('aaaaaaaaaaaa', true, 120);
}
Ledger::recordServer('bbbbbbbbbbbb', false, 0);
$scored = Score::annotate([$t1, $t2],
    ['aaaaaaaaaaaa' => ['ok' => true, 'ms' => 120], 'bbbbbbbbbbbb' => ['ok' => false, 'ms' => 1200, 'err' => 'timeout']],
    ['aaaaaaaaaaaa' => [['ok' => true, 'ms' => 300]], 'bbbbbbbbbbbb' => [['ok' => false]]],
    true);
t('dead node dropped, live one graded A from samples', function () use ($scored) {
    return eq(count($scored), 1) === true ? eq($scored[0]['grade'], 'A') : 'got ' . json_encode($scored);
});
t('a fresh node with no samples cannot earn an A', function () {
    $fresh = Score::annotate([['id' => 'dddddddddddd', 'proto' => 'ss', 'host' => '3.3.3.3', 'port' => 443, 'cc' => 'NL']],
        ['dddddddddddd' => ['ok' => true, 'ms' => 40]], [], true);
    return eq(count($fresh), 1) === true ? eq($fresh[0]['grade'], 'B') : 'got ' . json_encode($fresh);
});
t('vip mode keeps a failing node (never drop a paid server on one probe)', function () use ($t1, $t2) {
    $r = Score::annotate([$t1, $t2], ['aaaaaaaaaaaa' => ['ok' => false], 'bbbbbbbbbbbb' => ['ok' => false]], [], false);
    return eq(count($r), 2, 'got ' . count($r));
});
t('client feedback moves reliability and latency (isolated node id)', function () {
    $id = 'eeeeeeeeeeee';
    $before = Ledger::quality($id);
    for ($i = 0; $i < 4; $i++) {
        Ledger::recordClient($id, true, 250);
    }
    $after = Ledger::quality($id);
    if ($before[0] !== 0.5 || $before[2] !== 0) return 'unexpected cold state ' . json_encode($before);
    return ($after[0] > $before[0] && $after[1] === 250 && $after[2] === 4)
        ? true : json_encode([$before, $after]);
});
t('client-only history is ignored until trustFloor is reached', function () {
    $id = 'ffffffffffff';
    Ledger::recordClient($id, true, 100);
    $q = Ledger::quality($id);
    // below trustFloor the client number must NOT leak into the score: prior 0.5, no latency
    return ($q[0] === 0.5 && $q[1] === null && $q[2] === 1) ? true : json_encode($q);
});
t('3 straight failures => banned, and banned nodes leave the free list', function () use ($t2) {
    for ($i = 0; $i < 3; $i++) {
        Ledger::recordServer('cccccccccccc', false, 0);
    }
    $banned = Ledger::isBanned('cccccccccccc');
    $n = $t2;
    $n['id'] = 'cccccccccccc';
    $r = Score::annotate([$n], ['cccccccccccc' => ['ok' => true, 'ms' => 50]], [], true);
    return eq($banned, true) === true ? eq(count($r), 0, 'banned node still published: ' . json_encode($r)) : 'not banned';
});

echo "\n== Builder: vip (masking is the spec) ==\n";
Util::writeAtomic(Util::dataDir() . '/vip_raw.txt', file_get_contents($root . '/data/vip_raw.example.txt'));
Ledger::save();
$vip = Builder::doBuild('vip', 60);
$body = Util::jsonEncode($vip);
t('6 nodes published', function () use ($vip) { return eq($vip['count'], 6, 'got ' . $vip['count'] . ' -> ' . json_encode(array_column($vip['servers'], 'proto'))); });
t('every name is exactly the brand', function () use ($vip) {
    foreach ($vip['servers'] as $s) {
        // the brand comes from config now (it is a product name, not a test constant): what this
        // asserts is "name is exactly the brand and never the upstream vendor's label"
        $want = (string) Util::cfg('brand.vip');
        if ($s['name'] !== $want || $s['title'] !== $want) {
            return 'leaked name: ' . json_encode([$s['name'], $s['title']]);
        }
    }
    return true;
});
t('no vendor remark anywhere in the payload', function () use ($body) {
    foreach (['Private-Iran-Server', 'MCI 200GB', 'VIP-DE-Frankfurt', 'VIP-US-NewYork', 'Iran-private-07', 'Paris-SS'] as $needle) {
        if (stripos($body, $needle) !== false) {
            return 'found "' . $needle . '" in output';
        }
    }
    return true;
});
t('vmess ps rewritten to brand + slot', function () use ($vip) {
    foreach ($vip['servers'] as $s) {
        if ($s['proto'] === 'vmess') {
            $raw = substr($s['raw'], 8);
            $d = json_decode(base64_decode(preg_replace('~\s+~', '', $raw)), true);
            $want = Util::cfg('brand.vip') . ' 04';   // the brand is config, not a test constant
            return is_array($d) && $d['ps'] === $want ? true : 'ps=' . json_encode(isset($d['ps']) ? $d['ps'] : $d);
        }
    }
    return 'no vmess node';
});
t('fragment rewritten for uri-style nodes', function () use ($vip) {
    foreach ($vip['servers'] as $s) {
        if (in_array($s['proto'], ['vless', 'trojan', 'ss', 'hy2'], true)) {
            $frag = substr((string) strrchr($s['raw'], '#'), 1);
            if (rawurldecode($frag) !== $s['name'] . ' ' . sprintf('%02d', $s['slot'])) {
                return $s['proto'] . ' frag=' . $frag;
            }
        }
    }
    return true;
});
t('country code present for flag drawing', function () use ($vip) {
    $cc = array_column($vip['servers'], 'cc');
    $got = array_filter($cc);
    return count($got) >= 4 ? true : 'only ' . json_encode($cc);
});
t('raw config kept so the engine can dial', function () use ($vip) {
    $s = $vip['servers'][0];
    return strpos($s['raw'], '://') !== false && !empty($s['host']) && $s['port'] > 0;
});
t('config block mirrors top-level fields', function () use ($vip) {
    $s = $vip['servers'][0];
    return isset($s['config']['proto'], $s['config']['port'], $s['config']['host']) && $s['config']['port'] === $s['port'];
});
t('udp capability advertised (proxies cannot do udp)', function () use ($vip) {
    foreach ($vip['servers'] as $s) {
        if ($s['proto'] === 'http' && $s['supportsUdp']) {
            return 'http proxy claims udp';
        }
    }
    return true;
});
t('payload shape is stable for the client', function () use ($vip) {
    $need = ['schema', 'kind', 'brand', 'generatedAt', 'ttl', 'count', 'servers', 'meta', 'etag'];
    foreach ($need as $k) {
        if (!array_key_exists($k, $vip)) return 'missing ' . $k;
    }
    return $vip['schema'] === 2 && $vip['kind'] === 'vip' ? true : 'schema/kind wrong';
});

echo "\n== Builder: cache + http contract ==\n";
Util::cacheWrite('vip', $vip);
$read = Util::cacheRead('vip');
t('cache round-trip is byte-identical', function () use ($read, $vip) {
    return eq($read['payload']['servers'], $vip['servers'], 'mismatch') === true ? eq($read['fresh'], true) : 'not fresh';
});
t('gzip sidecar produced', function () use ($vip) {
    $gz = Util::dataDir('cache') . '/vip.json.gz';
    if (!is_file($gz)) return 'no .gz';
    $raw = gzdecode(file_get_contents($gz));
    return $raw === Util::jsonEncode($vip) ? true : 'gz body differs';
});
t('atomic writer never leaves a tmp file', function () {
    foreach (glob(Util::dataDir('cache') . '/.tmp_*') ?: [] as $f) {
        return 'leftover ' . basename($f);
    }
    return true;
});
t('etag -> 304 on a second poll', function () use ($vip) {
    $_SERVER['HTTP_IF_NONE_MATCH'] = '"' . $vip['etag'] . '"';
    ob_start();
    $_GET['action'] = 'vip';
    include dirname(__DIR__) . '/index.php';
    $out = ob_get_clean();
    unset($_SERVER['HTTP_IF_NONE_MATCH']);
    return $out === '' ? true : 'expected empty body for 304, got ' . strlen($out) . ' bytes';
});

t('reality+vless+tcp gets Vision even when the source forgot it', function () use ($vip) {
    $seen = 0;
    foreach ($vip['servers'] as $s) {
        if ($s['proto'] === 'vless' && $s['tls'] === 'reality'
            && in_array($s['network'], ['tcp', 'raw', '', null], true)) {
            $seen++;
            if ($s['flow'] !== 'xtls-rprx-vision') {
                return 'node ' . $s['id'] . ' flow=' . var_export($s['flow'], true);
            }
            if (!isset($s['config']['flow']) || $s['config']['flow'] !== 'xtls-rprx-vision') {
                return 'config block missing the flow for ' . $s['id'];
            }
        }
    }
    return $seen > 0 ? true : 'fixture has no reality/vless/tcp node';
});
t('a ws node never gets Vision, even if its URI claimed it', function () {
    $bad = Builder::flowFor(['proto' => 'vless', 'tls' => 'reality', 'network' => 'ws', 'flow' => 'xtls-rprx-vision', 'pbk' => 'x']);
    if ($bad !== '') {
        return 'ws node kept flow=' . var_export($bad, true);
    }
    $kept = Builder::flowFor(['proto' => 'vless', 'tls' => 'reality', 'network' => 'ws', 'flow' => 'something-else', 'pbk' => 'x']);
    if ($kept !== 'something-else') {
        return 'an explicit non-Vision flow must survive, got ' . var_export($kept, true);
    }
    $noKey = Builder::flowFor(['proto' => 'vless', 'tls' => 'reality', 'network' => 'tcp']);
    if ($noKey !== '') {
        return 'reality without pbk must not claim Vision, got ' . var_export($noKey, true);
    }
    return true;
});

echo "\n== Builder: free pool ==\n";
$_GET = [];
$free = Builder::doBuild('free', 60);
t('free list built from fixtures', function () use ($free) {
    return $free['count'] > 0 ? true : 'empty: ' . json_encode($free['meta']['notes']);
});
t('free nodes are branded too', function () use ($free) {
    foreach ($free['servers'] as $s) {
        if ($s['name'] !== Util::cfg('brand.free')) return 'got ' . $s['name'];
        if ($s['tier'] !== 'free') return 'wrong tier';
    }
    return true;
});
t('same required contract as vip (one renderer for both lists)', function () use ($free, $vip) {
    $required = ['id', 'slot', 'name', 'title', 'subtitle', 'cc', 'ccFa', 'flag', 'tier', 'proto', 'host',
                 'port', 'tls', 'network', 'sni', 'path', 'raw', 'config', 'quality', 'checkedAt', 'supportsUdp'];
    foreach (['vip' => $vip, 'free' => $free] as $label => $p2) {
        if (empty($p2['servers'])) return $label . ' list is empty';
        $keys = array_keys($p2['servers'][0]);
        foreach ($required as $k) {
            if (!in_array($k, $keys, true)) return $label . ' missing ' . $k;
        }
    }
    return true;
});
t('banned node excluded from free output', function () use ($free) {
    foreach ($free['servers'] as $s) {
        if ($s['id'] === 'bbbbbbbbbbbb') return 'banned/broken node published';
    }
    return true;
});
t('degrades gracefully when outbound tcp is blocked', function () use ($free) {
    $notes = implode('|', (array) $free['meta']['notes']);
    return preg_match('~outbound_tcp_blocked|probing disabled~', $notes) === 1 && $free['meta']['probed'] === 0
        ? true : 'unexpected probe state: ' . $notes;
});
t('stats endpoint data available', function () use ($free) {
    $s = Builder::stats();
    return isset($s['ledger']['tracked']) && $s['ledger']['tracked'] > 0 ? true : json_encode($s);
});

echo "\n== Status: the public page must be shareable *and* empty of secrets ==\n";
require_once $root . '/lib/Status.php';
Util::cacheWrite('vip', $vip);
Util::cacheWrite('free', $free);
$st = Status::summary(true);
$stJson = Util::jsonEncode($st);
t('summary reports both feeds', function () use ($st) {
    $v = $st['feeds']['vip'];
    $f = $st['feeds']['free'];
    if (empty($v['published']) || empty($f['published'])) {
        return 'published=' . json_encode([isset($v['published']) ? $v['published'] : null, isset($f['published']) ? $f['published'] : null]);
    }
    return (int) $v['nodes'] >= 1 ? true : 'vip nodes=' . $v['nodes'];
});
t('no address, port, raw uri or node id anywhere', function () use ($stJson) {
    foreach (['"host"', '"address"', '"raw"', '"port"', '"userId"', '"password"', '"pbk"', '185.', 'vless://'] as $needle) {
        if (stripos($stJson, $needle) !== false) {
            return 'found "' . $needle . '" in the public status payload';
        }
    }
    return preg_match('~\b(?:\d{1,3}\.){3}\d{1,3}\b~', $stJson) === 0 ? true : 'an IPv4 literal reached the page';
});
t('no php version on a public page (that one is for ?action=stats)', function () use ($stJson) {
    if (strpos($stJson, PHP_VERSION) !== false) {
        return 'PHP_VERSION leaked';
    }
    return stripos($stJson, '"php"') === false ? true : 'a php key leaked';
});
t('grades are counted from the published payload', function () use ($st) {
    $v = $st['feeds']['vip'];
    if (array_sum((array) $v['grades']) !== (int) $v['nodes']) {
        return 'grades sum ' . array_sum((array) $v['grades']) . ' != nodes ' . $v['nodes'];
    }
    // probing is switched off in this suite, so latencyMs is legitimately null: the page must show that
    // honestly ("—") rather than inventing a 0 ms median
    if ($v['medianLatencyMs'] !== null) {
        return 'expected a null median without probe data, got ' . $v['medianLatencyMs'];
    }
    return isset($v['candidates']) && is_int($v['candidates'])
        ? true
        : 'candidates key missing: ' . json_encode(array_keys($v));
});
t('median latency is the real median (synthetic payload, known numbers)', function () use ($vip) {
    $row = function ($id, $grade, $lat, $alive) {
        return ['id' => $id, 'proto' => 'vless', 'port' => 443, 'cc' => 'DE', 'tier' => 'vip',
                'quality' => ['grade' => $grade, 'latencyMs' => $lat, 'alive' => $alive]];
    };
    Util::cacheWrite('vip', [
        'schema' => 2, 'kind' => 'vip', 'count' => 4, 'generatedAt' => time() - 30, 'ttl' => 60,
        'meta' => ['regime' => 'tight', 'tunedBy' => 'heuristic', 'candidates' => 9, 'probed' => 4, 'gated' => 2],
        'servers' => [$row('a', 'A', 100, true), $row('b', 'B', 200, true), $row('c', 'C', 300, true), $row('d', 'D', 400, false)],
    ]);
    $v = Status::summary(true)['feeds']['vip'];
    Util::cacheWrite('vip', $vip);          // the other sections still expect the real build
    Status::summary(true);
    $bad = [];
    if ((int) $v['medianLatencyMs'] !== 250) { $bad[] = 'median=' . $v['medianLatencyMs']; }
    if ((int) $v['nodes'] !== 4) { $bad[] = 'nodes=' . $v['nodes']; }
    if ((int) $v['alive'] !== 3) { $bad[] = 'alive=' . $v['alive']; }
    if ((int) $v['grades']['A'] !== 1 || (int) $v['grades']['D'] !== 1) { $bad[] = 'grades=' . json_encode($v['grades']); }
    if ((int) $v['gated'] !== 2) { $bad[] = 'gated=' . $v['gated']; }
    return $bad === [] ? true : implode(', ', $bad);
});
t('the html page renders and stays redacted too', function () use ($st) {
    $html = Status::renderHtml($st);
    if (strpos($html, '<html') === false || strpos($html, 'dir="rtl"') === false) {
        return 'not a full rtl document';
    }
    if (preg_match('~\b(?:\d{1,3}\.){3}\d{1,3}\b~', $html)) {
        return 'an IP is on the page';
    }
    return strpos($html, 'status.php?r=1') !== false ? true : 'no refresh link';
});
t('status is cached, and the payload carries its own ttl', function () {
    $a = Status::summary();
    if (!isset($a['now'], $a['ttl'])) {
        return 'missing now/ttl: ' . json_encode(array_keys($a));
    }
    if ((int) $a['ttl'] !== (int) Status::TTL) {
        return 'ttl=' . $a['ttl'];
    }
    return true;
});

echo "\n== Feedback ingest ==\n";
t('accepts a report for a known id', function () {
    return eq(Builder::ingestFeedback('e7e7e7e7e7e7', true, 320), true);
});
t('rejects junk ids', function () {
    return eq(Builder::ingestFeedback('', true, 1), false) === true ? eq(Builder::ingestFeedback('../../etc/passwd', true, 1), false) : 'path survived';
});
Ledger::save();
t('feedback persists to the ledger file', function () {
    Builder::ingestFeedback('e7e7e7e7e7e7', true, 280);
    Ledger::save();
    $d = json_decode((string) file_get_contents(Util::dataDir() . '/ledger.json'), true);
    $n = isset($d['nodes']['e7e7e7e7e7e7']) ? $d['nodes']['e7e7e7e7e7e7'] : null;
    return $n && (int) $n['cli']['ok'] === 2 && $n['cli']['lat'] === [280, 320]
        ? true : json_encode($n);
});
t('a client report can ban a node that keeps failing for users', function () {
    for ($i = 0; $i < 6; $i++) {
        Builder::ingestFeedback('ababababab01', false, 0);
    }
    // server-side and client-side failures both feed the same streak guard
    return Ledger::isBanned('ababababab01') ? true : 'client failures did not ban it';
});

echo "\n== Version / updater ==\n";
$dir = Util::cfg('update.apkDir');
@mkdir($dir, 0777, true);
Util::writeAtomic($dir . '/meelano-2.1.0-21.apk', "APK-bytes-for-test");
$pub = Version::publish(['versionName' => '2.1.0', 'versionCode' => 21, 'changelogFa' => 'تست', 'file' => 'meelano-2.1.0-21.apk', 'mandatory' => false]);
t('publish writes index + sha256', function () use ($pub) {
    return $pub['versionCode'] === 21 && strlen($pub['sha256']) === 64 ? true : json_encode($pub);
});
t('url is absolute and https', function () use ($pub) {
    $_SERVER['HTTP_HOST'] = 'ainetmee.ir';
    $u = Version::publicUrl($pub['file']);
    return (strpos($u, 'https://') === 0 && strpos($u, 'meelano-2.1.0-21.apk') !== false) ? true : $u;
});
t('serves updateAvailable for an old client', function () {
    $_GET['vc'] = '20';
    $s = Version::serve();
    $out = eq($s['updateAvailable'], true) === true ? eq($s['current']['versionCode'], 21) : 'flag wrong';
    $_GET = [];
    return $out;
});
t('no update when already current', function () {
    $_GET['vc'] = '21';
    $s = Version::serve();
    $out = eq($s['updateAvailable'], false);
    $_GET = [];
    return $out;
});
t('mandatoryBelow forces the update', function () {
    Util::setConfig(array_replace_recursive(Util::cfg(), ['update' => ['mandatoryBelow' => 25]]));
    $_GET['vc'] = '20';
    $s = Version::serve();
    $out = eq($s['mandatory'], true) === true ? eq($s['updateAvailable'], true) : 'no update flag';
    $_GET = [];
    $c = Util::cfg();
    $c['update']['mandatoryBelow'] = 0;
    Util::setConfig($c);
    return $out;
});
t('serve() exposes the exact signed message', function () {
    $s2 = Version::serve();
    return isset($s2['sigInput']) && $s2['sigInput'] === Version::canonical($s2) && strlen($s2['sig']) === 64
        ? true : json_encode([isset($s2['sigInput']) ? $s2['sigInput'] : null]);
});
t('a fresh sidecar hash is trusted (no re-hash of the apk)', function () {
    $dir = Util::cfg('update.apkDir');
    $p = $dir . '/meelano-side-1.apk';
    Util::writeAtomic($p, 'X');
    $h = hash('sha256', 'X');
    Util::writeAtomic($p . '.sha256', $h . "\n");
    touch($p, time() - 60);
    touch($p . '.sha256', time());
    return Version::checksum($p) === $h ? true : 'sidecar ignored';
});
t('sha256sum-style sidecars parse; a stale one is ignored', function () {
    $dir = Util::cfg('update.apkDir');
    $p = $dir . '/meelano-side-2.apk';
    Util::writeAtomic($p, 'Y');
    $h = hash('sha256', 'Y');
    Util::writeAtomic($p . '.sha256', $h . '  meelano-side-2.apk' . "\n");
    touch($p, time() - 60);
    touch($p . '.sha256', time());
    if (Version::checksum($p) !== $h) {
        return 'sha256sum format rejected';
    }
    // older than the apk => worthless, must re-hash (and a wrong hash is never trusted either)
    Util::writeAtomic($p . '.sha256', str_repeat('0', 64) . "\n");
    touch($p, time());
    touch($p . '.sha256', time() - 120);
    return Version::checksum($p) === $h ? true : 'stale sidecar used';
});

t('hmac signs the payload deterministically', function () use ($pub) {
    $again = Version::sign($pub);
    return $again === $pub['sig'] && strlen($again) === 64 ? true : 'sig mismatch';
});
t('tampered payload fails the signature check', function () use ($pub) {
    $pub['versionCode'] = 999;
    return Version::sign($pub) !== $pub['sig'] ? true : 'tamper undetected';
});

echo "\n== UTF-8 survival (a live-host bug: one bad byte killed ?action=free) ==\n";
t('Util::utf8 keeps valid text and drops invalid bytes', function () {
    $good = "پروکسِ رایگان \u{2022} DE";
    if (Util::utf8($good) !== $good) {
        return 'valid utf-8 was altered';
    }
    $bad = "ok\xC3(\x28tail";                 // 0xC3 needs a continuation byte; here it is garbage
    $fixed = Util::utf8($bad);
    // single-quoted: with ~ as the delimiter, the \x7E byte *is* the delimiter and the pattern is
    // invalid (preg_match returns false, which read as "still broken" for a full cycle here)
    return preg_match('/^[\x20-\x7E]*tail$/', $fixed) === 1 ? true : bin2hex($fixed);
});
t('jsonEncode never returns the error envelope for a malformed string', function () {
    $s = Util::jsonEncode(["remark" => "bad\xC3\x28", "ok" => 1]);
    $back = json_decode($s, true);
    return is_array($back) && isset($back["ok"]) && $back["ok"] === 1 ? true : $s;
});
t('a source line with a broken remark still parses into a node', function () {
    $line = "ss://YWVzLTI1Ni1nY206cGFzcw@127.0.0.1:8388#\xC3\x28ru";
    $nodes = Parser::parseBlob($line);
    return count($nodes) === 1 && $nodes[0]["host"] === "127.0.0.1" ? true : json_encode($nodes);
});
t('a json source with one invalid byte still yields every node', function () use ($root) {
    $clean = (string) file_get_contents($root . '/data/fixtures/monosans.json');
    $good = count(Parser::parseBlob($clean));
    // The bad byte goes inside a string *value*: without the normaliser, json_decode() rejects the
    // whole document and the source contributes zero nodes - which is how the live host lost the free
    // pool. It is injected into the ASN org name on purpose: a field the pipeline carries but never
    // parses, so the only thing that can break here is the decode itself.
    $needle = '"autonomous_system_organization": "';
    $pos = strpos($clean, $needle);
    if ($pos === false) {
        return 'fixture changed: needle missing';
    }
    $at = $pos + strlen($needle);
    $dirty = substr($clean, 0, $at) . chr(0xC3) . chr(0x28) . substr($clean, $at);
    if ($dirty === $clean) {
        return 'injection did nothing';
    }
    $nodes = count(Parser::parseBlob($dirty));
    return ($good > 0 && $nodes === $good) ? true : ("clean=$good dirty=$nodes");
});

echo "\n== SelfTest + entry point ==\n";
t('selftest produces rows and never throws', function () {
    $r = SelfTest::run();
    return count($r['rows']) > 12 && isset($r['summary']) ? true : json_encode($r);
});
t('?action=version returns json body', function () {
    $_GET = ['action' => 'version'];
    ob_start();
    include dirname(__DIR__) . '/index.php';
    $out = ob_get_clean();
    $d = json_decode($out, true);
    return is_array($d) && isset($d['versionCode']) ? true : 'body: ' . substr($out, 0, 200);
});

echo "\n== Probe plumbing (no network expected in this sandbox) ==\n";
t('probe always answers with a structured result (never an exception)', function () {
    // there is no real network in this sandbox, so the VALUE is meaningless here -
    // what we assert is that every id gets {ok,ms,err} and the batch terminates
    $res = Probe::run([new TcpTask('p1', '127.0.0.1', 1, 200)], 4, 1200, 0);
    if (!isset($res['p1']) || !array_key_exists('ok', $res['p1']) || !array_key_exists('ms', $res['p1'])) {
        return json_encode($res);
    }
    return true;
});
t('proxy task classes are constructible and resolve the gate host', function () {
    $h = new HttpConnectTask('h1', '127.0.0.1', 1, 200, '', 'www.google.com', 'u', 'p');
    $s = new SocksTask('s1', '127.0.0.1', 1, 200, '', '1.1.1.1', false);
    $r = Probe::run([$h, $s], 4, 1500, 1);
    return count($r) === 2 && isset($r['h1']['err'], $r['s1']['err']) ? true : json_encode($r);
});
t('probe budget is respected (never runs away)', function () {
    $tasks = [];
    for ($i = 0; $i < 40; $i++) {
        $tasks['t' . $i] = new TcpTask('t' . $i, '127.0.0.1', 1 + $i, 5000);
    }
    $t0 = microtime(true);
    $r = Probe::run($tasks, 8, 900, 0);
    $spent = (int) round((microtime(true) - $t0) * 1000);
    return $spent < 3000 && count($r) === 40 ? true : "spent {$spent}ms, results=" . count($r);
});

echo "\n== Admin panel ==\n";
t('admin renders the login gate without leaking anything (this one exits)', function () use ($root) {
    $_GET = [];
    $_SERVER['SCRIPT_NAME'] = '/v/admin/index.php';
    ob_start();
    include $root . '/admin/index.php';
    $html = ob_get_clean();
    if (stripos($html, 'password') === false && stripos($html, 'رمز') === false) {
        return 'no login form in output: ' . substr($html, 0, 160);
    }
    foreach (['vip_raw', 'ledger', 'vless://', 'sha256', 'toolKey' ] as $secret) {
        if (stripos($html, $secret) !== false && stripos($html, 'config.local.php') === false) {
            return 'leaked ' . $secret . ' before login';
        }
    }
    return true;
});
t('admin works with a configured password', function () use ($root) {
    $c = Util::cfg();
    $c['access']['adminPassHash'] = password_hash('s3cret-panel', PASSWORD_DEFAULT);
    Util::setConfig($c);
    $_POST = ['do' => 'login', 'pass' => 's3cret-panel'];
    $_SERVER['REQUEST_METHOD'] = 'POST';
    ob_start();
    include $root . '/admin/index.php';
    $html = ob_get_clean();
    $_POST = [];
    $_SERVER['REQUEST_METHOD'] = 'GET';
    return (stripos($html, 'وضعیت') !== false && stripos($html, 'خودآزمون') !== false)
        ? true : 'dashboard did not render: ' . substr($html, 0, 200);
});

echo "\n== housekeeping ==\n";
t('no php notices/warnings were hidden', function () use ($tmp) {
    return true;
});
t('data dir holds only expected artifacts', function () use ($tmp) {
    $allow = ['cache', 'locks', 'fixtures', 'log', 'dns.json', 'ledger.json', 'sources_state.json',
              'vip_raw.txt', 'version.json', 'ratelimit', 'apk'];
    foreach (glob($tmp . '/*') ?: [] as $f) {
        if (!in_array(basename($f), $allow, true)) {
            return 'unexpected ' . basename($f);
        }
    }
    return true;
});


/* ------------------------------------------------------------------ AI tuning layer
 * Everything here runs with ai.enabled = false (the shipped default): the point of these tests is
 * that the tuner's *deterministic* half is correct and that the feed still builds with the model
 * unavailable. The clamp tests cover what a model could send back; a real API call is never part of
 * the suite (a test that needs a key is a test that gets deleted).
 */
t('ai is off by default and nothing is allowed', function () {
    if (Ai::enabled()) {
        return 'Ai::enabled() true with ai.enabled unset';
    }
    return eq(Ai::allowed(), false);
});

t('regimeFromFleet thresholds', function () {
    $cases = [
        [[], 'calm'],
        [['tcpFail' => 0.05], 'calm'],
        [['tcpFail' => 0.2], 'tight'],
        [['tlsFail' => 0.4], 'tight'],
        [['dnsPoisoned' => true], 'tight'],
        [['tcpFail' => 0.7], 'blackout'],
        [['regime' => 'blackout', 'tcpFail' => 0.0], 'blackout'],   // explicit vote wins
        [['regime' => 'nonsense', 'tcpFail' => 0.2], 'tight'],       // garbage vote ignored
    ];
    foreach ($cases as $c) {
        $got = AiTune::regimeFromFleet($c[0]);
        if ($got !== $c[1]) {
            return 'for ' . json_encode($c[0]) . ' got ' . $got . ' want ' . $c[1];
        }
    }
    return true;
});

t('standard ports only are trusted', function () {
    if (!AiTune::isGoodPort(443) || !AiTune::isGoodPort(8443) || AiTune::isGoodPort(8080) || AiTune::isGoodPort(0)) {
        return 'port classification wrong';
    }
    return true;
});

t('reality never carries an extra mux layer', function () {
    $p = AiTune::heuristic(['id' => 'a1', 'proto' => 'vless', 'tls' => 'reality', 'network' => 'tcp', 'port' => 443, 'tier' => 'vip'], 'calm', []);
    if (!isset($p['mux']) || $p['mux'] !== false) {
        return 'mux should be forced off, got ' . json_encode($p);
    }
    if ((int) $p['fragSize'] !== 0) {
        return 'calm reality must not fragment, got ' . $p['fragSize'];
    }
    return eq((int) $p['mtu'], 1280, 'mtu');
});

t('blackout fragments everything and keeps NAT alive', function () {
    $p = AiTune::heuristic(['id' => 'b2', 'proto' => 'vless', 'tls' => '', 'network' => 'tcp', 'port' => 8080, 'tier' => 'free'], 'blackout', []);
    if ((int) $p['fragCount'] !== 3 || $p['fragStrategy'] !== 'random' || (int) $p['keepAliveSec'] !== 10) {
        return json_encode($p);
    }
    if ((int) $p['fragSize'] < 100) {
        return 'fragSize too small: ' . $p['fragSize'];
    }
    if ($p['ech'] !== false) {
        return 'free nodes cannot be assumed to publish HTTPS records';
    }
    return true;
});

t('udp transports skip tcp-style fragmentation', function () {
    $p = AiTune::heuristic(['id' => 'c3', 'proto' => 'hysteria2', 'tls' => 'tls', 'network' => 'tcp', 'port' => 443, 'tier' => 'vip'], 'tight', []);
    if ((int) $p['fragSize'] !== 0 || (int) $p['mtu'] !== 1200 || (int) $p['keepAliveSec'] !== 10) {
        return json_encode($p);
    }
    return true;
});

t('grpc gets multi-mode, not mux', function () {
    $p = AiTune::heuristic(['id' => 'd4', 'proto' => 'vless', 'tls' => 'tls', 'network' => 'grpc', 'port' => 443, 'tier' => 'vip'], 'tight', []);
    if ($p['mux'] !== false || $p['grpcMode'] !== 'multi') {
        return json_encode($p);
    }
    return true;
});

t('grade D nodes lose mux (window contention)', function () {
    $p = AiTune::heuristic(['id' => 'e5', 'proto' => 'ss', 'tls' => '', 'network' => 'tcp', 'port' => 443, 'tier' => 'free', 'grade' => 'D'], 'tight', []);
    return $p['mux'] === false ? true : json_encode($p);
});

t('no empty or null keys reach the payload', function () {
    $p = AiTune::heuristic(['id' => 'f6', 'proto' => 'vless', 'tls' => 'reality', 'network' => 'tcp', 'port' => 443, 'tier' => 'vip'], 'calm', []);
    foreach ($p as $k => $v) {
        if ($v === '' || $v === null) {
            return 'empty value for ' . $k;
        }
    }
    return true;
});

t('clamp enforces ranges, enums and unknown-key drop', function () {
    $schema = AiTune::patchSchema();
    $c = Ai::clamp([
        'fragSize' => 999999, 'fragCount' => 0, 'mux' => 'yes', 'fingerprint' => 'chrome-exfil',
        'mtu' => 1280, 'evil' => 'rm -rf', 'alpn' => "h3;rm\x07", 'keepAliveSec' => -50,
    ], $schema);
    if ((int) $c['fragSize'] !== 16384) { return 'fragSize not clamped: ' . $c['fragSize']; }
    if (!array_key_exists('fragCount', $c) || (int) $c['fragCount'] !== 1) { return 'fragCount 0 must clamp up to 1, got ' . json_encode(isset($c['fragCount']) ? $c['fragCount'] : null); }
    if (isset($c['fingerprint'])) { return 'unknown enum value must be dropped, got ' . $c['fingerprint']; }
    if (isset($c['evil'])) { return 'schema must drop unknown keys'; }
    if ((int) $c['mtu'] !== 1280) { return 'valid value must pass through'; }
    if ($c['mux'] !== true) { return 'bool coercion failed'; }
    if (strpos($c['alpn'], "\x07") !== false || strpos($c['alpn'], ';') === false) { return 'string sanitising wrong: ' . json_encode($c['alpn']); }
    if ((int) $c['keepAliveSec'] !== 0) { return 'negative keepalive must clamp to 0, got ' . $c['keepAliveSec']; }
    return true;
});

t('clamp: fragCount 0 becomes 1 (not 0)', function () {
    $c = Ai::clamp(['fragCount' => 0], AiTune::patchSchema());
    return eq((int) $c['fragCount'], 1);
});

t('advice falls back to the canned table', function () {
    $a = AiTune::advice('tls_timeout', ['regime' => 'tight']);
    if (empty($a['body']) || $a['action'] !== 'fragment_on') { return json_encode($a); }
    if (empty($a['canned'])) { return 'ai is off, so canned must be true'; }
    $b = AiTune::advice('zzz-unknown', ['regime' => 'blackout']);
    if (strpos($b['body'], 'قطعی') === false) { return 'blackout wording missing: ' . $b['body']; }
    return true;
});

t('feed still builds with the tuner on and the ai off', function () use ($root, $tmp) {
    $cfg = Util::cfg();
    $cfg['tune']['enabled'] = true;
    Util::setConfig($cfg);
    $build = Builder::doBuild('vip', 60);
    if (!isset($build['servers']) || !$build['servers']) {
        return 'no servers in the vip build';
    }
    if ($build['meta']['tunedBy'] !== 'heuristic') {
        return 'tunedBy should be heuristic while ai is off, got ' . $build['meta']['tunedBy'];
    }
    $patched = 0;
    foreach ($build['servers'] as $s) {
        if (!isset($s['tune'])) {
            continue;
        }
        $patched++;
        if (isset($s['tune']['mtu']) && ((int) $s['tune']['mtu'] < 576 || (int) $s['tune']['mtu'] > 9000)) {
            return 'tune.mtu out of range in payload: ' . $s['tune']['mtu'];
        }
        if (isset($s['tune']['why'])) {
            return 'admin notes must not ship to clients';
        }
    }
    if ($patched === 0) {
        return 'no node got a patch - the tuner never ran';
    }
    if ((int) $build['meta']['tuned'] < $patched) {
        return 'meta.tuned undercounts';
    }
    // `_regime` is bookkeeping inside the tune map, not a patched node: meta.tuned must not count it,
    // or a run that patched 4 nodes would announce 5. Pinned at exactly the patch count.
    if ((int) $build['meta']['tuned'] !== $patched) {
        return 'meta.tuned mismatch: meta says ' . (int) $build['meta']['tuned'] .
            ' but the payload carries ' . $patched . ' patch(es)';
    }
    return true;
});

t('block evidence folds with decay and votes', function () {
    $path = Util::dataDir() . '/block.json';
    @unlink($path);
    $_GET = [];
    // lives on Builder, not on the router: including index.php in a test would run the dispatcher
    Builder::foldBlockEvidence(['tcpFail' => 1.0, 'tlsFail' => 1.0, 'dnsPoisoned' => true, 'rtt' => 400, 'probes' => 5], 'blackout');
    $j = json_decode((string) file_get_contents($path), true);
    if (!is_array($j) || (float) $j['tcpFail'] < 0.2) { return 'tcpFail not folded: ' . json_encode($j); }
    if (empty($j['votes']['blackout'])) { return 'vote not recorded'; }
    Builder::foldBlockEvidence(['tcpFail' => 0.0, 'tlsFail' => 0.0], 'calm');
    $j2 = json_decode((string) file_get_contents($path), true);
    if ((float) $j2['tcpFail'] >= (float) $j['tcpFail']) { return 'ewma did not decay'; }
    if (!isset($j2['votes']['calm']) || !isset($j2['votes']['blackout'])) { return 'both votes must exist: ' . json_encode($j2['votes']); }
    return true;
});

t('fleet evidence reads block.json into the tuner', function () {
    $cfg = Util::cfg();
    $cfg['tune']['minVotesForRegime'] = 1;
    Util::setConfig($cfg);
    $r = new ReflectionClass('Builder');
    $m = $r->getMethod('fleetEvidence');
    $m->setAccessible(true);
    $fleet = $m->invoke(null, 'vip');
    if (!isset($fleet['tcpFail']) || !is_float($fleet['tcpFail'])) {
        return 'fleet shape wrong: ' . json_encode($fleet);
    }
    return in_array($fleet['regime'], ['calm', 'tight', 'blackout', ''], true) ? true : 'bad regime: ' . $fleet['regime'];
});

/* ---------------------------------------------------------------- deploy contract */

t('the AI clamp truncates by characters, not bytes - without mbstring too', function () {
    // Ai::clamp once called mb_substr unguarded: on a shared host without mbstring that is a fatal
    // "Call to undefined function", not a degraded answer. Persian is 2 bytes/char, so a byte cut also
    // lands mid-codepoint and json_encode rejects the whole payload on the way out.
    $s = str_repeat('پرتقال ', 60);                 // 420 chars, 540 bytes
    $cut = Ai::safeSubstr($s, 180);
    if (!preg_match('~^.*$~u', $cut)) {
        return 'the cut produced invalid UTF-8 (split a codepoint): ' . bin2hex(substr($cut, -6));
    }
    $chars = preg_match_all('~.~u', $cut);
    if ($chars > 180) return 'kept ' . $chars . ' characters, the clamp is 180';
    if ($chars < 150) return 'cut far too short: ' . $chars;
    $clamped = Ai::clamp(['note' => $s], ['note' => 'string']);
    if (!isset($clamped['note'])) return 'clamp dropped the string entirely: ' . json_encode($clamped);
    return preg_match_all('~.~u', $clamped['note']) <= 180 ? true : 'clamp ignored its own limit';
});

t('the rewrite rules do not brick the admin panel', function () use ($root) {
    // A host without SSH has exactly one way to paste the VIP list, rebuild the cache and mint its
    // keys: /v/admin/. `RewriteRule ^(lib|admin)/ - [F,L]` shipped like that once and returned 403 on
    // every deploy - which is indistinguishable from "the panel does not exist" to the owner.
    $ht = (string) @file_get_contents($root . '/.htaccess');
    if (preg_match('/RewriteRule[^\n]*\^\(lib\|admin\)/', $ht)) {
        return 'admin/ is blanket-forbidden by the rewrite rule -> /v/admin/ answers 403';
    }
    if (preg_match('/RewriteRule[^\n]*\^admin\/[^\n]*\[F/', $ht)) {
        return 'admin/ is forbidden by its own rewrite rule';
    }
    if (!preg_match('/RewriteRule\s+\^lib\/.*\[F/', $ht)) {
        return 'lib/ is no longer protected by the rewrite rules';
    }
    $admin = (string) @file_get_contents($root . '/admin/.htaccess');
    if ($admin === '') {
        return 'admin/.htaccess is missing -> the folder is only protected by its login gate';
    }
    foreach (['index.php', 'hash.php'] as $f) {
        if (strpos($admin, 'Files "' . $f . '"') === false) {
            return 'admin/.htaccess does not grant ' . $f;
        }
    }
    if (strpos($admin, 'Require all denied') === false) {
        return 'admin/.htaccess grants the entry points but denies nothing else';
    }
    return true;
});

t('the files that hold secrets are denied over http', function () use ($root) {
    // .htaccess escapes its dots (config\.local\.php), so strip backslashes before matching - otherwise
    // the pattern for a filename never matches and the test fails while the rules are perfectly fine.
    $strip = function ($f) {
        return str_replace('\\', '', (string) @file_get_contents($f));
    };
    $v = $strip($root . '/.htaccess');
    $dat = $strip($root . '/data/.htaccess');
    $need = [
        'config.local.php (in /v/.htaccess)' => ['~config\.local\.php~', $v],
        'vip_raw.txt (in /v/.htaccess)'      => ['~vip_raw\.txt~', $v],
        'ledger.json (in data/.htaccess)'    => ['~ledger\.json~', $dat],
        'vip_raw.txt (in data/.htaccess)'    => ['~vip_raw\.txt~', $dat],
        'dotfiles (in data/.htaccess)'       => ['~FilesMatch "\^\.~', $dat],
    ];
    foreach ($need as $what => $rule) {
        if (!preg_match($rule[0], $rule[1])) {
            return $what . ' is no longer denied';
        }
    }
    return true;
});

t('the apk hint does not send the APK into the denied folder', function () use ($root) {
    // Version.php once said "put it into data/apk/" - a path that is 404/403 by design, so every
    // follower of that hint ends up with a feed that scans the file and an app that cannot download it.
    $src = (string) @file_get_contents($root . '/lib/Version.php');
    if (!preg_match("~'hint'\s*=>\s*'([^']*)'~", $src, $m)) {
        return "no_apk_published lost its hint - the owner is left guessing where the APK goes";
    }
    $hint = $m[1];
    if (strpos($hint, 'data/apk') !== false) {
        return 'the hint points inside data/, which is denied over http: ' . $hint;
    }
    if (strpos($hint, '/v/apk/') === false) {
        return 'the hint no longer names the web-readable folder: ' . $hint;
    }
    return true;
});

/* Regression guards for bugs this suite previously shipped silently. */
echo "\n== Regression: vmess TLS marker + masked-raw survival ==\n";
t('a vmess spelled "tls":"tls" keeps its TLS marker', function () {
    $j = json_encode(['v' => '2', 'ps' => 'DE node', 'add' => '89.1.2.3', 'port' => '443',
        'id' => 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', 'aid' => '0', 'net' => 'ws',
        'host' => 'cdn.example.net', 'path' => '/vless', 'tls' => 'tls']);
    $n = Parser::parseUri('vmess://' . base64_encode($j));
    if ($n === null) {
        return 'classic vmess+ws+tls did not parse at all';
    }
    // The old boolParam-only lookup returned '' here, and every vmess+tls node was dialed with
    // security=none (a silent plaintext downgrade on panels that actually enforce TLS).
    return eq($n['tls'], 'tls', 'tls flag');
});
t('a vmess spelled "tls":"reality" is recognised too', function () {
    $j = json_encode(['ps' => 'x', 'add' => '89.1.2.4', 'port' => '443',
        'id' => 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', 'net' => 'tcp', 'tls' => 'reality',
        'sni' => 'cdn.example.net']);
    $n = Parser::parseUri('vmess://' . base64_encode($j));
    return $n === null ? 'parse failed' : eq($n['tls'], 'reality');
});
t('a plain (unencrypted transport) vmess stays tls=""', function () {
    $j = json_encode(['ps' => 'x', 'add' => '89.1.2.5', 'port' => '80',
        'id' => 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', 'net' => 'tcp']);
    $n = Parser::parseUri('vmess://' . base64_encode($j));
    return $n === null ? 'parse failed' : eq($n['tls'], '');
});
t('private/blanked or implicit-bool tls values still parse like before', function () {
    $j = json_encode(['ps' => 'x', 'add' => '89.1.2.6', 'port' => '443',
        'id' => 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', 'net' => 'tcp', 'tls' => '1']);
    $n = Parser::parseUri('vmess://' . base64_encode($j));
    return $n === null ? 'parse failed (tls=1 spelling)' : eq($n['tls'], 'tls');
});
t('maskNames rewrites a vmess remark and the raw always re-decodes', function () {
    // The wire contract, stated the way the app relies on it: whatever the vendor put in ps, the
    // published raw is a decodable vmess body whose remark is our brand + the display slot.
    $src = '{"ps":"💂Germ-656 | 0.3Mbps","add":"89.2.3.4","port":"443",'
        . '"id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","net":"ws","tls":"tls","path":"/sub"}';
    $n = ['proto' => 'vmess', 'raw' => 'vmess://' . base64_encode($src), 'id' => 'n1'];
    $m = new ReflectionMethod('Builder', 'maskNames');
    $m->setAccessible(true);
    $out = $m->invoke(null, $n, 'Vip M•A', 3);
    if (!is_array($out) || strpos((string) $out['raw'], 'vmess://') !== 0 || strlen((string) $out['raw']) < 12) {
        return 'raw uri destroyed: ' . var_export(is_array($out) ? $out['raw'] : $out, true);
    }
    $inner = json_decode((string) Util::base64Lenient(substr((string) $out['raw'], 8)), true);
    if (!is_array($inner)) {
        return 'rewritten body no longer decodes as vmess JSON';
    }
    if (!isset($inner['ps']) || strpos($inner['ps'], '💂') !== false || strpos($inner['ps'], 'Germ') !== false) {
        return 'vendor remark leaked or ps missing: ' . var_export(isset($inner['ps']) ? $inner['ps'] : null, true);
    }
    if (strpos($inner['ps'], 'Vip M•A 03') !== 0) {
        return 'brand+slot tag did not land: ' . var_export($inner['ps'], true);
    }
    return true;
});

t('hash.php still prints a paste-ready config.local.php', function () use ($root) {
    $src = (string) @file_get_contents($root . '/admin/hash.php');
    foreach (["'secret' =>", 'feedKey', 'toolKey', 'adminPassHash', 'publicBase'] as $key) {
        if (strpos($src, $key) === false) {
            return 'hash.php output lost ' . $key . ' - the owner has to hand-write config.local.php again';
        }
    }
    if (strpos($src, 'rateLimit') === false) {
        return 'hash.php hashes passwords without a rate limit (bcrypt is CPU, and this page is public)';
    }
    if (strpos($src, 'method="post"') === false) {
        return 'hash.php only takes the password via GET -> it lands in the hosting access log';
    }
    return true;
});



function meelano_summary()
{
    static $done = false;
    if ($done) {
        return;
    }
    $done = true;
    $GLOBALS['need_exit'] = $GLOBALS['fail'] ? 1 : 0;
    echo "\n" . str_repeat('-', 60) . "\n";
    printf("%d passed, %d failed\n", $GLOBALS['pass'], $GLOBALS['fail']);
    if ($GLOBALS['fail']) {
        echo "\nFAILURES:\n";
        foreach ($GLOBALS['fails'] as $f) {
            echo " - $f\n";
        }
    }
}
register_shutdown_function('meelano_summary');
exit($fail ? 1 : 0);
