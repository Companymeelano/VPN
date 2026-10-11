<?php
/**
 * End-to-end platform gate for raw HTTP/SOCKS5 proxies. A successful TCP connect or CONNECT reply only
 * proves that the proxy speaks its protocol; these requests prove that Google, gstatic and Cloudflare
 * return their exact connectivity-check status from the proxy's exit IP.
 */
final class PlatformProbe
{
    public static function run(array $nodes, array $endpoints, $timeoutMs = 5000)
    {
        $out = [];
        foreach ($nodes as $n) {
            $out[$n['id']] = ['applied' => true, 'ok' => false, 'clean' => false, 'blocked' => false,
                'robot' => false, 'ms' => 0, 'checks' => [], 'err' => 'not_tested'];
        }
        if (!$nodes || !$endpoints) {
            return $out;
        }
        if (!function_exists('curl_multi_exec') || !function_exists('curl_init')) {
            foreach ($out as $id => $_) $out[$id]['err'] = 'curl_required';
            return $out;
        }

        $mh = curl_multi_init();
        $handles = [];
        foreach ($nodes as $n) {
            if (!in_array($n['proto'], ['http', 'socks5'], true)) continue;
            foreach ($endpoints as $i => $endpoint) {
                $ep = self::endpoint($endpoint, $i);
                if ($ep === null) continue;
                $key = $n['id'] . '|' . $ep['id'];
                $ch = curl_init($ep['url']);
                $counter = (object) ['bytes' => 0];
                $write = function ($handle, $chunk) use ($counter) {
                    $counter->bytes += strlen($chunk);
                    return $counter->bytes <= 8192 ? strlen($chunk) : 0;
                };
                // Pin the proxy endpoint to the same public-only DNS answer used by the TCP stage.
                // Otherwise a hostile hostname can rebind cURL to loopback/LAN between the two probes.
                Dns::setBudget(100);
                $proxyIp = Dns::lookup($n['host']);
                if ($proxyIp === null) continue;
                $proxyType = CURLPROXY_HTTP;
                if ($n['proto'] === 'socks5') {
                    $proxyType = CURLPROXY_SOCKS5_HOSTNAME;
                } elseif (!empty($n['tls'])) {
                    if (!defined('CURLPROXY_HTTPS')) continue;
                    $proxyType = CURLPROXY_HTTPS;
                }
                $options = [
                    CURLOPT_RETURNTRANSFER => false,
                    CURLOPT_FOLLOWLOCATION => false,
                    CURLOPT_CONNECTTIMEOUT_MS => min((int) $timeoutMs, 3000),
                    CURLOPT_TIMEOUT_MS => (int) $timeoutMs,
                    CURLOPT_PROXY => $proxyIp,
                    CURLOPT_PROXYPORT => (int) $n['port'],
                    CURLOPT_PROXYTYPE => $proxyType,
                    CURLOPT_HTTPPROXYTUNNEL => $n['proto'] === 'http',
                    CURLOPT_SSL_VERIFYPEER => true,
                    CURLOPT_SSL_VERIFYHOST => 2,
                    CURLOPT_HTTPHEADER => ['Accept: */*', 'Cache-Control: no-cache', 'Connection: close'],
                    CURLOPT_USERAGENT => 'MeelanoPlatformProbe/1.0',
                    CURLOPT_WRITEFUNCTION => $write,
                    CURLOPT_PROTOCOLS => CURLPROTO_HTTPS,
                    CURLOPT_NOSIGNAL => true,
                ];
                if (!empty($n['username'])) {
                    $options[CURLOPT_PROXYUSERPWD] = (string) $n['username'] . ':' . (isset($n['password']) ? (string) $n['password'] : '');
                    $options[CURLOPT_PROXYAUTH] = CURLAUTH_ANY;
                }
                curl_setopt_array($ch, $options);
                curl_multi_add_handle($mh, $ch);
                $handles[$key] = ['ch' => $ch, 'node' => $n['id'], 'endpoint' => $ep];
            }
        }

        $active = 0;
        $deadline = microtime(true) + max(1.0, ((int) $timeoutMs / 1000) + 2.0);
        do {
            $status = curl_multi_exec($mh, $active);
            if ($active && curl_multi_select($mh, 0.20) === -1) usleep(20000);
            if (microtime(true) >= $deadline) break;
        } while ($active && $status === CURLM_OK);

        $checks = [];
        foreach ($handles as $item) {
            $ch = $item['ch'];
            $id = $item['node'];
            $ep = $item['endpoint'];
            $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
            $ms = (int) round(((float) curl_getinfo($ch, CURLINFO_TOTAL_TIME)) * 1000);
            $err = curl_error($ch);
            $checks[$id][$ep['id']] = [
                'status' => $status > 0 ? $status : null,
                'expected' => $ep['status'],
                'ok' => $status === $ep['status'] && $err === '',
                'ms' => $ms,
                'err' => $err !== '' ? $err : null,
            ];
            curl_multi_remove_handle($mh, $ch);
            curl_close($ch);
        }
        curl_multi_close($mh);

        foreach ($out as $id => $_) {
            $out[$id] = self::classify(isset($checks[$id]) ? $checks[$id] : [], $endpoints);
        }
        return $out;
    }

    /** Pure classifier, public so the exact-status/block separation has a network-free regression test. */
    public static function classify(array $checks, array $endpoints)
    {
        $need = [];
        foreach ($endpoints as $i => $raw) {
            $ep = self::endpoint($raw, $i);
            if ($ep !== null) $need[$ep['id']] = $ep;
        }
        $ok = 0;
        $maxMs = 0;
        $robot = false;
        foreach ($need as $id => $ep) {
            $c = isset($checks[$id]) ? $checks[$id] : [];
            $status = isset($c['status']) ? (int) $c['status'] : 0;
            $checkOk = array_key_exists('ok', $c)
                ? !empty($c['ok'])
                : ($status === (int) $ep['status'] && empty($c['err']));
            if ($checkOk) $ok++;
            if ($status === 403 || $status === 429) $robot = true;
            $maxMs = max($maxMs, isset($c['ms']) ? (int) $c['ms'] : 0);
        }
        $all = count($need) > 0 && $ok === count($need);
        $cf = isset($checks['cloudflare']['status']) && (int) $checks['cloudflare']['status'] === 204;
        $google = isset($checks['google']['status']) && (int) $checks['google']['status'] === 204;
        $gstatic = isset($checks['gstatic']['status']) && (int) $checks['gstatic']['status'] === 204;
        $blocked = $cf && (!$google || !$gstatic);
        return [
            'applied' => true,
            'ok' => $all,
            'clean' => $all,
            'blocked' => $blocked,
            'robot' => $robot,
            'okCount' => $ok,
            'required' => count($need),
            'ms' => $maxMs,
            'checks' => $checks,
            'err' => $all ? null : ($blocked ? 'platform_blocked' : ($robot ? 'robot_or_rate_limited' : 'deep_probe_failed')),
        ];
    }

    private static function endpoint($raw, $index)
    {
        if (!is_array($raw)) return null;
        $id = isset($raw['name']) ? preg_replace('~[^a-z0-9_-]~', '', strtolower((string) $raw['name'])) : 'endpoint' . $index;
        $url = isset($raw['url']) ? (string) $raw['url'] : '';
        if ($url === '' && !empty($raw['host'])) $url = 'https://' . $raw['host'] . '/generate_204';
        if (!preg_match('~^https://~i', $url)) return null;
        return ['id' => $id, 'url' => $url, 'status' => isset($raw['status']) ? (int) $raw['status'] : 204];
    }
}
