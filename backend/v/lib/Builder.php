<?php
/**
 * Build pipeline. Two products, one shape:
 *
 *   vip.json   your own list (pasted in the admin panel, or fetched from a file on your host)
 *              -> names stripped, only "Vip Meelano" + country code survives
 *   free.json  aggregated from public feeds -> deduped -> probed -> graded -> top-N
 *
 * Both are written once, cached, and served with ETag; a request NEVER waits on a rebuild
 * (it gets the stale build instantly and the refresh happens for whoever triggers it).
 */
final class Builder
{
    /** @return array */
    public static function vip()
    {
        return self::cachedBuild('vip');
    }

    /** @return array */
    public static function free()
    {
        if (!Util::cfg('free.enabled', true)) {
            return ['error' => 'free_disabled', 'servers' => []];
        }
        return self::cachedBuild('free');
    }

    /** cache-first wrapper: never blocks, never duplicates work */
    public static function cachedBuild($kind)
    {
        $ttl = (int) Util::cfg('cache.' . $kind . 'Ttl', 900);
        $cached = Util::cacheRead($kind);
        if ($cached && $cached['fresh'] && empty($_GET['fresh'])) {
            $p = $cached['payload'];
            $p['cache'] = ['state' => 'fresh', 'age' => $cached['age']];
            return $p;
        }
        $stale = $cached ? $cached['payload'] : null;

        $built = Util::withLock($kind, function () use ($kind, $ttl) {
            // double-check: another worker may have just finished
            $c = Util::cacheRead($kind);
            if ($c && $c['fresh'] && empty($_GET['fresh'])) {
                return null;
            }
            return self::doBuild($kind, $ttl);
        });

        if (is_array($built) && isset($built['servers'])) {
            $min = (int) Util::cfg($kind === 'vip' ? 'vip.maxNodes' : 'free.minNodes', 0);
            $count = count($built['servers']);
            if ($kind === 'free' && $stale && $count < $min && (isset($stale['servers']) ? count($stale['servers']) : 0) > $count) {
                $built = $stale;
                $built['degraded'] = 'thin_build_kept_previous';
            } else {
                Util::cacheWrite($kind, $built);
                $built['cache'] = ['state' => 'rebuilt', 'age' => 0];
                return $built;
            }
        }

        if ($stale && (time() - (int) (isset($stale['generatedAt']) ? $stale['generatedAt'] : 0)) <= $ttl + (int) Util::cfg('cache.staleGrace', 604800)) {
            $stale['cache'] = ['state' => 'stale_served', 'age' => $cached['age'], 'reason' => $built === null ? 'rebuild_in_progress_elsewhere' : 'build_failed'];
            return $stale;
        }
        if ($built === null) {
            // no cache yet and someone else holds the lock: tell the app to retry shortly
            return ['schema' => 2, 'kind' => $kind, 'servers' => [], 'building' => true,
                    'retryAfter' => 15, 'generatedAt' => time()];
        }
        return is_array($built) ? $built : ['schema' => 2, 'kind' => $kind, 'servers' => [],
                                            'generatedAt' => time(), 'error' => 'build_failed'];
    }

