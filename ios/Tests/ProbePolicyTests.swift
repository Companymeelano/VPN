import XCTest

final class ProbePolicyTests: XCTestCase {

    func testUnknownRttUsesMobileSafeMiddle() {
        XCTAssertEqual(ProbePolicy.tcpTimeoutMs(previousMedianRttMs: -1), 2200)
        XCTAssertEqual(ProbePolicy.tlsTimeoutMs(previousMedianRttMs: -1), 3200)
        XCTAssertEqual(ProbePolicy.tcpTimeoutMs(previousMedianRttMs: 0), 2200)
    }

    func testFloorsKeepAFastNetworkFast() {
        // 80 ms median -> 4x = 320 ms would be too aggressive; the 1500 floor applies.
        XCTAssertEqual(ProbePolicy.tcpTimeoutMs(previousMedianRttMs: 80), 1500)
        XCTAssertEqual(ProbePolicy.tlsTimeoutMs(previousMedianRttMs: 80), 2200)
    }

    func testCeilingsKeepADeadNetworkFromSerialWaits() {
        XCTAssertEqual(ProbePolicy.tcpTimeoutMs(previousMedianRttMs: 5000), 3500)
        XCTAssertEqual(ProbePolicy.tlsTimeoutMs(previousMedianRttMs: 5000), 5000)
    }

    func testSlowCarrierGetsItsPhysics() {
        // Irancell under load: 800 ms median must keep a ~3.2 s TCP budget, not the old 1200 ms.
        XCTAssertEqual(ProbePolicy.tcpTimeoutMs(previousMedianRttMs: 800), 3200)
        XCTAssertEqual(ProbePolicy.tlsTimeoutMs(previousMedianRttMs: 800), 5000)
    }

    func testAwaitCapAddsSchedulingSlack() {
        XCTAssertEqual(ProbePolicy.awaitCapMs(timeoutMs: 2200), 3100)
    }
}
