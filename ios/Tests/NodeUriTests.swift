import XCTest

/// URI ingestion contract: what a phone must accept, and - just as important - what it must refuse.
final class NodeUriTests: XCTestCase {

    func testVlessRealityParses() {
        let uri = "vless://2a4e0000-0000-4000-8000-000000000001@37.120.190.11:443"
            + "?security=reality&pbk=SbVKpk&sid=a1b2c3d4&fp=chrome&sni=www.microsoft.com&type=tcp"
            + "#%F0%9F%87%A9%F0%9F%87%AA%20Vip%20Meelano"
        let n = NodeUri.parseUri(uri)
        XCTAssertNotNil(n)
        XCTAssertEqual(n?.proto, "vless")
        XCTAssertEqual(n?.host, "37.120.190.11")
        XCTAssertEqual(n?.port, 443)
        XCTAssertEqual(n?.tls, "reality")
        XCTAssertEqual(n?.pbk, "SbVKpk")
        XCTAssertEqual(n?.sid, "a1b2c3d4")
        XCTAssertEqual(n?.fingerprint, "chrome")
        XCTAssertEqual(n?.userId, "2a4e0000-0000-4000-8000-000000000001")
        XCTAssertEqual(n?.cc, "DE", "flag emoji pair must become the country code")
    }

    func testBracketedIpv6Endpoint() {
        let uri = "vless://2a4e0000-0000-4000-8000-000000000001@[2001:4860:4860::8888]:443"
            + "?security=reality&pbk=pk&sid=ab&type=tcp"
        let n = NodeUri.parseUri(uri)
        XCTAssertNotNil(n)
        XCTAssertEqual(n?.host, "2001:4860:4860::8888")
        XCTAssertEqual(n?.port, 443)
    }

    func testPrivateAndDocAddressesRefused() {
        XCTAssertNil(NodeUri.parseUri("vless://uuid@127.0.0.1:443?security=reality&pbk=pk&sid=ab&type=tcp"))
        XCTAssertNil(NodeUri.parseUri("vless://uuid@10.0.0.5:443?security=reality&pbk=pk&sid=ab&type=tcp"))
        XCTAssertNil(NodeUri.parseUri("vless://uuid@[2001:db8::1]:443?security=reality&pbk=pk&sid=ab&type=tcp"))
        XCTAssertNil(NodeUri.parseUri("vless://uuid@[fe80::1]:443?security=reality&pbk=pk&sid=ab&type=tcp"))
    }

    func testInsecureLinksRefused() {
        XCTAssertNil(NodeUri.parseUri("trojan://pw@1.2.3.4:443?security=tls&allowinsecure=1"))
    }

    func testTrojanRequiresPassword() {
        XCTAssertNil(NodeUri.parseUri("trojan://@1.2.3.4:443?security=tls&sni=x.com"))
        let n = NodeUri.parseUri("trojan://pw123@1.2.3.4:443?security=tls&sni=x.com&type=tcp")
        XCTAssertEqual(n?.password, "pw123")
        XCTAssertEqual(n?.tls, "tls")
    }

    func testVmessBodyParses() throws {
        let body: [String: Any] = [
            "v": "2", "ps": "🇫🇷 Vip Meelano", "add": "vm.example.com", "port": "443",
            "id": "2a4e0000-0000-4000-8000-000000000001", "aid": "0", "scy": "auto",
            "net": "ws", "host": "cdn.example.com", "path": "/ws", "tls": "tls",
        ]
        let json = try JSONSerialization.data(withJSONObject: body)
        let b64 = json.base64EncodedString()
        let n = NodeUri.parseUri("vmess://\(b64)")
        XCTAssertNotNil(n)
        XCTAssertEqual(n?.host, "vm.example.com")
        XCTAssertEqual(n?.port, 443)
        XCTAssertEqual(n?.network, "ws")
        XCTAssertEqual(n?.cc, "FR")
    }

    func testSsSip002BothSpellings() {
        // literal method:password
        let direct = NodeUri.parseUri("ss://aes-256-gcm:pw@1.2.3.4:8388?type=tcp")
        XCTAssertEqual(direct?.method, "aes-256-gcm")
        // base64 method:password
        let blob = Data("chacha20-ietf-poly1305:s3cret".utf8).base64EncodedString()
        let b64 = NodeUri.parseUri("ss://\(blob)@5.6.7.8:8388")
        XCTAssertEqual(b64?.method, "chacha20-ietf-poly1305")
        XCTAssertEqual(b64?.password, "s3cret")
    }

    func testSsGarbageCredentialRefused() {
        // A UUID-shaped userinfo decodes to garbage - must be refused, not published as grade B.
        let garbage = Data("2a4e0000-0000-4000-8000-000000000001".utf8).base64EncodedString()
        XCTAssertNil(NodeUri.parseUri("ss://\(garbage)@1.2.3.4:8388"))
    }

    func testProxyLineForms() {
        let a = NodeUri.parseProxyLine("1.2.3.4:1080", protocolHint: nil)
        XCTAssertEqual(a?.proto, "socks5", "classic proxy ports default to socks5")
        let b = NodeUri.parseProxyLine("user:pass@1.2.3.4:3128", protocolHint: nil)
        XCTAssertEqual(b?.proto, "http")
        XCTAssertEqual(b?.username, "user")
        let c = NodeUri.parseProxyLine("1.2.3.4:3128:user:pass", protocolHint: nil)
        XCTAssertEqual(c?.username, "user")
        XCTAssertEqual(c?.password, "pass")
    }

    func testParseBlobMasksVendorRemarks() {
        let blob = "vless://2a4e0000-0000-4000-8000-000000000001@1.2.3.4:443"
            + "?security=reality&pbk=pk&sid=ab&type=tcp#SomeVendorName"
        let nodes = NodeUri.parseBlob(blob, tier: "vip", brand: "Vip Meelano")
        XCTAssertEqual(nodes.count, 1)
        XCTAssertEqual(nodes.first?.name, "Vip Meelano")
        XCTAssertFalse(nodes.first?.subtitle.contains("SomeVendorName") ?? true)
    }

    func testBase64SubscriptionUnwraps() {
        let inner = "vless://2a4e0000-0000-4000-8000-000000000001@1.2.3.4:443"
            + "?security=reality&pbk=pk&sid=ab&type=tcp"
        let wrapped = Data(inner.utf8).base64EncodedString()
        let nodes = NodeUri.parseBlob(wrapped, tier: "free", brand: "Free Meelano")
        XCTAssertEqual(nodes.count, 1)
    }

    func testIdIsStableAcrossCalls() {
        let a = NodeUri.id("vless", host: "1.2.3.4", port: 443, secret: "uuid", extra: "pk")
        let b = NodeUri.id("vless", host: "1.2.3.4", port: 443, secret: "uuid", extra: "pk")
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.count, 12)
        let c = NodeUri.id("vless", host: "1.2.3.4", port: 443, secret: "other", extra: "pk")
        XCTAssertNotEqual(a, c)
    }

    func testCcFromLoneTokenAndWord() {
        XCTAssertEqual(NodeUri.ccFrom("DE Vip Meelano"), "DE")
        XCTAssertEqual(NodeUri.ccFrom("fast germany node"), "DE")
        XCTAssertNil(NodeUri.ccFrom("just some words"))
    }
}
