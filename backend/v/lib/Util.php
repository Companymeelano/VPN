<?php
/**
 * Small helpers: config access, atomic cache files, flock, http fetch, json out,
 * etag/304 handling, pre-gzipped streaming, rate limiting, ip sanity.
 * No composer, no global state beyond a static config handle.
 */
final class Util
{
    /** @var array|null */
    private static $cfg = null;
    /** @var array<string,string> */
    private static $mem = [];

    public static function cfg($path = null, $default = null)
    {
        if (self::$cfg === null) {
            self::$cfg = require dirname(__DIR__) . '/config.php';
        }
        if ($path === null) {
            return self::$cfg;
        }
        $node = self::$cfg;
        foreach (explode('.', $path) as $key) {
            if (!is_array($node) || !array_key_exists($key, $node)) {
                return $default;
            }
            $node = $node[$key];
        }
        return $node;
    }

    /** Only for tests. */
    public static function setConfig(array $cfg)
    {
        self::$cfg = $cfg;
    }

    public static function dataDir($sub = '')
    {
        $dir = rtrim((string) self::cfg('dataDir'), '/');
        if ($sub !== '') {
            $dir .= '/' . trim($sub, '/');
        }
        if (!is_dir($dir)) {
            @mkdir($dir, 0775, true);
        }
        return $dir;
    }

    /* ------------------------------------------------------------------ time */

    public static function now()
    {
        return time();
    }

    public static function nowMs()
    {
        return (int) round(microtime(true) * 1000);
    }

    /* ------------------------------------------------------------------ json + http */

    /**
     * Respond with JSON, honouring If-None-Match (304) and Accept-Encoding (pre-gzipped file).
     *
     * @param array $meta  keys: etag, maxAge, freshUntil, gzPath, status
     */
    public static function respond(array $payload, array $meta = [])
    {
        $status = isset($meta['status']) ? (int) $meta['status'] : 200;
        $etag   = isset($meta['etag']) ? (string) $meta['etag'] : null;
        $maxAge = isset($meta['maxAge']) ? (int) $meta['maxAge'] : (int) self::cfg('cache.httpMaxAge', 300);

        if ($status === 304) {
            self::headers($etag, $maxAge, null);
            if (!headers_sent()) {
                http_response_code(304);
            }
            return;
        }

        $body = self::jsonEncode($payload);
        $bodyLen = strlen($body);

        // Pre-built .gz: stream it as-is, zero CPU.
        $gzPath = isset($meta['gzPath']) ? $meta['gzPath'] : null;
        $serveGz = $gzPath && self::wantsGzip() && is_file($gzPath) && filesize($gzPath) > 0;

        self::headers($etag, $maxAge, $serveGz ? 'gzip' : null);
        if ($serveGz) {
            if (!headers_sent()) {
                header('Content-Encoding: gzip');
                header('Content-Length: ' . filesize($gzPath));
                http_response_code($status);
            }
            readfile($gzPath);
            return;
        }
        if ($bodyLen < 2048 && self::wantsGzip() && function_exists('gzencode')) {
            $z = @gzencode($body, 5);
            if ($z !== false && strlen($z) < $bodyLen) {
                if (!headers_sent()) {
                    header('Content-Encoding: gzip');
                    header('Content-Length: ' . strlen($z));
                    http_response_code($status);
                }
                echo $z;
                return;
            }
        }
        if (!headers_sent()) {
            header('Content-Length: ' . $bodyLen);
            http_response_code($status);
        }
        echo $body;
    }

    private static function headers($etag, $maxAge, $encoding)
    {
        if (headers_sent()) {
            return;   // something already echoed (a stray BOM, a debug print) - never warn on top of it
        }
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: public, max-age=' . $maxAge . ', stale-while-revalidate=60');
        header('Vary: Accept-Encoding');
        header('X-Meelano: 1');
        if ($etag !== null && $etag !== '') {
            header('ETag: "' . $etag . '"');
            header('Last-Modified: ' . gmdate('D, d M Y H:i:s') . ' GMT');
        }
    }

