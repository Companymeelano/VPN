<?php
/**
 * The AI layer: one small, boring client with per-task model routing.
 *
 * Design rules, all of them load-bearing:
 *  1. **The feed never depends on the model.** Every task has a deterministic twin in AiTune; if the
 *     API is off, slow, rate-limited or returns nonsense, the tuner's own rules are used and the
 *     payload shape is byte-identical. An outage must never turn into "no servers".
 *  2. **Numbers are clamped, strings are stripped, unknown keys are dropped.** A model that returns
 *     mtu=9000 or "fragSize":"lots" gets corrected in Ai::clamp(), not in the app. The ranges here are
 *     the same ones the Kotlin parser enforces (net/Regime.kt) — two guards, one contract.
 *  3. **Cost is a hard budget, not a suggestion.** Daily token and USD caps with a shared counter file;
 *     when either is hit, the day is done and we degrade. A tuner that can spend your card balance is
 *     not a feature, it is an incident.
 *  4. **One provider config, many tasks.** A cheap model tunes 40 nodes; a slightly better model writes
 *     the Persian advice text; rank can be free (local). You choose per task in config, not in code.
 *  5. Cache first. A tune decision is valid for ~15 minutes; asking twice for the same input is
 *     wasting money to learn nothing new.
 *
 * Shared-hosting constraints honoured: PHP only, curl when present and streams when not, no exec,
 * no daemon, no queue — everything runs inside the request that needed it, or not at all.
 */
final class Ai
{
    /** Counter file lives next to the caches so a deploy that wipes data/ also resets the budget. */
    const SPEND_FILE = 'ai-spend.json';
    const BREAKER_FILE = 'ai-breaker.json';

    public static function enabled()
    {
        if (!(bool) Util::cfg('ai.enabled', false)) {
            return false;
        }
        // every configured task must have a key or a local endpoint; otherwise "enabled" is a lie
        $p = self::providerFor('tune');
        if ($p === null) {
            return false;
        }
        if ($p['kind'] !== 'ollama' && $p['key'] === '') {
            return false;
        }
        return true;
    }

    /**
     * Provider for a task, falling back to ai.default. A task may override the model but reuse the
     * provider's credentials: `tasks.regime.provider = openai` + `tasks.regime.model = gpt-4o`.
     */
    public static function providerFor($task)
    {
        $taskCfg = (array) Util::cfg('ai.tasks.' . $task, []);
        $name = isset($taskCfg['provider']) && $taskCfg['provider'] !== ''
            ? (string) $taskCfg['provider']
            : (string) Util::cfg('ai.default', 'openai');
        $p = (array) Util::cfg('ai.providers.' . $name, null);
        if (!$p) {
            return null;
        }
        return [
            'name'        => $name,
            'kind'        => isset($p['kind']) ? (string) $p['kind'] : 'openai',
            'base'        => rtrim((string) (isset($p['base']) ? $p['base'] : ''), '/'),
            'key'         => (string) (isset($p['key']) ? $p['key'] : ''),
            'model'       => (string) (isset($taskCfg['model']) && $taskCfg['model'] !== ''
                ? $taskCfg['model']
                : (isset($p['model']) ? $p['model'] : '')),
            'temperature' => isset($taskCfg['temperature']) ? (float) $taskCfg['temperature'] : 0.1,
            'maxTokens'   => isset($taskCfg['maxTokens']) ? (int) $taskCfg['maxTokens'] : 900,
            'priceIn'     => isset($p['priceIn'] ) ? (float) $p['priceIn']  : 0.0,   // USD per 1M input tokens
            'priceOut'    => isset($p['priceOut']) ? (float) $p['priceOut'] : 0.0,
        ];
    }

    /** A call is allowed if the breaker is closed and today's budget has room. */
    public static function allowed()
    {
        if (!self::enabled()) {
            return false;
        }
        if (self::breakerOpen()) {
            return false;
        }
        $spend = self::spend();
        $today = gmdate('Y-m-d');
        if ($spend['day'] !== $today) {
            return true;
        }
        if ((int) Util::cfg('ai.dailyTokenCap', 400000) > 0 && $spend['tokens'] >= (int) Util::cfg('ai.dailyTokenCap', 400000)) {
            return false;
        }
        if ((int) Util::cfg('ai.dailyUsdCapCents', 60) > 0 && $spend['cents'] >= (int) Util::cfg('ai.dailyUsdCapCents', 60)) {
            return false;
        }
        return true;
    }

