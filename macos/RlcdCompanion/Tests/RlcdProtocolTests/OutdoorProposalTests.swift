import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct OutdoorProposalTests {
    static func rows(_ sensor: Int, days: Int, min: Double, max: Double) -> [WeekRow] {
        (0..<days).map { WeekRow(sensor: sensor, day: $0, date: "", temperatureMinC: min, temperatureMaxC: max,
                                 humidityMinPct: nil, humidityMaxPct: nil) }
    }

    /// Onboard 19 to 22 C, slot 1 18 to 21 C, slot 2 outdoors with -2 to 12 C.
    static var week: [WeekRow] {
        rows(0, days: 5, min: 19, max: 22) + rows(1, days: 5, min: 18, max: 21) + rows(2, days: 5, min: -2, max: 12)
    }

    @Test func clearProposal() throws {
        let p = try #require(OutdoorProposal.propose(week: Self.week, config: ConfigReply(values: ["outdoor_slot": "0"])))
        #expect(p.slot == 2)
        #expect(p.rangeC == 14)
        #expect(p.nextRangeC == 3)
        #expect(p.minC == -2)
        #expect(p.name == "s2")
        #expect(p.onboardMinC == 19)
        #expect(p.text == "Outdoor slot 2 (s2): range 14.0 C, next 3.0 C, minimum -2.0 C, onboard minimum 19.0 C")
    }

    @Test func noProposalForEqualRanges() {
        let week = Self.rows(0, days: 5, min: 19, max: 22) + Self.rows(1, days: 5, min: -2, max: 12) + Self.rows(2, days: 5, min: -2, max: 12)
        #expect(OutdoorProposal.propose(week: week, config: nil) == nil)
    }

    @Test func noProposalForFewerThanThreeDays() {
        let week = Self.rows(0, days: 5, min: 19, max: 22) + Self.rows(2, days: 2, min: -2, max: 12)
        #expect(OutdoorProposal.propose(week: week, config: nil) == nil)
    }

    @Test func noProposalForWarmSlot() {
        let week = Self.rows(0, days: 5, min: 15, max: 22) + Self.rows(1, days: 5, min: 18, max: 21) + Self.rows(2, days: 5, min: 16, max: 30)
        #expect(OutdoorProposal.propose(week: week, config: nil) == nil)
    }

    @Test func noProposalForActiveValue() {
        #expect(OutdoorProposal.propose(week: Self.week, config: ConfigReply(values: ["outdoor_slot": "2"])) == nil)
    }

    @Test func assistantAppliesThroughConfigBatch() {
        var a = SetupAssistantTests.assigned()
        _ = a.checkReception()
        a.receive(.frame(lines: SetupAssistantTests.statusLines(slot2: "state=OK", mac: SetupAssistantTests.newMac)))
        #expect(a.step == .calibrate)
        let config = ConfigReply(values: ["outdoor_slot": "0"])
        #expect(a.outdoorProposal(week: Self.week, config: config)?.slot == 2)
        #expect(a.beginApplyOutdoor(week: Self.week, config: config) == .set(key: "outdoor_slot", value: "2"))
        a.receive(.status("OK SET outdoor_slot"))
        #expect(a.nextCommand() == .config)
        a.receive(.frame(lines: ["outdoor_slot=2"]))
        #expect(a.step == .calibrate)
        #expect(a.stop == nil)
        #expect(a.beginCalibrate(reference: 0) == .calib(0))
    }

    @Test func noProposalOutsideCalibrateStepAndNoApply() {
        var a = SetupAssistant()
        #expect(a.outdoorProposal(week: Self.week, config: nil) == nil)
        #expect(a.beginApplyOutdoor(week: Self.week) == nil)
    }

    @Test func rangeExactlyTwiceTheNextSlotQualifiesAndJustBelowDoesNot() {
        let base = Self.rows(0, days: 5, min: 19, max: 22)
        let exact = base + Self.rows(1, days: 5, min: 10, max: 14) + Self.rows(2, days: 5, min: -2, max: 6)
        #expect(OutdoorProposal.propose(week: exact, config: nil)?.slot == 2)
        let below = base + Self.rows(1, days: 5, min: 10, max: 14.5) + Self.rows(2, days: 5, min: -2, max: 6)
        #expect(OutdoorProposal.propose(week: below, config: nil) == nil)
    }

    private static func calibrateAssistant() -> SetupAssistant {
        var a = SetupAssistantTests.assigned()
        _ = a.checkReception()
        a.receive(.frame(lines: SetupAssistantTests.statusLines(slot2: "state=OK", mac: SetupAssistantTests.newMac)))
        return a
    }

    @Test func errorOnOutdoorSetStopsAndLinkLossClearsBatch() {
        var a = Self.calibrateAssistant()
        _ = a.beginApplyOutdoor(week: Self.week)
        a.receive(.status("ERR READ_ONLY"))
        #expect(a.isStopped)
        #expect(a.nextCommand() == nil)
        var b = Self.calibrateAssistant()
        _ = b.beginApplyOutdoor(week: Self.week)
        b.linkLost("gone")
        #expect(b.isStopped)
        #expect(b.beginApplyOutdoor(week: Self.week) == nil)
    }

    @Test func secondApplyAfterSuccessIsAllowedAndProposalVanishesWithActiveValue() {
        var a = Self.calibrateAssistant()
        _ = a.beginApplyOutdoor(week: Self.week)
        a.receive(.status("OK SET outdoor_slot"))
        _ = a.nextCommand()
        a.receive(.frame(lines: ["outdoor_slot=2"]))
        #expect(a.step == .calibrate)
        #expect(a.beginApplyOutdoor(week: Self.week) == nil)
        #expect(a.notice == "No outdoor proposal")
    }
}
