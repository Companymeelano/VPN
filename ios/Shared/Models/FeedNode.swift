import Foundation

/// One row of the `/v/` feed, or one node parsed out of a pasted share link.
///
/// This is the Swift twin of `android/.../data/ServerFeedRepository.kt::FeedNode` and it must stay
/// one: the iOS and Android clients rank, display and hand the *same* rows to the same engine, and a
/// field that exists on one platform only is how "works on Android, empty on iPhone" bugs start.
/// Parsing is deliberately lenient (every field has a default) - the server's `Parser.php` has already
/// validated; a phone that throws on an unknown extra field is a phone that dies on the next schema
/// bump.
struct FeedNode: Codable, Identifiable, Equatable, Hashable {
    var id: String
    var slot: Int
    var name: String
    var subtitle: String
    var cc: String?
    var ccFa: String?
    var kind: String
    var tier: String
    var proto: String
    var host: String
    var port: Int
    var tls: String?
    var network: String?
    var sni: String?
    var path: String?
    var hostHeader: String?
    var serviceName: String?
    var alpn: String?
    var flow: String?
    var pbk: String?
    var sid: String?
    var fingerprint: String?
    var userId: String?
    var alterId: Int?
    var password: String?
    var username: String?
    var method: String?
    var cipher: String?
    var insecure: Bool
    var supportsUdp: Bool
    var raw: String?
    /// Device deep-probe verdict: clean | robot_blocked | platform_blocked | dead | unknown.
    var platformStatus: String
    /// Server grade A|B|C|D and its measured latency, straight from `quality`.
    var grade: String
    var latencyMs: Int64?
    var reliability: Double
    var samples: Int
    /// Per-node transport patch (see Regime.swift). Absent keys stay the client's decision.
    var tune: [String: AnyCodable]

    init(
        id: String,
        slot: Int = 0,
        name: String = "",
        subtitle: String = "",
        cc: String? = nil,
        ccFa: String? = nil,
        kind: String = "vip",
        tier: String = "vip",
        proto: String = "",
        host: String = "",
        port: Int = 0,
        tls: String? = nil,
        network: String? = nil,
        sni: String? = nil,
        path: String? = nil,
        hostHeader: String? = nil,
        serviceName: String? = nil,
        alpn: String? = nil,
        flow: String? = nil,
        pbk: String? = nil,
        sid: String? = nil,
        fingerprint: String? = nil,
        userId: String? = nil,
        alterId: Int? = nil,
        password: String? = nil,
        username: String? = nil,
        method: String? = nil,
        cipher: String? = nil,
        insecure: Bool = false,
        supportsUdp: Bool = true,
        raw: String? = nil,
        platformStatus: String = "unknown",
        grade: String = "D",
        latencyMs: Int64? = nil,
        reliability: Double = 0,
        samples: Int = 0,
        tune: [String: AnyCodable] = [:]
    ) {
        self.id = id
        self.slot = slot
        self.name = name
        self.subtitle = subtitle
        self.cc = cc
        self.ccFa = ccFa
        self.kind = kind
        self.tier = tier
        self.proto = proto
        self.host = host
        self.port = port
        self.tls = tls
        self.network = network
        self.sni = sni
        self.path = path
        self.hostHeader = hostHeader
        self.serviceName = serviceName
        self.alpn = alpn
        self.flow = flow
        self.pbk = pbk
        self.sid = sid
        self.fingerprint = fingerprint
        self.userId = userId
        self.alterId = alterId
        self.password = password
        self.username = username
        self.method = method
        self.cipher = cipher
        self.insecure = insecure
        self.supportsUdp = supportsUdp
        self.raw = raw
        self.platformStatus = platformStatus
        self.grade = grade
        self.latencyMs = latencyMs
        self.reliability = reliability
        self.samples = samples
        self.tune = tune
    }

    var isVip: Bool { tier == "vip" }
    var isRawProxy: Bool {
        let p = proto.lowercased()
        return p == "http" || p == "socks" || p == "socks5"
    }
    /// A raw proxy is never allowed to masquerade as a tunnel row in auto-pick/failover.
    var isUsable: Bool { !isRawProxy || platformStatus == "clean" }

    var latencyLabel: String {
        guard let ms = latencyMs else { return "—" }
        return ms < 1000 ? "\(ms)" : String(format: "%.1fs", Double(ms) / 1000)
    }

