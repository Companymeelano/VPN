<?php
/**
 * The division of labour for the AI layer, in one file, with the deterministic twin of every task next
 * to it. Read this file as a contract: each task says what it decides, what data it gets, and what
 * happens when the model is unavailable — which is the normal case, not the error case.
 *
 *   tune     per-node transport parameters  ->  merged into the feed payload ("tune" per server)
 *   regime   "what is blocked right now"     ->  fleet-wide preset + ordering hints
 *   rank     re-order of the list             ->  score multipliers only, never deletions
 *   explain  Persian advice text             ->  ?action=advice, on demand, cached hard
 *
 * Why the client keeps its own copy of these rules (android .../net/Regime.kt): the phone knows the
 * last mile, the server knows the far side. The server's patch is a *suggestion* the client may
 * override with its own live probes; the client's report is *evidence* the server folds into the next
 * build. Neither side is authoritative about the other's half, and that is the whole design.
 */
final class AiTune
{
    /** The schema Ai::clamp() enforces for a per-node patch. Ranges mirror net/Regime.kt exactly. */
    public static function patchSchema()
    {
        return [
            'fragSize'       => [0, 16384],
            'fragCount'      => [1, 64],
            'fragStrategy'   => ['enum', '', 'fixed', 'random', 'variable'],
            'fragDelayMs'    => [0, 600],
            'alpn'           => 'string',
            'fingerprint'    => ['enum', 'chrome', 'firefox', 'safari', 'ios', 'android', 'edge', '360', 'qq', 'random', 'randomized', ''],
            'sni'            => 'string',
            'ech'            => ['bool'],
            'keepAliveSec'   => [0, 300],
            'mux'            => ['bool'],
            'muxConcurrency' => [1, 64],
            'allowLan'       => ['bool'],
            'mtu'            => [576, 9000],
            'mss'            => [300, 1460],
            'grpcMode'       => ['enum', 'multi', 'one', 'normal', ''],
            'connectionReuse' => ['bool'],
            'why'            => 'string',      // admin-facing only; never reaches the app's UI text
        ];
    }

    public static function regimeSchema()
    {
        return [
            'regime'      => ['enum', 'calm', 'tight', 'blackout'],
            'prefer'      => ['list'],          // transport ids, best first
            'avoid'       => ['list'],
            'portHint'    => [1, 65535],
            'note'        => 'string',
            'confidence'  => [0, 100],
        ];
    }

    public static function adviceSchema()
    {
        return [
            'title'   => 'string',
            'body'    => 'string',
            'action'  => ['enum', 'retry', 'switch_node', 'change_regime', 'fragment_on', 'fragment_off', 'wait', 'contact'],
        ];
    }

    /* ================================================================== tune */

    /**
     * Decide a patch for every node in the build.
     *
     * The heuristic runs first, always. The model only ever *replaces* a patch for a node it was given
     * facts about and whose id it echoed back — so a hallucinated node, a wrong id, or an outage all
     * produce the same result: the heuristic. That is why this function cannot fail the feed.
     *
     * @param array $nodes  internal node rows (proto/tls/network/port/grade/latency + fleet stats)
     * @param array $fleet  aggregate client reports: ['tcpFail'=>0..1,'tlsFail'=>0..1,'dnsPoisoned'=>bool,'regime'=>'tight']
     * @return array        [id => patch]
     */
    public static function tune(array $nodes, array $fleet)
    {
        $regime = self::regimeFromFleet($fleet);
        $out = [];
        foreach ($nodes as $n) {
            $id = isset($n['id']) ? (string) $n['id'] : '';
            if ($id === '') {
                continue;
            }
            $out[$id] = self::heuristic($n, $regime, $fleet);
        }
        $patched = self::askModel($out, $nodes, $regime, $fleet);
        foreach ($patched as $id => $p) {
            if (isset($out[$id])) {
                // merge, never replace: the model writes only what it disagrees about, and every value
                // it wrote already went through Ai::clamp()
                $out[$id] = array_merge($out[$id], $p);
            }
        }
        return $out;
    }