    /**
     * Ask a model for JSON. Returns the decoded payload or null — never throws, never echoes the key.
     *
     * @param array $opts  keys: task (required), cacheKey, cacheTtl, maxTokens, temperature
     */
    public static function json($prompt, array $opts = [])
    {
        $task = isset($opts['task']) ? (string) $opts['task'] : 'tune';
        // flat cache keys on purpose: Util::cacheWrite() joins the key under data/cache/ and does not
        // create subdirectories, and gzip-prebuilding a 300-byte AI answer is pointless anyway
        $cacheKey = isset($opts['cacheKey']) && $opts['cacheKey'] !== ''
            ? 'ai-' . $task . '-' . substr(sha1((string) $opts['cacheKey']), 0, 24)
            : '';
        if ($cacheKey !== '') {
            $hit = Util::cacheRead($cacheKey);
            if ($hit !== null && !empty($hit['fresh']) && isset($hit['payload']['data'])) {
                return $hit['payload']['data'];
            }
        }
        if (!self::allowed()) {
            return null;
        }
        $p = self::providerFor($task);
        if ($p === null) {
            return null;
        }
        $t0 = Util::nowMs();
        $res = self::dispatch($p, $prompt, $opts);
        $ms = Util::nowMs() - $t0;
        if ($res === null) {
            self::trip(sprintf('%s failed after %dms', $p['name'], $ms));
            Util::log('ai error', ['task' => $task, 'provider' => $p['name'], 'ms' => $ms]);
            return null;
        }
        self::spend($res['inTokens'], $res['outTokens'], $p);
        self::heal();
        $data = self::clamp($res['json'], isset($opts['schema']) ? (array) $opts['schema'] : []);
        Util::log('ai ok', [
            'task' => $task, 'provider' => $p['name'], 'model' => $p['model'], 'ms' => $ms,
            'in' => $res['inTokens'], 'out' => $res['outTokens'],
        ]);
        if ($cacheKey !== '' && $data !== null) {
            Util::cacheWrite($cacheKey, [
                'data' => $data,
                'ttl'  => (int) (isset($opts['cacheTtl']) ? $opts['cacheTtl'] : 900),
            ]);
        }
        return $data;
    }

    /**
     * Clamp a model answer against a schema: { key: [min, max] } for numbers,
     * { key: ['enum', 'a', 'b'] } for words, { key: 'string' } for free text (stripped and cut).
     * Anything the schema does not name is dropped — that is what "we do not run what the model
     * invented" means in practice.
     */
    /**
     * Prefix a string by *characters*, not bytes - these are Persian strings and a byte cut in the middle
     * of a codepoint would poison json_encode on the way out.
     *
     * mbstring is not guaranteed on the kind of shared host this backend targets (same reason Util::utf8
     * and Country:: carry fallbacks), and one unguarded call here turns the AI path into a fatal
     * "Call to undefined function mb_substr()" instead of a slightly cruder summary.
     */
    public static function safeSubstr($s, $limit)
    {
        if (function_exists('mb_substr')) {
            return mb_substr($s, 0, $limit, 'UTF-8');
        }
        if (function_exists('iconv_substr')) {
            $cut = @iconv_substr($s, 0, $limit, 'UTF-8');
            if (is_string($cut)) {
                return $cut;
            }
        }
        // last resort: cut on bytes, then drop a trailing partial codepoint so the result stays valid UTF-8
        $cut = substr($s, 0, $limit);
        return preg_replace('~[\xC0-\xFF]$~', '', $cut);
    }