    /// Feed row (`servers[]` item) -> node. Lenient by contract: unknown fields are ignored.
    init?(feedJson obj: [String: Any]) {
        guard let id = obj["id"] as? String, !id.isEmpty else { return nil }
        let quality = obj["quality"] as? [String: Any] ?? [:]
        var tuneMap: [String: AnyCodable] = [:]
        if let t = obj["tune"] as? [String: Any] {
            for (k, v) in t { tuneMap[k] = AnyCodable(v) }
        }
        self.init(
            id: id,
            slot: FeedNode.int(obj["slot"]) ?? 0,
            name: (obj["name"] as? String) ?? "",
            subtitle: (obj["subtitle"] as? String) ?? "",
            cc: (obj["cc"] as? String)?.nilIfBlank,
            ccFa: (obj["ccFa"] as? String)?.nilIfBlank,
            kind: ((obj["kind"] as? String)?.nilIfBlank) ?? "vip",
            tier: ((obj["tier"] as? String)?.nilIfBlank) ?? "vip",
            proto: ((obj["proto"] as? String) ?? "").lowercased(),
            host: (obj["host"] as? String) ?? "",
            port: FeedNode.int(obj["port"]) ?? 0,
            tls: ((obj["tls"] as? String)?.nilIfBlank)?.lowercased(),
            network: ((obj["network"] as? String)?.nilIfBlank)?.lowercased(),
            sni: (obj["sni"] as? String)?.nilIfBlank,
            path: (obj["path"] as? String)?.nilIfBlank,
            hostHeader: (obj["hostHeader"] as? String)?.nilIfBlank,
            serviceName: (obj["serviceName"] as? String)?.nilIfBlank,
            alpn: (obj["alpn"] as? String)?.nilIfBlank,
            flow: (obj["flow"] as? String)?.nilIfBlank,
            pbk: (obj["pbk"] as? String)?.nilIfBlank,
            sid: (obj["sid"] as? String)?.nilIfBlank,
            fingerprint: (obj["fingerprint"] as? String)?.nilIfBlank,
            userId: (obj["userId"] as? String)?.nilIfBlank,
            alterId: FeedNode.int(obj["alterId"]),
            password: (obj["password"] as? String)?.nilIfBlank,
            username: (obj["username"] as? String)?.nilIfBlank,
            method: (obj["method"] as? String)?.nilIfBlank,
            cipher: (obj["cipher"] as? String)?.nilIfBlank,
            insecure: (obj["insecure"] as? Bool) ?? false,
            supportsUdp: (obj["supportsUdp"] as? Bool) ?? true,
            raw: (obj["raw"] as? String)?.nilIfBlank,
            platformStatus: ((obj["platformStatus"] as? String)?.nilIfBlank) ?? "unknown",
            grade: ((quality["grade"] as? String)?.nilIfBlank) ?? "D",
            latencyMs: FeedNode.int64(quality["latencyMs"]),
            reliability: FeedNode.double(quality["reliability"]) ?? 0,
            samples: FeedNode.int(quality["samples"]) ?? 0,
            tune: tuneMap
        )
    }

    // JSON numbers arrive as Int, Double or NSNumber depending on the writer; never trust one spelling.
    static func int(_ v: Any?) -> Int? {
        if let i = v as? Int { return i }
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    static func int64(_ v: Any?) -> Int64? {
        if let i = v as? Int64 { return i }
        if let n = v as? NSNumber { return n.int64Value }
        if let s = v as? String { return Int64(s.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    static func double(_ v: Any?) -> Double? {
        if let d = v as? Double { return d }
        if let n = v as? NSNumber { return n.doubleValue }
        if let s = v as? String { return Double(s.trimmingCharacters(in: .whitespaces)) }
        return nil
    }
}

/// Tiny type-erased JSON value so `tune` patches round-trip through Codable without a schema.
struct AnyCodable: Codable, Equatable, Hashable {
    let value: Any

    init(_ value: Any) { self.value = value }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { value = NSNull() }
        else if let b = try? c.decode(Bool.self) { value = b }
        else if let i = try? c.decode(Int64.self) { value = i }
        else if let d = try? c.decode(Double.self) { value = d }
        else if let s = try? c.decode(String.self) { value = s }
        else if let arr = try? c.decode([AnyCodable].self) { value = arr.map { $0.value } }
        else if let dict = try? c.decode([String: AnyCodable].self) {
            value = dict.mapValues { $0.value }
        } else {
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "unsupported JSON value")
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch value {
        case is NSNull: try c.encodeNil()
        case let b as Bool: try c.encode(b)
        case let i as Int64: try c.encode(i)
        case let i as Int: try c.encode(Int64(i))
        case let d as Double: try c.encode(d)
        case let s as String: try c.encode(s)
        case let arr as [Any]: try c.encode(arr.map { AnyCodable($0) })
        case let dict as [String: Any]: try c.encode(dict.mapValues { AnyCodable($0) })
        default:
            throw EncodingError.invalidValue(value, .init(codingPath: encoder.codingPath,
                                                         debugDescription: "unsupported JSON value"))
        }
    }

    static func == (lhs: AnyCodable, rhs: AnyCodable) -> Bool {
        switch (lhs.value, rhs.value) {
        case (is NSNull, is NSNull): return true
        case let (l as Bool, r as Bool): return l == r
        case let (l as Int64, r as Int64): return l == r
        case let (l as Double, r as Double): return l == r
        case let (l as String, r as String): return l == r
        default: return false
        }
    }

    func hash(into hasher: inout Hasher) {
        switch value {
        case let b as Bool: hasher.combine(b)
        case let i as Int64: hasher.combine(i)
        case let d as Double: hasher.combine(d)
        case let s as String: hasher.combine(s)
        default: hasher.combine(0)
        }
    }
}

extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
