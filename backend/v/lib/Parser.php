<?php
/**
 * Turns ANY blob into normalized nodes: plain text, HTML, markdown, CSV, a base64
 * subscription, or upstream proxy JSON. Only scheme:// URIs and host:port lines are kept,
 * so pasting a whole webpage into the admin panel still works.
 *
 * Output node (internal, before masking):
 *   proto host port remark raw params userId password method alterId tls network sni
 *   hostHeader path flow pbk sid fingerprint insecure upstreamLatencyMs cc
 */
final class Parser
{
    /** URI characters allowed inside a match (deliberately excludes <>" and whitespace). */
    /**
     * A config URI is "scheme://" followed by anything that is not whitespace or a
     * quote/tag delimiter. Deliberately generous (UTF-8 remarks like  #🇫🇮 Helsinki  must
     * survive); clean() then trims trailing punctuation. '~' is the delimiter, so it is
     * escaped even though it only appears inside the class.
     */
    const URI_RE = '~\b(?:vless|vmess|trojan|ssr|shadowsocks|ss|hysteria2|hy2|hysteria|hy|tuic|anytls|wireguard|socks5h|socks5|socks4|socks|https?)://[^\s"\'<>]+~i';

    /** trailing noise a regex greedy match may have swallowed from HTML/text */
    private static $trimTail = " \t\n\r,;\"'<>)]}";

    private static $alias = [
        'shadowsocks' => 'ss',
        'hysteria2'   => 'hy2',
        'hysteria'    => 'hy',
        'socks5h'     => 'socks5',
        'socks'       => 'socks5',
        'ssr'         => 'ssr',
        'anytls'      => 'anytls',
        'wireguard'   => 'wireguard',
    ];

    private static $skipExt = '~\.(png|jpe?g|gif|svg|ico|webp|css|js|map|woff2?|ttf|eot|mp4|webm|pdf|zip|apk)$~i';

    /**
     * @return array normalized, deduplicated nodes
     */
    public static function parseBlob($blob)
    {
        // One normaliser for every input shape (see Util::utf8): the JSON branch below dies on an invalid
        // byte inside json_decode, and the text branch would happily carry it into the payload.
        $blob = Util::utf8((string) $blob);
        if (trim($blob) === '') {
            return [];
        }
        $nodes = [];

        // 1) JSON feeds (monosans / jetkai / our own shape)
        $head = ltrim(substr($blob, 0, 64));
        if (isset($head[0]) && ($head[0] === '[' || $head[0] === '{')) {
            $decoded = json_decode($blob, true);
            if (is_array($decoded)) {
                $nodes = array_merge($nodes, self::fromJson($decoded));
            }
        }

        // 2) base64 subscription (single-line, no scheme visible)
        $probe = substr(strip_tags($blob), 0, 4096);
        if (!$nodes && strpos($probe, '://') === false) {
            $decoded = Util::base64Lenient($blob);
            if ($decoded !== null && strpos($decoded, '://') !== false) {
                $blob = $decoded;
                $probe = $blob;
            }
        }

        // 3) every scheme:// token in the (possibly HTML) text
        $seenTokens = [];
        if (preg_match_all(self::URI_RE, $blob, $m, PREG_SET_ORDER)) {
            foreach ($m as $row) {
                $token = self::clean($row[0]);
                $n = self::parseUri($token);
                if ($n) {
                    $nodes[] = $n;
                    $seenTokens[$token] = 1;
                }
            }
        }

        // 4) bare proxy lines: ip:port / ip:port:user:pass / user:pass@ip:port.
        // Always tried (not only when step 3 found nothing): real-world files mix both styles.
        foreach (preg_split('/\R+/', strip_tags($blob)) as $line) {
            $line = trim($line);
            if ($line === '' || isset($seenTokens[$line]) || strpos($line, '://') !== false) {
                continue;
            }
            $line = trim($line, " \t,;\"'<>");
            if ($line === '' || $line[0] === '#' || $line[0] === '/' || $line[0] === '<' || $line[0] === '=') {
                continue;
            }
            $n = self::parseProxyLine($line);
            if ($n === null) {
                // "some words 1.2.3.4:8080 trailing words" - pull the address out of the noise
                if (preg_match('~(?:\d{1,3}\.){3}\d{1,3}:\d{2,5}~', $line, $mm)) {
                    $n = self::parseProxyLine($mm[0]);
                }
            }
            if ($n) {
                $nodes[] = $n;
            }
        }

        return self::dedupe($nodes);
    }