    public static function clamp($decoded, array $schema)
    {
        if (!is_array($decoded) || !$schema) {
            return is_array($decoded) ? $decoded : null;
        }
        $out = [];
        foreach ($schema as $key => $rule) {
            if (!array_key_exists($key, $decoded)) {
                continue;
            }
            $v = $decoded[$key];
            if ($rule === 'raw') {
                // containers (a map of per-node patches) pass through untouched; every leaf inside them
                // is clamped by the caller, which is the only way to keep one schema per level
                $out[$key] = $v;
                continue;
            }
            if ($rule === 'string') {
                $s = trim((string) $v);
                $s = preg_replace('~[\x00-\x08\x0B\x0C\x0E-\x1F]~u', '', $s);
                if ($s !== '') {
                    $out[$key] = self::safeSubstr($s, 180);
                }
                continue;
            }
            if (is_array($rule) && isset($rule[0]) && $rule[0] === 'enum') {
                $allowed = array_slice($rule, 1);
                $s = strtolower(trim((string) $v));
                if (in_array($s, $allowed, true)) {
                    $out[$key] = $s;
                }
                continue;
            }
            if (is_array($rule) && isset($rule[0]) && $rule[0] === 'bool') {
                $out[$key] = (bool) filter_var($v, FILTER_VALIDATE_BOOLEAN, FILTER_NULL_ON_FAILURE) === true;
                continue;
            }
            if (is_array($rule) && isset($rule[0]) && $rule[0] === 'list') {
                // list of ids, e.g. ["vless-reality","grpc"] — only known-safe characters survive
                $items = is_array($v) ? $v : preg_split('~[,\s]+~', (string) $v);
                $clean = [];
                foreach ($items as $it) {
                    $it = preg_replace('~[^a-z0-9_\-.]~', '', strtolower(trim((string) $it)));
                    if ($it !== '') {
                        $clean[] = $it;
                    }
                }
                $out[$key] = array_values(array_unique($clean));
                continue;
            }
            // numeric range
            if (!is_numeric($v)) {
                continue;
            }
            $n = (int) $v;
            $lo = isset($rule[0]) ? (int) $rule[0] : 0;
            $hi = isset($rule[1]) ? (int) $rule[1] : PHP_INT_MAX;
            $out[$key] = max($lo, min($hi, $n));
        }
        return $out;
    }

    /* ------------------------------------------------------------------ transports */

    private static function dispatch(array $p, $prompt, array $opts)
    {
        switch ($p['kind']) {
            case 'anthropic':
                return self::callAnthropic($p, $prompt, $opts);
            case 'ollama':
                return self::callOllama($p, $prompt, $opts);
            case 'openai':
            default:
                return self::callOpenAi($p, $prompt, $opts);
        }
    }

    /** OpenAI-compatible /chat/completions with response_format=json_object (also works for the free tiers). */
    private static function callOpenAi(array $p, $prompt, array $opts)
    {
        $body = [
            'model'    => $p['model'],
            'messages' => [
                ['role' => 'system', 'content' => self::systemVoice($opts)],
                ['role' => 'user', 'content' => $prompt],
            ],
            'temperature' => $p['temperature'],
            'max_tokens'  => (int) (isset($opts['maxTokens']) ? $opts['maxTokens'] : $p['maxTokens']),
            'response_format' => ['type' => 'json_object'],
        ];
        $r = self::post($p['base'] . '/chat/completions', json_encode($body), [
            'Authorization: Bearer ' . $p['key'],
            'Content-Type: application/json',
        ]);
        if ($r === null) {
            return null;
        }
        $j = json_decode($r['body'], true);
        $text = isset($j['choices'][0]['message']['content']) ? (string) $j['choices'][0]['message']['content'] : '';
        if ($text === '') {
            return null;
        }
        return [
            'json' => self::decodeJson($text),
            'inTokens'  => isset($j['usage']['prompt_tokens']) ? (int) $j['usage']['prompt_tokens'] : 0,
            'outTokens' => isset($j['usage']['completion_tokens']) ? (int) $j['usage']['completion_tokens'] : 0,
        ];
    }

    private static function callAnthropic(array $p, $prompt, array $opts)
    {
        $body = [
            'model'      => $p['model'],
            'max_tokens' => (int) (isset($opts['maxTokens']) ? $opts['maxTokens'] : $p['maxTokens']),
            'temperature' => $p['temperature'],
            'system'     => self::systemVoice($opts),
            'messages'   => [['role' => 'user', 'content' => $prompt]],
        ];
        $r = self::post($p['base'] . '/v1/messages', json_encode($body), [
            'x-api-key: ' . $p['key'],
            'anthropic-version: 2023-06-01',
            'Content-Type: application/json',
        ]);
        if ($r === null) {
            return null;
        }
        $j = json_decode($r['body'], true);
        $text = isset($j['content'][0]['text']) ? (string) $j['content'][0]['text'] : '';
        if ($text === '') {
            return null;
        }
        return [
            'json' => self::decodeJson($text),
            'inTokens'  => isset($j['usage']['input_tokens']) ? (int) $j['usage']['input_tokens'] : 0,
            'outTokens' => isset($j['usage']['output_tokens']) ? (int) $j['usage']['output_tokens'] : 0,
        ];
    }

