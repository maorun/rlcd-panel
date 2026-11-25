import Foundation
import Testing
@testable import RlcdProtocol

private func drop(_ time: String, _ slot: Int) -> EventRow {
    EventRow(time: time, event: "JUMP_DROP", detail: slot)
}

private func config(_ limit: String?) -> ConfigReply {
    var values = ["slot2_name": "Garten", "slot2_mac": "AA:BB:CC:DD:EE:02"]
    if let limit { values["jump_limit_c"] = limit }
    return ConfigReply(values: values)
}

@Suite struct JumpAdviceTests {
    let now = AlertTimeline.parse(time: "2026-01-02 12:00:00")!

    @Test func threeDropsOfSlot2GiveOneAdvice() {
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 2)]
        let advice = JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now)
        #expect(advice.count == 1)
        #expect(advice.first?.slot == 2)
        #expect(advice.first?.drops == 3)
        #expect(advice.first?.name == "Garten")
        #expect(advice.first?.change == ConfigChange(key: "jump_limit_c", old: "8.0", new: "10.0"))
    }

    @Test func twoDropsGiveNoAdvice() {
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now).isEmpty)
    }

    @Test func dropsOlderThan24HoursGiveNoAdvice() {
        let rows = [drop("2026-01-01 11:00:00", 2), drop("2026-01-01 10:00:00", 2), drop("2026-01-01 09:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now).isEmpty)
    }

    @Test func dropsOfDifferentSlotsAreNotSummed() {
        let rows = [drop("2026-01-02 06:00:00", 1), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 3)]
        #expect(JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now).isEmpty)
    }

    @Test func otherEventsAndBadSlotsAreIgnored() {
        // Two valid drops of slot 2: each wrongly counted row would reach 3.
        let rows = [
            drop("2026-01-02 06:00:00", 0), drop("2026-01-02 07:00:00", 11), drop("2026-01-02 08:00:00", 2),
            drop("2026-01-02 08:30:00", 2),
            EventRow(time: "2026-01-02 09:00:00", event: "SLOT_STALE", detail: 2),
            EventRow(time: "", event: "JUMP_DROP", detail: 2),
            EventRow(time: "2026-01-02 13:00:00", event: "JUMP_DROP", detail: 2), // in the future
        ]
        #expect(JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now).isEmpty)
    }

    @Test func windowEdgeIs24HoursInclusive() {
        let edge = [drop("2026-01-01 12:00:00", 2), drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: edge, config: config("8.0"), now: now).first?.drops == 3)
        let past = [drop("2026-01-01 11:59:59", 2), drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: past, config: config("8.0"), now: now).isEmpty)
    }

    @Test func twoSlotsGiveAdviceInAscendingOrder() {
        let rows = (0..<3).flatMap { i in
            [drop("2026-01-02 0\(6 + i):00:00", 5), drop("2026-01-02 0\(6 + i):10:00", 2)]
        }
        #expect(JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now).map(\.slot) == [2, 5])
    }

    @Test func nilConfigAndBadLimitGiveNoAdvice() {
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: rows, config: nil, now: now).isEmpty)
        #expect(JumpAdvice.evaluate(events: rows, config: config("abc"), now: now).isEmpty)
    }

    @Test func dropsBeforeLimitChangeDoNotCount() throws {
        let index = try #require(ConfigSchema.firmwareKeys.firstIndex(of: "jump_limit_c"))
        let set = EventRow(time: "2026-01-02 11:00:00", event: "CONFIG_SET", detail: index)
        let old = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: old + [set], config: config("10.0"), now: now).isEmpty)
        // A change of another key does not reset the count.
        let other = EventRow(time: "2026-01-02 11:00:00", event: "CONFIG_SET", detail: index + 1)
        #expect(JumpAdvice.evaluate(events: old + [other], config: config("10.0"), now: now).count == 1)
        // New drops after the change count again.
        let again = old + [set] + [drop("2026-01-02 11:10:00", 2), drop("2026-01-02 11:20:00", 2), drop("2026-01-02 11:30:00", 2)]
        #expect(JumpAdvice.evaluate(events: again, config: config("10.0"), now: now).first?.change.new == "12.0")
    }

    @Test func bleSetRowAndSameSecondDropCount() throws {
        let index = try #require(ConfigSchema.firmwareKeys.firstIndex(of: "jump_limit_c"))
        // Transport BLE is the high byte 1; a drop in the same second as the change belongs to the old limit.
        let set = EventRow(time: "2026-01-02 11:00:00", event: "CONFIG_SET", detail: 0x0100 | index)
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 11:00:00", 2), set]
        #expect(JumpAdvice.evaluate(events: rows, config: config("10.0"), now: now).isEmpty)
    }

    @Test func textNamesSlotCountAndChange() {
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 2)]
        let text = JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now).first?.text
        #expect(text == "Garten: the jump guard dropped 3 readings in 24 hours. Raise jump_limit_c: 8.0 -> 10.0.")
    }

    @Test func proposalAddsTwoAndClampsAt20() {
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: rows, config: config("8.0"), now: now).first?.change.new == "10.0")
        #expect(JumpAdvice.evaluate(events: rows, config: config("19.0"), now: now).first?.change.new == "20.0")
        #expect(JumpAdvice.evaluate(events: rows, config: config("20.0"), now: now).isEmpty)
    }

    @Test func limitZeroGivesNoAdvice() {
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: rows, config: config("0"), now: now).isEmpty)
        #expect(JumpAdvice.evaluate(events: rows, config: config("0.0"), now: now).isEmpty)
    }

    @Test func missingKeyUsesDefault8() {
        let rows = [drop("2026-01-02 06:00:00", 2), drop("2026-01-02 08:00:00", 2), drop("2026-01-02 10:00:00", 2)]
        #expect(JumpAdvice.evaluate(events: rows, config: config(nil), now: now).first?.change.old == "8.0")
    }
}
