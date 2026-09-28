<?php
/**
 * One-click deployment diagnostics. Run it ONCE after upload: /v/?action=selftest&key=<admin>
 * It answers the only three questions that matter on shared hosting:
 *   1. can PHP write the cache?
 *   2. can PHP reach github (for the free feeds)?
 *   3. can PHP open outbound TCP to arbitrary ports (for probing proxies)?  <- most hosts say no
 */
final class SelfTest
{
    public static function run()
    {
        $rows = [];
        $add = function ($group, $name, $state, $detail = '') use (&$rows) {
            $rows[] = ['group' => $group, 'name' => $name, 'state' => $state, 'detail' => $detail];
        };

        // ---- runtime
        $v = PHP_VERSION;
        $add('php', 'version', version_compare($v, '7.4', '>=') ? 'ok' : 'bad', $v . ' (need 7.4+)');
        foreach (['json', 'zlib', 'mbstring', 'curl', 'openssl'] as $ext) {
            $add('php', 'ext:' . $ext, extension_loaded($ext) ? 'ok' : 'warn', $ext . ' ' . (extension_loaded($ext) ? 'loaded' : 'missing (fallbacks exist)'));
        }
        $add('php', 'allow_url_fopen', ini_get('allow_url_fopen') ? 'ok' : 'warn', ini_get('allow_url_fopen') ? 'on' : 'off (curl still used when present)');
        $add('php', 'memory_limit', (float) ini_get('memory_limit') >= 128 || trim((string) ini_get('memory_limit')) === '-1' ? 'ok' : 'warn', (string) ini_get('memory_limit'));
        $maxExec = (int) ini_get('max_execution_time');
        $add('php', 'max_execution_time', ($maxExec === 0 || $maxExec >= 30) ? 'ok' : 'warn', $maxExec . 's (build needs ~25s worst case)');
        $disabled = (string) ini_get('disable_functions');
        $need = ['stream_socket_client', 'stream_select', 'flock', 'file_get_contents', 'curl_multi_exec'];
        $blocked = [];
        foreach ($need as $fn) {
            if ($disabled !== '' && preg_match('~(?:^|[, ]|\s)' . preg_quote($fn, '~') . '(?=[, ]|$)~', $disabled)) {
                $blocked[] = $fn;
            }
        }
        $add('php', 'disable_functions', $blocked ? 'bad' : 'ok', $blocked ? ('disabled: ' . implode(', ', $blocked)) : 'nothing critical disabled');

        // ---- filesystem
        $dir = Util::dataDir();
        $add('fs', 'data dir', is_dir($dir) && is_writable($dir) ? 'ok' : 'bad', $dir . ($dir && is_writable($dir) ? ' writable' : ' NOT writable (chmod 775, or 755->775 on the dir)'));
        $probe = $dir . '/.write_test';
        $okWrite = @file_put_contents($probe, 'x') !== false && @unlink($probe);
        $add('fs', 'atomic write', $okWrite ? 'ok' : 'bad', 'tmp+rename ' . ($okWrite ? 'works' : 'failed'));
        $add('fs', 'flock', function_exists('flock') ? 'ok' : 'warn', 'needed to avoid stampedes');
        $add('fs', 'open_basedir', ini_get('open_basedir') ? 'warn' : 'ok', ini_get('open_basedir') ? (string) ini_get('open_basedir') : 'unrestricted');
        $free = @disk_free_space($dir);
        $add('fs', 'disk free', $free === false ? 'warn' : ($free > 100 * 1048576 ? 'ok' : 'warn'), $free === false ? 'unknown' : round($free / 1048576) . ' MB');

        // ---- outbound HTTP (are the feeds reachable from this box at all?)
        if (Util::cfg('selftest.network', true)) {
            $r = Util::fetchMulti([
                'gh' => 'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt',
                'cf' => 'https://1.1.1.1/cdn-cgi/trace',
            ]);
            foreach ($r as $k => $row) {
                $add('http', $k, $row['ok'] ? 'ok' : 'bad', $row['ok'] ? ($row['status'] . ', ' . strlen($row['body']) . ' bytes') : ('err=' . $row['err']));
            }

            // ---- outbound TCP to odd ports (this decides whether server-side testing is possible)
            $egress = Probe::egressTest();
            foreach ($egress as $target => $state) {
                $add('egress', $target, $state === 'open' ? 'ok' : ($state === 'no_handshake' ? 'warn' : 'bad'), $state);
            }
            $e1 = 0;
            $e2 = '';
            $odd = @stream_socket_client('tcp://1.1.1.1:8080', $e1, $e2, 2.5);
            if (is_resource($odd)) {
                @fclose($odd);
                $add('egress', 'odd_port_8080', 'ok', 'outbound to non-standard ports allowed');
            } else {
                $add('egress', 'odd_port_8080', 'warn', 'host may block odd ports -> free-pool testing disabled, ranking falls back to client feedback. detail: ' . $e2);
            }
        } else {
            $add('http', 'skipped', 'warn', 'selftest.network=false -> outbound checks skipped');
        }

        // ---- build smoke test (uses your real data; no network if probing is off)
        if (Util::cfg('selftest.build', true)) {
            try {
                $p = Builder::doBuild('free', 60);
                $add('build', 'free', $p['count'] > 0 ? 'ok' : 'warn', count($p['servers']) . ' nodes in ' . $p['meta']['buildMs'] . 'ms');
                $v2 = Builder::doBuild('vip', 60);
                $add('build', 'vip', $v2['count'] > 0 ? 'ok' : 'warn', $v2['count'] . ' nodes (empty until you paste your VIP list)');
                $masked = true;
                $withCc = 0;
                foreach (array_slice($v2['servers'], 0, 8) as $s) {
                    if ($s['name'] !== Util::cfg('brand.vip')) {
                        $masked = false;
                    }
                    if (!empty($s['cc'])) {
                        $withCc++;
                    }
                }
                $add('build', 'name masking', $masked ? 'ok' : 'bad', $masked ? 'every node shows "' . Util::cfg('brand.vip') . '"' : 'the vendor\'s original names leaked into the response');
                $add('build', 'country/flag detection', $withCc > 0 ? 'ok' : 'warn', $withCc . '/' . max(1, count($v2['servers'])) . ' nodes resolved a country code');
                $add('build', 'cache write', Util::cacheRead('vip') !== null ? 'ok' : 'warn', 'data/cache/vip.json ' . (Util::cacheRead('vip') ? 'written' : 'missing'));
            } catch (Throwable $e) {
                $add('build', 'exception', 'bad', get_class($e) . ': ' . $e->getMessage() . ' @' . basename($e->getFile()) . ':' . $e->getLine());
            }
        }

        $counts = ['ok' => 0, 'warn' => 0, 'bad' => 0];
        foreach ($rows as $r2) {
            $counts[$r2['state']] = isset($counts[$r2['state']]) ? $counts[$r2['state']] + 1 : 1;
        }
        return [
            'ok' => $counts['bad'] === 0,
            'summary' => sprintf('%d ok / %d warnings / %d blockers', $counts['ok'], $counts['warn'], $counts['bad']),
            'mode' => $counts['bad'] === 0
                ? 'full: feeds + probes + client feedback'
                : 'degraded: check the bad rows above',
            'rows' => $rows,
            'at' => time(),
        ];
    }

    /** HTML rendering for the admin panel. */
    public static function html(array $report)
    {
        $colors = ['ok' => '#4ADE9B', 'warn' => '#F5A524', 'bad' => '#FF6B6B'];
        $h = '<div class="report"><table><tbody>';
        $group = '';
        foreach ($report['rows'] as $r) {
            if ($r['group'] !== $group) {
                $group = $r['group'];
                $h .= '<tr class="gh"><td colspan="3">' . htmlspecialchars($group) . '</td></tr>';
            }
            $c = isset($colors[$r['state']]) ? $colors[$r['state']] : '#8899a6';
            $h .= '<tr><td>' . htmlspecialchars($r['name']) . '</td>'
                . '<td style="color:' . $c . ';font-weight:700">' . $r['state'] . '</td>'
                . '<td class="mut">' . htmlspecialchars($r['detail']) . '</td></tr>';
        }
        $h .= '</tbody></table><p>' . htmlspecialchars($report['summary']) . ' — ' . htmlspecialchars($report['mode']) . '</p></div>';
        return $h;
    }
}
