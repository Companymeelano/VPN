<?php
/**
 * Probing without ext-sockets, pcntl or exec: non-blocking streams + stream_select, all in
 * one process under a hard wall-clock budget, so a shared-hosting CPU quota is never blown.
 *
 * Three levels of truth (cheapest first):
 *   tcp        -> is the port open at all
 *   usable     -> does it actually proxy traffic (HTTP CONNECT / SOCKS5 handshake)
 *   cleanGate  -> can it reach Google + Cloudflare  (= "IP not blocked by Google/Cloudflare")
 *
 * On hosts where outbound TCP is firewalled, everything degrades to "not_probed" and the
 * ranking falls back to client feedback. lib/SelfTest.php tells you which mode you are in.
 */

abstract class NetTask
{
    const S_CONNECT = 1;
    const S_DATA    = 2;
    const S_DONE    = 3;

    /** @var string */
    public $id;
    /** @var string */
    public $host;
    /** @var int */
    public $port;
    /** @var int */
    public $timeoutMs;
    /** @var string resolved IPv4/IPv6 */
    public $ip = '';

    /** @var resource|null */
    protected $sock = null;
    /** @var int */
    protected $stage = self::S_CONNECT;
    /** @var string */
    protected $buf = '';
    /** @var float */
    protected $t0 = 0.0;
    /** @var bool */
    protected $finished = false;
    /** @var array */
    protected $result = ['ok' => false, 'ms' => 0, 'err' => 'init'];

    public function __construct($id, $host, $port, $timeoutMs)
    {
        $this->id = (string) $id;
        $this->host = (string) $host;
        $this->port = (int) $port;
        $this->timeoutMs = (int) $timeoutMs;
    }

    /** @return bool true when the caller must poll this task */
    public function start()
    {
        $this->t0 = microtime(true);
        $target = 'tcp://' . (strpos($this->ip, ':') !== false ? '[' . $this->ip . ']' : $this->ip) . ':' . $this->port;
        $errno = 0;
        $errstr = '';
        $this->sock = @stream_socket_client($target, $errno, $errstr, 0, STREAM_CLIENT_CONNECT | STREAM_CLIENT_ASYNC_CONNECT);
        if (!is_resource($this->sock)) {
            $this->finish(false, 'connect_failed' . ($errstr !== '' ? ':' . $errstr : ''));
            return false;
        }
        @stream_set_blocking($this->sock, false);
        return true;
    }

    /** @return resource|null */
    public function socket()
    {
        return $this->sock;
    }

    public function poll($writable, $readable)
    {
        if ($this->finished || !is_resource($this->sock)) {
            return;
        }
        if ($this->stage === self::S_CONNECT) {
            if (!$writable && !$readable) {
                return;
            }
            if (!$this->connected()) {
                $this->finish(false, 'unreachable');
                return;
            }
            $this->stage = self::S_DATA;
            $this->onConnected();
            return;
        }
        if ($this->stage === self::S_DATA) {
            if (!$readable) {
                return;
            }
            $chunk = @fread($this->sock, 8192);
            if ($chunk === false) {
                return;
            }
            $this->onData($chunk);
        }
    }

    /**
     * "Did the async connect actually succeed?"
     *   1. best: SO_ERROR (needs ext-sockets constants)
     *   2. fallback: a refused connect is readable-immediately-with-EOF, so treat that as failure.
     * Without step 2 a host that lacks SO_ERROR would mark every closed port as alive.
     */
    protected function connected()
    {
        // three separate things must line up: the constants, the function, and the socket being
        // a real stream. shared hosts and CI builds routinely have the constants but not the
        // function (it is tied to ext-sockets on some distros), so guard on all of them.
        if (function_exists('stream_socket_get_option') && defined('SOL_SOCKET') && defined('SO_ERROR')) {
            $err = null;
            if (@stream_socket_get_option($this->sock, SOL_SOCKET, SO_ERROR, $err)) {
                return (int) $err === 0;
            }
        }
        if (@stream_socket_get_name($this->sock, true) === false) {
            return false;
        }
        $r = [$this->sock];
        $w = $e = null;
        if (@stream_select($r, $w, $e, 0) > 0) {
            $peek = @fread($this->sock, 1);
            if (($peek === '' || $peek === false) && @feof($this->sock)) {
                return false;
            }
        }
        return true;
    }

