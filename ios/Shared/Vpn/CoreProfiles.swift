import Foundation

/// Feed node + resolved tune -> the Xray JSON the linked core parses. Swift twin of
/// `android/.../vpn/CoreApi.kt::CoreProfiles` (Xray dialect), with exactly one platform difference:
/// the inbound is a `mixed` socks/http listener on 127.0.0.1:10808 instead of the Android TUN
/// inbound, because on iOS the Packet Tunnel Provider hands traffic to Xray through
/// `NEProxySettings` (see PacketTunnelProvider). Everything else - the simplified v26 outbound
/// shape, the freedom-dialer fragment chain for plain TLS, the triple DoH, the LAN rules - is kept
/// identical on purpose: a profile that behaves differently per platform is how "works on Android"
/// support tickets start.
enum CoreProfiles {

    static let localPort = 10808
    private static let fragmentTag = "fragment"
    private static let echDoh = "https://1.1.1.1/dns-query"

    /// In-tunnel DoH, by IP, in try-order: Cloudflare, Google, AliDNS (historically last throttled).
    static let dnsServers = [
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
        "https://223.5.5.5/dns-query",
    ]

    static let lanCidr = [
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "::1/128",
        "fe80::/10", "fc00::/7",
    ]

    struct SpecError: Error, LocalizedError {
        let reason: String
        var errorDescription: String? { reason }
    }

    /// The normalised transport decision - one source of truth for the profile builder.
    struct Spec {
        var proto: String
        var address: String
        var port: Int
        var userId: String
        var alterId: Int
        var username: String
        var password: String
        var method: String
        /// "none" | "tls" | "reality"
        var security: String
        var sni: String
        var alpn: [String]
        var fingerprint: String
        var ech: Bool
        var network: String
        var path: String
        var hostHeader: String
        var serviceName: String
        var grpcMulti: Bool
        var keepAliveSec: Int
        var flow: String
        var publicKey: String
        var shortId: String
        var mux: Bool
        var muxConcurrency: Int
        var fragSize: Int
        var fragCount: Int
        var fragStrategy: String
        var fragDelayMs: Int
        var mtu: Int
        var allowLan: Bool
        var connectionReuse: Bool
        var tuneEcho: [String: Any]

        var isReality: Bool { security == "reality" }
        var isTls: Bool { security == "tls" || isReality }
        var needsFragment: Bool { fragSize > 0 }
    }