    public static function wantsGzip()
    {
        $ae = isset($_SERVER['HTTP_ACCEPT_ENCODING']) ? strtolower((string) $_SERVER['HTTP_ACCEPT_ENCODING']) : '';
        return $ae !== '' && strpos($ae, 'gzip') !== false;
    }

    public static function ifNoneMatch()
    {
        $h = isset($_SERVER['HTTP_IF_NONE_MATCH']) ? (string) $_SERVER['HTTP_IF_NONE_MATCH'] : '';
        return trim($h, " \t\"W/");
    }

    public static function jsonEncode($v)
    {
        $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
        if (defined('JSON_PRETTY_PRINT') && !empty($_GET['pretty'])) {
            $flags |= JSON_PRETTY_PRINT;
        }
        if (defined('JSON_INVALID_UTF8_IGNORE')) {
            $flags |= JSON_INVALID_UTF8_IGNORE;      // PHP 7.2+: drop the bad bytes, keep the payload
        }
        $s = json_encode($v, $flags);
        if ($s === false) {
            // still failing (invalid *keys*, or a flag this PHP lacks): sanitise recursively and retry,
            // because "the whole feed is 500" is never the right price for one mangled upstream remark
            $s = json_encode(self::utf8Deep($v), $flags);
        }
        return $s === false ? json_encode(['error' => 'json_encode_failed', 'detail' => json_last_error_msg()]) : $s;
    }

    /** utf8() over every string key and value, recursively. Only reached when the cheap path failed. */
    public static function utf8Deep($v)
    {
        if (is_array($v)) {
            $out = [];
            foreach ($v as $k => $item) {
                $out[is_string($k) ? self::utf8($k) : $k] = self::utf8Deep($item);
            }
            return $out;
        }
        return is_string($v) ? self::utf8($v) : $v;
    }

    public static function fail($code, $msg, array $extra = [])
    {
        if (!headers_sent()) {
            http_response_code($code);
            header('Content-Type: application/json; charset=utf-8');
        }
        echo self::jsonEncode(array_merge(['error' => $msg, 'status' => $code], $extra));
        exit;
    }

    /* ------------------------------------------------------------------ files */

    /**
     * Make text safe for json_encode/json_decode. Free lists are scraped from places that
     * do not care about encoding - CP1251 remarks, mojibake, a multi-byte character cut in half by a
     * head-truncation - and *one* bad byte used to kill the whole endpoint: json_encode() returns false
     * ("Malformed UTF-8 characters") and json_decode() rejects the entire upstream JSON feed. Seen live
     * on ainetmee.ir on the first real fetch of ?action=free, which is precisely when no test could have
     * caught it.
     *
     * Valid bytes are passed through untouched; only invalid subsequences are dropped (mbstring), with
     * iconv and a byte-strip as fallbacks for hosts that lack mbstring.
     */
    public static function utf8($s)
    {
        $s = (string) $s;
        if ($s === '' || !preg_match('~[\x80-\xFF]~', $s)) {
            return $s;                       // pure ASCII: nothing to do, and this is the hot path
        }
        if (function_exists('mb_convert_encoding')) {
            $fixed = @mb_convert_encoding($s, 'UTF-8', 'UTF-8');
            if (is_string($fixed) && $fixed !== '') {
                return $fixed;
            }
        }
        if (function_exists('iconv')) {
            $fixed = @iconv('UTF-8', 'UTF-8//IGNORE', $s);
            if (is_string($fixed)) {
                return $fixed;
            }
        }
        return preg_replace('~[\x80-\xFF]~', '', $s);
    }

    public static function readText($path, $default = null)
    {
        if (!is_file($path)) {
            return $default;
        }
        $s = @file_get_contents($path);
        return $s === false ? $default : $s;
    }