    protected function onConnected()
    {
        $this->finish(true);            // TCP probe: connected == alive
    }

    protected function onData($chunk)
    {
        $this->buf .= $chunk;
        $this->finish($this->buf !== '');
    }

    protected function elapsedMs()
    {
        return (int) round((microtime(true) - $this->t0) * 1000);
    }

    protected function finish($ok, $err = null, array $extra = [])
    {
        if ($this->finished) {
            return;
        }
        $this->finished = true;
        $this->stage = self::S_DONE;
        $this->result = array_merge([
            'ok'  => (bool) $ok,
            'ms'  => $this->elapsedMs(),
            'err' => $ok ? null : (string) $err,
        ], $extra);
        $this->close();
    }

    public function overdue()
    {
        return !$this->finished && $this->t0 > 0 && (($this->elapsedMs() >= $this->timeoutMs));
    }

    public function timedOut()
    {
        $this->finish(false, 'timeout', ['ms' => $this->timeoutMs]);
    }

    public function isFinished()
    {
        return $this->finished;
    }

    public function result()
    {
        return $this->result;
    }

    public function close()
    {
        if (is_resource($this->sock)) {
            @fclose($this->sock);
        }
        $this->sock = null;
    }

    protected function write($data)
    {
        if (!is_resource($this->sock)) {
            return false;
        }
        return @fwrite($this->sock, $data) !== false;
    }

    protected static function rawIp($ip)
    {
        if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)) {
            return null;
        }
        $b = @inet_pton($ip);
        return $b === false || strlen($b) !== 4 ? null : $b;
    }
}

/** TCP only: "is anything listening on this port". */
final class TcpTask extends NetTask
{
}

/** HTTP proxy gate: CONNECT <gate>:443 and expect a 2xx. Proves it forwards TLS traffic. */
final class HttpConnectTask extends NetTask
{
    /** @var string */
    private $gateHost;
    /** @var string */
    private $user;
    /** @var string */
    private $pass;

    public function __construct($id, $host, $port, $timeoutMs, $ip, $gateHost, $user = '', $pass = '')
    {
        parent::__construct($id, $host, $port, $timeoutMs);
        $this->ip = $ip;
        $this->gateHost = $gateHost;
        $this->user = (string) $user;
        $this->pass = (string) $pass;
    }

    protected function onConnected()
    {
        $req = "CONNECT {$this->gateHost}:443 HTTP/1.1\r\nHost: {$this->gateHost}:443\r\n";
        if ($this->user !== '') {
            $req .= 'Proxy-Authorization: Basic ' . base64_encode($this->user . ':' . $this->pass) . "\r\n";
        }
        $req .= "Connection: keep-alive\r\n\r\n";
        $this->write($req);
    }

    protected function onData($chunk)
    {
        $this->buf .= $chunk;
        if (preg_match('~^HTTP/\d(?:\.\d)?[ \t]+(\d{3})~i', $this->buf, $m)) {
            $code = (int) $m[1];
            $this->finish($code >= 200 && $code < 300, 'gate_http_' . $code, ['gate' => $this->gateHost]);
            return;
        }
        if ($chunk === '' || strlen($this->buf) > 8192) {
            $this->finish(false, 'no_http_response', ['gate' => $this->gateHost]);
        }
    }
}

/** SOCKS4 / SOCKS5 handshake + CONNECT through it (IPv4 gate targets only). */
final class SocksTask extends NetTask
{
    /** @var string */
    private $gateIp;
    /** @var int */
    private $gatePort;
    /** @var bool */
    private $v4;