    /// (feed node, resolved tune) -> spec. Throws named errors; a half-built profile is worse than none.
    static func specFor(_ node: FeedNode, tune: Tune) throws -> Spec {
        guard !node.host.isEmpty, (1...65535).contains(node.port) else {
            throw SpecError(reason: "invalid_endpoint")
        }
        guard !node.insecure else { throw SpecError(reason: "insecure_tls_not_supported") }
        let proto = try canonicalProtocol(node.proto)
        let net = try normNetwork(node.network)
        let sec: String
        switch node.tls?.trimmingCharacters(in: .whitespaces).lowercased() {
        case "reality": sec = "reality"
        case "tls", "https": sec = "tls"
        default: sec = "none"
        }
        let sni = tune.sni.isEmpty ? (node.sni ?? node.host) : tune.sni
        let requestedAlpn = (node.alpn ?? tune.alpn).split(separator: ",").map {
            $0.trimmingCharacters(in: .whitespaces)
        }.filter { !$0.isEmpty }
        // These transports have hard ALPN requirements; advertising h3 for a TLS-over-TCP WebSocket
        // is not camouflage - the peer selects a protocol this connection cannot speak.
        let alpn: [String]
        switch net {
        case "ws", "httpupgrade": alpn = ["http/1.1"]
        case "grpc": alpn = ["h2"]
        default: alpn = requestedAlpn.filter { $0.lowercased() != "h3" }.reduce(into: []) {
            if !$0.contains($1) { $0.append($1) }
        }
        }
        let feedFlow = node.flow ?? ""
        // Vision is what makes Reality fast, and only legal on plain TCP - derived here instead of
        // copied from whatever the volatile community feed happened to contain.
        let flow: String
        if !feedFlow.isEmpty {
            flow = feedFlow
        } else if sec == "reality", proto == "vless", net == "tcp" {
            flow = "xtls-rprx-vision"
        } else {
            flow = ""
        }
        // Mux under Reality is a documented footgun; grpc already multiplexes streams itself.
        let mux = tune.mux && net != "grpc" && sec != "reality"
        let reuse = tune.connectionReuse && net != "grpc"
        let method: String
        switch proto {
        case "shadowsocks": method = node.method ?? node.cipher ?? ""
        case "vmess": method = node.cipher ?? node.method ?? "auto"
        default: method = node.method ?? ""
        }
        let userId = node.userId ?? ""
        let password = node.password ?? ""
        switch proto {
        case "vless", "vmess":
            if userId.isEmpty { throw SpecError(reason: "missing_user_id") }
        case "trojan":
            if password.isEmpty { throw SpecError(reason: "missing_password") }
        case "shadowsocks":
            if password.isEmpty || method.isEmpty { throw SpecError(reason: "missing_shadowsocks_credentials") }
        default: break
        }
        return Spec(
            proto: proto, address: node.host, port: node.port,
            userId: userId, alterId: node.alterId ?? 0,
            username: node.username ?? "", password: password, method: method,
            security: sec, sni: sni, alpn: alpn,
            fingerprint: !(node.fingerprint ?? "").isEmpty ? node.fingerprint! : tune.fingerprint,
            ech: tune.ech && sec == "tls",
            network: net,
            path: node.path ?? "", hostHeader: node.hostHeader ?? "",
            serviceName: (node.serviceName?.nilIfBlank) ?? "grpc",
            grpcMulti: tune.grpcMode == "multi",
            keepAliveSec: tune.keepAliveSec,
            flow: flow,
            publicKey: node.pbk ?? "", shortId: node.sid ?? "",
            mux: mux, muxConcurrency: tune.muxConcurrency,
            fragSize: tune.fragSize, fragCount: tune.fragCount,
            fragStrategy: tune.fragStrategy, fragDelayMs: tune.fragDelayMs,
            mtu: tune.mtu, allowLan: tune.allowLan,
            connectionReuse: reuse,
            tuneEcho: [
                "fragSize": tune.fragSize, "fragCount": tune.fragCount, "fragStrategy": tune.fragStrategy,
                "fragDelayMs": tune.fragDelayMs, "alpn": tune.alpn, "fingerprint": tune.fingerprint,
                "sni": tune.sni, "ech": tune.ech, "keepAliveSec": tune.keepAliveSec,
                "mux": tune.mux, "muxConcurrency": tune.muxConcurrency, "allowLan": tune.allowLan,
                "mtu": tune.mtu, "mss": tune.mss, "grpcMode": tune.grpcMode,
                "connectionReuse": tune.connectionReuse,
            ]
        )
    }

    static func normNetwork(_ n: String?) throws -> String {
        switch n?.trimmingCharacters(in: .whitespaces).lowercased() {
        case "ws", "websocket": return "ws"
        case "grpc": return "grpc"
        case "httpupgrade": return "httpupgrade"
        case "xhttp", "splithttp": return "xhttp"
        case "raw", "tcp", "stream", "", nil: return "tcp"
        case "h2", "h3", "http", "quic":
            throw SpecError(reason: "removed_transport:\(n?.lowercased() ?? "")")
        default:
            throw SpecError(reason: "unsupported_transport:\(n?.lowercased() ?? "")")
        }
    }

    static func canonicalProtocol(_ p: String) throws -> String {
        switch p.trimmingCharacters(in: .whitespaces).lowercased() {
        case "vless", "vmess", "trojan": return p.trimmingCharacters(in: .whitespaces).lowercased()
        case "ss", "shadowsocks": return "shadowsocks"
        case "socks", "socks5", "socks5h": return "socks"
        case "socks4": throw SpecError(reason: "unsupported_protocol:socks4")
        case "http", "https": return "http"
        default: throw SpecError(reason: "unsupported_protocol:\(p.lowercased())")
        }
    }

