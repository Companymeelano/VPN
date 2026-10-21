import Foundation

/// What the network is doing to us right now, and what to do about it. Swift twin of
/// `android/.../net/Regime.kt` - keep the three levels and the ladder identical across platforms:
/// the backend's `Score::iranRank` and both clients must order the same node the same way.
enum Regime: String, Codable, CaseIterable {
    case calm, tight, blackout

    /// Unknown or blank -> `.tight`: the safe default for Iran today.
    static func of(_ id: String?) -> Regime {
        switch id?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "calm": return .calm
        case "blackout": return .blackout
        default: return .tight
        }
    }
}

/// Per-node transport parameters. Every field has a working default; 0 / "" means "not specified".
struct Tune: Codable, Equatable {
    /// TLS ClientHello record splitting: split the handshake into ~`fragSize`-byte writes.
    var fragSize: Int
    var fragCount: Int
    /// "fixed" | "random" | "variable" - how the first packet's size is chosen.
    var fragStrategy: String
    /// Inter-fragment pause in ms; 0 = send back-to-back (only matters when fragSize > 0).
    var fragDelayMs: Int
    /// Generic TLS ALPN; the profile builder narrows WS/HTTPUpgrade to http/1.1 and gRPC to h2.
    var alpn: String
    /// uTLS fingerprint ("chrome", "firefox", "safari", "ios", "android", ...).
    var fingerprint: String
    /// SNI override; "" = the node's host.
    var sni: String
    var ech: Bool
    /// Application-level keepalive in seconds. TIGHT wants it under the ~60s carrier flow timeout.
    var keepAliveSec: Int
    var mux: Bool
    var muxConcurrency: Int
    var allowLan: Bool
    var mtu: Int
    var mss: Int
    /// grpc: "multi" (one long stream) | "one" | "normal".
    var grpcMode: String
    var connectionReuse: Bool

    static let `default` = Tune(
        fragSize: 0, fragCount: 1, fragStrategy: "", fragDelayMs: 0,
        alpn: "h2,http/1.1", fingerprint: "chrome", sni: "", ech: false,
        keepAliveSec: 15, mux: true, muxConcurrency: 8, allowLan: false,
        mtu: 1280, mss: 0, grpcMode: "multi", connectionReuse: true
    )

    static func forRegime(_ r: Regime) -> Tune {
        var t = Tune.default
        switch r {
        case .calm:
            t.mux = true
            t.keepAliveSec = 30
            t.fragSize = 0
        case .tight:
            t.fragSize = 200
            t.fragCount = 2
            t.fragStrategy = "variable"
            t.fragDelayMs = 20
            t.mux = true
            t.keepAliveSec = 15
            t.mtu = 1280
            t.mss = 1300
        case .blackout:
            t.fragSize = 120
            t.fragCount = 3
            t.fragStrategy = "random"
            t.fragDelayMs = 40
            t.mux = true
            t.muxConcurrency = 16
            t.keepAliveSec = 10
            t.mtu = 1280
            t.mss = 1240
            t.grpcMode = "multi"
            t.connectionReuse = true
        }
        return t
    }

    var fragmented: Bool { fragSize > 0 }
}

/// What the feed sends per node: a *patch*, never a full object. The wire shape is either a sparse
/// object or a preset name ("calm" | "tight" | "blackout").
struct TunePatch: Codable, Equatable {
    var fragSize: Int?
    var fragCount: Int?
    var fragStrategy: String?
    var fragDelayMs: Int?
    var alpn: String?
    var fingerprint: String?
    var sni: String?
    var ech: Bool?
    var keepAliveSec: Int?
    var mux: Bool?
    var muxConcurrency: Int?
    var allowLan: Bool?
    var mtu: Int?
    var mss: Int?
    var grpcMode: String?
    var connectionReuse: Bool?

    static let empty = TunePatch()

    func apply(to base: Tune) -> Tune {
        var t = base
        if let v = fragSize { t.fragSize = clamp(v, 0, 16384) }
        if let v = fragCount { t.fragCount = clamp(v, 1, 64) }
        if let v = fragStrategy { t.fragStrategy = v }
        if let v = fragDelayMs { t.fragDelayMs = clamp(v, 0, 600) }
        if let v = alpn { t.alpn = v }
        if let v = fingerprint { t.fingerprint = v }
        if let v = sni { t.sni = v }
        if let v = ech { t.ech = v }
        if let v = keepAliveSec { t.keepAliveSec = clamp(v, 0, 300) }
        if let v = mux { t.mux = v }
        if let v = muxConcurrency { t.muxConcurrency = clamp(v, 1, 64) }
        if let v = allowLan { t.allowLan = v }
        if let v = mtu { t.mtu = clamp(v, 576, 9000) }
        if let v = mss { t.mss = v == 0 ? 0 : clamp(v, 300, 1460) }
        if let v = grpcMode { t.grpcMode = v }
        if let v = connectionReuse { t.connectionReuse = v }
        return t
    }

    /// Lenient + clamped: a hostile or malformed feed must never produce a hostile profile.
    static func fromAny(_ v: Any?) -> TunePatch {
        if let s = v as? String {
            if s.trimmingCharacters(in: .whitespaces).isEmpty || s == "auto" { return .empty }
            return fromObject(["preset": s])
        }
        guard let o = v as? [String: Any] else { return .empty }
        return fromObject(o)
    }