    /** @return array */
    public static function doBuild($kind, $ttl)
    {
        $t0 = microtime(true);
        $meta = ['sources' => [], 'notes' => []];
        $nodes = [];

        if ($kind === 'vip') {
            $blob = self::vipBlob($meta);
            $nodes = Parser::parseBlob($blob);
            foreach ($nodes as &$n) {
                $n['tier'] = 'vip';
            }
            unset($n);
        } else {
            $nodes = self::freeNodes($meta);
            foreach ($nodes as &$n) {
                $n['tier'] = 'free';
            }
            unset($n);
        }

        $nodes = Parser::dedupe($nodes);
        $nodes = self::prefilter($nodes, $kind, $meta);

        $probeOn = $kind === 'vip' ? (bool) Util::cfg('vip.probe', true) : (bool) Util::cfg('free.probe.enabled', true);
        $tcp = $gate = [];
        if ($probeOn && $nodes) {
            $conc = (int) Util::cfg('free.probe.concurrency', 40);
            $budget = (int) Util::cfg('free.probe.budgetMs', 18000);
            $tcpTmo = (int) Util::cfg('free.probe.tcpTimeoutMs', 1200);
            $tasks = [];
            foreach ($nodes as $n) {
                $tasks[$n['id']] = new TcpTask($n['id'], $n['host'], $n['port'], $tcpTmo);
            }
            $tcp = Probe::run($tasks, $conc, max(2000, (int) ($budget * 0.45)), 60);
            $blocked = true;
            foreach ($tcp as $r) {
                if (!empty($r['ok'])) {
                    $blocked = false;
                    break;
                }
            }
            if ($blocked && Util::cfg('free.probe.autoDisableOnBlocked', true)) {
                $meta['notes'][] = 'outbound_tcp_blocked_on_this_host -> ranking from client feedback only';
                $tcp = [];
                $gate = [];
            } else {
                $alive = [];
                foreach ($tcp as $id => $r) {
                    if (!empty($r['ok'])) {
                        $alive[$id] = true;
                    }
                }
                if ($kind === 'free') {
                    $gate = self::gatePass($nodes, $alive, $meta);
                }
            }
            foreach ($tcp as $id => $r) {
                if (!isset($gate[$id]) || $kind === 'vip') {
                    Ledger::recordServer($id, !empty($r['ok']), isset($r['ms']) ? (int) $r['ms'] : 0);
                }
            }
        }

        $drop = $kind === 'free';
        $scored = Score::annotate($nodes, $tcp, $gate, $drop);
        Ledger::save();

        if (!$probeOn) {
            $meta['notes'][] = 'probing disabled (free.probe.enabled=false) -> ranking from ledger + client feedback only';
        }
        $max = (int) Util::cfg($kind === 'vip' ? 'vip.maxNodes' : 'free.maxNodes', 60);
        $scored = array_slice($scored, 0, $max);

        $brand = (string) Util::cfg('brand.' . $kind, $kind === 'vip' ? 'Vip Meelano' : 'Free Meelano');
        $mask = (bool) Util::cfg('vip.maskNames', true);
        $servers = [];
        foreach (array_values($scored) as $i => $n) {
            $slot = $i + 1;
            if ($mask) {
                $n = self::maskNames($n, $brand, $slot);
            }
            $servers[] = self::toPublic($n, $kind, $slot, $brand);
        }

        $payload = [
            'schema'      => 2,
            'kind'        => $kind,
            'appName'     => Util::cfg('appName'),
            'brand'       => $brand,
            'generatedAt' => time(),
            'ttl'         => $ttl,
            'count'       => count($servers),
            'namePolicy'  => $mask ? 'masked:brand+cc' : 'raw',
            'servers'     => $servers,
            'meta'        => array_merge($meta, [
                'buildMs'      => (int) round((microtime(true) - $t0) * 1000),
                'candidates'   => count($nodes),
                'probed'       => count($tcp),
                'gated'        => count($gate),
                'php'          => PHP_VERSION,
                'ledger'       => Ledger::stats(),
            ]),
        ];
        $payload['etag'] = substr(sha1(Util::jsonEncode(array_column($servers, 'id')) . '|' . $payload['generatedAt']), 0, 16);
        return $payload;
    }

    /* ------------------------------------------------------------------ inputs */

    private static function vipBlob(array &$meta)
    {
        $file = Util::dataDir() . '/' . basename((string) Util::cfg('vip.sourceFile', 'vip_raw.txt'));
        $blob = Util::readText($file, '');
        $url = (string) Util::cfg('vip.url', '');
        if ($url !== '') {
            $r = Util::fetchMulti(['vip' => $url]);
            if (isset($r['vip']) && $r['vip']['ok']) {
                $meta['sources']['vip.url'] = ['ok' => true, 'bytes' => strlen($r['vip']['body']), 'status' => $r['vip']['status']];
                if (trim($blob) === '' || strlen($r['vip']['body']) > strlen($blob)) {
                    $blob = $r['vip']['body'];
                }
            } else {
                $meta['sources']['vip.url'] = ['ok' => false, 'err' => isset($r['vip']) ? $r['vip']['err'] : 'fetch_failed'];
            }
        }
        if (trim($blob) === '') {
            $meta['notes'][] = 'vip list is empty -> edit ' . basename($file) . ' from the admin panel';
        } else {
            $meta['sources']['vip.file'] = ['ok' => true, 'bytes' => strlen($blob)];
        }
        return $blob;
    }