    /// The full Xray profile as a JSON object (mixed inbound on loopback).
    static func xray(_ s: Spec) -> [String: Any] {
        var settings: [String: Any] = ["address": s.address, "port": s.port]
        switch s.proto {
        case "vless":
            settings["id"] = s.userId
            settings["encryption"] = "none"
            if !s.flow.isEmpty { settings["flow"] = s.flow }
        case "vmess":
            settings["id"] = s.userId
            settings["security"] = s.method
        case "trojan":
            settings["password"] = s.password
        case "shadowsocks":
            settings["method"] = s.method
            settings["password"] = s.password
        case "http", "socks":
            if !s.username.isEmpty {
                settings["user"] = s.username
                settings["pass"] = s.password
            }
        default:
            break
        }

        var stream: [String: Any] = ["network": s.network]
        switch s.network {
        case "ws":
            var ws: [String: Any] = ["path": s.path.isEmpty ? "/" : s.path]
            if !s.hostHeader.isEmpty { ws["host"] = s.hostHeader }
            stream["wsSettings"] = ws
        case "grpc":
            var g: [String: Any] = ["serviceName": s.serviceName, "multiMode": s.grpcMulti]
            if s.keepAliveSec > 0 {
                g["idle_timeout"] = max(s.keepAliveSec * 2, 30)
                g["health_check_timeout"] = max(s.keepAliveSec * 4, 60)
            }
            stream["grpcSettings"] = g
        case "httpupgrade":
            var h: [String: Any] = ["path": s.path.isEmpty ? "/" : s.path]
            if !s.hostHeader.isEmpty { h["host"] = s.hostHeader }
            stream["httpupgradeSettings"] = h
        case "xhttp":
            var x: [String: Any] = ["path": s.path.isEmpty ? "/" : s.path, "mode": "auto"]
            if !s.hostHeader.isEmpty { x["host"] = s.hostHeader }
            stream["xhttpSettings"] = x
        default:
            stream["tcpSettings"] = ["header": ["type": "none"]]
        }
        if s.isTls { stream["security"] = s.security }
        if s.security == "tls" {
            var tls: [String: Any] = ["serverName": s.sni]
            if !s.alpn.isEmpty { tls["alpn"] = s.alpn }
            tls["fingerprint"] = s.fingerprint
            if s.ech { tls["echConfigList"] = echDoh }
            stream["tlsSettings"] = tls
        }
        if s.isReality {
            stream["realitySettings"] = [
                "fingerprint": s.fingerprint,
                "serverName": s.sni,
                "publicKey": s.publicKey,
                "shortId": s.shortId,
                "spiderX": "",
            ]
        }

        // Fragmentation the way Xray actually does it: the proxy outbound dials *through* a freedom
        // outbound whose only job is to slice the first TLS record ("tlshello"). Plain TLS only -
        // never Reality (its ClientHello is a genuine borrowed handshake) and never plaintext.
        let fragmentWanted = s.needsFragment && s.security == "tls"
        var sockopt: [String: Any] = ["tcpNoDelay": true, "tcpFastOpen": s.connectionReuse]
        if fragmentWanted { sockopt["dialerProxy"] = fragmentTag }
        stream["sockopt"] = sockopt

        var outbound = usableOutbound(s, settings: settings, stream: stream)
        if s.needsFragment { outbound["meelanoFragment"] = fragmentPlan(s) }
        outbound["meelanoTune"] = s.tuneEcho

        var rules: [[String: Any]] = []
        // Private destinations never silently escape the tunnel unless allowLan is on.
        rules.append([
            "type": "field",
            "outboundTag": s.allowLan ? "direct" : "block",
            "ip": lanCidr,
        ])
        // the tunnel must own DNS or the local resolver keeps leaking what we look up
        rules.append(["type": "field", "network": "tcp,udp", "port": "53", "outboundTag": "dns-out"])
        rules.append(["type": "field", "protocol": ["tls", "quic"], "outboundTag": "proxy"])

        var profile: [String: Any] = [:]
        profile["log"] = ["loglevel": "warning"]
        // The mixed inbound the Packet Tunnel's NEProxySettings points at. Same port family as the
        // Android/desktop builds (10808) so docs and support playbooks say one number.
        let sniffing: [String: Any] = [
            "enabled": true,
            "destOverride": ["http", "tls", "quic"],
            "routeOnly": true,
        ]
        let inbound: [String: Any] = [
            "port": localPort,
            "listen": "127.0.0.1",
            "protocol": "mixed",
            "tag": "mixed-in",
            "sniffing": sniffing,
        ]
        profile["inbounds"] = [inbound]
        // Three resolvers, three networks, all dialed by IP so no bootstrap lookup can be poisoned.
        let dnsEntries: [[String: Any]] = dnsServers.map { s in
            ["address": s, "tag": "proxy", "timeoutMs": 4000]
        }
        profile["dns"] = [
            "queryStrategy": "UseIPv4",
            "servers": dnsEntries,
        ]
        var outbounds: [[String: Any]] = [outbound]
        if fragmentWanted {
            outbounds.append([
                "protocol": "freedom",
                "tag": fragmentTag,
                "settings": ["fragment": xrayFragment(s)],
            ])
        }
        outbounds.append(["protocol": "dns", "tag": "dns-out", "settings": [String: Any]()])
        outbounds.append(["protocol": "freedom", "tag": "direct"])
        outbounds.append(["protocol": "blackhole", "tag": "block"])
        profile["outbounds"] = outbounds
        profile["routing"] = ["domainStrategy": "IPIfNonMatch", "rules": rules] as [String: Any]
        return profile
    }

