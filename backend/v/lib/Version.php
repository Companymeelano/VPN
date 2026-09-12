<?php
/**
 * Version / self-update index for the whole app.
 *
 * You publish by dropping an APK in data/apk/ (or uploading it from the admin panel) and
 * writing data/version.json. The app polls this, compares versionCode, downloads, verifies
 * sha256 (+ HMAC below), and hands the file to the installer.
 */
final class Version
{
    public static function indexFile()
    {
        return Util::dataDir() . '/' . basename((string) Util::cfg('update.fileName', 'version.json'));
    }

    public static function apkDir()
    {
        // rtrim only: trim() with "/" in the charlist would eat the leading slash of an
        // absolute path and turn /srv/x into srv/x (which we would then re-prefix).
        $d = rtrim(trim((string) Util::cfg('update.apkDir', 'apk')), "/ \t\\");
        if ($d === '') {
            $d = 'apk';
        }
        // absolute path (recommended) or path relative to data/
        return $d[0] === '/' || preg_match('~^[a-z]:[\\\\/]~i', $d) ? $d : Util::dataDir() . '/' . $d;
    }

    /** @return array the payload to serve */
    public static function serve()
    {
        $data = self::readIndex();
        if ($data === null) {
            $data = self::scan();
        }
        if (!isset($data['sig'])) {
            $data['sig'] = self::sign($data);
        }
        // the exact string that was signed, so the client verifies without guessing JSON rules;
        // UpdateManager recomposes it from the parsed fields and refuses if they disagree.
        $data['sigInput'] = self::canonical($data);
        $data['policy'] = [
            'autoDownloadOverWifi' => true,
            'checkIntervalHours'   => 12,
            'requireHttps'         => (bool) Util::cfg('update.requireHttpsForApk', true),
        ];
        $data['current'] = [
            'versionCode' => isset($data['versionCode']) ? (int) $data['versionCode'] : 0,
            'versionName' => isset($data['versionName']) ? (string) $data['versionName'] : '',
            'channel'     => isset($data['channel']) ? $data['channel'] : Util::cfg('update.channel', 'stable'),
            'releasedAt'  => isset($data['releasedAt']) ? $data['releasedAt'] : null,
        ];
        // The app sends ?vc=<its versionCode>; we answer with the verdict so the client
        // needs no comparison logic of its own (and cannot be fooled into skipping one).
        $published = (int) (isset($data['versionCode']) ? $data['versionCode'] : 0);
        $vc = isset($_GET['vc']) && preg_match('~^\d{1,9}$~', (string) $_GET['vc']) ? (int) $_GET['vc'] : null;
        $mandatoryBelow = (int) Util::cfg('update.mandatoryBelow', 0);
        if (isset($data['mandatoryBelow']) && is_numeric($data['mandatoryBelow'])) {
            $mandatoryBelow = max($mandatoryBelow, (int) $data['mandatoryBelow']);
        }
        $data['mandatoryBelow'] = $mandatoryBelow;
        $data['mandatory'] = !empty($data['mandatory']);
        $data['updateAvailable'] = $vc === null ? null : ($vc < $published);
        if ($vc !== null) {
            $data['currentVersionCode'] = $vc;
            if ($mandatoryBelow > 0 && $vc < $mandatoryBelow) {
                $data['mandatory'] = true;
            }
        }
        if (!empty($data['error'])) {
            $data['updateAvailable'] = false;
        }
        return $data;
    }

    /** @return array|null */
    public static function readIndex()
    {
        $raw = Util::readText(self::indexFile());
        if ($raw === null) {
            return null;
        }
        $d = json_decode($raw, true);
        return is_array($d) ? $d : null;
    }

    /**
     * Derive the index from whatever APKs are sitting in data/apk/.
     * Naming convention:  meelano-<versionName>-<versionCode>.apk   (e.g. meelano-1.7.0-12.apk)
     */
    public static function scan($persist = false)
    {
        $dir = self::apkDir();
        $best = null;
        if (is_dir($dir)) {
            foreach (scandir($dir) ?: [] as $f) {
                if (!preg_match('~^(?P<base>.+)-(?P<name>\d+\.\d+(?:\.\d+)?)-(?P<code>\d+)\.apk$~', $f, $m)) {
                    continue;
                }
                $full = $dir . '/' . $f;
                if (!is_file($full)) {
                    continue;
                }
                $row = [
                    'file' => $f,
                    'versionName' => $m['name'],
                    'versionCode' => (int) $m['code'],
                    'sizeBytes' => (int) filesize($full),
                    'mtime' => (int) filemtime($full),
                ];
                if ($best === null || $row['versionCode'] > $best['versionCode']) {
                    $best = $row;
                }
            }
        }
        if ($best === null) {
            return ['schema' => 1, 'error' => 'no_apk_published', 'versionCode' => 0,
                    // NOT data/apk/: data/ is denied over http on purpose, so an APK put there is findable by
                    // the scanner and undownloadable by the app. The web-readable folder is /v/apk/.
                    'versionName' => '', 'apkUrl' => '', 'hint' => 'put meelano-<versionName>-<versionCode>.apk into the public /v/apk/ folder (not data/) or upload from the admin panel',
                    'note' => self::note(), 'generatedAt' => time()];
        }
        $payload = [
            'schema'      => 1,
            'channel'     => Util::cfg('update.channel', 'stable'),
            'versionCode' => $best['versionCode'],
            'versionName' => $best['versionName'],
            'apkUrl'      => self::publicUrl($best['file']),
            'sizeBytes'   => $best['sizeBytes'],
            'sha256'      => self::checksum($dir . '/' . $best['file'], $best['file']),
            'releasedAt'  => gmdate('c', $best['mtime']),
            'mandatory'   => false,
            'changelogFa' => '',
            'generatedAt' => time(),
        ];
        if ($persist) {
            Util::writeAtomic(self::indexFile(), Util::jsonEncode($payload));
        }
        return $payload;
    }

