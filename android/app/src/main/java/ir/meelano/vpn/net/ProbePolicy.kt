package ir.meelano.vpn.net

/**
 * How long a probe is allowed to wait before it may call a node "unreachable from here".
 *
 * The old constants (TCP 1200 ms, TLS 2000 ms) were calibrated on a good Wi-Fi. On Iranian
 * mobile carriers - Irancell above all - a healthy SYN/ACK round trip under load routinely
 * takes 400-900 ms and a single dropped SYN is retransmitted by the kernel only after ~1 s,
 * so a 1200 ms budget systematically mislabelled live nodes as dead. The visible symptom was
 * exactly the reported one: "it connects only sometimes" - whether the user got a working
 * node depended on probe luck, not on the node.
 *
 * The policy is deliberately a pure function of the last measured median RTT: a network that
 * answers in 80 ms keeps snappy 1.5 s probes, a network that answers in 800 ms gets the time
 * its physics needs. Floors keep a fast network fast; ceilings keep a dead network from
 * turning every refresh into a 12-node serial wait (probes run in parallel regardless).
 */
object ProbePolicy {

    /** TCP connect budget. Unknown RTT (first run today) uses the mobile-safe middle. */
    fun tcpTimeoutMs(previousMedianRttMs: Long = -1L): Int =
        if (previousMedianRttMs <= 0L) 2_200
        else (previousMedianRttMs * 4L).coerceIn(1_500L, 3_500L).toInt()

    /**
     * TLS handshake budget: at minimum two extra round trips on top of the TCP connect
     * (ClientHello -> ServerHello/certificate -> Finished), plus certificate bytes that on a
     * lossy link arrive in several segments. Hence the wider multiplier and the fixed slack.
     */
    fun tlsTimeoutMs(previousMedianRttMs: Long = -1L): Int =
        if (previousMedianRttMs <= 0L) 3_200
        else (previousMedianRttMs * 6L + 800L).coerceIn(2_200L, 5_000L).toInt()

    /** Coroutine-level cap around one probe: the socket timeout plus scheduling slack. */
    fun awaitCapMs(timeoutMs: Int): Long = timeoutMs.toLong() + 900L
}