    /**
     * The deterministic tuner. Rules are ordered by how much they cost the user, cheapest first —
     * because when two rules disagree, the one that keeps the connection *up* wins.
     */
    public static function heuristic(array $n, $regime, array $fleet)
    {
        $proto = strtolower((string) (isset($n['proto']) ? $n['proto'] : ''));
        $tls   = strtolower((string) (isset($n['tls']) ? $n['tls'] : ''));
        $net   = strtolower((string) (isset($n['network']) ? $n['network'] : 'tcp'));
        $port  = (int) (isset($n['port']) ? $n['port'] : 0);
        $tier  = (string) (isset($n['tier']) ? $n['tier'] : 'free');
        $p = [];

        // --- MTU/MSS: one clamp for every transport. 1280 survives tunnel + fragment + v6 overhead on
        //     Iranian mobile links; 1500 invites PMTUD blackholes, which look exactly like "VPN connected,
        //     websites don't load" — the single most reported symptom of a wrong MTU.
        $p['mtu'] = 1280;
        $p['mss'] = 1220;

        // --- Reality: no mux on top (multiplexed streams inside a Reality handshake are where some
        //     builds deadlock), and fragmentation off in calm (the handshake is already indistinguishable)
        if ($tls === 'reality') {
            $p['mux'] = false;
            $p['fingerprint'] = 'chrome';
            $p['fragSize'] = $regime === 'calm' ? 0 : 200;
            if ($regime !== 'calm') {
                $p['fragStrategy'] = 'variable';
                $p['fragCount'] = 2;
            }
        } elseif ($net === 'grpc') {
            // grpc multiplexes by itself; a second mux layer only adds head-of-line blocking
            $p['mux'] = false;
            $p['grpcMode'] = 'multi';
            $p['keepAliveSec'] = 15;
        } elseif ($proto === 'hysteria2' || $proto === 'hy2' || $proto === 'tuic' || $net === 'udp') {
            // QUIC fragments at the transport level; TCP-style fragmenting is meaningless here, and a
            // short keepalive is what keeps the NAT mapping alive through an ISP's 30-60s UDP timeout
            $p['fragSize'] = 0;
            $p['mux'] = false;
            $p['keepAliveSec'] = 10;
            $p['mtu'] = 1200;
        } elseif ($net === 'ws') {
            // plain-over-443 websocket is the shape they DROP: fragment, and prefer h2/h3 in the ALPN
            $p['mux'] = true;
            $p['fragSize'] = $regime === 'blackout' ? 120 : ($regime === 'calm' ? 0 : 180);
            $p['alpn'] = 'h2,http/1.1';
        } else {
            $p['mux'] = true;
            $p['fragSize'] = $regime === 'blackout' ? 120 : ($regime === 'calm' ? 0 : 200);
        }

        if ($regime === 'blackout') {
            $p['fragCount'] = 3;
            $p['fragStrategy'] = 'random';
            $p['fragDelayMs'] = 40;
            $p['keepAliveSec'] = 10;
            $p['muxConcurrency'] = 16;
        }
        // --- off-standard ports are where free proxies rot first: no TLS shape to hide behind on 8080,
        //     and a port they have never needed to filter is a port they will block first.
        if (!self::isGoodPort($port)) {
            $p['fragSize'] = max((int) (isset($p['fragSize']) ? $p['fragSize'] : 0), $regime === 'calm' ? 0 : 160);
            $p['connectionReuse'] = true;
        }
        // --- free tier: these nodes were scraped, so assume nothing about ECH/HTTPS records
        if ($tier === 'free') {
            $p['ech'] = false;
            $p['connectionReuse'] = true;
        }
        if (!empty($fleet['dnsPoisoned'])) {
            // resolvers are lying: any SNI that is a *domain we resolve* is a risk; the cover name the
            // feed shipped stays, but we stop letting the client invent one
            $p['sni'] = isset($n['sni']) && $n['sni'] !== '' ? (string) $n['sni'] : '';
        }
        // a client-side C-grade node on a saturated link does not need mux fighting for the same window
        if (isset($n['grade']) && $n['grade'] === 'D') {
            $p['mux'] = false;
        }
        foreach ($p as $k => $v) {
            if ($v === '' || $v === null) {
                unset($p[$k]);
            }
        }
        return $p;
    }

    /** 443/8443/2053/2083/2087/2096/2082/2052/2095 - the TLS-ish set that survives a filter sweep. */
    public static function isGoodPort($port)
    {
        return in_array((int) $port, [443, 8443, 2053, 2082, 2083, 2087, 2095, 2096, 4443, 2080], true);
    }

