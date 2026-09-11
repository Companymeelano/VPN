<?php
/**
 * Meelano VPN — feed endpoint configuration.
 * Target: shared hosting (cPanel), PHP 7.4+, no Node, no SSH, no composer, no cron required.
 *
 * Put host-specific overrides into config.local.php (it is never overwritten when you
 * update the code). Return the same nested shape; it is deep-merged over these defaults.
 */

$config = [
    'appName'   => 'Meelano VPN',
    'brand'     => [
        'vip'  => 'Vip Meelano',   // <-- the ONLY name the app must show for VIP nodes
        'free' => 'Free Meelano',  // <-- same idea for the tested free pool
    ],
    'timezone'  => 'Asia/Tehran',
    'dataDir'   => __DIR__ . '/data',
    'libDir'    => __DIR__ . '/lib',

    // Shared secret used to HMAC-sign version.json (defence in depth against a
    // tampered CDN/host). Put a 64 hex chars value here AND in BuildConfig.MEELANO_FEED_SECRET.
    'secret'    => 'CHANGE_ME_64_HEX',

    'access'    => [
        // Optional. If non-empty, the app must send header  X-Feed-Key: <value>
        // Stops other apps from free-riding on your paid VIP list.
        'feedKey'      => '',
        // Long random string used for stats|selftest|refresh  (?key=...)
        'toolKey'      => '',
        // Admin panel password. Generate the hash once with  /v/admin/hash.php?pass=YOURPASS
        // and paste it here (or into config.local.php). Empty = panel disabled.
        'adminPassHash' => '',
        'adminSessionTtl' => 7200,
    ],

    'cache'     => [
        'vipTtl'     => 600,     // rebuild vip.json after 10 min (on demand, never on a timer)
        'freeTtl'    => 3600,    // rebuild free.json hourly
        'versionTtl' => 300,
        'httpMaxAge' => 300,     // Cache-Control: public, max-age -> app hits 304, near-zero traffic
        // Serve the previous (stale) build instantly while a rebuild runs in the background.
        // This is what makes the list feel "instant" in the app.
        'serveStaleWhileRebuild' => true,
        'staleGrace' => 604800,  // still usable for 7 days if rebuilds keep failing
        'gzipPrebuild' => true,  // store a .gz next to the json and stream it untouched
    ],

    'http'      => [
        'timeout'        => 6,
        'connectTimeout' => 4,
        'maxBytes'       => 4194304,   // 4 MB per source
        'userAgent'      => 'MeelanoFeed/2.0 (+https://ainetmee.ir)',
        'retries'        => 1,
    ],

    'vip'       => [
        // Where the VIP list lives. Options:
        //   file  -> data/vip_raw.txt  (edited from the admin panel)
        //   url   -> any http(s) file on your host (html/php/txt/base64 — all tolerated)
        'sourceFile'  => 'vip_raw.txt',
        'url'         => '',                       // e.g. https://ainetmee.ir/v/private-list
        'maskNames'   => true,                     // drop original remarks, show brand + flag
        'maxNodes'    => 40,
        'probe'       => true,                     // measure latency, but never drop a VIP for one fail
        'dropIfProbeFails' => false,
    ],

    'free'      => [
        'enabled'       => true,
        'maxCandidates' => 900,    // hard cap before probing (shared hosting CPU guard)
        'maxNodes'      => 80,      // nodes published in free.json
        'minNodes'      => 6,       // if we end up below this, keep the previous build
        'shuffleSeed'   => 'daily', // rotate the probed subset instead of starving the tail
        // Only these ports are probed for raw http/socks proxies (a real-world filter on its own).
        'allowPorts'    => [80, 443, 800, 808, 888, 1080, 1934, 2002, 2052, 2082, 2086, 2095,
                             3000, 3128, 4443, 5050, 5222, 8000, 8080, 8081, 8085, 8090, 8888, 9999, 10000, 20000],
        'probe'         => [
            // master switch: set false on hosts where outbound TCP is useless/pointless
            'enabled'       => true,
            'concurrency'   => 40,
            'tcpTimeoutMs'  => 1200,
            'proxyTimeoutMs'=> 2500,
            'budgetMs'      => 18000,   // never spend more than 18s probing in one request
            'deepGate'      => false,   // TLS-through-tunnel check (expensive; enable if host allows)
            'deepTopN'      => 12,
            'deepTimeoutMs' => 1500,
            // If outbound TCP is blocked by the host, everything still works: the ranking
            // then comes from client feedback only. selftest.php tells you which mode you are in.
            'autoDisableOnBlocked' => true,
        ],
        'grades' => [
            ['grade' => 'A', 'maxLatencyMs' => 400,  'minReliability' => 0.75],
            ['grade' => 'B', 'maxLatencyMs' => 1000, 'minReliability' => 0.50],
            ['grade' => 'C', 'maxLatencyMs' => 2200, 'minReliability' => 0.25],
            ['grade' => 'D', 'maxLatencyMs' => 99999, 'minReliability' => 0.0],
        ],
        // The "not blocked by Google / Cloudflare" gate the brief asks for:
        // a proxy must CONNECT to >= requireOf of the endpoints below to enter the list.
        // probeAll=false keeps cost at one endpoint (the first one) — raise both on a fast box.
        'gate' => [
            'requireOf' => 1,
            'probeAll'  => false,
            'endpoints' => [
                ['name' => 'google',     'host' => 'www.google.com',         'port' => 443],
                ['name' => 'cloudflare', 'host' => '1.1.1.1',               'port' => 443],
                ['name' => 'gstatic',    'host' => 'connectivitycheck.gstatic.com', 'port' => 443],
            ],
        ],
        'bans' => [
            'failLimit'   => 3,
            'banSeconds'  => 21600,   // 6h cool-off, then it may earn its way back
            'banFile'     => 'bans.json',
        ],
        'feedback' => [
            'enabled'      => true,
            'weight'       => 0.55,  // real users inside Iran outweigh a Frankfurt probe
            'maxPerIpPerMin' => 12,
            'trustFloor'   => 3,     // ignore a node's client score before N reports
        ],
    ],

    'update'  => [
        // APKs must be web-readable, so they live OUTSIDE data/ (data/ is denied to http).
        // Naming:  meelano-<versionName>-<versionCode>.apk
        'apkDir'    => __DIR__ . '/apk',
        // Optional absolute public base, e.g. https://ainetmee.ir/v/apk  (recommended: set it)
        'publicBase' => '',
        'channel'   => 'stable',
        'fileName'  => 'version.json',
        // Below this versionCode the app shows a blocking "must update" sheet.
        'mandatoryBelow' => 0,
        // APK downloads MUST be https:// — plain http allows a MITM to swap the APK.
        'requireHttpsForApk' => true,
    ],

    'selftest' => [
        'network' => true,   // false = skip the outbound probes (used by the test suite)
    ],

    'limits'  => [
        'reqPerMinPerIp' => 60,
        'logRequests'    => false,
    ],
];

$localFile = __DIR__ . '/config.local.php';
if (is_file($localFile)) {
    $local = require $localFile;
    if (is_array($local)) {
        $config = array_replace_recursive($config, $local);
    }
}

return $config;
