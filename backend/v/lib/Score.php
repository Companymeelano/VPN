<?php
/**
 * Trust ledger: per-node history that survives across builds, so a proxy that fails three
 * times in a row is cooled off for 6h instead of coming back every hour and being picked again.
 *
 * Two measurement sources are blended:
 *   server probes  (from the hosting box, cheap, but geographically wrong)
 *   client reports (real users inside Iran -> authoritative, weighted higher)
 */
final class Ledger
{
    /** @var array|null */
    private static $d = null;
    /** @var bool */
    private static $dirty = false;

    private static function file()
    {
        return Util::dataDir() . '/ledger.json';
    }

    public static function load()
    {
        if (self::$d !== null) {
            return self::$d;
        }
        $d = ['at' => 0, 'nodes' => []];
        $raw = Util::readText(self::file());
        if ($raw !== null) {
            $decoded = json_decode($raw, true);
            if (is_array($decoded)) {
                $d = $decoded + $d;
                if (!isset($d['nodes']) || !is_array($d['nodes'])) {
                    $d['nodes'] = [];
                }
            }
        }
        return self::$d = $d;
    }

    public static function save()
    {
        self::load();
        if (!self::$dirty) {
            return;
        }
        self::prune();
        Util::writeAtomic(self::file(), Util::jsonEncode(self::$d));
        self::$dirty = false;
    }

    private static function prune()
    {
        $maxIds = 4000;
        $maxAge = 86400 * 10;
        $nodes = self::$d['nodes'];
        foreach ($nodes as $id => $n) {
            $last = isset($n['last']) ? (int) $n['last'] : 0;
            $bannedUntil = isset($n['bannedUntil']) ? (int) $n['bannedUntil'] : 0;
            if ($last > 0 && time() - $last > $maxAge && $bannedUntil < time()) {
                unset($nodes[$id]);
            }
        }
        if (count($nodes) > $maxIds) {
            uasort($nodes, function ($a, $b) {
                return (isset($b['last']) ? (int) $b['last'] : 0) <=> (isset($a['last']) ? (int) $a['last'] : 0);
            });
            $nodes = array_slice($nodes, 0, $maxIds, true);
        }
        self::$d['nodes'] = $nodes;
        self::$d['at'] = time();
    }

    /** @return array */
    public static function view($id)
    {
        $d = self::load();
        if (!isset($d['nodes'][$id])) {
            return ['ok' => 0, 'fail' => 0, 'streak' => 0, 'bannedUntil' => 0, 'lat' => [], 'cli' => []];
        }
        return $d['nodes'][$id] + ['ok' => 0, 'fail' => 0, 'streak' => 0, 'bannedUntil' => 0, 'lat' => [], 'cli' => []];
    }