    public static function regimeFromFleet(array $fleet)
    {
        if (isset($fleet['regime']) && in_array($fleet['regime'], ['calm', 'tight', 'blackout'], true)) {
            return (string) $fleet['regime'];
        }
        $tcp = isset($fleet['tcpFail']) ? (float) $fleet['tcpFail'] : 0.0;
        $tls = isset($fleet['tlsFail']) ? (float) $fleet['tlsFail'] : 0.0;
        if ($tcp >= 0.65) {
            return 'blackout';
        }
        if ($tls >= 0.35 || $tcp >= 0.15 || !empty($fleet['dnsPoisoned'])) {
            return 'tight';
        }
        return 'calm';
    }

    /**
     * One model call for the whole fleet: cheaper, and better, than one per node — the comparison
     * *between* nodes ("these four are the same host on different ports; treat them as one") is the
     * only thing a language model can see that the heuristic cannot.
     */
    private static function askModel(array $current, array $nodes, $regime, array $fleet)
    {
        if (!$nodes || !Ai::allowed()) {
            return [];
        }
        if (!(bool) Util::cfg('ai.tasks.tune.enabled', true)) {
            return [];
        }
        $facts = [];
        foreach (array_slice($nodes, 0, (int) Util::cfg('ai.tasks.tune.maxNodes', 40)) as $n) {
            $facts[] = [
                'id'    => (string) $n['id'],
                'proto' => $n['proto'], 'tls' => $n['tls'], 'net' => $n['network'],
                'port'  => (int) $n['port'], 'tier' => $n['tier'],
                'grade' => isset($n['grade']) ? $n['grade'] : 'D',
                'ms'    => isset($n['latencyMs']) ? (int) $n['latencyMs'] : 0,
                'rel'   => isset($n['reliability']) ? round((float) $n['reliability'], 2) : 0,
                'samples' => isset($n['samples']) ? (int) $n['samples'] : 0,
            ];
        }
        $prompt =
            "Regime: {$regime}. Fleet: tcpFail=" . (isset($fleet['tcpFail']) ? round((float) $fleet['tcpFail'], 2) : 0)
            . " tlsFail=" . (isset($fleet['tlsFail']) ? round((float) $fleet['tlsFail'], 2) : 0)
            . " dnsPoisoned=" . (empty($fleet['dnsPoisoned']) ? 'false' : 'true') . ".\n"
            . "Current client-side defaults for this regime: " . json_encode($regime === 'blackout'
                ? ['fragSize' => 120, 'fragCount' => 3, 'mux' => true, 'mtu' => 1280]
                : ['fragSize' => 200, 'fragCount' => 2, 'mux' => true, 'mtu' => 1280]) . ".\n"
            . "Nodes (JSON): " . json_encode($facts) . "\n\n"
            . "Return {\"patches\": {\"<node id>\": {patch}}}. Emit a node ONLY when you would change "
            . "something from the defaults above; do not echo the defaults back. Allowed patch keys: "
            . implode(', ', array_keys(self::patchSchema())) . ". "
            . "Do not invent ids. Fragmentation helps when TLS is shape-matched; mux helps handshake "
            . "counts and hurts lossy links; grpc and reality must not carry an extra mux layer.";
        $res = Ai::json($prompt, [
            'task'     => 'tune',
            'cacheKey' => 'tune:' . $regime . ':' . substr(sha1(json_encode($facts)), 0, 16),
            'cacheTtl' => (int) Util::cfg('ai.cacheTtl.tune', 900),
            'schema'   => ['patches' => 'raw'],
        ]);
        if (!is_array($res) || !isset($res['patches']) || !is_array($res['patches'])) {
            return [];
        }
        $out = [];
        foreach ($res['patches'] as $id => $raw) {
            if (!isset($current[(string) $id]) || !is_array($raw)) {
                continue;                       // a node we never sent is a node we do not tune
            }
            $clean = Ai::clamp($raw, self::patchSchema());
            if ($clean) {
                unset($clean['why']);           // admin notes stay in the log, not in the payload
                $out[(string) $id] = $clean;
            }
        }
        if ($out) {
            Util::log('ai tune applied', ['nodes' => count($out), 'of' => count($current)]);
        }
        return $out;
    }

    /* ================================================================== regime */

