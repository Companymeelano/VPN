import Foundation

/// "From Iran, on a mobile carrier, which of these nodes should be tried first?" - one pure function,
/// Swift twin of `android/.../data/NodeRanking.kt`, shared by the feed list and the failover ladder so
/// the two can never disagree about what a good first try looks like. Transport survivability beats
/// raw speed, geography is the next key, port 443 breaks ties. A bucket is coarse on purpose: measured
/// latency and this phone's own probe ledger decide inside a bucket, and nothing here deletes a node.
enum NodeRanking {

    /// Lower is better. Unknown country sorts after every known one except a domestic exit.
    static func countryRank(_ cc: String?) -> Int {
        switch cc?.trimmingCharacters(in: .whitespaces).uppercased() {
        // The neighbourhood: one or two hops from Iranian carriers, sub-100 ms RTTs.
        case "TR", "AE", "AM", "AZ", "GE", "IQ", "QA", "KW", "OM", "BH", "CY": return 0
        // Near abroad / eastern Europe: still short paths, little congestion.
        case "KZ", "RU", "UA", "RO", "BG", "MD", "RS", "HU", "AT", "CZ", "PL", "SK", "GR": return 1
        // The usual high-quality exits: DE/NL/FI first among equals in practice.
        case "DE", "NL", "FI", "SE", "NO", "DK", "CH", "FR", "BE", "IT", "ES", "PT",
             "GB", "IE", "EE", "LV", "LT", "IS", "LU", "SI": return 2
        // Asia-Pacific: reachable and often unfiltered, but the RTT tax is real.
        case "IN", "SG", "MY", "TH", "ID", "VN", "HK", "TW", "JP", "KR", "CN": return 3
        // The far side of the planet: works, pays 180-300 ms before anything else.
        case "US", "CA", "AU", "NZ", "BR", "AR", "MX", "ZA", "EG", "IL", "SA": return 4
        // A domestic exit cannot cross the filter it sits behind; dialable, never auto-preferred.
        case "IR": return 6
        default: return 5
        }
    }

    /// 443 first (the port of the web itself), then the CDN-style TLS alternates, then 80.
    static func portRank(_ port: Int) -> Int {
        switch port {
        case 443: return 0
        case 8443, 2053, 2083, 2087, 2096: return 1
        case 80, 8080: return 2
        default: return 3
        }
    }

    /// Position of this node's transport on the regime's survivability ladder.
    static func transportIndex(_ node: FeedNode, regime: Regime = .tight) -> Int {
        let order = Ladder.order(regime)
        let proto = node.proto.trimmingCharacters(in: .whitespaces).lowercased() == "shadowsocks"
            ? "ss" : node.proto.trimmingCharacters(in: .whitespaces).lowercased()
        let key = Ladder.transportOf(
            proto: proto,
            tls: node.tls,
            network: normalizedNetwork(node.network)
        )
        guard let i = order.firstIndex(of: key) else { return order.count }
        return i
    }

    /// One comparable number: transport dominates (x1000), geography next (x100), port last.
    static func bucket(_ node: FeedNode, regime: Regime = .tight) -> Int {
        transportIndex(node, regime: regime) * 1000
            + countryRank(node.cc) * 100
            + portRank(node.port) * 10
    }

    /// Feed spellings the ladder does not know, folded onto the names it does.
    private static func normalizedNetwork(_ network: String?) -> String? {
        switch network?.trimmingCharacters(in: .whitespaces).lowercased() {
        case "websocket": return "ws"
        case "splithttp": return "xhttp"
        case "raw": return "tcp"
        default: return network?.trimmingCharacters(in: .whitespaces).lowercased()
        }
    }
}