    private static function bump($id, $field, $ok, $ms, $tier = '')
    {
        self::load();
        $d = &self::$d;          // write straight into the static, not a copy of it
        if (!isset($d['nodes'][$id])) {
            $d['nodes'][$id] = ['ok' => 0, 'fail' => 0, 'streak' => 0, 'bannedUntil' => 0, 'lat' => [], 'cli' => ['ok' => 0, 'fail' => 0, 'lat' => []]];
        }
        $n = &$d['nodes'][$id];
        if ($field === 'server') {
            $ok ? $n['ok'] = (int) $n['ok'] + 1 : $n['fail'] = (int) $n['fail'] + 1;
            $n['streak'] = $ok ? 0 : (int) $n['streak'] + 1;
            if (!$ok && (int) $n['streak'] >= (int) Util::cfg('free.bans.failLimit', 3)) {
                $n['bannedUntil'] = time() + (int) Util::cfg('free.bans.banSeconds', 21600);
                $n['streak'] = 0;
            }
            $n['last'] = time();
            $n['lat'] = self::push($field, $n['lat'], $ok, $ms);
            if ($tier !== '') {
                $n['tier'] = $tier;
            }
        } else {
            if (!isset($n['cli']) || !is_array($n['cli'])) {
                $n['cli'] = ['ok' => 0, 'fail' => 0, 'lat' => [], 'streak' => 0];
            }
            $ok ? $n['cli']['ok'] = (int) $n['cli']['ok'] + 1 : $n['cli']['fail'] = (int) $n['cli']['fail'] + 1;
            $cliStreak = isset($n['cli']['streak']) ? (int) $n['cli']['streak'] : 0;
            $n['cli']['streak'] = $ok ? 0 : $cliStreak + 1;
            $n['cli']['last'] = time();
            $n['cli']['lat'] = self::push('client', $n['cli']['lat'], $ok, $ms);
            $n['last'] = time();
            // Users' reports can cool a node off too, but at a higher bar than the server's own
            // probes: one angry (or malicious) device must not be able to empty the free pool.
            $total = (int) $n['cli']['ok'] + (int) $n['cli']['fail'];
            $need = (int) Util::cfg('free.bans.failLimit', 3) * 2;
            $floor = (int) Util::cfg('free.feedback.trustFloor', 3);
            if (!$ok && $total >= $floor && (int) $n['cli']['streak'] >= $need && (int) (isset($n['bannedUntil']) ? $n['bannedUntil'] : 0) < time()) {
                $n['bannedUntil'] = time() + (int) Util::cfg('free.bans.banSeconds', 21600);
                $n['cli']['streak'] = 0;
            }
        }
        unset($n);
        unset($d);
        self::$dirty = true;
    }

    private static function push($field, $list, $ok, $ms)
    {
        $list = is_array($list) ? $list : [];
        if ($ok && $ms > 0) {
            $list[] = (int) $ms;
            $list = array_slice($list, -8);
            sort($list);
        } elseif (!$ok) {
            $list[] = -1;
            $list = array_slice($list, -8);
        }
        return $list;
    }

    /**
     * @param string $tier 'vip' when the measured node is one of *our* servers. The tier matters
     *     downstream: the share of failed probes is read as evidence of filtering (see
     *     Builder::fleetEvidence), and a public proxy list fails ~99% of the time for reasons that have
     *     nothing to do with filtering. Without this flag the free pool alone was driving every node -
     *     including the VIP ones - into blackout tuning.
     */
    public static function recordServer($id, $ok, $ms = 0, $tier = '')
    {
        self::bump($id, 'server', $ok, $ms, $tier);
    }

    public static function recordClient($id, $ok, $ms = 0)
    {
        self::bump($id, 'client', $ok, $ms);
    }

    public static function isBanned($id)
    {
        $n = self::view($id);
        return (int) $n['bannedUntil'] > time();
    }

    /** @return array [reliability01, p50ms|null, samples] */
    public static function quality($id)
    {
        $n = self::view($id);
        $sOk = (int) $n['ok'];
        $sFail = (int) $n['fail'];
        $cOk = isset($n['cli']['ok']) ? (int) $n['cli']['ok'] : 0;
        $cFail = isset($n['cli']['fail']) ? (int) $n['cli']['fail'] : 0;
        $w = (float) Util::cfg('free.feedback.weight', 0.55);
        $floor = (int) Util::cfg('free.feedback.trustFloor', 3);

        $serverRel = ($sOk + 1) / ($sOk + $sFail + 2);
        $cliRel = ($cOk + 1) / ($cOk + $cFail + 2);
        $useClient = ($cOk + $cFail) >= $floor;
        $rel = $useClient ? ($serverRel * (1 - $w) + $cliRel * $w) : $serverRel;

        $lat = ($useClient && !empty($n['cli']['lat'])) ? array_values(array_filter($n['cli']['lat'], function ($v) { return $v > 0; })) : [];
        if (!$lat) {
            $lat = array_values(array_filter((array) $n['lat'], function ($v) { return $v > 0; }));
        }
        sort($lat);
        $p50 = $lat ? (int) $lat[(int) floor((count($lat) - 1) / 2)] : null;
        if ($p50 === null && $useClient) {
            $p50 = (int) round(1200 * max(0.2, 1 - $cliRel));  // unknown + unreliable => assume slow
        }
        return [$rel, $p50, $sOk + $sFail + $cOk + $cFail];
    }