    /**
     * The fleet-wide verdict: "what is blocked tonight". Runs on the aggregate of client reports, which
     * is exactly the data no single phone has. Falls back to the pure threshold function.
     */
    public static function regime(array $fleet, array $perCountry = [])
    {
        $guess = self::regimeFromFleet($fleet);
        $res = null;
        if (Ai::allowed() && (bool) Util::cfg('ai.tasks.regime.enabled', true)) {
            $prompt = "Recent client evidence from inside the country (JSON): "
                . json_encode(['fleet' => $fleet, 'byCc' => $perCountry]) . "\n"
                . "Decide the regime (calm|tight|blackout) the app should assume for the next 15 minutes, "
                . "which transports to prefer (ids: reality, xhttp-tls, grpc-tls, tcp-tls, ws-tls, udp-hy2, "
                . "tcp-ss, tcp-plain), which to avoid, and a one-line note for the operator. "
                . "Confidence is 0-100: use a low number when the sample is small.\n"
                . "Return {\"regime\":...,\"prefer\":[...],\"avoid\":[...],\"note\":\"...\",\"confidence\":...}.";
            $res = Ai::json($prompt, [
                'task'     => 'regime',
                'cacheKey' => 'regime:' . substr(sha1(json_encode([$fleet, array_keys($perCountry)])), 0, 16),
                'cacheTtl' => (int) Util::cfg('ai.cacheTtl.regime', 600),
                'schema'   => self::regimeSchema(),
            ]);
        }
        if (!is_array($res) || empty($res['regime'])) {
            $res = ['regime' => $guess, 'confidence' => min(60, 10 + 5 * (int) (isset($fleet['reports']) ? $fleet['reports'] : 0))];
        }
        if (!empty($res['confidence']) && (int) $res['confidence'] < (int) Util::cfg('ai.minConfidence', 25)) {
            $res['regime'] = $guess;             // low confidence must not move the whole fleet
        }
        return $res;
    }

    /* ================================================================== rank */

    /**
     * Score nudges from the model, applied as multipliers in Score::annotate's style. Bounded hard:
     * 0.85..1.15, because a ranking the model can swing by 2x is not a ranking, it is an outage
     * waiting for a bad day at the API.
     */
    public static function rankMultipliers(array $nodes, array $fleet)
    {
        $lo = (float) Util::cfg('ai.rank.min', 0.85);
        $hi = (float) Util::cfg('ai.rank.max', 1.15);
        if (!Ai::allowed() || !(bool) Util::cfg('ai.tasks.rank.enabled', false)) {
            return [];
        }
        $rows = [];
        foreach ($nodes as $n) {
            $rows[] = ['id' => (string) $n['id'], 'proto' => $n['proto'], 'port' => (int) $n['port'],
                       'grade' => isset($n['grade']) ? $n['grade'] : 'D',
                       'ms' => isset($n['latencyMs']) ? (int) $n['latencyMs'] : 0,
                       'rel' => isset($n['reliability']) ? round((float) $n['reliability'], 2) : 0];
        }
        $res = Ai::json(
            "Rank these for a phone on a mobile Iranian network tonight. Fleet: "
            . json_encode($fleet) . ". Nodes: " . json_encode($rows) . ".\n"
            . "Return {\"weights\": {\"<id>\": <0.." . (int) round($hi * 100) . ">}} where the number is a "
            . "percentage of the maximum nudge: 100 = boost by " . $hi . ", 0 = cut by " . $lo . ". "
            . "Fewer handshakes and standard ports beat raw latency during filtering.",
            ['task' => 'rank', 'cacheKey' => 'rank:' . substr(sha1(json_encode($rows)), 0, 16),
             'cacheTtl' => (int) Util::cfg('ai.cacheTtl.rank', 1800),
             'schema' => ['weights' => 'raw']]
        );
        $out = [];
        if (is_array($res) && isset($res['weights']) && is_array($res['weights'])) {
            $ids = [];
            foreach ($nodes as $n) {
                $ids[(string) $n['id']] = true;
            }
            foreach ($res['weights'] as $id => $w) {
                if (!isset($ids[(string) $id]) || !is_numeric($w)) {
                    continue;
                }
                $f = max(0, min(100, (int) $w)) / 100.0;
                $out[(string) $id] = round($lo + $f * ($hi - $lo), 4);
            }
        }
        return $out;
    }