    /** Atomic write: temp file + rename. Never leaves a half-written json behind. */
    public static function writeAtomic($path, $contents)
    {
        $dir = dirname($path);
        if (!is_dir($dir)) {
            @mkdir($dir, 0775, true);
        }
        $tmp = $dir . '/.tmp_' . basename($path) . '.' . getmypid() . '.' . mt_rand(1000, 9999);
        if (@file_put_contents($tmp, $contents, LOCK_EX) === false) {
            @unlink($tmp);
            return false;
        }
        @chmod($tmp, 0644);
        if (!@rename($tmp, $path)) {
            @unlink($tmp);
            return false;
        }
        return true;
    }

    /** @return array{payload:array,mtime:int,fresh:bool,age:int} */
    public static function cacheRead($key)
    {
        $file = self::dataDir('cache') . '/' . $key . '.json';
        if (!is_file($file)) {
            return null;
        }
        $raw = self::readText($file);
        $payload = json_decode((string) $raw, true);
        if (!is_array($payload)) {
            return null;
        }
        $mtime = (int) @filemtime($file);
        $ttl = isset($payload['ttl']) ? (int) $payload['ttl'] : 0;
        $age = time() - $mtime;
        return [
            'payload' => $payload,
            'mtime'   => $mtime,
            'age'     => $age,
            'fresh'   => $ttl > 0 ? $age <= $ttl : false,
        ];
    }

    public static function cacheWrite($key, array $payload)
    {
        $body = self::jsonEncode($payload);
        $dir  = self::dataDir('cache');
        $file = $dir . '/' . $key . '.json';
        if (!self::writeAtomic($file, $body)) {
            return false;
        }
        if (self::cfg('cache.gzipPrebuild', true) && function_exists('gzencode')) {
            $z = @gzencode($body, 6);
            if ($z !== false) {
                self::writeAtomic($dir . '/' . $key . '.json.gz', $z);
            }
        }
        return true;
    }

    public static function etagOf($key, array $payload)
    {
        $cand = self::dataDir('cache') . '/' . $key . '.etag';
        $etag = self::readText($cand);
        if (!is_string($etag) || $etag === '') {
            $etag = substr(sha1(self::jsonEncode($payload)), 0, 16);
            self::writeAtomic($cand, $etag);
        }
        return trim($etag);
    }

    /**
     * Run $fn while holding an exclusive lock; other concurrent requests do NOT wait —
     * they immediately get null and serve stale instead. Keeps Apache workers free.
     */
    public static function withLock($name, callable $fn, $blocking = false)
    {
        $lockFile = self::dataDir('locks') . '/' . preg_replace('/[^a-z0-9_\-]/i', '', $name) . '.lock';
        $fp = @fopen($lockFile, 'c');
        if ($fp === false) {
            return $fn();
        }
        $would = 0;
        $got = $blocking ? @flock($fp, LOCK_EX) : @flock($fp, LOCK_EX | LOCK_NB, $would);
        if (!$got) {
            fclose($fp);
            return null;
        }
        try {
            return $fn();
        } finally {
            @flock($fp, LOCK_UN);
            fclose($fp);
        }
    }

    /* ------------------------------------------------------------------ fetching */