    public static function clean($token)
    {
        $token = trim((string) $token);
        $token = rtrim($token, self::$trimTail);
        // keep balanced parentheses (vless remarks love "(3)")
        while (substr($token, -1) === ')') {
            $open = substr_count($token, '(');
            $close = substr_count($token, ')');
            if ($close > $open) {
                $token = substr($token, 0, -1);
            } else {
                break;
            }
        }
        return $token;
    }

    /** @return array|null */
    public static function parseUri($uri)
    {
        $uri = trim((string) $uri);
        if (!preg_match('~^([A-Za-z0-9]+)://(.*)$~s', $uri, $m)) {
            return null;
        }
        $proto = strtolower($m[1]);
        $proto = isset(self::$alias[$proto]) ? self::$alias[$proto] : $proto;
        $rest = $m[2];

        $remark = '';
        if (($h = strrpos($rest, '#')) !== false) {
            $remark = Util::utf8(rawurldecode(substr($rest, $h + 1)));
            $rest = substr($rest, 0, $h);
        }
        $query = '';
        $path = '';
        // query = everything after the first '?' that is not inside the host part
        if (($q = strpos($rest, '?')) !== false) {
            $query = substr($rest, $q + 1);
            $rest = substr($rest, 0, $q);
        }

        if ($proto === 'vmess' && strpos($rest, '@') === false) {
            return self::parseVmess($rest, $remark);
        }
        if ($proto === 'ss') {
            return self::parseSs($rest, $remark, $query);
        }

        $userInfo = '';
        if (($at = strrpos($rest, '@')) !== false) {
            $userInfo = substr($rest, 0, $at);
            $rest = substr($rest, $at + 1);
        }
        // drop trailing slash / path (kept when meaningful, e.g. obfs ws path in query)
        $rest = trim($rest, '/');
        if ($rest === '' || !preg_match('~^[A-Za-z0-9\.\-\[\]:]+$~', $rest)) {
            return null;
        }
        $host = $rest;
        $port = 0;
        if (preg_match('~^(.*):(\d{1,5})$~', $rest, $hp)) {
            $host = $hp[1];
            $port = (int) $hp[2];
        }
        if ($host === '' || $port < 1 || $port > 65535) {
            return null;
        }
        // strip IPv6 brackets
        if (isset($host[0]) && $host[0] === '[' && substr($host, -1) === ']') {
            $host = substr($host, 1, -1);
        }

        $params = [];
        if ($query !== '') {
            parse_str($query, $params);
            $params = is_array($params) ? $params : [];
        }

        $node = [
            'proto'   => $proto,
            'host'    => strtolower($host),
            'port'    => $port,
            'remark'  => $remark,
            'params'  => $params,
            'raw'     => $uri,
            'path'    => isset($params['path']) ? (string) $params['path'] : '',
            'network' => isset($params['type']) ? strtolower((string) $params['type']) : (isset($params['network']) ? strtolower((string) $params['network']) : 'tcp'),
            'hostHeader' => isset($params['host']) ? (string) $params['host'] : '',
            'sni'     => isset($params['sni']) ? (string) $params['sni'] : (isset($params['peer']) ? (string) $params['peer'] : ''),
            'alpn'    => isset($params['alpn']) ? (string) $params['alpn'] : '',
            'tls'     => self::tlsOf($params),
            'insecure' => self::boolParam($params, ['allowInsecure', 'insecure', 'skip-cert-verify']),
            'flow'    => isset($params['flow']) ? (string) $params['flow'] : '',
            'pbk'     => isset($params['pbk']) ? (string) $params['pbk'] : '',
            'sid'     => isset($params['sid']) ? (string) $params['sid'] : '',
            'publicKey' => isset($params['public-key']) ? (string) $params['public-key'] : '',
            'fingerprint' => isset($params['fp']) ? (string) $params['fp'] : '',
            'serviceName' => isset($params['serviceName']) ? (string) $params['serviceName'] : '',
        ];

        if ($node['sni'] === '' && in_array($node['tls'], ['tls', 'reality'], true) && $path !== '') {
            // nothing sensible to do; leave empty, engine falls back to host
        }

        switch ($proto) {
            case 'vless':
                $node['userId'] = $userInfo;
                break;
            case 'trojan':
            case 'hy2':
            case 'hy':
            case 'anytls':
                $node['password'] = self::beforeQuestion($userInfo);
                if ($proto === 'hy2' || $proto === 'hy') {
                    $node['password'] = self::beforeQuestion($userInfo);
                }
                break;
            case 'tuic':
                if (strpos($userInfo, ':') !== false) {
                    list($node['userId'], $node['password']) = explode(':', $userInfo, 2);
                } else {
                    $node['password'] = $userInfo;
                }
                break;
            case 'socks5':
            case 'socks4':
            case 'http':
            case 'https':
                if ($userInfo !== '') {
                    $parts = explode(':', $userInfo, 2);
                    $node['username'] = $parts[0];
                    $node['password'] = isset($parts[1]) ? $parts[1] : '';
                }
                $node['tls'] = $proto === 'https' ? 'tls' : '';
                break;
            case 'wireguard':
                $node['privateKey'] = $userInfo;
                break;
            default:
                return null;   // protocol we do not ship configs for
        }

        return self::finalize($node);
    }

