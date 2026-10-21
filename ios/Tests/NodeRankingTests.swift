import XCTest

final class NodeRankingTests: XCTestCase {

    private func node(_ proto: String, tls: String?, network: String?, cc: String?, port: Int) -> FeedNode {
        FeedNode(id: "\(proto)-\(cc ?? "?")-\(port)", cc: cc,
                 proto: proto, host: "example.com", port: port,
                 tls: tls, network: network)
    }

    func testTransportLadderDominatesBucket() {
        // A slow Reality node must sort before a fast plaintext one: survivability beats speed.
        let reality = node("vless", tls: "reality", network: "tcp", cc: "US", port: 8443)
        let plain = node("vmess", tls: nil, network: "tcp", cc: "TR", port: 443)
        XCTAssertLessThan(NodeRanking.bucket(reality), NodeRanking.bucket(plain))
    }

    func testNeighbourhoodBeatsFarSideAtEqualTransport() {
        let istanbul = node("vless", tls: "reality", network: "tcp", cc: "TR", port: 443)
        let america = node("vless", tls: "reality", network: "tcp", cc: "US", port: 443)
        XCTAssertLessThan(NodeRanking.bucket(istanbul), NodeRanking.bucket(america))
    }

    func testPort443BreaksTies() {
        let web = node("vless", tls: "reality", network: "tcp", cc: "DE", port: 443)
        let odd = node("vless", tls: "reality", network: "tcp", cc: "DE", port: 8443)
        XCTAssertLessThan(NodeRanking.bucket(web), NodeRanking.bucket(odd))
    }

    func testDomesticExitNeverAutoPreferred() {
        let tehran = node("vless", tls: "reality", network: "tcp", cc: "IR", port: 443)
        let unknown = node("vless", tls: "reality", network: "tcp", cc: nil, port: 443)
        XCTAssertLessThan(NodeRanking.bucket(unknown), NodeRanking.bucket(tehran))
    }

    func testShadowsocksSpellingFoldsOntoSsLadderKey() {
        let a = node("shadowsocks", tls: nil, network: "tcp", cc: "DE", port: 443)
        let b = node("ss", tls: nil, network: "tcp", cc: "DE", port: 443)
        XCTAssertEqual(NodeRanking.transportIndex(a), NodeRanking.transportIndex(b))
    }

    func testNetworkSpellingNormalization() {
        let ws = node("vless", tls: "tls", network: "websocket", cc: "DE", port: 443)
        let ws2 = node("vless", tls: "tls", network: "ws", cc: "DE", port: 443)
        XCTAssertEqual(NodeRanking.transportIndex(ws), NodeRanking.transportIndex(ws2))
    }
}