    /**
     * Fetch several URLs. Uses curl_multi when available (parallel -> fast on shared hosting),
     * falls back to file_get_contents. `fixture://name` reads data/fixtures/name.txt (tests/dev).
     *
     * @param array $urls list of url => arbitrary key
     * @return array key => ['ok'=>bool,'body'=>string,'status'=>int,'err'=>string|null]
     */
    public static function fetchMulti(array $urls)
    {
        $out = [];
        if (!$urls) {
            return $out;
        }
        $timeout = (int) self::cfg('http.timeout', 6);
        $connTmo = (int) self::cfg('http.connectTimeout', 4);
        $maxBytes = (int) self::cfg('http.maxBytes', 4194304);
        $ua = (string) self::cfg('http.userAgent', 'MeelanoFeed/2.0');

        $pending = [];
        foreach ($urls as $key => $url) {
            if (strpos($url, 'fixture://') === 0) {
                $name = substr($url, 10);
                $base = preg_replace('/[^a-z0-9_.\-]/i', '', $name);
                $file = self::dataDir('fixtures') . '/' . $base . (preg_match('~\.[a-z0-9]+$~i', $base) ? '' : '.txt');
                $body = self::readText($file);
                $out[$key] = ['ok' => $body !== null, 'body' => (string) $body, 'status' => $body === null ? 404 : 200,
                              'err' => $body === null ? 'fixture_missing' : null];
                continue;
            }
            if (!preg_match('~^https?://~i', $url)) {
                $out[$key] = ['ok' => false, 'body' => '', 'status' => 0, 'err' => 'bad_scheme'];
                continue;
            }
            $pending[$key] = $url;
        }

        if ($pending && function_exists('curl_multi_exec') && function_exists('curl_init')) {
            $mh = curl_multi_init();
            $chs = [];
            foreach ($pending as $key => $url) {
                $ch = curl_init($url);
                curl_setopt_array($ch, [
                    CURLOPT_RETURNTRANSFER => true,
                    CURLOPT_FOLLOWLOCATION => true,
                    CURLOPT_MAXREDIRS      => 3,
                    CURLOPT_CONNECTTIMEOUT => $connTmo,
                    CURLOPT_TIMEOUT        => $timeout,
                    CURLOPT_USERAGENT      => $ua,
                    CURLOPT_ENCODING       => '',          // accept gzip from upstream = less transfer
                    CURLOPT_SSL_VERIFYPEER => true,
                    CURLOPT_SSL_VERIFYHOST => 2,
                    CURLOPT_HTTPHEADER     => ['Accept: text/plain,application/json;q=0.9,*/*;q=0.5'],
                    CURLOPT_PROTOCOLS      => CURLPROTO_HTTP | CURLPROTO_HTTPS,
                ]);
                curl_multi_add_handle($mh, $ch);
                $chs[$key] = $ch;
            }
            $active = 0;
            $started = microtime(true);
            do {
                $status = curl_multi_exec($mh, $active);
                if ($active) {
                    if (curl_multi_select($mh, 0.25) === -1) {
                        usleep(25000);
                    }
                }
                if (microtime(true) - $started > $timeout + $connTmo + 2) {
                    break;  // wall-clock guard: never let one slow upstream hang the request
                }
            } while ($active && $status === CURLM_OK);

            foreach ($chs as $key => $ch) {
                $body = curl_multi_getcontent($ch);
                $code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
                $err  = curl_error($ch);
                curl_multi_remove_handle($mh, $ch);
                curl_close($ch);
                if (is_string($body) && $code >= 200 && $code < 300) {
                    if (strlen($body) > $maxBytes) {
                        $body = substr($body, 0, $maxBytes);
                    }
                    $out[$key] = ['ok' => true, 'body' => $body, 'status' => $code, 'err' => null];
                } else {
                    $out[$key] = ['ok' => false, 'body' => '', 'status' => $code,
                                  'err' => $err !== '' ? $err : ('http_' . $code)];
                }
            }
            curl_multi_close($mh);
        }

        // Fallback path for anything curl didn't answer.
        foreach ($pending as $key => $url) {
            if (isset($out[$key])) {
                continue;
            }
            if (!ini_get('allow_url_fopen')) {
                $out[$key] = ['ok' => false, 'body' => '', 'status' => 0, 'err' => 'no_curl_and_fopen_off'];
                continue;
            }
            $ctx = stream_context_create(['http' => [
                'timeout' => $timeout + $connTmo,
                'user_agent' => $ua,
                'header' => "Accept-Encoding: gzip\r\n",
                'ignore_errors' => true,
            ], 'ssl' => ['verify_peer' => true, 'verify_peer_name' => true]]);
            $body = @file_get_contents($url, false, $ctx);
            if (is_string($body) && isset($http_response_header[0]) && strpos($http_response_header[0], '200') !== false) {
                if (substr($body, 0, 2) === "\x1f\x8b" && function_exists('gzdecode')) {
                    $body = (string) gzdecode($body);
                }
                $out[$key] = ['ok' => true, 'body' => substr($body, 0, $maxBytes), 'status' => 200, 'err' => null];
            } else {
                $out[$key] = ['ok' => false, 'body' => '', 'status' => 0, 'err' => 'fetch_failed'];
            }
        }
        return $out;
    }