    private static function parseVmess($b64, $remark)
    {
        $json = Util::base64Lenient($b64);
        if ($json === null) {
            return null;
        }
        $d = json_decode($json, true);
        if (!is_array($d)) {
            return null;
        }
        $host = isset($d['add']) ? (string) $d['add'] : '';
        $port = isset($d['port']) ? (int) $d['port'] : 0;
        if ($host === '' || $port < 1) {
            return null;
        }
        $params = $d;
        return self::finalize([
            'proto'    => 'vmess',
            'host'     => strtolower($host),
            'port'     => $port,
            'userId'   => isset($d['id']) ? (string) $d['id'] : '',
            'alterId'  => isset($d['aid']) ? (int) $d['aid'] : (isset($d['alterId']) ? (int) $d['alterId'] : 0),
            'cipher'   => isset($d['scy']) ? (string) $d['scy'] : (isset($d['security']) ? (string) $d['security'] : 'auto'),
            'network'  => isset($d['net']) ? strtolower((string) $d['net']) : 'tcp',
            'path'     => isset($d['path']) ? (string) $d['path'] : '',
            'hostHeader' => isset($d['host']) ? (string) $d['host'] : '',
            'sni'      => isset($d['sni']) ? (string) $d['sni'] : (isset($d['servername']) ? (string) $d['servername'] : ''),
            'alpn'     => isset($d['alpn']) ? (string) $d['alpn'] : '',
            'tls'      => self::tlsOf($params),
            'insecure' => self::boolParam($params, ['allowInsecure']),
            'remark'   => $remark !== '' ? $remark : (isset($d['ps']) ? (string) $d['ps'] : (isset($d['name']) ? (string) $d['name'] : '')),
            'params'   => $params,
            'raw'      => 'vmess://' . trim($b64),
            'serviceName' => isset($d['serviceName']) ? (string) $d['serviceName'] : '',
        ]);
    }

