<?php
/**
 * Upstream sources for the free pool.
 *
 * `kind`:
 *   config -> lines of vless:// vmess:// trojan:// ss:// hy2:// tuic:// (tunnel-ready, what the app needs)
 *   proxy  -> host:port / user:pass@host:port / protocol://ip:port (raw http+socks proxies)
 *   json   -> monosans/jetkai style JSON (already scored + geolocated by the upstream -> cheapest path)
 *
 * Only the top of each list matters: they are all "sorted best first", so `take` keeps the
 * head and we never burn the shared host on 400k dead lines.
 *
 * You can drop a sources.local.php next to this file returning extra entries; they are appended.
 */

$urls = [
    // ---------------------------------------------------------------- real tunnel configs
    'gfp-vless' => [
        'kind' => 'config', 'take' => 300,
        'url'  => 'https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vless.txt',
    ],
    'gfp-trojan' => [
        'kind' => 'config', 'take' => 120,
        'url'  => 'https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/trojan.txt',
    ],
    'gfp-ss' => [
        'kind' => 'config', 'take' => 120,
        'url'  => 'https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/ss.txt',
    ],
    'gfp-vmess' => [
        'kind' => 'config', 'take' => 120,
        'url'  => 'https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vmess.txt',
    ],
    'gfp-hy2' => [
        'kind' => 'config', 'take' => 40,
        'url'  => 'https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/hy2.txt',
    ],
    // ---------------------------------------------------------------- pre-scored proxy JSON
    'monosans-json' => [
        'kind' => 'json', 'take' => 400, 'priority' => 10,
        'url'  => 'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies.json',
        'note' => 're-checked hourly upstream, ships response time + country -> cheap and accurate',
    ],
    // ---------------------------------------------------------------- raw proxy lists (fallbacks)
    'thespeedx-http' => [
        'kind' => 'proxy', 'take' => 250,
        'url'  => 'https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt',
    ],
    'thespeedx-socks5' => [
        'kind' => 'proxy', 'take' => 250,
        'url'  => 'https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt',
    ],
    'clarketm' => [
        'kind' => 'proxy', 'take' => 200,
        'url'  => 'https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt',
    ],
    'shiftytr' => [
        'kind' => 'proxy', 'take' => 200,
        'url'  => 'https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt',
    ],
    'monosans-http' => [
        'kind' => 'proxy', 'take' => 200,
        'url'  => 'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt',
    ],
    'monosans-socks5' => [
        'kind' => 'proxy', 'take' => 200,
        'url'  => 'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt',
    ],
    'jetkai' => [
        'kind' => 'proxy', 'take' => 200,
        'url'  => 'https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies.txt',
    ],
    'sunny9577' => [
        'kind' => 'proxy', 'take' => 150,
        'url'  => 'https://raw.githubusercontent.com/sunny9577/proxy-scraper/master/proxies.txt',
    ],
];

// Local fixtures for offline/dev testing: fixture://name  ->  data/fixtures/name.txt
$fixtures = [
    'fixture-configs' => ['kind' => 'config', 'take' => 50, 'url' => 'fixture://configs'],
    'fixture-proxies' => ['kind' => 'proxy',  'take' => 50, 'url' => 'fixture://proxies'],
];

return [
    'sources' => $urls,
    'fixturesOnly' => $fixtures,
    'disabled' => [],           // source ids to skip, e.g. ['gfp-vless']
];
