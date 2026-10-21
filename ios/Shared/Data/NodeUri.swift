import Foundation

/// In-app ingestion of raw proxy configs, so the app can build its own lists without the `/v/` host.
/// Swift twin of `android/.../data/NodeUri.kt` - a deliberate *subset* of `backend/v/lib/Parser.php`:
/// what a phone can do cheaply (decode what the user pastes, read public lists, keep the lines that
/// parse cleanly) without burning battery and quota like the server's probe fleet.
enum NodeUri {

    /// Ciphers a dialer can actually use. Kept in sync with `Parser::$ssMethods` on the server.
    static let ssMethods: Set<String> = [
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
        "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
        "chacha20-ietf", "chacha20-ietf-poly1305", "chacha20-poly1305", "xchacha20-ietf-poly1305",
        "sodium:chacha20-ietf-poly1305", "sodium:aes-256-gcm",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "rc4-md5", "bf-cfb", "cast5-cfb", "idea-cfb", "rc2-cfb", "seed-cfb",
    ]

    /// Protocols this app can hand to a core. Anything else is refused, not half-built.
    static let tunnelSchemes: Set<String> = ["vless", "vmess", "trojan", "ss", "http", "https", "socks5", "socks5h", "socks"]

    static let uriRegex = try! NSRegularExpression(
        pattern: #"(?i)\b(vless|vmess|trojan|ss|https?|socks5h?|socks)://[^\s"'<>\\]+"#
    )
    static let proxyLineRegex = try! NSRegularExpression(
        pattern: #"(?i)^(?:(socks5h|socks5|socks|https|http)://)?(?:([^\s:@/]+)(?::([^\s:@/]*))?@)?((?:\d{1,3}\.){3}\d{1,3}|[a-z0-9._-]+):(\d{2,5})(.*)$"#
    )

    static let countries: [String: String] = [
        "de": "germany", "fr": "france", "nl": "netherlands", "se": "sweden", "fi": "finland",
        "no": "norway", "dk": "denmark", "gb": "england", "uk": "england", "us": "america",
        "ca": "canada", "tr": "turkey", "ae": "dubai", "sg": "singapore", "jp": "japan",
        "hk": "hongkong", "kr": "korea", "in": "india", "au": "australia", "ru": "russia",
        "ua": "ukraine", "pl": "poland", "it": "italy", "es": "spain", "pt": "portugal",
        "at": "austria", "ch": "switzerland", "be": "belgium", "ro": "romania", "hu": "hungary",
        "cz": "czech", "gr": "greece", "lv": "latvia", "lt": "lithuania", "ee": "estonia",
        "ir": "iran", "cn": "china", "tw": "taiwan", "th": "thailand", "my": "malaysia",
        "za": "southafrica", "br": "brazil", "ar": "argentina", "md": "moldova", "lu": "luxembourg",
        "bg": "bulgaria", "rs": "serbia", "sk": "slovakia", "si": "slovenia", "ie": "ireland",
    ]
    static let ccSet: Set<String> = Set(countries.keys.map { $0.uppercased() })

    /// A blob of anything: pasted configs, a subscription body, an HTML page, a Telegram export, or a
    /// JSON list. `tier` decides the label (and what the connect path is allowed to do with it).
    static func parseBlob(_ blob: String, tier: String, brand: String,
                          proxyHint: String? = nil, maxNodes: Int = 240) -> [FeedNode] {
        let text = blob.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || maxNodes <= 0 { return [] }
        var out: [String: FeedNode] = [:]
        var order: [String] = []

        func keep(_ n: FeedNode?) {
            guard let n = n, out[n.id] == nil, out.count < maxNodes else { return }
            out[n.id] = n
            order.append(n.id)
        }

        // 1) every scheme:// token, wherever it hides.
        forEachUri(in: text, limit: maxNodes) { keep(parseUri($0)) }
        if out.isEmpty, !text.contains("://"), !text.contains("\n"), (24...400_000).contains(text.count) {
            // 2) one base64 line with no visible scheme: a subscription string. Unwrap and try again.
            if let inner = decodeBase64(text) {
                forEachUri(in: inner, limit: maxNodes) { keep(parseUri($0)) }
            }
        }
        // 3) bare proxy lines: ip:port, user:pass@ip:port, ip:port:user:pass
        if out.count < maxNodes {
            for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
                var line = rawLine.trimmingCharacters(in: .whitespaces)
                line = line.trimmingCharacters(in: CharacterSet(charactersIn: ",;\"'<> ()"))
                if line.isEmpty || line.hasPrefix("#") || line.contains("://") { continue }
                keep(parseProxyLine(line, protocolHint: proxyHint))
                if out.count >= maxNodes { break }
            }
        }
        // 4) a JSON list of {ip,port,proto,…} objects (already geolocated upstream)
        if out.count < maxNodes, let first = text.first, first == "[" || first == "{" {
            for n in jsonNodes(text, max: maxNodes - out.count) { keep(n) }
        }