    /// The outbound exactly as Xray spells it - and exactly the unit pingBatch races.
    private static func usableOutbound(_ s: Spec, settings: [String: Any], stream: [String: Any]) -> [String: Any] {
        var outbound: [String: Any] = [
            "protocol": s.proto,
            "tag": "proxy",
            "settings": settings,
            "streamSettings": stream,
        ]
        if s.mux {
            outbound["mux"] = ["enabled": true, "concurrency": min(max(s.muxConcurrency, 1), 64)] as [String: Any]
        }
        return outbound
    }

    /// What `pingBatch` receives for one node: a throwaway config whose outbounds carry no app-only
    /// fields (`meelano*`), because the temporary core is constructed from this alone.
    static func pingConfigJson(_ node: FeedNode, tune: Tune) throws -> String {
        let full = xray(try specFor(node, tune: tune))
        guard let all = full["outbounds"] as? [[String: Any]], let proxy = all.first else {
            return "{\"outbounds\":[]}"
        }
        let cleaned = proxy.filter { !($0.key.hasPrefix("meelano")) }
        var keep: [[String: Any]] = [cleaned]
        if let fragment = all.first(where: { $0["tag"] as? String == fragmentTag }) {
            keep.append(fragment)
        }
        return try render(["outbounds": keep])
    }

    /// Full profile rendered to the string `runXray` takes.
    static func profileJson(_ node: FeedNode, tune: Tune) throws -> String {
        try render(xray(try specFor(node, tune: tune)))
    }

    /// "100-200" style range: variable widens it, fixed pins it.
    static func fragRange(size: Int, strategy: String, count: Int) -> String {
        switch strategy {
        case "random", "variable":
            return "\(Int(Double(size) * 0.75))-\(Int(Double(size) * 1.25))"
        default:
            return count > 1 ? "\(size)-\(size)" : "\(size)"
        }
    }

    static func fragInterval(delayMs: Int, strategy: String) -> String {
        if strategy == "random" || strategy == "variable" {
            return "\(delayMs)-\(max(delayMs * 2, delayMs + 10))"
        }
        return "\(delayMs)"
    }

    /// The Xray/freedom spelling: `packets` is "tlshello" there, not "tlstls".
    private static func xrayFragment(_ s: Spec) -> [String: Any] {
        [
            "packets": "tlshello",
            "length": fragRange(size: s.fragSize, strategy: s.fragStrategy, count: s.fragCount),
            "interval": fragInterval(delayMs: s.fragDelayMs, strategy: s.fragStrategy),
        ]
    }

    private static func fragmentPlan(_ s: Spec) -> [String: Any] {
        [
            "packets": s.isTls ? "tlstls" : "1-1",
            "length": fragRange(size: s.fragSize, strategy: s.fragStrategy, count: s.fragCount),
            "interval": fragInterval(delayMs: s.fragDelayMs, strategy: s.fragStrategy),
        ]
    }

    /// Stable, deterministic JSON (sorted keys) so diffs between two profiles show real changes.
    static func render(_ value: Any) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        guard let s = String(data: data, encoding: .utf8) else {
            throw SpecError(reason: "render_failed")
        }
        return s
    }
}
