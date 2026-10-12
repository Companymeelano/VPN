<?php
/**
 * Public service health — the numbers we are willing to show a stranger.
 *
 * Why this exists: the single most persuasive thing a filter-evasion service can offer is not a
 * promise, it is a visible, boring dashboard. "۹ از ۱۲ گره VIP سالم، میانهٔ تاخیر ۲۱۰ms، رژیم: TIGHT"
 * says more than "سریع و پایدار" ever will, and it costs us nothing because every number below is
 * already computed for the feed itself.
 *
 * What must never appear here (asserted in tests/run.php, not just promised in a comment):
 *  - host, port, raw URI or node id of anything: a status page is the one page a scraper reads;
 *  - the PHP version (admin surfaces may show it; a public page must not);
 *  - anything per-user. Only fleet aggregates, and only when enough clients reported to make a
 *    single user's ISP unidentifiable.
 */
final class Status
{
    /** Cached for two minutes: the cron refreshes feeds far slower than that, and this page is shared. */
    const TTL = 120;

    public static function summary($force = false)
    {
        if (!$force) {
            $c = Util::cacheRead('status');
            if ($c && !empty($c['fresh'])) {
                return $c['payload'];
            }
        }
        $out = [
            'schema' => 1,
            'app'    => (string) Util::cfg('appName', 'M•A VPN'),
            'now'    => time(),
            'time'   => date('c'),
            // cacheWrite takes the ttl from the payload itself (that is how vip/free work too), so a
            // hand-edited cache file cannot outlive the number it claims
            'ttl'    => self::TTL,
            'feeds'  => [
                'vip'  => self::feed('vip'),
                'free' => self::feed('free'),
            ],
            'fleet'  => self::fleet(),
            'update' => self::update(),
            'privacy' => [
                'noIps'       => true,
                'noHostnames' => true,
                'note'        => 'این صفحه فقط شمارشِ تجمعی است؛ نه آدرسی، نه شناسه‌ی گره‌ای، نه داده‌ی کاربری در آن نیست.',
            ],
        ];
        Util::cacheWrite('status', $out);
        return $out;
    }

    /**
     * One feed, reduced to what a visitor can act on.
     *
     * We read the *published* payload (the same array the phones get) rather than the source files:
     * whatever the app can see is the only thing this page is allowed to see, so the two can never
     * disagree, and a future field added to the payload cannot leak here by accident - the whitelist
     * below is a whitelist, not a rename.
     */
    private static function feed($kind)
    {
        $c = Util::cacheRead($kind);
        $p = $c && isset($c['payload']) ? $c['payload'] : null;
        if (!is_array($p)) {
            return ['published' => false, 'nodes' => 0];
        }
        $grades = ['A' => 0, 'B' => 0, 'C' => 0, 'D' => 0];
        $alive = 0;
        $lat = [];
        $cc = [];
        $proto = [];
        foreach ((array) (isset($p['servers']) ? $p['servers'] : []) as $s) {
            $q = isset($s['quality']) && is_array($s['quality']) ? $s['quality'] : [];
            $g = isset($q['grade']) ? strtoupper((string) $q['grade']) : 'D';
            if (!isset($grades[$g])) {
                $grades[$g] = 0;
            }
            $grades[$g]++;
            if (!empty($q['alive'])) {
                $alive++;
            }
            if (isset($q['latencyMs']) && is_numeric($q['latencyMs'])) {
                $lat[] = (int) $q['latencyMs'];
            }
            if (!empty($s['cc'])) {
                $two = strtoupper((string) $s['cc']);
                $cc[$two] = isset($cc[$two]) ? $cc[$two] + 1 : 1;
            }
            if (!empty($s['proto'])) {
                $pr = (string) $s['proto'];
                $proto[$pr] = isset($proto[$pr]) ? $proto[$pr] + 1 : 1;
            }
        }
        sort($lat, SORT_NUMERIC);
        $n = count($lat);
        $half = intdiv($n, 2);
        $med = $n === 0 ? null : (int) round($n % 2 === 1 ? $lat[$half] : ($lat[$half - 1] + $lat[$half]) / 2);
        arsort($cc);
        arsort($proto);
        $meta = isset($p['meta']) && is_array($p['meta']) ? $p['meta'] : [];
        $gen = isset($p['generatedAt']) ? (int) $p['generatedAt'] : 0;
        return [
            'published'        => true,
            'nodes'            => (int) (isset($p['count']) ? $p['count'] : array_sum($grades)),
            'alive'            => $alive,
            'grades'           => $grades,
            'medianLatencyMs'  => $med,
            'countries'        => array_slice($cc, 0, 6, true),
            'protocols'        => $proto,
            'regime'           => isset($meta['regime']) ? (string) $meta['regime'] : '',
            'tunedBy'          => isset($meta['tunedBy']) ? (string) $meta['tunedBy'] : '',
            'candidates'       => isset($meta['candidates']) ? (int) $meta['candidates'] : 0,
            'probed'           => isset($meta['probed']) ? (int) $meta['probed'] : 0,
            'gated'            => isset($meta['gated']) ? (int) $meta['gated'] : 0,
            'generatedAt'      => $gen ? date('c', $gen) : null,
            'ageSec'           => $gen ? max(0, time() - $gen) : null,
        ];
    }

