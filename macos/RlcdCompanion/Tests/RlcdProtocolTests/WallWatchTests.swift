import Foundation
import Testing
@testable import RlcdProtocol

struct WallWatchTests {
    private final class Clock {
        var now = Date(timeIntervalSince1970: 1_000_000)
    }

    private func make() -> (WallWatch, Clock) {
        let c = Clock()
        return (WallWatch(clock: { c.now }), c)
    }

    @Test func riseGivesOneResult() {
        var (w, _) = make()
        #expect(w.update(tile: 1, rh: 79.9) == nil)
        #expect(w.update(tile: 1, rh: 80.0) == .notify(tile: 1, rh: 80.0))
        #expect(w.update(tile: 1, rh: 81.0) == nil)
    }

    @Test func releaseBelowLimit() {
        var (w, c) = make()
        #expect(w.update(tile: 1, rh: 80.0) != nil)
        #expect(w.update(tile: 1, rh: 77.0) == nil)
        #expect(w.update(tile: 1, rh: 76.9) == nil)
        c.now.addTimeInterval(WallWatch.repeatInterval)
        #expect(w.update(tile: 1, rh: 80.0) == .notify(tile: 1, rh: 80.0))
    }

    @Test func riseFiveHoursAfterNotifyGivesNoResult() {
        var (w, c) = make()
        #expect(w.update(tile: 2, rh: 85) != nil)
        #expect(w.update(tile: 2, rh: 70) == nil)
        c.now.addTimeInterval(5 * 3600)
        #expect(w.update(tile: 2, rh: 85) == nil)
    }

    @Test func riseSixHoursAfterNotifyGivesResult() {
        var (w, c) = make()
        #expect(w.update(tile: 2, rh: 85) != nil)
        #expect(w.update(tile: 2, rh: 70) == nil)
        c.now.addTimeInterval(6 * 3600)
        #expect(w.update(tile: 2, rh: 85) == .notify(tile: 2, rh: 85))
    }

    @Test func nilReleasesTile() {
        var (w, c) = make()
        #expect(w.update(tile: 0, rh: 90) != nil)
        #expect(w.update(tile: 0, rh: nil) == nil)
        c.now.addTimeInterval(6 * 3600)
        #expect(w.update(tile: 0, rh: 90) != nil)
    }

    @Test func nilReleaseKeepsRepeatWindow() {
        var (w, c) = make()
        #expect(w.update(tile: 0, rh: 90) != nil)
        #expect(w.update(tile: 0, rh: nil) == nil)
        c.now.addTimeInterval(3600)
        #expect(w.update(tile: 0, rh: 90) == nil)
    }

    @Test func tilesHaveSeparateStates() {
        var (w, _) = make()
        #expect(w.update(tile: 0, rh: 90) == .notify(tile: 0, rh: 90))
        #expect(w.update(tile: 3, rh: 90) == .notify(tile: 3, rh: 90))
        #expect(w.update(tile: 0, rh: 90) == nil)
        #expect(w.update(tile: 6, rh: 90) == nil)
    }
}
