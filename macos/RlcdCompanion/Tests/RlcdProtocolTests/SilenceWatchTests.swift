import Foundation
import Testing
@testable import RlcdProtocol

private final class SilenceClock {
    var now = Date(timeIntervalSince1970: 1_000_000)
    func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds) }
}

@Suite final class SilenceWatchTests {
    private let clock = SilenceClock()
    private var watch: SilenceWatch

    init() {
        let c = clock
        watch = SilenceWatch(clock: { c.now })
    }

    @Test func testNoReportWithoutFirstFrame() {
        clock.advance(10 * 3600)
        #expect(watch.check() == .none)
        #expect(watch.lastFrameAt == nil)
    }

    @Test func testSilentOnceAfterLimit() {
        #expect(watch.onFrame() == .none)
        clock.advance(35 * 60)
        #expect(watch.check() == .none)
        clock.advance(1)
        #expect(watch.check() == .silent)
        clock.advance(25 * 60 - 1)
        #expect(watch.check() == .none)
    }

    @Test func testBackOnceAtNextFrame() {
        _ = watch.onFrame()
        clock.advance(35 * 60 + 1)
        #expect(watch.check() == .silent)
        #expect(watch.onFrame() == .back)
        #expect(watch.onFrame() == .none)
    }

    @Test func testSilentAgainAfterBack() {
        _ = watch.onFrame()
        clock.advance(35 * 60 + 1)
        #expect(watch.check() == .silent)
        #expect(watch.onFrame() == .back)
        clock.advance(35 * 60 + 1)
        #expect(watch.check() == .silent)
    }

    @Test func testClockRunningBackGivesNoReport() {
        _ = watch.onFrame()
        clock.advance(-3600)
        #expect(watch.check() == .none)
        clock.advance(35 * 60)
        #expect(watch.check() == .none)
        clock.advance(1)
        #expect(watch.check() == .silent)
    }
}
