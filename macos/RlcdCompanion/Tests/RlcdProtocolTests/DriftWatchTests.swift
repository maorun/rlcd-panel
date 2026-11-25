import Foundation
import Testing
@testable import RlcdProtocol

struct DriftWatchTests {
    @Test func firstReadGivesNoTile() {
        var w = DriftWatch()
        #expect(w.update(mask: 0x402).isEmpty)
    }

    @Test func risingBitGivesItsTile() {
        var w = DriftWatch()
        #expect(w.update(mask: 0x000).isEmpty)
        #expect(w.update(mask: 0x002) == [1])
        #expect(w.update(mask: 0x002).isEmpty)
        #expect(w.update(mask: 0x403) == [0, 10])
    }

    @Test func fallingBitGivesNoTile() {
        var w = DriftWatch()
        _ = w.update(mask: 0x006)
        #expect(w.update(mask: 0x002).isEmpty)
        #expect(w.update(mask: 0x000).isEmpty)
        #expect(w.update(mask: 0x004) == [2])
    }

    @Test func nilMaskGivesNoTileAndKeepsState() {
        var w = DriftWatch()
        #expect(w.update(mask: nil).isEmpty)
        #expect(w.update(mask: 0x002).isEmpty) // the first real read is still the baseline
        #expect(w.update(mask: nil).isEmpty)
        #expect(w.update(mask: 0x002).isEmpty)
        #expect(w.update(mask: 0x006) == [2])
    }

    @Test func bitsAbove10AreIgnored() {
        var w = DriftWatch()
        _ = w.update(mask: 0)
        #expect(w.update(mask: 0x800).isEmpty)
    }

    @Test func notificationBodyNamesTheSensor() {
        #expect(DriftWatch.notificationBody(tile: 0, config: nil) == "Sensor drift: Onboard. Open the calibration.")
        #expect(DriftWatch.notificationBody(tile: 3, config: nil) == "Sensor drift: Slot 3. Open the calibration.")
    }
}