    public function __construct($id, $host, $port, $timeoutMs, $ip, $gateIp, $v4 = false, $gatePort = 443)
    {
        parent::__construct($id, $host, $port, $timeoutMs);
        $this->ip = $ip;
        $this->gateIp = $gateIp;
        $this->v4 = (bool) $v4;
        $this->gatePort = (int) $gatePort;
    }

    protected function onConnected()
    {
        if ($this->v4) {
            $bin = self::rawIp($this->gateIp);
            if ($bin === null) {
                $this->finish(false, 'gate_not_ipv4');
                return;
            }
            $this->write(chr(4) . chr(1) . pack('n', $this->gatePort) . $bin . "meelano\0");
            return;
        }
        $this->write(chr(5) . chr(1) . chr(0));       // SOCKS5 greeting, method: no auth
    }

    protected function onData($chunk)
    {
        if ($chunk === '' && $this->buf === '') {
            $this->finish(false, 'closed_by_proxy');
            return;
        }
        $this->buf .= $chunk;

        if ($this->v4) {
            if (strlen($this->buf) < 8) {
                return;
            }
            $cd = ord($this->buf[1]);
            $this->finish($cd === 90, 'socks4_reject_' . $cd, ['socks' => 4]);
            return;
        }
        if ($this->buf === '') {
            return;
        }
        if (strlen($this->buf) < 2) {
            return;
        }
        if ($this->gateSent === false) {
            $ver = ord($this->buf[0]);
            $sel = ord($this->buf[1]);
            if ($ver !== 5) {
                $this->finish(false, 'not_socks5');
                return;
            }
            if ($sel !== 0) {
                $this->finish(false, 'auth_required');   // proxy wants creds we were not given
                return;
            }
            $this->buf = (string) substr($this->buf, 2);
            $bin = self::rawIp($this->gateIp);
            if ($bin === null) {
                $this->finish(false, 'gate_not_ipv4');
                return;
            }
            $this->gateSent = true;
            $this->write(chr(5) . chr(1) . chr(0) . chr(1) . $bin . pack('n', $this->gatePort));
            return;
        }
        if (strlen($this->buf) < 10) {
            return;
        }
        $rep = ord($this->buf[1]);
        $this->finish($rep === 0, 'socks5_rep_' . $rep, ['socks' => 5]);
    }

    /** @var bool */
    private $gateSent = false;
}

final class Dns
{
    /** @var array<string,string|null>|null */
    private static $cache = null;
    private static $dirty = false;
    private static $budget = 0;

    private static function file()
    {
        return Util::dataDir() . '/dns.json';
    }

    private static function load()
    {
        if (self::$cache !== null) {
            return;
        }
        $cache = [];
        $raw = Util::readText(self::file());
        if ($raw !== null) {
            $d = json_decode($raw, true);
            if (is_array($d) && isset($d['at']) && time() - (int) $d['at'] < 21600) {
                foreach ((array) $d as $host => $ip) {
                    if ($host !== 'at' && is_string($ip)) {
                        $cache[$host] = $ip === '' ? null : $ip;
                    }
                }
            }
        }
        self::$cache = $cache;
    }

    public static function setBudget($n)
    {
        self::$budget = (int) $n;
    }

    public static function flush()
    {
        self::load();
        if (!self::$dirty || self::$cache === null) {
            return;
        }
        $out = ['at' => time()];
        $i = 0;
        foreach (self::$cache as $host => $ip) {
            if (++$i > 3000) {
                break;
            }
            $out[$host] = $ip === null ? '' : $ip;
        }
        Util::writeAtomic(self::file(), Util::jsonEncode($out));
        self::$dirty = false;
    }

    /** @return string|null */
    public static function lookup($host)
    {
        self::load();
        if (filter_var($host, FILTER_VALIDATE_IP)) {
            return $host;
        }
        if (array_key_exists($host, self::$cache)) {
            return self::$cache[$host];
        }
        if (self::$budget <= 0) {
            return null;
        }
        self::$budget--;
        $ip = @gethostbyname($host);
        $ok = is_string($ip) && $ip !== $host && filter_var($ip, FILTER_VALIDATE_IP);
        self::$cache[$host] = $ok ? $ip : null;
        self::$dirty = true;
        return $ok ? $ip : null;
    }
}