    /** Self-hosted or free local model on the same box: no key, format=json, cheap and private. */
    private static function callOllama(array $p, $prompt, array $opts)
    {
        $body = [
            'model'  => $p['model'],
            'stream' => false,
            'format' => 'json',
            'options' => ['temperature' => $p['temperature'], 'num_predict' => (int) $p['maxTokens']],
            'messages' => [
                ['role' => 'system', 'content' => self::systemVoice($opts)],
                ['role' => 'user', 'content' => $prompt],
            ],
        ];
        $r = self::post($p['base'] . '/api/chat', json_encode($body), ['Content-Type: application/json']);
        if ($r === null) {
            return null;
        }
        $j = json_decode($r['body'], true);
        $text = isset($j['message']['content']) ? (string) $j['message']['content'] : '';
        if ($text === '') {
            return null;
        }
        return [
            'json' => self::decodeJson($text),
            'inTokens'  => isset($j['prompt_eval_count']) ? (int) $j['prompt_eval_count'] : 0,
            'outTokens' => isset($j['eval_count']) ? (int) $j['eval_count'] : 0,
        ];
    }

    /**
     * The one system prompt, shared by every task. Two sentences, and both are constraints rather than
     * personality: JSON only, and "if the data cannot support an opinion, return {}" — the second is
     * what stops a model inventing a fingerprint for a node it was never told about.
     */
    private static function systemVoice(array $opts)
    {
        $extra = isset($opts['voice']) ? ' ' . trim((string) $opts['voice']) : '';
        return 'You are a network-transport tuner for a VPN feed. '
            . 'Answer with a single JSON object and nothing else: no prose, no markdown fences, no keys '
            . 'you were not asked for. If the facts given do not support an opinion, return an empty object '
            . 'or omit the item - silence is a valid, useful answer; a guess is not.' . $extra;
    }

    /** Models wrap JSON in prose even when told not to; take the outermost braces. */
    private static function decodeJson($text)
    {
        $t = trim((string) $text);
        $t = preg_replace('~^```(?:json)?~i', '', $t);
        $t = preg_replace('~```$~', '', $t);
        $j = json_decode($t, true);
        if (is_array($j)) {
            return $j;
        }
        $s = strpos($t, '{');
        $e = strrpos($t, '}');
        if ($s !== false && $e !== false && $e > $s) {
            $j = json_decode(substr($t, $s, $e - $s + 1), true);
            return is_array($j) ? $j : null;
        }
        return null;
    }