    /** Fleet evidence: the same aggregate the tuner uses, with the sample count so a 2-report vote reads as one. */
    private static function fleet()
    {
        $vip = Util::cacheRead('vip');
        $fleet = isset($vip['payload']['meta']['fleet']) && is_array($vip['payload']['meta']['fleet'])
            ? $vip['payload']['meta']['fleet'] : [];
        $min = (int) Util::cfg('tune.minVotesForRegime', 3);
        $reports = isset($fleet['reports']) ? (int) $fleet['reports'] : 0;
        return [
            'regime'       => isset($fleet['regime']) ? (string) $fleet['regime'] : '',
            'reports'      => $reports,
            'meaningful'   => $reports >= $min,
            'tcpFailPct'   => isset($fleet['tcpFail']) ? (int) round(((float) $fleet['tcpFail']) * 100) : 0,
            'tlsFailPct'   => isset($fleet['tlsFail']) ? (int) round(((float) $fleet['tlsFail']) * 100) : 0,
            'dnsPoisoned'  => !empty($fleet['dnsPoisoned']),
            'updatedAt'    => isset($fleet['at']) && $fleet['at'] ? date('c', (int) $fleet['at']) : null,
        ];
    }

    /** What the update endpoint would hand out right now - so "new version not showing" is answerable here. */
    private static function update()
    {
        $v = Util::cacheRead('version');
        if ($v && isset($v['payload']) && is_array($v['payload'])) {
            $p = $v['payload'];
        } else {
            $p = Version::serve();
        }
        return [
            'published'    => !isset($p['error']),
            'versionName'  => isset($p['versionName']) ? (string) $p['versionName'] : '',
            'versionCode'  => isset($p['versionCode']) ? (int) $p['versionCode'] : 0,
            'sizeBytes'     => isset($p['sizeBytes']) ? (int) $p['sizeBytes'] : 0,
            'sha256'       => isset($p['sha256']) ? (string) $p['sha256'] : '',
        ];
    }

