import Foundation
import Testing
@testable import RlcdProtocol

struct ReceptionWatchTests {
    @Test func firstReadGivesNoSlot() {
        var w = ReceptionWatch()
        #expect(w.update(mask: 0b101).isEmpty)
    }

    @Test func risingBitGivesSlot() {
        var w = ReceptionWatch()
        _ = w.update(mask: 0)
        #expect(w.update(mask: 0b1001) == [1, 4])
    }

    @Test func equalMaskGivesNoSlot() {
        var w = ReceptionWatch()
        _ = w.update(mask: 0b10)
        #expect(w.update(mask: 0b10).isEmpty)
    }

    @Test func fallingBitGivesNoSlotAndRisesAgain() {
        var w = ReceptionWatch()
        _ = w.update(mask: 0b10)
        #expect(w.update(mask: 0).isEmpty)
        #expect(w.update(mask: 0b10) == [2])
    }

    @Test func nilMaskGivesNoSlotAndKeepsMask() {
        var w = ReceptionWatch()
        _ = w.update(mask: 0b10)
        #expect(w.update(mask: nil).isEmpty)
        #expect(w.update(mask: 0b10).isEmpty)
        #expect(w.update(mask: 0b110) == [3])
    }

    @Test func nilMaskBeforeFirstReadStaysFirstRead() {
        var w = ReceptionWatch()
        #expect(w.update(mask: nil).isEmpty)
        #expect(w.update(mask: 0b1).isEmpty)
    }

    @Test func slotTenIsReported() {
        var w = ReceptionWatch()
        _ = w.update(mask: 0)
        #expect(w.update(mask: 1 << 9) == [10])
    }

    @Test func bitsAboveSlotTenGiveNoSlot() {
        var w = ReceptionWatch()
        _ = w.update(mask: 0)
        #expect(w.update(mask: 1 << 10).isEmpty)
    }

    @Test func notificationBodyFallsBackToSlotNumber() {
        #expect(ReceptionWatch.notificationBody(slot: 4, config: nil) == "Weak signal: Slot 4")
    }

    @Test func notificationBodyNamesTheSensorOfItsSlot() {
        let config = ConfigReply(values: [
            "slot1_mac": "AA:BB:CC:DD:EE:01", "slot1_name": "Kitchen",
            "slot2_mac": "AA:BB:CC:DD:EE:02", "slot2_name": "Garage",
        ])
        #expect(ReceptionWatch.notificationBody(slot: 2, config: config) == "Weak signal: Garage")
        #expect(ReceptionWatch.notificationBody(slot: 1, config: config) == "Weak signal: Kitchen")
        #expect(ReceptionWatch.notificationBody(slot: 3, config: config) == "Weak signal: Slot 3")
    }

    /// Reads tests/fixtures/status_reply_sample.txt, the file of the firmware and phone tests.
    @Test func fixtureRxlowMaskGivesItsSlots() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let text = try String(contentsOf: url.appendingPathComponent("tests/fixtures/status_reply_sample.txt"), encoding: .utf8)
        let lines = text.split(whereSeparator: \.isNewline).map(String.init).filter { !$0.hasPrefix("CRC32") }
        let latch = try DiagnosticsReply.parse(status: StatusReply.parse(lines: lines)).latch
        #expect(latch?.receptionLow == 0x202)
        var w = ReceptionWatch()
        _ = w.update(mask: 0)
        #expect(w.update(mask: latch?.receptionLow) == [2, 10])
    }
}