        return order.compactMap { out[$0] }.map { mask($0, brand: brand, tier: tier) }
    }

    private static func forEachUri(in text: String, limit: Int, _ body: (String) -> Void) {
        let range = NSRange(text.startIndex..., in: text)
        var count = 0
        uriRegex.enumerateMatches(in: text, range: range) { m, _, stop in
            guard let m = m, count < limit else {
                if count >= limit { stop.pointee = true }
                return
            }
            guard let r = Range(m.range, in: text) else { return }
            let token = String(text[r]).trimmingCharacters(in: CharacterSet(charactersIn: ",;\"')]"))
            body(token)
            count += 1
        }
    }

    /// One `scheme://…` line. Nil when the line cannot possibly connect.
    static func parseUri(_ token: String) -> FeedNode? {
        guard let schemeEnd = token.range(of: "://") else { return nil }
        let rawScheme = String(token[token.startIndex..<schemeEnd.lowerBound]).lowercased()
        let scheme = (rawScheme == "socks" || rawScheme == "socks5h") ? "socks5" : rawScheme
        guard tunnelSchemes.contains(scheme) else { return nil }
        if scheme == "http" || scheme == "https" || scheme == "socks5" {
            return parseProxyLine(token, protocolHint: nil)
        }
        var rest = String(token[schemeEnd.upperBound...])
        let remark = unescape(substring(rest, after: "#", default: ""))
        rest = String(rest.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0])
        let q = parseQuery(substring(rest, after: "?", default: ""))
        let authority = String(rest.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0])
        let cred: String = {
            guard let at = authority.lastIndex(of: "@") else { return "" }
            return String(authority[authority.startIndex..<at])
        }()
        let hostPort = {
            if let at = authority.lastIndex(of: "@") { return String(authority[authority.index(after: at)...]) }
            return authority
        }()
        // Bracketed IPv6 literals ([2001:db8::1]:443) must not be mangled by a first-colon split.
        let host: String
        let port: Int?
        if hostPort.hasPrefix("[") {
            guard let close = hostPort.firstIndex(of: "]") else { return nil }
            host = String(hostPort[hostPort.index(after: hostPort.startIndex)..<close])
            let tail = String(hostPort[hostPort.index(after: close)...]).drop(while: { $0 == ":" })
            port = Int(String(tail.prefix(while: { $0.isNumber })))
        } else {
            if let c = hostPort.firstIndex(of: ":") {
                host = String(hostPort[hostPort.startIndex..<c]).trimmingCharacters(in: .whitespaces)
                port = Int(String(hostPort[hostPort.index(after: c)...].prefix(while: { $0.isNumber })))
            } else {
                host = hostPort.trimmingCharacters(in: .whitespaces)
                port = nil
            }
        }

        // vmess carries its whole body base64'd in the userinfo.
        if scheme == "vmess" {
            let payload = cred.isEmpty ? authority : cred
            guard let j = decodeBase64(payload),
                  let data = j.data(using: .utf8),
                  let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
            let h = ((o["add"] as? String) ?? (o["address"] as? String))?.nilIfBlank
            let p = FeedNode.int(o["port"])
            let uid = (o["id"] as? String)?.nilIfBlank
            guard let h = h, let p = p, p > 0, p <= 65535, isPublicAddress(h), let uid = uid else { return nil }
            return FeedNode(
                id: id(scheme, host: h, port: p, secret: uid, extra: nil),
                cc: ccFrom((o["ps"] as? String) ?? remark),
                proto: scheme, host: h, port: p,
                tls: (o["tls"] as? String)?.nilIfBlank,
                network: (o["net"] as? String)?.nilIfBlank,
                path: (o["path"] as? String).map(unescape),
                hostHeader: (o["host"] as? String)?.nilIfBlank,
                userId: uid,
                alterId: FeedNode.int(o["aid"]) ?? 0,
                cipher: ((o["scy"] as? String)?.nilIfBlank) ?? "auto",
                raw: token
            )
        }

        guard let port = port, (1...65535).contains(port), isPublicAddress(host) else { return nil }

        if scheme == "ss" {
            guard let (method, password) = ssCredentials(cred) else { return nil }
            return FeedNode(
                id: id(scheme, host: host, port: port, secret: password, extra: method),
                cc: ccFrom(remark),
                proto: scheme, host: host, port: port,
                tls: (q["security"]?.nilIfBlank).flatMap { $0 == "none" ? nil : $0 }
                    ?? ((q["type"] == "tls") ? "tls" : nil),
                network: (q["type"]?.nilIfBlank).flatMap { $0 == "tcp" ? nil : $0 } ?? "tcp",
                sni: q["sni"],
                path: (q["path"]?.nilIfBlank).map(unescape),
                hostHeader: q["host"]?.nilIfBlank,
                password: password,
                method: method,
                raw: token
            )
        }

        // vless / trojan (and friends): none of these dial without their secret.
        let secret = cred.nilIfBlank
        guard let secret = secret else { return nil }
        if q["allowinsecure"] == "1" || (q["allowinsecure"] ?? "").lowercased() == "true"
            || q["insecure"] == "1" || (q["insecure"] ?? "").lowercased() == "true" {
            return nil
        }
        return FeedNode(
            id: id(scheme, host: host, port: port, secret: secret, extra: q["pbk"] ?? q["sid"]),
            cc: ccFrom(remark),
            proto: scheme, host: host, port: port,
            tls: {
                if q["security"] == "reality" { return "reality" }
                if let s = q["security"], !s.isEmpty { return s }
                if q["allowinsecure"] != nil || q["insecure"] != nil { return "tls" }
                return nil
            }(),
            network: q["type"]?.nilIfBlank,
            sni: q["sni"] ?? q["peer"],
            path: (q["path"]?.nilIfBlank).map(unescape),
            hostHeader: q["host"]?.nilIfBlank,
            serviceName: q["servicename"]?.nilIfBlank ?? q["service_name"]?.nilIfBlank,
            alpn: q["alpn"]?.nilIfBlank,
            flow: q["flow"]?.nilIfBlank,
            pbk: q["pbk"]?.nilIfBlank,
            sid: q["sid"]?.nilIfBlank,
            // v2rayN writes `fingerprint=`, sing-box links `fp=`, a few panels `sfp=`.
            fingerprint: q["fp"]?.nilIfBlank ?? q["fingerprint"]?.nilIfBlank ?? q["sfp"]?.nilIfBlank,
            userId: scheme == "vless" ? secret : nil,
            password: scheme == "vless" ? nil : secret,
            insecure: q["allowinsecure"] == "1" || q["insecure"] == "1",
            raw: token
        )
    }

    /// `ip:port`, `user:pass@ip:port`, `ip:port:user:pass`, and the `socks5://…` spelling.
    static func parseProxyLine(_ line: String, protocolHint: String?) -> FeedNode? {
        let ns = line as NSString
        guard let m = proxyLineRegex.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        func group(_ i: Int) -> String {
            guard m.numberOfRanges > i, m.range(at: i).location != NSNotFound,
                  let r = Range(m.range(at: i), in: line) else { return "" }
            return String(line[r])
        }
        let scheme = group(1).lowercased()
        var user = group(2).nilIfBlank
        var pass = group(3).nilIfBlank
        let hostRaw = group(4)
        let portRaw = group(5)
        let tail = group(6)
        let host = hostRaw.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard let port = Int(portRaw), (1...65535).contains(port), isPublicAddress(host) else { return nil }

        if user == nil, !tail.isEmpty {
            let extra = tail.drop(while: { $0 == ":" || $0 == "/" }).split(separator: ":", maxSplits: 1,
                                                                            omittingEmptySubsequences: false)
            if extra.count == 2 {
                user = String(extra[0]).nilIfBlank
                pass = String(extra[1]).nilIfBlank
            }
        }
        let hinted = (protocolHint ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        let proto0 = !scheme.isEmpty ? scheme
            : (!hinted.isEmpty ? hinted
                : ([1080, 10808, 4145].contains(port) ? "socks5" : "http"))
        let proto = (proto0 == "socks" || proto0 == "socks5h") ? "socks5" : proto0
        guard ["http", "https", "socks5"].contains(proto) else { return nil }
        return FeedNode(
            id: id(proto, host: host, port: port, secret: user, extra: pass),
            tier: "free",
            proto: proto == "https" ? "http" : proto,
            host: host, port: port,
            tls: proto == "https" ? "tls" : nil,
            network: "tcp",
            password: pass.map(unescape),
            username: user.map(unescape),
            supportsUdp: false,
            raw: line
        )
    }

    /// `ss://` userinfo is either base64(`method:password`) (SIP002) or the literal `method:password`.
    static func ssCredentials(_ cred: String) -> (String, String)? {
        if cred.isEmpty { return nil }
        let direct = cred.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        if direct.count == 2, ssMethods.contains(direct[0].trimmingCharacters(in: .whitespaces).lowercased()) {
            return (direct[0].trimmingCharacters(in: .whitespaces).lowercased(), String(direct[1]))
        }
        guard let decoded = decodeBase64(cred) else { return nil }
        let parts = decoded.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let method = parts[0].trimmingCharacters(in: .whitespaces).lowercased()
        guard ssMethods.contains(method) else { return nil }
        let password = parts[1].trimmingCharacters(in: .whitespaces)
        if password.isEmpty || password.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }) {
            return nil
        }
        return (method, password)
    }

    /// Vendor remarks are masked with the server's own rule: brand + country + proto + port.
    static func mask(_ n: FeedNode, brand: String, tier: String? = nil) -> FeedNode {
        var n = n
        if let t = tier { n.tier = t }
        // Kotlin: raw.substringAfterLast('#', "") - a remark only exists when the raw has a '#'.
        let remark: String = {
            guard let raw = n.raw, let hash = raw.lastIndex(of: "#") else { return "" }
            return String(raw[raw.index(after: hash)...])
        }()
        let cc = n.cc ?? ccFrom(remark)
        let country = cc.flatMap { countries[$0.lowercased()] }
        let bits = [country, n.proto.isEmpty ? nil : n.proto, n.port > 0 ? "\(n.port)" : nil]
            .compactMap { $0 }.joined(separator: " · ")
        n.name = brand
        n.subtitle = bits
        n.cc = cc
        if n.grade.isEmpty { n.grade = "D" }
        return n
    }

    /// Flag emoji pair -> "DE", else a lone ISO-looking token in CC_SET, else a known country word.
    static func ccFrom(_ remark: String) -> String? {
        if remark.isEmpty { return nil }
        // Regional indicators live at 0x1F1E6..0x1F1FF and must be adjacent, exactly like Country.php.
        let scalars = Array(remark.unicodeScalars)
        var i = 0
        while i + 1 < scalars.count {
            let a = scalars[i].value
            let b = scalars[i + 1].value
            if (0x1F1E6...0x1F1FF).contains(a), (0x1F1E6...0x1F1FF).contains(b) {
                let s1 = Unicode.Scalar(65 + (a - 0x1F1E6))!
                let s2 = Unicode.Scalar(65 + (b - 0x1F1E6))!
                return "\(s1)\(s2)"
            }
            i += 1
        }
        let upper = remark.uppercased()
        let pattern = try! NSRegularExpression(pattern: #"(?<![A-Z])([A-Z]{2})(?![A-Z])"#)
        let ns = upper as NSString
        let matches = pattern.matches(in: upper, range: NSRange(location: 0, length: ns.length))
        for m in matches {
            let cand = ns.substring(with: m.range(at: 1))
            if ccSet.contains(cand) { return cand }
        }
        let lower = remark.lowercased()
        for (cc, name) in countries where lower.contains(name) {
            return cc.uppercased()
        }
        return nil
    }

    static func isPublicAddress(_ host: String) -> Bool {
        let h = host.trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h.isEmpty { return false }
        // IPv6 literals are welcome exactly when globally routable; a hostname never reaches the
        // literal check, so no DNS lookup can hide in here.
        if h.contains(":") { return IpFamily.isGlobalIpv6Literal(h) }
        let parts = h.split(separator: ".").map(String.init)
        if parts.count == 4, parts.allSatisfy({ Int($0) != nil }) {
            let octets = parts.compactMap { Int($0) }
            if octets.contains(where: { $0 < 0 || $0 > 255 }) { return false }
            let a = octets[0], b = octets[1]
            if [0, 10, 127].contains(a) { return false }
            if a == 100, (64...127).contains(b) { return false } // carrier-grade NAT
            if a == 169, b == 254 { return false } // link-local
            if a == 172, (16...31).contains(b) { return false }
            if a == 192, b == 168 { return false }
            if a == 198, b == 18 || b == 19 { return false }
            if a == 8, b == 8, octets[2] == 8, octets[3] == 8 { return false } // probe magnet
            if a >= 224 { return false } // multicast / reserved
            return true
        }
        if h.lowercased() == "localhost" || !h.contains(".") { return false }
        guard let dot = h.firstIndex(of: ".") else { return false }
        return dot > h.startIndex && dot < h.index(before: h.endIndex)
    }

    /// Stable machine id: FNV-1a 32 over `proto|host|port|secret|extra`, then the UTF-16 hash, 12 hex.
    /// Mirrors `NodeUri.id()` on Android and `Parser::id()` on the server so pins travel across apps.
    static func id(_ proto: String, host: String, port: Int, secret: String?, extra: String?) -> String {
        let src = [proto, host, "\(port)", secret ?? "", extra ?? ""].joined(separator: "|")
        var fnv: UInt32 = 0x80F544E9 // -0x7f0abb17 as signed Int32
        var javaHash: Int32 = 0
        for u in src.utf16 {
            fnv ^= UInt32(u)
            fnv = fnv &* 0x01000193
            javaHash = javaHash &* 31 &+ Int32(bitPattern: UInt32(u))
        }
        return String(String(format: "%08x%08x", fnv, UInt32(bitPattern: javaHash)).prefix(12))
    }

    static func parseQuery(_ q: String) -> [String: String] {
        var out: [String: String] = [:]
        for pair in q.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard let k = kv.first else { continue }
            let key = unescape(String(k))
            let value = kv.count > 1 ? unescape(String(kv[1])) : ""
            if !key.isEmpty { out[key] = value }
        }
        return out
    }

    static func unescape(_ s: String) -> String {
        s.removingPercentEncoding ?? s
    }

    static func decodeBase64(_ s: String) -> String? {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        if t.count < 8 { return nil }
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\n\r")
        if t.unicodeScalars.contains(where: { !allowed.contains($0) }) { return nil }
        t = t.replacingOccurrences(of: "\n", with: "").replacingOccurrences(of: "\r", with: "")
        t += String(repeating: "=", count: (4 - t.count % 4) % 4)
        guard let data = Data(base64Encoded: t) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// JSON list of node objects (monosans/jetkai shape): {"servers":[…]} or a bare array.
    private static func jsonNodes(_ text: String, max: Int) -> [FeedNode] {
        guard max > 0, let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let arr: [[String: Any]]
        if let a = obj as? [[String: Any]] {
            arr = a
        } else if let o = obj as? [String: Any], let a = o["servers"] as? [[String: Any]] {
            arr = a
        } else {
            return []
        }
        var out: [FeedNode] = []
        for item in arr.prefix(max) {
            if let n = FeedNode(feedJson: item) {
                out.append(n)
            } else if let host = (item["ip"] as? String) ?? (item["host"] as? String),
                      let port = FeedNode.int(item["port"]) {
                let proto = ((item["proto"] as? String) ?? (item["protocol"] as? String) ?? "socks5").lowercased()
                out.append(FeedNode(
                    id: id(proto, host: host, port: port, secret: item["user"] as? String, extra: item["pass"] as? String),
                    tier: "free", proto: proto, host: host, port: port,
                    password: item["pass"] as? String, username: item["user"] as? String,
                    supportsUdp: false
                ))
            }
            if out.count >= max { break }
        }
        return out
    }

    private static func substring(_ s: String, after marker: String, default def: String) -> String {
        guard let r = s.range(of: marker) else { return def }
        return String(s[r.upperBound...])
    }
}