    public static function stats()
    {
        $d = self::load();
        $banned = 0;
        foreach ($d['nodes'] as $n) {
            if ((int) (isset($n['bannedUntil']) ? $n['bannedUntil'] : 0) > time()) {
                $banned++;
            }
        }
        return ['tracked' => count($d['nodes']), 'banned' => $banned, 'updatedAt' => isset($d['at']) ? (int) $d['at'] : 0];
    }
}

final class Score
{
    /** @return string A|B|C|D */
    public static function grade($latencyMs, $reliability)
    {
        $rows = (array) Util::cfg('free.grades', []);
        $lat = $latencyMs === null ? 999999 : (int) $latencyMs;
        foreach ($rows as $row) {
            if ($lat <= (int) $row['maxLatencyMs'] && $reliability >= (float) $row['minReliability']) {
                return (string) $row['grade'];
            }
        }
        return 'D';
    }

    /**
     * Annotate nodes with probe + ledger results and sort them best-first.
     *
     * @param array $nodes
     * @param array $tcp    id => result
     * @param array $gate   id => result
     * @param bool  $dropFailed  free=true, vip=false (never drop a paid node on one bad probe)
     */
    public static function annotate(array $nodes, array $tcp, array $gate, $dropFailed)
    {
        $requireOf = max(1, (int) Util::cfg('free.gate.requireOf', 2));
        $deepOn = (bool) Util::cfg('free.probe.deepGate', false);
        $out = [];
        foreach ($nodes as $n) {
            $id = $n['id'];
            $t = isset($tcp[$id]) ? $tcp[$id] : null;
            $alive = $t === null ? null : (bool) $t['ok'];
            $tcpMs = ($t !== null && $alive) ? (int) $t['ms'] : null;
            $upstream = isset($n['upstreamLatencyMs']) && $n['upstreamLatencyMs'] > 0 ? (int) $n['upstreamLatencyMs'] : null;

            $gateOk = null;
            if (isset($gate[$id])) {
                $tested = (array) $gate[$id];
                $good = 0;
                foreach ($tested as $g) {
                    if (!empty($g['ok'])) {
                        $good++;
                    }
                }
                // never demand more endpoints than we actually measured
                $need = min($requireOf, max(1, count($tested)));
                $gateOk = $good >= $need;
            }

            list($rel, $ledgerLat, $samples) = Ledger::quality($id);
            $lat = $tcpMs !== null ? $tcpMs : ($ledgerLat !== null ? $ledgerLat : $upstream);

            $usable = $alive === null ? true : (bool) $alive;
            if ($dropFailed) {
                if (!$usable) {
                    continue;
                }
                if ($gateOk === false && !$deepOn) {
                    continue;   // blocked by Google/Cloudflare or only port-80: not tunnel-usable
                }
            }
            $n['alive'] = $usable;
            $n['tcpMs'] = $tcpMs;
            $n['gateOk'] = $gateOk;
            $n['reliability'] = round($rel, 3);
            $n['samples'] = $samples;
            $n['latencyMs'] = $lat;
            $n['grade'] = self::grade($lat, $rel);
            $n['banned'] = Ledger::isBanned($id);
            if ($dropFailed && $n['banned']) {
                continue;
            }
            $out[] = $n;
        }

        $rank = ['A' => 0, 'B' => 1, 'C' => 2, 'D' => 3];
        usort($out, function ($a, $b) use ($rank) {
            $ra = isset($rank[$a['grade']]) ? $rank[$a['grade']] : 9;
            $rb = isset($rank[$b['grade']]) ? $rank[$b['grade']] : 9;
            if ($ra !== $rb) {
                return $ra <=> $rb;
            }
            $la = $a['latencyMs'] === null ? PHP_INT_MAX : (int) $a['latencyMs'];
            $lb = $b['latencyMs'] === null ? PHP_INT_MAX : (int) $b['latencyMs'];
            if ($la !== $lb) {
                return $la <=> $lb;
            }
            return $b['reliability'] <=> $a['reliability'];
        });
        return $out;
    }
}
