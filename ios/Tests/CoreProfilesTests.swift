import XCTest

/// The core contract - the iOS twin of the Android `CoreProfilesTest`. Every assertion here exists
/// because a wrong profile fails *at the user's phone*, silently, looking like "the server is dead".
final class CoreProfilesTests: XCTestCase {

    private func realityNode() -> FeedNode {
        FeedNode(
            id: "n1", proto: "vless", host: "37.120.190.11", port: 443,
            tls: "reality", network: "tcp", sni: "www.microsoft.com",
            flow: nil, pbk: "SbVKpublickey", sid: "a1b2c3d4", fingerprint: "chrome",
            userId: "2a4e0000-0000-4000-8000-000000000001"
        )
    }

    private func plainTlsWsNode() -> FeedNode {
        FeedNode(
            id: "n2", proto: "trojan", host: "example.org", port: 443,
            tls: "tls", network: "ws", sni: "example.org", path: "/ws",
            password: "pw123"
        )
    }

    func testRealityProfileShape() throws {
        let tune = Tune.forRegime(.tight)
        let json = try CoreProfiles.xray(try CoreProfiles.specFor(realityNode(), tune: tune))
        let outbounds = json["outbounds"] as? [[String: Any]] ?? []
        let proxy = outbounds.first ?? [:]
        XCTAssertEqual(proxy["protocol"] as? String, "vless")
        let settings = proxy["settings"] as? [String: Any] ?? [:]
        XCTAssertEqual(settings["id"] as? String, "2a4e0000-0000-4000-8000-000000000001")
        // Vision is derived, not copied from the feed.
        XCTAssertEqual(settings["flow"] as? String, "xtls-rprx-vision")
        let stream = proxy["streamSettings"] as? [String: Any] ?? [:]
        XCTAssertEqual(stream["security"] as? String, "reality")
        let reality = stream["realitySettings"] as? [String: Any] ?? [:]
        XCTAssertEqual(reality["publicKey"] as? String, "SbVKpublickey")
        XCTAssertEqual(reality["shortId"] as? String, "a1b2c3d4")
        XCTAssertEqual(reality["serverName"] as? String, "www.microsoft.com")
    }

    func testFragmentChainOnlyForPlainTls() throws {
        // Plain TLS + fragmentation -> freedom dialer chain is real (since 2.6.0 on Android).
        var tune = Tune.forRegime(.tight)
        tune.fragSize = 200
        let spec = try CoreProfiles.specFor(plainTlsWsNode(), tune: tune)
        let json = CoreProfiles.xray(spec)
        let outbounds = json["outbounds"] as? [[String: Any]] ?? []
        let fragment = outbounds.first { $0["tag"] as? String == "fragment" }
        XCTAssertNotNil(fragment, "plain TLS with fragSize>0 must carry the freedom fragment outbound")
        let fragSettings = (fragment?["settings"] as? [String: Any])?["fragment"] as? [String: Any]
        XCTAssertEqual(fragSettings?["packets"] as? String, "tlshello")
        let proxy = outbounds.first ?? [:]
        let stream = proxy["streamSettings"] as? [String: Any] ?? [:]
        let sockopt = stream["sockopt"] as? [String: Any] ?? [:]
        XCTAssertEqual(sockopt["dialerProxy"] as? String, "fragment")

        // Reality must NOT fragment: slicing a borrowed ClientHello adds a failure mode for nothing.
        let rjson = CoreProfiles.xray(try CoreProfiles.specFor(realityNode(), tune: tune))
        let rout = rjson["outbounds"] as? [[String: Any]] ?? []
        XCTAssertNil(rout.first { $0["tag"] as? String == "fragment" })
    }

    func testMixedInboundOnLoopback() throws {
        let json = CoreProfiles.xray(try CoreProfiles.specFor(realityNode(), tune: .default))
        let inbounds = json["inbounds"] as? [[String: Any]] ?? []
        let first = inbounds.first ?? [:]
        XCTAssertEqual(first["protocol"] as? String, "mixed")
        XCTAssertEqual(first["listen"] as? String, "127.0.0.1")
        XCTAssertEqual(first["port"] as? Int, CoreProfiles.localPort)
    }

    func testTripleDoh() throws {
        let json = CoreProfiles.xray(try CoreProfiles.specFor(realityNode(), tune: .default))
        let dns = json["dns"] as? [String: Any] ?? [:]
        let servers = dns["servers"] as? [[String: Any]] ?? []
        XCTAssertEqual(servers.count, 3)
        XCTAssertEqual(servers.first?["address"] as? String, "https://1.1.1.1/dns-query")
    }

    func testPingConfigStripsAppOnlyEchoesButKeepsFragment() throws {
        var tune = Tune.forRegime(.tight)
        tune.fragSize = 200
        let ping = try CoreProfiles.pingConfigJson(plainTlsWsNode(), tune: tune)
        XCTAssertFalse(ping.contains("meelano"), "ping configs must carry no app-only fields")
        XCTAssertTrue(ping.contains("\"fragment\""), "a fragmented proxy needs its freedom dialer")
    }

    func testMuxNeverUnderReality() throws {
        var tune = Tune.forRegime(.tight)
        tune.mux = true
        let spec = try CoreProfiles.specFor(realityNode(), tune: tune)
        XCTAssertFalse(spec.mux)
    }

    func testInsecureRefused() {
        var n = plainTlsWsNode()
        n.insecure = true
        XCTAssertThrowsError(try CoreProfiles.specFor(n, tune: .default))
    }

    func testRemovedTransportsRefused() {
        var n = plainTlsWsNode()
        n.network = "quic"
        XCTAssertThrowsError(try CoreProfiles.specFor(n, tune: .default))
    }
}