    private static func fromObject(_ o: [String: Any]) -> TunePatch {
        let preset = Regime.of((o["preset"] as? String).flatMap { $0.nilIfBlank })
        let p = Tune.forRegime(preset)
        func i(_ k: String, _ d: Int, _ lo: Int, _ hi: Int) -> Int? {
            guard let raw = o[k], !(raw is NSNull) else { return nil }
            return min(max(FeedNode.int(raw) ?? d, lo), hi)
        }
        func b(_ k: String, _ d: Bool) -> Bool? {
            guard let raw = o[k], !(raw is NSNull) else { return nil }
            return (raw as? Bool) ?? d
        }
        func s(_ k: String, _ d: String) -> String? {
            guard let raw = o[k], !(raw is NSNull) else { return nil }
            return ((raw as? String) ?? d).nilIfBlank
        }
        return TunePatch(
            fragSize: i("fragSize", p.fragSize, 0, 16384),
            fragCount: i("fragCount", p.fragCount, 1, 64),
            fragStrategy: s("fragStrategy", p.fragStrategy),
            fragDelayMs: i("fragDelayMs", p.fragDelayMs, 0, 600),
            alpn: s("alpn", p.alpn),
            fingerprint: s("fingerprint", p.fingerprint),
            sni: s("sni", p.sni),
            ech: b("ech", p.ech),
            keepAliveSec: i("keepAliveSec", p.keepAliveSec, 0, 300),
            mux: b("mux", p.mux),
            muxConcurrency: i("muxConcurrency", p.muxConcurrency, 1, 64),
            allowLan: b("allowLan", p.allowLan),
            mtu: i("mtu", p.mtu, 576, 9000),
            mss: i("mss", p.mss, 300, 1460),
            grpcMode: s("grpcMode", p.grpcMode),
            connectionReuse: b("connectionReuse", p.connectionReuse)
        )
    }

    private static func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { min(max(v, lo), hi) }
}

/// The user's own switches. Anything left nil means "the app decides".
struct Overrides: Codable, Equatable {
    var fragment: Bool?
    var mux: Bool?
    var mtu: Int?
    var keepAliveSec: Int?
    var fingerprint: String?
    var autoFailover: Bool?

    static let `default` = Overrides()
}

/// The precedence, in one place: regime default <- feed patch <- user override.
enum Tuner {
    static func resolve(regime: Regime, patch: TunePatch?, user o: Overrides) -> Tune {
        var t = Tune.forRegime(regime)
        if let p = patch { t = p.apply(to: t) }
        if let v = o.mux { t.mux = v }
        if let v = o.mtu {
            t.mtu = min(max(v, 576), 9000)
            t.mss = t.mss > 0 ? t.mtu - 60 : 0
        }
        if let v = o.keepAliveSec { t.keepAliveSec = min(max(v, 0), 300) }
        if let v = o.fingerprint { t.fingerprint = v }
        if let on = o.fragment {
            if on {
                if t.fragSize <= 0 {
                    t.fragSize = regime == .blackout ? 120 : 200
                    t.fragCount = regime == .blackout ? 3 : 2
                    if t.fragStrategy.isEmpty { t.fragStrategy = "variable" }
                    if t.fragDelayMs == 0 { t.fragDelayMs = 20 }
                }
            } else {
                t.fragSize = 0
                t.fragCount = 1
                t.fragStrategy = ""
                t.fragDelayMs = 0
            }
        }
        return t
    }
}

/// How to order candidate transports under a regime - a preference *vector*, not a filter.
enum Ladder {
    static func transportOf(proto: String, tls: String?, network: String?) -> String {
        let p = proto.trimmingCharacters(in: .whitespaces).lowercased()
        let t = tls?.trimmingCharacters(in: .whitespaces).lowercased()
        let n = network?.trimmingCharacters(in: .whitespaces).lowercased()
        if t == "reality" { return "reality" }
        if t == "tls", n == "grpc" { return "grpc-tls" }
        if t == "tls", n == "xhttp" { return "xhttp-tls" }
        if t == "tls", n == "ws" { return "ws-tls" }
        if t == "tls" { return "tcp-tls" }
        if p == "hysteria2" || p == "hy2" { return "udp-hy2" }
        if p == "tuic" { return "udp-tuic" }
        if p == "ss" || p == "shadowsocks" { return "tcp-ss" }
        return "tcp-plain"
    }

    static func order(_ r: Regime) -> [String] {
        switch r {
        case .calm:
            return ["reality", "grpc-tls", "tcp-tls", "xhttp-tls", "ws-tls", "udp-hy2", "tcp-ss", "tcp-plain"]
        case .tight:
            return ["reality", "xhttp-tls", "grpc-tls", "tcp-tls", "udp-hy2", "ws-tls", "tcp-ss", "tcp-plain"]
        case .blackout:
            return ["reality", "grpc-tls", "xhttp-tls", "udp-hy2", "tcp-tls", "ws-tls", "tcp-ss", "tcp-plain"]
        }
    }

    static func preference(_ r: Regime, transport: String) -> Int {
        let o = order(r)
        guard let i = o.firstIndex(of: transport) else { return 0 }
        return (o.count - i) * (14 / o.count + 1)
    }
}