    /* ------------------------------------------------------------------ security bits */

    public static function hmac($payload)
    {
        $secret = (string) self::cfg('secret', '');
        if ($secret === '' || $secret === 'CHANGE_ME_64_HEX') {
            return '';
        }
        return hash_hmac('sha256', is_string($payload) ? $payload : self::jsonEncode($payload), $secret);
    }

    public static function clientIp()
    {
        foreach (['HTTP_CF_CONNECTING_IP', 'HTTP_X_FORWARDED_FOR', 'HTTP_X_REAL_IP', 'REMOTE_ADDR'] as $k) {
            if (!empty($_SERVER[$k])) {
                $v = explode(',', (string) $_SERVER[$k]);
                $ip = trim($v[0]);
                if (filter_var($ip, FILTER_VALIDATE_IP)) {
                    return $ip;
                }
            }
        }
        return '0.0.0.0';
    }

    /** Simple file-window rate limit (no redis needed). */
    public static function rateLimit($bucket, $max, $windowSec = 60)
    {
        if ($max <= 0) {
            return true;
        }
        $file = self::dataDir('ratelimit') . '/' . sha1($bucket . '|' . self::clientIp()) . '.cnt';
        $now = time();
        $slot = (int) floor($now / max(1, $windowSec));
        $data = json_decode((string) self::readText($file, '{}'), true);
        if (!is_array($data) || !isset($data['slot']) || (int) $data['slot'] !== $slot) {
            $data = ['slot' => $slot, 'n' => 0];
        }
        $data['n']++;
        self::writeAtomic($file, json_encode($data));
        return $data['n'] <= $max;
    }

    public static function isPublicIp($ip)
    {
        if (!filter_var($ip, FILTER_VALIDATE_IP)) {
            return false;
        }
        return filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE) !== false;
    }

    public static function base64Lenient($s)
    {
        $s = preg_replace('/\s+/', '', (string) $s);
        if ($s === null || $s === '') {
            return null;
        }
        if (strpos($s, '-') !== false || strpos($s, '_') !== false) {
            $s = strtr($s, '-_', '+/');
        }
        $pad = strlen($s) % 4;
        if ($pad) {
            $s .= str_repeat('=', 4 - $pad);
        }
        $d = base64_decode($s, true);
        if ($d === false || $d === '') {
            return null;
        }
        // must look like text, not random binary
        if (!preg_match('/[A-Za-z0-9\/+:]/', $d)) {
            return null;
        }
        return $d;
    }

    public static function log($msg, array $ctx = [])
    {
        if (!self::cfg('limits.logRequests', false)) {
            return;
        }
        $line = date('c') . ' ' . $msg . ($ctx ? ' ' . json_encode($ctx) : '') . "\n";
        $f = self::dataDir('log') . '/feed.log';
        if (is_file($f) && filesize($f) > 512 * 1024) {
            @rename($f, $f . '.1');
        }
        @file_put_contents($f, $line, FILE_APPEND | LOCK_EX);
    }

    public static function humanMs($ms)
    {
        $ms = (int) $ms;
        return $ms . 'ms';
    }
}