    private static function parseSs($rest, $remark, $query)
    {
        $params = [];
        if ($query !== '') {
            parse_str($query, $params);
            $params = is_array($params) ? $params : [];
        }
        // SIP002:  ss://base64(method:password)@host:port
        if (($at = strrpos($rest, '@')) !== false) {
            $creds = Util::base64Lenient(substr($rest, 0, $at));
            $tail = substr($rest, $at + 1);
            if ($creds === null) {
                $creds = substr($rest, 0, $at);
            }
            if (preg_match('~^(.*?):(\d{1,5})(?:[/?].*)?$~s', trim($tail, '/'), $hp) && $hp[1] !== '') {
                list($method, $password) = array_pad(explode(':', $creds, 2), 2, '');
                return self::finalize([
                    'proto' => 'ss', 'host' => strtolower($hp[1]), 'port' => (int) $hp[2],
                    'method' => $method, 'password' => rawurldecode($password),
                    'remark' => $remark, 'params' => $params, 'raw' => 'ss://' . $rest . ($remark !== '' ? '#' . rawurlencode($remark) : ''),
                ]);
            }
        }
        // legacy: ss://base64(method:password@host:port)
        $dec = Util::base64Lenient($rest);
        if ($dec !== null && preg_match('~^(.*?):(.*?)@([^:@]+):(\d{1,5})$~s', $dec, $x)) {
            return self::finalize([
                'proto' => 'ss', 'host' => strtolower($x[3]), 'port' => (int) $x[4],
                'method' => $x[1], 'password' => $x[2],
                'remark' => $remark, 'params' => $params, 'raw' => 'ss://' . $rest . ($remark !== '' ? '#' . rawurlencode($remark) : ''),
            ]);
        }
        return null;
    }

    /** @return array|null */
    public static function parseProxyLine($line)
    {
        $line = trim((string) $line);
        if ($line === '') {
            return null;
        }
        if (strpos($line, '://') !== false) {
            return self::parseUri(self::clean($line));
        }
        $user = $pass = '';
        if (($at = strrpos($line, '@')) !== false) {
            $user = substr($line, 0, $at);
            $line = substr($line, $at + 1);
        }
        $parts = explode(':', $line);
        if (count($parts) === 4 && $user === '' && self::isHost($parts[0])) {
            list($host, $port, $user, $pass) = $parts;         // ip:port:user:pass
        } elseif (count($parts) >= 2 && self::isHost($parts[0])) {
            $host = $parts[0];
            $port = $parts[1];
        } else {
            return null;
        }
        if ($user !== '' && strpos($user, ':') !== false) {    // user:pass@ip:port
            list($user, $pass) = explode(':', $user, 2);
        }
        if (!preg_match('~^\d{1,5}$~', $port) || (int) $port < 1 || (int) $port > 65535) {
            return null;
        }
        $proto = self::looksLikeSocks($host, (int) $port) ? 'socks5' : 'http';
        $n = [
            'proto' => $proto, 'host' => strtolower($host), 'port' => (int) $port,
            'username' => $user, 'password' => $pass, 'remark' => '', 'params' => [],
            'raw' => $proto . '://' . ($user !== '' ? $user . ':' . $pass . '@' : '') . $host . ':' . $port,
        ];
        return self::finalize($n);
    }