    private static function freeNodes(array &$meta)
    {
        $conf = require dirname(__DIR__) . '/sources.php';
        // config.local.php can fully replace the source set (used by the test suite too)
        $override = Util::cfg('free.sourcesOverride', null);
        if (is_array($override) && $override) {
            $conf['sources'] = $override;
            $conf['disabled'] = [];
        }
        if (is_file(dirname(__DIR__) . '/sources.local.php')) {
            $extra = require dirname(__DIR__) . '/sources.local.php';
            if (isset($conf['sources']) && is_array($extra)) {
                $conf['sources'] = array_merge($conf['sources'], $extra);
            }
        }
        $disabled = array_flip((array) $conf['disabled']);
        // the admin panel toggles sources here instead of editing code
        $state = json_decode((string) Util::readText(Util::dataDir() . '/sources_state.json', '{}'), true);
        if (is_array($state)) {
            foreach ($state as $id => $on) {
                if (!$on) {
                    $disabled[$id] = 1;
                } else {
                    unset($disabled[$id]);
                }
            }
        }
        $wanted = [];
        foreach ($conf['sources'] as $id => $src) {
            if (isset($disabled[$id])) {
                continue;
            }
            if (empty($src['url'])) {
                continue;
            }
            $wanted[$id] = $src['url'];
        }
        $meta['notes'][] = 'fetching ' . count($wanted) . ' upstream source(s)';
        $res = Util::fetchMulti($wanted);
        $nodes = [];
        foreach ($conf['sources'] as $id => $src) {
            if (isset($disabled[$id]) || !isset($res[$id])) {
                continue;
            }
            $r = $res[$id];
            if (!$r['ok']) {
                $meta['sources'][$id] = ['ok' => false, 'err' => $r['err']];
                continue;
            }
            $body = $r['body'];
            $kind = isset($src['kind']) ? $src['kind'] : 'proxy';
            $take = isset($src['take']) ? (int) $src['take'] : 200;
            if ($kind !== 'json') {
                $lines = preg_split('/\R+/', $body);
                $lines = array_slice((array) $lines, 0, max(10, $take * 4));  // head only: lists are best-first
                $body = implode("\n", $lines);
            }
            $parsed = Parser::parseBlob($body);
            if ($kind === 'json') {
                $parsed = array_slice($parsed, 0, max(10, $take));
            }
            foreach ($parsed as $n) {
                $n['source'] = $id;
                $n['sourceKind'] = $kind;
                $nodes[] = $n;
            }
            $meta['sources'][$id] = ['ok' => true, 'bytes' => strlen($r['body']), 'nodes' => count($parsed)];
        }
        return $nodes;
    }

    private static function prefilter(array $nodes, $kind, array &$meta)
    {
        $allowPorts = array_flip(array_map('intval', (array) Util::cfg('free.allowPorts', [])));
        $out = [];
        $seen = [];
        $dropped = ['insane' => 0, 'port' => 0, 'dup' => 0, 'banned' => 0];
        foreach ($nodes as $n) {
            if (!Parser::isSane($n)) {
                $dropped['insane']++;
                continue;
            }
            $isProxy = in_array($n['proto'], ['http', 'socks4', 'socks5'], true);
            if ($isProxy && $allowPorts && !isset($allowPorts[(int) $n['port']])) {
                $dropped['port']++;
                continue;
            }
            if (isset($seen[$n['id']])) {
                $dropped['dup']++;
                continue;
            }
            if ($kind === 'free' && Ledger::isBanned($n['id'])) {
                $dropped['banned']++;
                continue;
            }
            $seen[$n['id']] = 1;
            $out[] = $n;
        }
        $cap = (int) Util::cfg('free.maxCandidates', 900);
        if ($kind === 'free' && count($out) > $cap) {
            // deterministic-but-rotating sample so the tail of the list gets probed on later runs
            $seed = Util::cfg('free.shuffleSeed') === 'daily' ? (int) floor(time() / 3600) : 0;
            mt_srand($seed);
            usort($out, function ($a, $b) { return (crc32($a['id']) ^ $seed) <=> (crc32($b['id']) ^ $seed); });
            $out = array_slice($out, 0, $cap);
            $meta['notes'][] = 'capped to ' . $cap . ' candidates (rotating sample)';
        }
        $meta['prefilter'] = $dropped;
        return $out;
    }

