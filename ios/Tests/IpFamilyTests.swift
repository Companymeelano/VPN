import XCTest

final class IpFamilyTests: XCTestCase {

    func testGlobalUnicastAccepted() {
        XCTAssertTrue(IpFamily.isGlobalIpv6Literal("2001:4860:4860::8888"))
        XCTAssertTrue(IpFamily.isGlobalIpv6Literal("2a01:4f8::1"))
        XCTAssertTrue(IpFamily.isGlobalIpv6Literal("2001:4860:8888::8844"))
    }

    func testDocumentationRangeRefused() {
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("2001:db8::1"))
    }

    func testLoopbackLinkLocalUlaMulticastRefused() {
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("::1"))
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("::"))
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("fe80::1"))
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("fc00::1"))
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("ff02::1"))
    }

    func testV4MappedRefused() {
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("::ffff:1.2.3.4"))
    }

    func testHostnamesNeverParse() {
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("example.com"))
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("localhost"))
        XCTAssertFalse(IpFamily.isGlobalIpv6Literal("1.2.3.4"))
    }

    func testBracketedAndScopedForms() {
        XCTAssertTrue(IpFamily.isGlobalIpv6Literal("[2001:4860:4860::8888]"))
        XCTAssertTrue(IpFamily.isGlobalIpv6Literal("2001:4860:4860::8888%en0"))
    }

    func testParserRoundTripsGroups() {
        XCTAssertEqual(IpFamily.parseIpv6("::1")?.count, 16)
        XCTAssertEqual(IpFamily.parseIpv6("2001:db8:0:0:0:0:0:1")?.count, 16)
        XCTAssertEqual(IpFamily.parseIpv6("::ffff:1.2.3.4")?.count, 16)
        XCTAssertEqual(IpFamily.parseIpv6("2001:4860:4860::8.8.8.8")?.count, 16)
        XCTAssertNil(IpFamily.parseIpv6("2001:db8::1::2"))
        XCTAssertNil(IpFamily.parseIpv6("gggg::1"))
    }
}