    private static function fromJson(array $d)
    {
        $rows = [];
        if (isset($d[0]) && is_array($d[0])) {
            $rows = $d;
        } else {
            foreach (['servers', 'nodes', 'proxies', 'items', 'data'] as $k) {
                if (isset($d[$k]) && is_array($d[$k])) {
                    $rows = $d[$k];
                    break;
                }
            }
        }
        $out = [];
        foreach ($rows as $r) {
            if (!is_array($r)) {
                // maybe a list of raw strings
                $n = self::parseUri(self::clean((string) $r));
                if ($n === null) {
                    $n = self::parseProxyLine((string) $r);
                }
                if ($n) {
                    $out[] = $n;
                }
                continue;
            }
            if (isset($r['raw']) && !isset($r['host'])) {
                $n = self::parseUri(self::clean((string) $r['raw']));
                if ($n) {
                    $out[] = $n;
                }
                continue;
            }
            if (!isset($r['host']) || !isset($r['port'])) {
                continue;
            }
            $host = strtolower((string) $r['host']);
            $port = (int) $r['port'];
            if (!self::isHost($host) || $port < 1 || $port > 65535) {
                continue;
            }
            $proto = isset($r['protocol']) ? strtolower((string) $r['protocol']) : (isset($r['scheme']) ? strtolower((string) $r['scheme']) : 'http');
            if ($proto === 'https') {
                $proto = 'http';
            }
            if (!in_array($proto, ['http', 'socks4', 'socks5'], true)) {
                $proto = 'http';
            }
            $lat = null;
            foreach (['timeout', 'response_time', 'latency', 'speed'] as $k) {
                if (isset($r[$k]) && is_numeric($r[$k])) {
                    $v = (float) $r[$k];
                    // upstream uses seconds (<50) or ms (>50)
                    $lat = $v > 0 && $v < 50 ? (int) round($v * 1000) : (int) round($v);
                    break;
                }
            }
            $cc = null;
            if (isset($r['geolocation']['country']['iso_code'])) {
                $cc = strtoupper((string) $r['geolocation']['country']['iso_code']);
            } elseif (isset($r['country'])) {
                $cc = Country::fromToken((string) $r['country']);
            }
            $out[] = self::finalize([
                'proto' => $proto, 'host' => $host, 'port' => $port,
                'username' => isset($r['username']) && $r['username'] !== null ? (string) $r['username'] : '',
                'password' => isset($r['password']) && $r['password'] !== null ? (string) $r['password'] : '',
                'remark'   => isset($r['name']) ? (string) $r['name'] : '',
                'params'   => [],
                'cc'       => $cc,
                'upstreamLatencyMs' => $lat,
                'upstreamCheckedAt' => isset($r['checked_at']) ? strtotime((string) $r['checked_at']) ?: null : null,
                'raw' => $proto . '://' . (isset($r['username']) && $r['username'] ? $r['username'] . ':' . (isset($r['password']) ? $r['password'] : '') . '@' : '') . $host . ':' . $port,
            ]);
        }
        return $out;
    }

    private static function finalize(array $n)
    {
        if (empty($n['host']) || empty($n['port'])) {
            return null;
        }
        if (isset($n['remark']) && preg_match(self::$skipExt, (string) $n['remark'])) {
            return null;   // looks like an asset URL, not a proxy
        }
        if (in_array($n['proto'], ['http', 'https'], true) && preg_match(self::$skipExt, (string) $n['raw'])) {
            return null;   // <img src="http://cdn/x.png"> inside an html blob is not a proxy
        }
        if (!empty($n['params']['path']) && preg_match(self::$skipExt, (string) $n['params']['path'])) {
            return null;
        }
        $n['id'] = substr(sha1($n['proto'] . '|' . $n['host'] . '|' . $n['port'] . '|' . (isset($n['userId']) ? $n['userId'] : '') . '|' . (isset($n['publicKey']) ? $n['publicKey'] : '') . '|' . (isset($n['password']) ? $n['password'] : '')), 0, 12);
        if (!isset($n['cc'])) {
            $n['cc'] = Country::detect(isset($n['remark']) ? $n['remark'] : '', $n['host'], isset($n['sni']) ? $n['sni'] : '');
        }
        if (empty($n['tls'])) {
            $n['tls'] = '';
        }
        return $n;
    }