    /**
     * "clean IP" gate: can this proxy actually reach Google + Cloudflare over TLS?
     * A proxy that only answers on port 80, or whose exit IP is blocked, fails here.
     */
    private static function gatePass(array $nodes, array $aliveIds, array &$meta)
    {
        $endpoints = (array) Util::cfg('free.gate.endpoints', []);
        if (!Util::cfg('free.gate.probeAll', false)) {
            $endpoints = array_slice($endpoints, 0, 1);
        }
        if (!$endpoints || !$aliveIds) {
            return [];
        }
        $gate = $endpoints[0];
        $gateHost = (string) $gate['host'];
        $tmo = (int) Util::cfg('free.probe.proxyTimeoutMs', 2500);
        $tasks = [];
        $byId = [];
        foreach ($nodes as $n) {
            $byId[$n['id']] = $n;
            if (!isset($aliveIds[$n['id']])) {
                continue;
            }
            if (in_array($n['proto'], ['http'], true)) {
                $tasks[] = new HttpConnectTask($n['id'], $n['host'], $n['port'], $tmo,
                    '', $gateHost, isset($n['username']) ? $n['username'] : '', isset($n['password']) ? $n['password'] : '');
            } elseif (in_array($n['proto'], ['socks5', 'socks4'], true)) {
                $gateIp = Dns::lookup($gateHost);
                if ($gateIp === null) {
                    continue;
                }
                $tasks[] = new SocksTask($n['id'], $n['host'], $n['port'], $tmo,
                    '', $gateIp, $n['proto'] === 'socks4');
            } else {
                // a vless/ss/trojan config IS its own tunnel; a TCP hit is the honest limit
                // of what a PHP box can verify. End-to-end truth comes from client feedback.
                $tasks[] = new TcpTask($n['id'], $n['host'], $n['port'], $tmo);
            }
        }
        $raw = Probe::run($tasks, (int) Util::cfg('free.probe.concurrency', 40),
                          (int) Util::cfg('free.probe.budgetMs', 18000), 30);
        $out = [];
        foreach ($raw as $id => $r) {
            $n = isset($byId[$id]) ? $byId[$id] : null;
            $isProxy = $n && in_array($n['proto'], ['http', 'socks4', 'socks5'], true);
            $out[$id][] = ['ok' => !empty($r['ok']), 'gate' => $gateHost, 'ms' => isset($r['ms']) ? $r['ms'] : 0,
                           'err' => isset($r['err']) ? $r['err'] : null, 'applied' => $isProxy];
            if ($isProxy) {
                Ledger::recordServer($id, !empty($r['ok']), isset($r['ms']) ? (int) $r['ms'] : 0);
            }
        }
        $meta['gate'] = ['endpoint' => $gateHost, 'tested' => count($out)];
        return $out;
    }

    /* ------------------------------------------------------------------ shaping */

    private static function maskNames(array $n, $brand, $slot)
    {
        $tag = sprintf('%s %02d', $brand, $slot);
        if ($n['proto'] === 'vmess' && strpos($n['raw'], 'vmess://') === 0) {
            $json = Util::base64Lenient(substr($n['raw'], 8));
            $d = json_decode((string) $json, true);
            if (is_array($d)) {
                $d['ps'] = $tag;
                unset($d['name'], $d['remark']);
                $n['raw'] = 'vmess://' . str_replace("\n", '', base64_encode(json_encode($d, JSON_UNESCAPED_UNICODE)));
            }
        } else {
            $u = $n['raw'];
            $h = strpos($u, '#');
            if ($h !== false) {
                $u = substr($u, 0, $h);
            }
            $n['raw'] = $u . '#' . rawurlencode($tag);
        }
        unset($n['remark']);
        return $n;
    }

