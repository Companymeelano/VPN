import Foundation

/// How long a probe may wait before it may call a node "unreachable from here". Swift twin of
/// `android/.../net/ProbePolicy.kt`: the budget is a pure function of the last measured median RTT,
/// because on Irancell a healthy SYN/ACK under load routinely takes 400-900 ms and a fixed 1200 ms
/// timeout systematically mislabels live nodes as dead.
enum ProbePolicy {

    /// TCP connect budget. Unknown RTT (first run today) uses the mobile-safe middle.
    static func tcpTimeoutMs(previousMedianRttMs: Int64 = -1) -> Int {
        if previousMedianRttMs <= 0 { return 2200 }
        return Int(min(max(previousMedianRttMs * 4, 1500), 3500))
    }

    /// TLS handshake budget: at minimum two extra round trips on top of the TCP connect.
    static func tlsTimeoutMs(previousMedianRttMs: Int64 = -1) -> Int {
        if previousMedianRttMs <= 0 { return 3200 }
        return Int(min(max(previousMedianRttMs * 6 + 800, 2200), 5000))
    }

    /// Coroutine-level cap around one probe: the socket timeout plus scheduling slack.
    static func awaitCapMs(timeoutMs: Int) -> Int64 { Int64(timeoutMs) + 900 }
}