    private static function tlsOf(array $params)
    {
        $sec = isset($params['security']) ? strtolower((string) $params['security']) : '';
        if ($sec === 'reality') {
            return 'reality';
        }
        if ($sec === 'tls' || $sec === 'https') {
            return 'tls';
        }
        if ($sec === 'none' || $sec === '') {
            if (!empty($params['tls']) && self::boolParam($params, ['tls'])) {
                return 'tls';
            }
            return '';
        }
        return $sec;
    }

    private static function boolParam(array $params, array $keys)
    {
        foreach ($keys as $k) {
            if (array_key_exists($k, $params)) {
                $v = strtolower(trim((string) $params[$k]));
                return in_array($v, ['1', 'true', 'yes', 'on'], true);
            }
        }
        return false;
    }

    private static function beforeQuestion($s)
    {
        $s = (string) $s;
        if (($q = strpos($s, '?')) !== false) {
            $s = substr($s, 0, $q);
        }
        return rawurldecode($s);
    }

    public static function isHost($h)
    {
        $h = (string) $h;
        if ($h === '' || strlen($h) > 253) {
            return false;
        }
        if (filter_var($h, FILTER_VALIDATE_IP)) {
            return true;
        }
        return (bool) preg_match('~^[a-z0-9]([a-z0-9\-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9\-]*[a-z0-9])?)+$~i', $h);
    }

    /** Weak heuristic only: real detection happens in the probe. */
    private static function looksLikeSocks($host, $port)
    {
        return in_array($port, [1080, 10808, 7992, 13659, 4145, 3629], true);
    }

    public static function dedupe(array $nodes)
    {
        $seen = [];
        $out = [];
        foreach ($nodes as $n) {
            if (!isset($n['id']) || isset($seen[$n['id']])) {
                continue;
            }
            $seen[$n['id']] = 1;
            $out[] = $n;
        }
        return $out;
    }

    /** Sanity gate used before probing anything (saves sockets on a shared box). */
    /**
     * SIP002 ciphers a dialer can actually use. Anything else (notably the garbage you get when a
     * `ss://<uuid>@host` line is decoded as if it were base64 `method:password`) can never connect, and
     * a node that cannot connect is not "a slow node" - it is a broken promise in the list, scored B by
     * a TCP probe that only proves the port is open. Seen live on the free pool on 2026-09-12.
     */
    private static $ssMethods = [
        'aes-128-gcm', 'aes-192-gcm', 'aes-256-gcm', 'aes-128-cfb', 'aes-192-cfb', 'aes-256-cfb',
        'aes-128-ctr', 'aes-192-ctr', 'aes-256-ctr', 'chacha20-ietf', 'chacha20-ietf-poly1305',
        'xchacha20-ietf-poly1305', 'sodium:chacha20-ietf-poly1305', 'sodium:aes-256-gcm',
        'rc4-md5', 'bf-cfb', 'cast5-cfb', 'idea-cfb', 'rc2-cfb', 'seed-cfb',
    ];

    public static function isSane(array $n)
    {
        if (!isset($n['host'], $n['port']) || $n['port'] < 1 || $n['port'] > 65535) {
            return false;
        }
        if (!self::isHost($n['host'])) {
            return false;
        }
        if (filter_var($n['host'], FILTER_VALIDATE_IP) && !Util::isPublicIp($n['host'])) {
            return false;   // LAN / multicast / reserved
        }
        // credential fields must be dialable text, not mojibake: a control byte there means the line was
        // decoded wrong, and no server on earth will accept it
        foreach (['password', 'method', 'userId', 'cipher', 'sni', 'path'] as $k) {
            if (isset($n[$k]) && is_string($n[$k]) && $n[$k] !== '' && preg_match('~[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]~', $n[$k])) {
                return false;
            }
        }
        if (isset($n['proto']) && $n['proto'] === 'ss') {
            $m = isset($n['method']) ? strtolower(trim((string) $n['method'])) : '';
            if ($m === '' || !in_array($m, self::$ssMethods, true)) {
                return false;
            }
        }
        return true;
    }
}
