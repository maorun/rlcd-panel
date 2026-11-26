import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct DoorProposalTests {
    /// `days` rows of one sensor with a humidity maximum of `max` %RH.
    static func rows(_ sensor: Int, days: Int, max: Double) -> [WeekRow] {
        (0..<days).map { WeekRow(sensor: sensor, day: $0, date: "", temperatureMinC: nil, temperatureMaxC: nil,
                                 humidityMinPct: max - 10, humidityMaxPct: max) }
    }

    /// Slot 1 is the door room; the slots 2, 3 and 4 have the mean maxima 70, 52 and 60 %RH.
    static var week: [WeekRow] {
        rows(1, days: 5, max: 65) + rows(2, days: 5, max: 70) + rows(3, days: 5, max: 52) + rows(4, days: 5, max: 60)
    }

    static let config = ConfigReply(values: ["door_ref_slot": "0", "outdoor_slot": "0"])

    @Test func proposesTheDriestMaximum() throws {
        let p = try #require(DoorProposal.propose(week: Self.week, config: Self.config))
        #expect(p.slot == 3)
        #expect(p.meanMaxPct == 52)
        #expect(p.nextMaxPct == 60)
        #expect(p.text == "Door reference slot 3 (s3): 52.0 %RH, next 60.0 %RH")
    }

    @Test func noProposalForADifferenceOfTwoPercent() {
        let week = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 54) + Self.rows(3, days: 5, max: 52)
        #expect(DoorProposal.propose(week: week, config: Self.config) == nil)
    }

    @Test func differenceOfExactlyThreePercentQualifies() {
        let week = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 55) + Self.rows(3, days: 5, max: 52)
        #expect(DoorProposal.propose(week: week, config: Self.config)?.slot == 3)
    }

    @Test func outdoorSlotIsNoCandidate() throws {
        let config = ConfigReply(values: ["door_ref_slot": "0", "outdoor_slot": "3"])
        // Without slot 3 the slots 2 and 4 stay: 60 and 70 %RH.
        let week = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 70) + Self.rows(3, days: 5, max: 52) + Self.rows(4, days: 5, max: 60)
        let p = try #require(DoorProposal.propose(week: week, config: config))
        #expect(p.slot == 4)
        #expect(p.nextMaxPct == 70)
    }

    @Test func noProposalWithTwoDaysOfData() {
        let week = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 70) + Self.rows(3, days: 2, max: 40)
        // Slot 3 has too few days: one candidate is left.
        #expect(DoorProposal.propose(week: week, config: Self.config) == nil)
    }

    @Test func noProposalWithoutReadingOfSlotOne() {
        let week = Self.rows(2, days: 5, max: 70) + Self.rows(3, days: 5, max: 52)
        #expect(DoorProposal.propose(week: week, config: Self.config) == nil)
        let two = Self.rows(1, days: 2, max: 65) + Self.rows(2, days: 5, max: 70) + Self.rows(3, days: 5, max: 52)
        #expect(DoorProposal.propose(week: two, config: Self.config) == nil)
    }

    @Test func noProposalForASingleCandidate() {
        let week = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 70)
        #expect(DoorProposal.propose(week: week, config: Self.config) == nil)
    }

    @Test func gapDaysWithoutHumidityPairDoNotCount() {
        var week = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 70)
        week += Self.rows(3, days: 5, max: 52).enumerated().map { i, r in
            i < 3 ? r : WeekRow(sensor: 3, day: r.day, date: "", temperatureMinC: nil, temperatureMaxC: nil,
                                humidityMinPct: nil, humidityMaxPct: nil)
        }
        #expect(DoorProposal.propose(week: week, config: Self.config)?.slot == 3)
        let fewer = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 70) + Self.rows(3, days: 2, max: 52)
        #expect(DoorProposal.propose(week: fewer, config: Self.config) == nil)
    }

    @Test func noProposalForActiveValue() {
        let config = ConfigReply(values: ["door_ref_slot": "3", "outdoor_slot": "0"])
        #expect(DoorProposal.propose(week: Self.week, config: config) == nil)
    }

    @Test func noProposalForACandidateWetterThanSlotOne() {
        let week = Self.rows(1, days: 5, max: 50) + Self.rows(2, days: 5, max: 70) + Self.rows(3, days: 5, max: 60)
        #expect(DoorProposal.propose(week: week, config: Self.config) == nil)
    }

    @Test func noProposalForATie() {
        let week = Self.rows(1, days: 5, max: 65) + Self.rows(2, days: 5, max: 52) + Self.rows(3, days: 5, max: 52)
        #expect(DoorProposal.propose(week: week, config: Self.config) == nil)
    }

    @Test func paddedActiveValueCountsAsActive() {
        let config = ConfigReply(values: ["door_ref_slot": " 3 ", "outdoor_slot": "0"])
        #expect(DoorProposal.propose(week: Self.week, config: config) == nil)
    }

    @Test func usesConfiguredSlotName() throws {
        let config = ConfigReply(values: ["door_ref_slot": "0", "slot3_name": "KELLER"])
        #expect(try #require(DoorProposal.propose(week: Self.week, config: config)).name == "KELLER")
    }

    private static func calibrateAssistant() -> SetupAssistant {
        var a = SetupAssistantTests.assigned()
        _ = a.checkReception()
        a.receive(.frame(lines: SetupAssistantTests.statusLines(slot2: "state=OK", mac: SetupAssistantTests.newMac)))
        return a
    }

    @Test func assistantAppliesThroughConfigBatch() {
        var a = Self.calibrateAssistant()
        #expect(a.doorProposal(week: Self.week, config: Self.config)?.slot == 3)
        #expect(a.beginApplyDoor(week: Self.week, config: Self.config) == .set(key: "door_ref_slot", value: "3"))
        a.receive(.status("OK SET door_ref_slot"))
        #expect(a.nextCommand() == .config)
        a.receive(.frame(lines: ["door_ref_slot=3"]))
        #expect(a.step == .calibrate)
        #expect(a.stop == nil)
        #expect(a.beginCalibrate(reference: 0) == .calib(0))
    }

    @Test func noProposalOutsideCalibrateStepAndNoApply() {
        var a = SetupAssistant()
        #expect(a.doorProposal(week: Self.week, config: Self.config) == nil)
        #expect(a.beginApplyDoor(week: Self.week, config: Self.config) == nil)
    }

    @Test func applyWithoutProposalSendsNothing() {
        var a = Self.calibrateAssistant()
        #expect(a.beginApplyDoor(week: [], config: Self.config) == nil)
        #expect(a.notice == "No door proposal")
    }

    @Test func errorOnDoorSetStops() {
        var a = Self.calibrateAssistant()
        _ = a.beginApplyDoor(week: Self.week, config: Self.config)
        a.receive(.status("ERR READ_ONLY"))
        #expect(a.isStopped)
        #expect(a.nextCommand() == nil)
    }
}