    /**
     * The HTML the user shares in the Telegram channel. Inline CSS on purpose: this file gets uploaded
     * next to index.php on a shared host, and a second asset is a second thing that can 404.
     */
    public static function renderHtml(array $d)
    {
        $esc = function ($v) { return htmlspecialchars((string) $v, ENT_QUOTES, 'UTF-8'); };
        $feed = function ($k, $label) use ($d, $esc) {
            $f = isset($d['feeds'][$k]) ? $d['feeds'][$k] : [];
            if (empty($f['published'])) {
                return '<section class="card"><h2>' . $esc($label) . '</h2><p class="dim">هنوز فیدی منتشر نشده است.'
                    . '<br><span class="mono">cron → index.php (action=' . $esc($k) . ')</span></p></section>';
            }
            $g = (array) $f['grades'];
            $chips = '';
            foreach (['A', 'B', 'C', 'D'] as $k2) {
                $n = isset($g[$k2]) ? (int) $g[$k2] : 0;
                $chips .= '<span class="chip g' . $k2 . '">' . $k2 . ' <b>' . $n . '</b></span> ';
            }
            $cc = [];
            foreach ((array) $f['countries'] as $code => $n) {
                $cc[] = '<span class="cc">' . $esc($code) . '·' . $n . '</span>';
            }
            $lat = $f['medianLatencyMs'] === null ? '—' : $esc($f['medianLatencyMs']) . 'ms';
            $age = $f['ageSec'] === null ? '' : ' · ' . $esc(round($f['ageSec'] / 60)) . ' دقیقه پیش';
            return '<section class="card"><h2>' . $esc($label) . '</h2>'
                . '<p class="big">' . $esc($f['alive']) . '<span class="of">/' . $esc($f['nodes']) . ' گره سالم</span></p>'
                . '<p class="row">' . $chips . '</p>'
                . '<p class="kv"><span>میانهٔ تاخیر</span><b class="mono">' . $lat . '</b></p>'
                . '<p class="kv"><span>رژیمِ ترنسپورت</span><b class="mono">' . $esc($f['regime'] ?: 'tight') . '</b></p>'
                . '<p class="kv"><span>تنظیم‌شده توسط</span><b class="mono">' . $esc($f['tunedBy'] ?: 'heuristic') . '</b></p>'
                . '<p class="kv"><span>کاندید / پروب / گیت</span><b class="mono">' . $esc($f['candidates']) . ' / '
                . $esc($f['probed']) . ' / ' . $esc($f['gated']) . '</b></p>'
                . '<p class="row">' . implode(' ', $cc) . '</p>'
                . '<p class="dim">انتشار: ' . $esc($f['generatedAt']) . $age . '</p>'
                . '</section>';
        };
        $fl = $d['fleet'];
        $up = $d['update'];
        $state = $fl['dnsPoisoned'] ? 'warn' : (($fl['tcpFailPct'] >= 40) ? 'bad' : 'ok');
        $stateText = [
            'ok'   => 'وضعیت عادی',
            'warn' => 'آلودگی DNS گزارش شده — برنامه به‌صورت خودکار از DoH استفاده می‌کند',
            'bad'  => 'موج اختلال گزارش شده — رژیم سخت‌گیرانه‌تر فعال شده است',
        ][$state];
        return '<!doctype html>
<html lang="fa" dir="rtl"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>وضعیت سرویس ' . $esc($d['app']) . '</title>
<style>
  :root{--bg:#0B0F14;--s:#13171C;--line:#232C33;--tx:#E6EDF3;--dim:#8FA1AD;--acc:#4ADE9B;--warn:#E9A23B;--bad:#F2635B}
  @media (prefers-color-scheme: light){:root{--bg:#F5F7F9;--s:#FFFFFF;--line:#DDE4EA;--tx:#0D151C;--dim:#4B5C6B;--acc:#0E8F5B;--warn:#8A5200;--bad:#C0362C}}
  *{box-sizing:border-box}body{margin:0;padding:24px;background:var(--bg);color:var(--tx);
    font:15px/1.75 -apple-system,"Vazirmatn","Segoe UI",Tahoma,sans-serif}
  .wrap{max-width:840px;margin:0 auto}
  h1{font-size:19px;margin:0 0 4px;letter-spacing:.2px}
  .sub{color:var(--dim);margin:0 0 18px;font-size:13px}
  .grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}
  @media (max-width:680px){.grid{grid-template-columns:1fr}}
  .card{background:var(--s);border:1px solid var(--line);border-radius:18px;padding:16px 18px;margin:0 0 14px}
  h2{font-size:13px;margin:0 0 10px;color:var(--dim);font-weight:600;letter-spacing:.4px}
  .big{font-size:30px;margin:0 0 6px;font-variant-numeric:tabular-nums}
  .of{font-size:13px;color:var(--dim)}
  .kv{display:flex;justify-content:space-between;gap:12px;margin:4px 0;font-size:13.5px}
  .kv span{color:var(--dim)}.kv b{font-weight:600}
  .mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-variant-numeric:tabular-nums}
  .row{margin:8px 0}
  .chip{display:inline-block;padding:2px 9px;border-radius:999px;border:1px solid var(--line);font-size:12.5px}
  .chip b{margin-inline-start:4px}
  .gA{color:var(--acc)}.gB{color:var(--dim)}.gC{color:var(--warn)}.gD{color:var(--bad)}
  .cc{font-size:12.5px;color:var(--dim);padding:2px 7px;border:1px solid var(--line);border-radius:8px;margin-inline-end:4px}
  .pill{display:inline-flex;align-items:center;gap:8px;padding:8px 14px;border-radius:999px;font-size:14px;
    border:1px solid var(--line);background:var(--s);margin:0 0 14px}
  .dot{width:9px;height:9px;border-radius:50%;background:var(--acc);box-shadow:0 0 0 4px rgba(74,222,155,.16)}
  .warn .dot{background:var(--warn);box-shadow:0 0 0 4px rgba(233,162,59,.18)}
  .bad .dot{background:var(--bad);box-shadow:0 0 0 4px rgba(242,99,91,.18)}
  a{color:var(--acc)}
  footer{color:var(--dim);font-size:12.5px;margin-top:8px}
</style></head><body><div class="wrap">
<h1>وضعیت سرویس ' . $esc($d['app']) . '</h1>
<p class="sub">شمارشِ تجمعی از همان داده‌ای که به گوشی‌ها می‌رسد — بدون آدرس، بدون شناسه، بدون لاگ.</p>
<div class="pill ' . $state . '"><i class="dot"></i>' . $esc($stateText) . '</div>
<div class="grid">' . $feed('vip', 'کاربران اختصاصی') . $feed('free', 'پروکسی‌های آزاد') . '</div>
<section class="card"><h2>شواهدِ جمعی</h2>
<p class="kv"><span>گزارش‌های اتصال در بازهٔ اخیر</span><b class="mono">' . $esc($fl['reports']) .
    ($fl['meaningful'] ? '' : ' (کم برای تغییرِ رژیم)') . '</b></p>
<p class="kv"><span>شکست TCP</span><b class="mono">' . $esc($fl['tcpFailPct']) . '%</b></p>
<p class="kv"><span>شکست TLS</span><b class="mono">' . $esc($fl['tlsFailPct']) . '%</b></p>
<p class="kv"><span>آلودگی DNS</span><b class="mono">' . ($fl['dnsPoisoned'] ? 'بله' : 'خیر') . '</b></p>
<p class="kv"><span>رژیمِ جاری</span><b class="mono">' . $esc($fl['regime'] ?: 'tight') . '</b></p>
</section>
<section class="card"><h2>آخرین نسخه</h2>
<p class="kv"><span>نسخه</span><b class="mono">' . ($up['published'] ? $esc($up['versionName']) . ' (' . $esc($up['versionCode']) . ')' : 'منتشر نشده') . '</b></p>
' . ($up['sizeBytes'] ? '<p class="kv"><span>حجم APK</span><b class="mono">' . $esc(round($up['sizeBytes'] / 1048576, 1)) . ' MB</b></p>' : '') . '
' . ($up['sha256'] ? '<p class="kv"><span>sha256</span><b class="mono">' . $esc(substr($up['sha256'], 0, 12)) . '…</b></p>' : '') . '
<p class="dim">JSON همین داده: <a class="mono" href="index.php?action=status">?action=status</a> · به‌روزرسانیِ همین صفحه با <a class="mono" href="status.php?r=1">?r=1</a></p>
</section>
<footer>ساخته‌شده در <span class="mono">' . $esc($d['time']) . '</span> · کشِ ۲ دقیقه‌ای</footer>
</div>
<p style="margin-top:14px"><a href="status.php?r=1" class="mono">⟳ بررسی دوباره</a></p>
</body></html>';
    }
}