    private static function note()
    {
        return is_dir(self::apkDir()) ? 'apk dir exists' : 'apk dir missing: ' . self::apkDir();
    }

    /** Public https URL for the APK (falls back to the host of this request). */
    public static function publicUrl($file)
    {
        $base = rtrim((string) Util::cfg('update.publicBase', ''), '/');
        if ($base === '') {
            $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'https';
            $host = isset($_SERVER['HTTP_HOST']) ? preg_replace('~:\d+$~', '', (string) $_SERVER['HTTP_HOST']) : 'ainetmee.ir';
            $dir = rtrim(dirname($_SERVER['SCRIPT_NAME'] ?? '/v/index.php'), '/');
            $base = $scheme . '://' . $host . $dir . '/data/' . trim((string) Util::cfg('update.apkDir', 'apk'), '/');
        }
        return $base . '/' . rawurlencode(basename($file));
    }

    /** Cached sha256 (next to the file) so we never hash a 60 MB APK on every poll. */
    public static function checksum($path, $file = null)
    {
        $side = $path . '.sha256';
        if (is_file($side) && is_file($path)) {
            $v = trim((string) Util::readText($side, ''));
            // Two shapes people actually produce: the bare hash, and `sha256sum > x.apk.sha256`
            // (hash + two spaces + filename). Both are caches, never sources of truth: whatever fails
            // the pattern below simply re-hashes the file below.
            // Freshness is filemtime vs filemtime - it used to compare the sidecar's *size* against the
            // apk's mtime, which is false forever (97 >= 1.7e9), so every poll re-hashed a 13 MB file
            // on the shared host and the sidecar feature silently did nothing.
            if (preg_match('~^([a-f0-9]{64})(?:\s|$)~', $v, $m)
                && (int) filemtime($side) >= (int) filemtime($path)) {
                return $m[1];
            }
        }
        if (!is_file($path)) {
            return '';
        }
        $h = @hash_file('sha256', $path);
        if (!is_string($h)) {
            return '';
        }
        Util::writeAtomic($side, $h . "\n");
        return $h;
    }

    public static function sign(array $payload)
    {
        $canonical = self::canonical($payload);
        $sig = Util::hmac($canonical);
        return $sig === '' ? '' : $sig;
    }

    /**
     * What the signature covers. Deliberately a flat, ordered string instead of "the JSON":
     * re-creating byte-identical JSON in two languages (key order, unicode escaping, number
     * formatting) is a classic source of "the client rejects every legit update".
     * Order must be mirrored in UpdateManager.canonical().
     */
    public static function canonical(array $d)
    {
        return implode('|', [
            'v' . (int) (isset($d['versionCode']) ? $d['versionCode'] : 0),
            (string) (isset($d['versionName']) ? $d['versionName'] : ''),
            (string) (isset($d['apkUrl']) ? $d['apkUrl'] : ''),
            strtolower((string) (isset($d['sha256']) ? $d['sha256'] : '')),
            (string) (int) (isset($d['sizeBytes']) ? $d['sizeBytes'] : 0),
            !empty($d['mandatory']) ? '1' : '0',
            (string) (int) (isset($d['mandatoryBelow']) ? $d['mandatoryBelow'] : 0),
            (string) (isset($d['channel']) ? $d['channel'] : 'stable'),
        ]);
    }

    /** @return array what was written */
    public static function publish(array $row)
    {
        $allowed = [
            'schema', 'channel', 'versionCode', 'versionName', 'apkUrl', 'file', 'sizeBytes',
            'sha256', 'releasedAt', 'mandatory', 'mandatoryBelow', 'changelogFa', 'notesFa',
            'minAndroidVersion', 'rollbackAllowed',
        ];
        $data = self::readIndex() ?: ['schema' => 1];
        foreach ($allowed as $k) {
            if (array_key_exists($k, $row)) {
                $data[$k] = $row[$k];
            }
        }
        if (isset($row['file'])) {
            $data['apkUrl'] = self::publicUrl($row['file']);
            $path = self::apkDir() . '/' . basename($row['file']);
            $data['sizeBytes'] = is_file($path) ? (int) filesize($path) : 0;
            $data['sha256'] = self::checksum($path, $row['file']);
            $data['releasedAt'] = gmdate('c');
        }
        $data['versionCode'] = (int) (isset($data['versionCode']) ? $data['versionCode'] : 0);
        if ($data['versionCode'] <= 0 && isset($data['file']) && preg_match('~-(\d+)\.apk$~', (string) $data['file'], $m)) {
            $data['versionCode'] = (int) $m[1];
        }
        $data['generatedAt'] = time();
        $data['channel'] = isset($data['channel']) ? $data['channel'] : Util::cfg('update.channel', 'stable');
        unset($data['sig'], $data['sigInput']);
        $data['sig'] = self::sign($data);
        $data['sigInput'] = self::canonical($data);
        Util::writeAtomic(self::indexFile(), Util::jsonEncode($data));
        @unlink(Util::dataDir('cache') . '/version.json');
        return $data;
    }
}
