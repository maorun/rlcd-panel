import Foundation
import Testing
@testable import RlcdProtocol

struct StuckWatchTests {
    @Test func firstReadGivesNoTile() {
        var w = StuckWatch()
        #expect(w.update(mask: 0x402).isEmpty)
    }

    @Test func risingBitGivesItsTile() {
        var w = StuckWatch()
        #expect(w.update(mask: 0x000).isEmpty)
        #expect(w.update(mask: 0x002) == [1])
        #expect(w.update(mask: 0x002).isEmpty)
        #expect(w.update(mask: 0x403) == [0, 10])
    }

    @Test func fallingBitGivesNoTile() {
        var w = StuckWatch()
        _ = w.update(mask: 0x006)
        #expect(w.update(mask: 0x002).isEmpty)
        #expect(w.update(mask: 0x000).isEmpty)
        #expect(w.update(mask: 0x004) == [2])
    }

    @Test func nilMaskGivesNoTileAndKeepsState() {
        var w = StuckWatch()
        #expect(w.update(mask: nil).isEmpty)
        #expect(w.update(mask: 0x002).isEmpty) // the first real read is still the baseline
        #expect(w.update(mask: nil).isEmpty)
        #expect(w.update(mask: 0x002).isEmpty)
        #expect(w.update(mask: 0x006) == [2])
    }

    @Test func bitsAbove10AreIgnored() {
        var w = StuckWatch()
        _ = w.update(mask: 0)
        #expect(w.update(mask: 0x800).isEmpty)
    }

    @Test func notificationBodyNamesTheSensor() {
        #expect(StuckWatch.notificationBody(tile: 0, config: nil) == "Sensor frozen: Onboard. It sends the same values.")
        #expect(StuckWatch.notificationBody(tile: 3, config: nil) == "Sensor frozen: Slot 3. It sends the same values.")
    }

    @Test func parsesStuckField() throws {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=000 rxlow=000 drift=000 heat=000"
        let m = try LatchMetrics.parse(line: base + " stuck=402")
        #expect(m.stuck == 0x402)
        #expect(m.hasStuck(sensor: 1))
        #expect(m.hasStuck(sensor: 10))
        #expect(!m.hasStuck(sensor: 0))
        #expect(!m.hasStuck(sensor: 11))
        #expect(!m.hasStuck(sensor: -1))
        #expect((0...10).allSatisfy { try! LatchMetrics.parse(line: base + " stuck=7FF").hasStuck(sensor: $0) })
    }

    @Test func stuckIsNilWithoutField() throws {
        let m = try LatchMetrics.parse(line: "LATCH vent=002 dry=000 batlow=008 stale=004 wall=002 window=402 rxlow=202 drift=002 heat=001")
        #expect(m.stuck == nil)
        #expect(!m.hasStuck(sensor: 0))
    }

    @Test func stuckRejectsBadFields() {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=000 rxlow=000 drift=000 heat=000"
        for bad in [base + " stuck=zzz", base + " stuck=800", base + " stuck=00", base + " stuck=0x1", base + " stuck="] {
            #expect(throws: ParseError.badLine(bad)) { try LatchMetrics.parse(line: bad) }
        }
    }
}