    private static function toPublic(array $n, $tier, $slot, $brand)
    {
        $cc = isset($n['cc']) && preg_match('~^[A-Z]{2}$~', (string) $n['cc']) ? $n['cc'] : null;
        $lat = isset($n['latencyMs']) && $n['latencyMs'] !== null ? (int) $n['latencyMs'] : null;
        $out = [
            'id'      => $n['id'],
            'slot'    => $slot,
            'name'    => $brand,          // fixed, per spec: never the vendor's own label
            'title'   => $brand,
            'subtitle'=> trim((string) preg_replace('~\s+~', ' ', ($cc ? $cc . ' · ' : '') . $n['proto'] . ' · ' . $n['port'])),
            'cc'      => $cc,
            'ccFa'    => $cc ? Country::nameFa($cc) : '',
            'flag'    => $cc,             // app draws its own flag asset from the 2-letter code
            'tier'    => $tier,
            'proto'   => $n['proto'],
            'host'    => $n['host'],
            'port'    => (int) $n['port'],
            'tls'     => isset($n['tls']) ? $n['tls'] : '',
            'network' => isset($n['network']) ? $n['network'] : 'tcp',
            'sni'     => isset($n['sni']) ? $n['sni'] : '',
            'path'    => isset($n['path']) ? $n['path'] : '',
            'hostHeader' => isset($n['hostHeader']) ? $n['hostHeader'] : '',
            'alpn'    => isset($n['alpn']) ? $n['alpn'] : '',
            'flow'    => isset($n['flow']) ? $n['flow'] : '',
            'pbk'     => isset($n['pbk']) ? $n['pbk'] : '',
            'sid'     => isset($n['sid']) ? $n['sid'] : '',
            'fingerprint' => isset($n['fingerprint']) ? $n['fingerprint'] : '',
            'insecure' => !empty($n['insecure']),
            'supportsUdp' => in_array($n['proto'], ['vless', 'vmess', 'trojan', 'ss', 'hy2', 'tuic', 'socks5'], true),
            'raw'     => $n['raw'],
            'quality' => [
                'grade'       => isset($n['grade']) ? $n['grade'] : 'D',
                'latencyMs'   => $lat,
                'reliability' => isset($n['reliability']) ? $n['reliability'] : 0,
                'samples'     => isset($n['samples']) ? (int) $n['samples'] : 0,
                'alive'       => isset($n['alive']) ? (bool) $n['alive'] : null,
                'gateOk'      => isset($n['gateOk']) ? $n['gateOk'] : null,
            ],
            'checkedAt' => time(),
        ];
        foreach (['userId', 'alterId', 'password', 'username', 'method', 'cipher', 'serviceName', 'publicKey'] as $k) {
            if (isset($n[$k]) && $n[$k] !== '' && $n[$k] !== null) {
                $out[$k] = is_int($n[$k]) ? $n[$k] : (string) $n[$k];
            }
        }
        $out['config'] = array_intersect_key($out, array_flip([
            'proto', 'host', 'port', 'tls', 'network', 'sni', 'path', 'hostHeader', 'alpn', 'flow',
            'pbk', 'sid', 'fingerprint', 'insecure', 'userId', 'alterId', 'password', 'username',
            'method', 'cipher', 'serviceName', 'publicKey',
        ]));
        return $out;
    }

    /* ------------------------------------------------------------------ misc */

    public static function stats()
    {
        $out = ['php' => PHP_VERSION, 'ledger' => Ledger::stats(), 'now' => date('c')];
        foreach (['vip', 'free', 'version'] as $k) {
            $c = Util::cacheRead($k);
            $out[$k] = $c ? ['age' => $c['age'], 'fresh' => $c['fresh'], 'count' => isset($c['payload']['count']) ? $c['payload']['count'] : 0,
                             'generatedAt' => isset($c['payload']['generatedAt']) ? date('c', (int) $c['payload']['generatedAt']) : null]
                          : ['cached' => false];
        }
        return $out;
    }

    /** Called by ?action=feedback after the app tried a node. */
    public static function ingestFeedback($id, $ok, $ms)
    {
        $id = strtolower(trim((string) $id));
        // node ids are hex; anything else is noise or someone poking the endpoint
        if (!preg_match('~^[a-f0-9]{6,40}$~', $id)) {
            return false;
        }
        Ledger::recordClient($id, (bool) $ok, max(0, min(60000, (int) $ms)));
        Ledger::save();
        return true;
    }
}