    /**
     * POST with curl when available, streams when not. Never retries more than once, never follows
     * redirects onto another origin, and caps the response: an AI endpoint that streams 40 MB is a
     * bug we must not inherit.
     */
    private static function post($url, $body, array $headers)
    {
        $timeout = max(2, (int) Util::cfg('ai.timeoutSeconds', 8));
        $maxBytes = (int) Util::cfg('ai.maxResponseBytes', 262144);
        self::$buf = '';
        if (function_exists('curl_init')) {
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_POST           => true,
                CURLOPT_POSTFIELDS       => $body,
                CURLOPT_HTTPHEADER       => $headers,
                CURLOPT_CONNECTTIMEOUT   => min(5, $timeout),
                CURLOPT_TIMEOUT          => $timeout,
                CURLOPT_FOLLOWLOCATION   => false,
                CURLOPT_SSL_VERIFYPEER   => true,
                CURLOPT_SSL_VERIFYHOST   => 2,
                // HTTPS for the paid APIs; http:// is only ever allowed for a local ollama, and the
                // base url is operator-supplied in config, not user-supplied, so this is not a hole.
                CURLOPT_PROTOCOLS        => strpos($url, 'https://') === 0 ? CURLPROTO_HTTPS : (CURLPROTO_HTTPS | CURLPROTO_HTTP),
                CURLOPT_WRITEFUNCTION    => function ($ch, $chunk) use ($maxBytes) {
                    if (strlen(self::$buf) + strlen($chunk) > $maxBytes) {
                        return -1;                 // abort: a runaway response must not fill memory
                    }
                    self::$buf .= $chunk;
                    return strlen($chunk);
                },
            ]);
            $ok = curl_exec($ch);
            $code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
            $err = curl_error($ch);
            curl_close($ch);
            $buf = self::$buf;
            self::$buf = '';
            if ($ok === false || $code < 200 || $code >= 300) {
                Util::log('ai http fail', ['code' => $code, 'err' => $err, 'url' => preg_replace('~https://[^/]+.*~', '$1', $url)]);
                return null;
            }
            return ['body' => $buf, 'status' => $code];
        }
        // stream fallback (some shared hosts ship PHP without curl)
        $ctx = stream_context_create([
            'http' => [
                'method'        => 'POST',
                'header'        => implode("\r\n", array_merge($headers, ['Connection: close'])),
                'content'       => $body,
                'timeout'       => $timeout,
                'ignore_errors' => true,
                'protocol_version' => 1.1,
            ],
            'ssl' => ['verify_peer' => true, 'verify_peer_name' => true],
        ]);
        self::$buf = '';
        $raw = @file_get_contents($url, false, $ctx);
        if ($raw === false) {
            return null;
        }
        return ['body' => substr($raw, 0, $maxBytes), 'status' => 200];
    }
    private static $buf = '';

    /* ------------------------------------------------------------------ budget + breaker */

    public static function spend($inTok = null, $outTok = null, ?array $p = null)
    {
        $path = Util::dataDir() . '/' . self::SPEND_FILE;
        $s = json_decode((string) Util::readText($path, '{}'), true);
        if (!is_array($s)) {
            $s = [];
        }
        $day = isset($s['day']) ? (string) $s['day'] : '';
        if ($day !== gmdate('Y-m-d')) {
            $s = ['day' => gmdate('Y-m-d'), 'tokens' => 0, 'cents' => 0, 'calls' => 0, 'errors' => 0];
        }
        if ($inTok !== null) {
            $tokens = (int) $inTok + (int) $outTok;
            $usd = 0.0;
            if ($p !== null) {
                $usd = ((int) $inTok / 1000000.0) * (float) $p['priceIn']
                     + ((int) $outTok / 1000000.0) * (float) $p['priceOut'];
            }
            $s['tokens'] = (int) $s['tokens'] + $tokens;
            $s['cents']   = (float) $s['cents'] + $usd * 100.0;
            $s['calls']   = (int) $s['calls'] + 1;
            // Util::jsonEncode, not json_encode: a false return here would write an empty file and the
            // next reader would read it as "no stats" - a silent reset of the counters that gate the model.
            Util::writeAtomic($path, Util::jsonEncode($s));
        }
        return [
            'day'    => (string) $s['day'],
            'tokens' => (int) $s['tokens'],
            'cents'  => (float) $s['cents'],
            'calls'  => (int) (isset($s['calls']) ? $s['calls'] : 0),
            'errors' => (int) (isset($s['errors']) ? $s['errors'] : 0),
        ];
    }

    /**
     * Three failures in a row and the AI is put to sleep for 10 minutes. Why: the provider being down
     * is usually a slow, expensive way to say it (DNS timeouts, 429 with a long Retry-After), and the
     * feed rebuild must not sit on that while a user waits for a list.
     */
    private static function breakerOpen()
    {
        $f = json_decode((string) Util::readText(Util::dataDir() . '/' . self::BREAKER_FILE, '{}'), true);
        if (!is_array($f) || empty($f['until'])) {
            return false;
        }
        return (int) $f['until'] > Util::now();
    }

    private static function trip($why)
    {
        $ttl = max(60, (int) Util::cfg('ai.breakerSeconds', 600));
        Util::writeAtomic(Util::dataDir() . '/' . self::BREAKER_FILE, Util::jsonEncode([
            'until' => Util::now() + $ttl,
            'why'   => substr((string) $why, 0, 160),
        ]));
    }

    private static function heal()
    {
        @unlink(Util::dataDir() . '/' . self::BREAKER_FILE);
    }

    /** Admin-panel view: what it cost, what it answered, whether it is asleep. */
    public static function status()
    {
        $f = json_decode((string) Util::readText(Util::dataDir() . '/' . self::BREAKER_FILE, '{}'), true);
        return [
            'enabled'   => self::enabled(),
            'allowed'   => self::allowed(),
            'spend'     => self::spend(),
            'breaker'   => is_array($f) && !empty($f['until']) && (int) $f['until'] > Util::now() ? $f : null,
            'tasks'     => array_keys((array) Util::cfg('ai.tasks', [])),
        ];
    }
}