    /* ================================================================== advice */

    /**
     * "It failed. What do I do?" — the text a user actually reads. Persian, one title, two sentences,
     * one action. The canned table is the product; the model is a polish layer on top of it, so a
     * missing key changes the wording, never the availability.
     */
    public static function advice($errCode, array $ctx = [])
    {
        $err = strtolower(trim((string) $errCode));
        $canned = self::cannedAdvice($err, $ctx);
        if (!Ai::allowed() || !(bool) Util::cfg('ai.tasks.advice.enabled', true)) {
            return $canned;
        }
        $res = Ai::json(
            "An Android VPN client in Iran failed with \"" . $err . "\". Context: "
            . json_encode($ctx) . ".\nWrite a short Persian reply for the user: title (max 4 words), "
            . "body (max 180 chars, plain text, no markdown), and exactly one action from: "
            . implode(', ', array_slice(self::adviceSchema()['action'], 1)) . ". "
            . "Do not promise a fix; name the next step. Return {\"title\":...,\"body\":...,\"action\":...}.",
            ['task' => 'advice', 'cacheKey' => 'advice:' . $err . ':' . substr(sha1(json_encode($ctx)), 0, 12),
             'cacheTtl' => (int) Util::cfg('ai.cacheTtl.advice', 3600),
             'schema' => self::adviceSchema(),
             'voice' => 'Persian, second person singular, no exclamation marks, no emoji.']
        );
        if (!is_array($res) || empty($res['body'])) {
            return $canned;
        }
        $res['canned'] = false;
        return $res;
    }

    private static function cannedAdvice($err, array $ctx)
    {
        $regime = isset($ctx['regime']) ? (string) $ctx['regime'] : 'tight';
        $table = [
            'tls_timeout' => [
                'title' => 'مسیر بسته است',
                'body'  => 'اتصال TCP برقرار شد ولی دست‌دادن TLS تمام نشد؛ این علامت فیلتر روی پورت است. تکه‌تکه‌سازی TLS را روشن کنید یا گره دیگری انتخاب کنید.',
                'action' => 'fragment_on',
            ],
            'tls_reset' => [
                'title' => 'ریست روی TLS',
                'body'  => 'میانه‌ی راه‌به‌راه handshake را قطع می‌کند. MTU را روی ۱۲۸۰ بگذارید و دوباره وصل شوید؛ اگر نشد، گره را عوض کنید.',
                'action' => 'switch_node',
            ],
            'timeout' => [
                'title' => 'پاسخی نیامد',
                'body'  => 'یا پورت بسته شده یا آن گره دیگر زنده نیست. چند ثانیه صبر کنید و گره بعدی را امتحان کنید.',
                'action' => 'switch_node',
            ],
            'dns' => [
                'title' => 'DNS مشکل‌ساز شد',
                'body'  => 'نام میزبان resolve نشد. «DNS امن» را روشن کنید؛ اگر تازه روشن است، یک‌بار قطع و وصل شوید.',
                'action' => 'retry',
            ],
            'auth' => [
                'title' => 'کلید پذیرفته نشد',
                'body'  => 'این گره احتمالاً منقضی شده است. فهرست را تازه‌سازی کنید؛ اگر بازم خورد، به پشتیبانی پیام دهید.',
                'action' => 'contact',
            ],
            'udp' => [
                'title' => 'UDP خفه می‌شود',
                'body'  => 'روی این اپراتور UDP محدود است. حالت سانسور را «سنگین» کنید تا از TCP استفاده کنیم.',
                'action' => 'change_regime',
            ],
        ];
        if (isset($table[$err])) {
            $r = $table[$err];
            $r['canned'] = true;
            return $r;
        }
        return [
            'title' => 'اتصال برقرار نشد',
            'body'  => $regime === 'blackout'
                ? 'در ساعت‌های قطعی، اولین گزینه‌ها ممکن است کار نکنند؛ چند گره را پشت سر هم امتحان کنید و حالت را روی «قطعی» بگذارید.'
                : 'یک گره‌ی دیگر را انتخاب کنید؛ اگر باز هم نشد، حالت سانسور را روی «سنگین» بگذارید و دوباره وصل شوید.',
            'action' => 'switch_node',
            'canned' => true,
        ];
    }
}
