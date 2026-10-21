import Foundation

/// "Is this IPv6 address a real, globally routable one?" - the same two questions the Android
/// `net/IpFamily.kt` answers: the tunnel only captures v6 when the path really has global v6, and the
/// URI parser only accepts global v6 literals as dial targets. Pure literal parsing, no DNS.
enum IpFamily {

    /// Bytes-level check. `nil` when the text is not an IPv6 literal at all.
    static func isGlobalIpv6(bytes b: [UInt8]) -> Bool {
        guard b.count == 16 else { return false }
        // 2001:db8::/32 is the documentation range: globally shaped, never routed.
        if b[0] == 0x20, b[1] == 0x01, b[2] == 0x0D, b[3] == 0xB8 { return false }
        // Loopback ::1, any ::, link-local fe80::/10, ULA fc00::/7, multicast ff00::/8.
        if b.dropLast().allSatisfy({ $0 == 0 }) {
            let last = b[15]
            if last == 0 || last == 1 { return false } // :: and ::1
        }
        let first = b[0]
        if first == 0xFE, (b[1] & 0xC0) == 0x80 { return false } // fe80::/10 link-local
        if (first & 0xFE) == 0xFC { return false } // fc00::/7 ULA
        if first == 0xFF { return false } // ff00::/8 multicast
        // v4-mapped ::ffff:a.b.c.d and v4-compatible are not v6 exits.
        if b.prefix(10).allSatisfy({ $0 == 0 }), b[10] == 0xFF, b[11] == 0xFF { return false }
        if b.prefix(12).allSatisfy({ $0 == 0 }), (b[12] & 0xF0) != 0 { return false }
        // Global unicast is 2000::/3. Everything outside it is not an exit either.
        return (first & 0xE0) == 0x20
    }

    /// Literal-only variant for text input (share links, feed rows). Refuses anything that is not
    /// shaped like an IPv6 literal *before* any name resolution, so a hostname can never trigger a
    /// DNS lookup from a parser.
    static func isGlobalIpv6Literal(_ host: String) -> Bool {
        var h = host.trimmingCharacters(in: .whitespaces)
        if h.hasPrefix("["), h.hasSuffix("]") { h = String(h.dropFirst().dropLast()) }
        if let pct = h.firstIndex(of: "%") { h = String(h[h.startIndex..<pct]) }
        guard h.contains(":") else { return false }
        guard h.unicodeScalars.allSatisfy({
            ("0"..."9").contains(String($0)) || ("a"..."f").contains(String($0).lowercased())
                || $0 == ":" || $0 == "."
        }) else { return false }
        guard let bytes = parseIpv6(h) else { return false }
        return isGlobalIpv6(bytes: bytes)
    }

    static func hasGlobalIpv6(_ literals: [String]) -> Bool {
        literals.contains { isGlobalIpv6Literal($0) }
    }

    /// Minimal RFC 4291 literal parser: "::" compression plus a trailing dotted-quad tail.
    static func parseIpv6(_ text: String) -> [UInt8]? {
        var s = text
        if s.hasPrefix("["), s.hasSuffix("]") { s = String(s.dropFirst().dropLast()) }
        if let pct = s.firstIndex(of: "%") { s = String(s[s.startIndex..<pct]) }
        guard !s.isEmpty else { return nil }

        // Split off a trailing v4 part (e.g. ::ffff:1.2.3.4).
        var head = s
        var tailBytes: [UInt8] = []
        if let lastColon = s.lastIndex(of: ":"), s[(s.index(after: lastColon))...].contains(".") {
            let v4Text = String(s[s.index(after: lastColon)...])
            head = String(s[s.startIndex..<lastColon])
            // "2001:db8::1.2.3.4": the cut above eats one colon of the "::" - put it back.
            if head.hasSuffix(":") { head += ":" }
            guard let v4 = parseIpv4(v4Text) else { return nil }
            tailBytes = v4
            if head == ":" { head = "" } // pure ":1.2.3.4" is not an IPv6 literal
        }

        var groups: [Int] = []
        if head.contains("::") {
            guard head.components(separatedBy: "::").count == 2 else { return nil }
            let parts = head.components(separatedBy: "::")
            let left = parts[0].isEmpty ? [] : parts[0].split(separator: ":").map(String.init)
            var right = parts[1].isEmpty ? [] : parts[1].split(separator: ":").map(String.init)
            if !tailBytes.isEmpty {
                // the last "group" of the right side already became the v4 tail
                if right.isEmpty { right = [] }
            }
            guard let l = parseGroups(left), let r = parseGroups(right) else { return nil }
            let missing = 8 - l.count - r.count - tailBytes.count / 2
            guard missing >= 0 else { return nil }
            groups = l + Array(repeating: 0, count: missing) + r
        } else {
            let parts = head.split(separator: ":").map(String.init)
            guard let g = parseGroups(parts) else { return nil }
            groups = g
        }

        var out: [UInt8] = []
        for g in groups {
            out.append(UInt8((g >> 8) & 0xFF))
            out.append(UInt8(g & 0xFF))
        }
        out += tailBytes
        return out.count == 16 ? out : nil
    }

    private static func parseGroups(_ parts: [String]) -> [Int]? {
        var out: [Int] = []
        for p in parts {
            guard p.count >= 1, p.count <= 4, let v = Int(p, radix: 16), v >= 0, v <= 0xFFFF else { return nil }
            out.append(v)
        }
        return out
    }

    private static func parseIpv4(_ text: String) -> [UInt8]? {
        let parts = text.split(separator: ".").map(String.init)
        guard parts.count == 4 else { return nil }
        var out: [UInt8] = []
        for p in parts {
            guard let v = Int(p), v >= 0, v <= 255, String(v) == p || (p.hasPrefix("0") == false) else { return nil }
            out.append(UInt8(v))
        }
        return out
    }
}
