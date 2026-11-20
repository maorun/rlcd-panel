import Foundation
import Testing
@testable import RlcdProtocol

struct StaleWatchTests {
    private final class Clock {
        var now = Date(timeIntervalSince1970: 1_000_000)
    }

    private func make() -> (StaleWatch, Clock) {
        let c = Clock()
        return (StaleWatch(clock: { c.now }), c)
    }

    @Test func neverValidGivesNoResult() {
        var (w, c) = make()
        #expect(w.update(tile: 2, valid: false) == nil)
        c.now.addTimeInterval(3600)
        #expect(w.update(tile: 2, valid: false) == nil)
    }

    @Test func lostAfterLimitOnce() {
        var (w, c) = make()
        #expect(w.update(tile: 2, valid: true) == nil)
        c.now.addTimeInterval(34 * 60)
        #expect(w.update(tile: 2, valid: false) == nil)
        c.now.addTimeInterval(60)
        #expect(w.update(tile: 2, valid: false) == .lost(2))
        c.now.addTimeInterval(60)
        #expect(w.update(tile: 2, valid: false) == nil)
    }

    @Test func backForNextValidFrame() {
        var (w, c) = make()
        _ = w.update(tile: 2, valid: true)
        c.now.addTimeInterval(StaleWatch.lostAfter)
        #expect(w.update(tile: 2, valid: false) == .lost(2))
        #expect(w.update(tile: 2, valid: true) == .back(2))
        #expect(w.update(tile: 2, valid: true) == nil)
    }

    @Test func tileOutsideRangeGivesNoResult() {
        var (w, c) = make()
        #expect(w.update(tile: 11, valid: true) == nil)
        c.now.addTimeInterval(3600)
        #expect(w.update(tile: 11, valid: false) == nil)
        #expect(w.update(tile: -1, valid: false) == nil)
    }

    @Test func clockRunningBackGivesNoResult() {
        var (w, c) = make()
        _ = w.update(tile: 0, valid: true)
        c.now.addTimeInterval(-3600)
        #expect(w.update(tile: 0, valid: false) == nil)
        c.now.addTimeInterval(34 * 60)
        #expect(w.update(tile: 0, valid: false) == nil)
        c.now.addTimeInterval(60)
        #expect(w.update(tile: 0, valid: false) == .lost(0))
    }

    @Test func tilesKeepSeparateStates() {
        var (w, c) = make()
        _ = w.update(tile: 1, valid: true)
        _ = w.update(tile: 4, valid: true)
        c.now.addTimeInterval(StaleWatch.lostAfter)
        #expect(w.update(tile: 1, valid: false) == .lost(1))
        #expect(w.update(tile: 4, valid: true) == nil)
        #expect(w.update(tile: 4, valid: false) == nil)
    }

    @Test func lostFiresAgainAfterBack() {
        var (w, c) = make()
        _ = w.update(tile: 3, valid: true)
        c.now.addTimeInterval(StaleWatch.lostAfter)
        #expect(w.update(tile: 3, valid: false) == .lost(3))
        #expect(w.update(tile: 3, valid: true) == .back(3))
        c.now.addTimeInterval(StaleWatch.lostAfter)
        #expect(w.update(tile: 3, valid: false) == .lost(3))
    }
}