final class Probe
{
    /**
     * @param NetTask[] $tasks
     * @return array id => ['ok'=>bool,'ms'=>int,'err'=>string|null,...]
     */
    public static function run(array $tasks, $concurrency = 40, $budgetMs = 18000, $dnsBudget = 60)
    {
        if (!$tasks) {
            return [];
        }
        Dns::setBudget($dnsBudget);
        $pending = array_values($tasks);
        $active = [];
        $results = [];
        $deadline = microtime(true) + max(0.5, $budgetMs / 1000);

        while (($pending || $active) && microtime(true) < $deadline) {
            while ($pending && count($active) < $concurrency) {
                $t = array_shift($pending);
                if ($t->ip === '') {
                    $ip = Dns::lookup($t->host);
                    if ($ip === null) {
                        $results[$t->id] = ['ok' => false, 'ms' => 0, 'err' => 'unresolved_host'];
                        continue;
                    }
                    $t->ip = $ip;
                }
                if (!$t->start()) {
                    $results[$t->id] = $t->result();
                    continue;
                }
                $active[$t->id] = $t;
            }

            if (!$active) {
                if (!$pending) {
                    break;
                }
                continue;
            }

            $read = $write = $ex = [];
            foreach ($active as $id => $t) {
                if (!is_resource($t->socket())) {
                    $results[$id] = $t->result();
                    unset($active[$id]);
                    continue;
                }
                $read[$id] = $t->socket();
                $write[$id] = $t->socket();
                $ex[$id] = $t->socket();
            }
            if (!$read) {
                continue;
            }
            $n = @stream_select($read, $write, $ex, 0, 60000);
            $readyW = [];
            $readyR = [];
            $readyE = [];
            if ($n > 0) {
                foreach ($write as $id => $_) { $readyW[$id] = 1; }
                foreach ($read as $id => $_) { $readyR[$id] = 1; }
                foreach ($ex as $id => $_) { $readyE[$id] = 1; }
            }
            foreach ($active as $id => $t) {
                $t->poll(isset($readyW[$id]) || isset($readyE[$id]), isset($readyR[$id]) || isset($readyE[$id]));
                if ($t->isFinished()) {
                    $results[$id] = $t->result();
                    unset($active[$id]);
                } elseif ($t->overdue()) {
                    $t->timedOut();
                    $results[$id] = $t->result();
                    unset($active[$id]);
                }
            }
            if ($n === false) {
                usleep(15000);
            }
        }

        foreach ($active as $id => $t) {
            $t->timedOut();
            $results[$id] = $t->result();
        }
        foreach ($pending as $t) {
            $results[$t->id] = ['ok' => false, 'ms' => 0, 'err' => 'not_probed_budget'];
        }
        Dns::flush();
        return $results;
    }

    /** Did at least one task connect? Used to auto-disable probing on blocked hosts. */
    public static function anyAlive(array $results)
    {
        foreach ($results as $r) {
            if (!empty($r['ok'])) {
                return true;
            }
        }
        return false;
    }

    /** Can this box open outbound TCP at all? (Many Iranian shared hosts cannot.) */
    public static function egressTest()
    {
        $out = [];
        foreach ([['github.com', 443], ['1.1.1.1', 443], ['8.8.8.8', 53]] as $t) {
            $errno = 0;
            $errstr = '';
            $s = @stream_socket_client('tcp://' . $t[0] . ':' . $t[1], $errno, $errstr, 3.0);
            if (is_resource($s)) {
                $w = [$s];
                $r = $e = null;
                $ok = @stream_select($r, $w, $e, 2);
                $out[$t[0] . ':' . $t[1]] = ($ok > 0) ? 'open' : 'no_handshake';
                @fclose($s);
            } else {
                $out[$t[0] . ':' . $t[1]] = 'blocked' . ($errstr !== '' ? ':' . $errstr : '');
            }
        }
        return $out;
    }
}
