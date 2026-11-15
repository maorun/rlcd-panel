import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct CalibrationTests {
    static let good = [
        "REFERENCE onboard",
        "SENSOR slot1 hours=12 temp_offset=0.0 rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1",
        "SENSOR slot3 hours=6 temp_offset=-0.5 rh_offset=1.0 temp_diff=-0.2 rh_diff=3.0",
        "SKIP slot2 hours=3",
        "slot1_temp_offset_c=-0.4",
        "slot1_rh_offset_pct=2.1",
        "slot3_temp_offset_c=-0.3",
        "slot3_rh_offset_pct=-2.0",
    ]

    @Test func parsesRecordedReply() throws {
        let r = try CalibrationReply.parse(lines: Self.good)
        #expect(r.reference == "onboard")
        #expect(r.sensors.count == 2)
        #expect(r.sensors[0].name == "slot1")
        #expect(r.sensors[0].hours == 12)
        #expect(r.sensors[0].tempOffset == 0.0)
        #expect(r.sensors[0].rhOffset == 0.0)
        #expect(r.sensors[0].tempDiff == 0.4)
        #expect(r.sensors[0].rhDiff == -2.1)
        #expect(r.sensors[1].name == "slot3")
        #expect(r.sensors[1].hours == 6)
        #expect(r.sensors[1].tempOffset == -0.5)
        #expect(r.sensors[1].rhOffset == 1.0)
        #expect(r.sensors[1].tempDiff == -0.2)
        #expect(r.sensors[1].rhDiff == 3.0)
        #expect(r.skipped.count == 1)
        #expect(r.skipped[0].name == "slot2")
        #expect(r.skipped[0].hours == 3)
        #expect(r.proposals.map(\.key) == ["slot1_temp_offset_c", "slot1_rh_offset_pct", "slot3_temp_offset_c", "slot3_rh_offset_pct"])
        #expect(r.proposals[0].value == "-0.4")
    }

    @Test func proposesSlots4And5() throws {
        let r = try CalibrationReply.parse(lines: [
            "REFERENCE slot1",
            "SENSOR slot5 hours=8 temp_offset=0.0 rh_offset=0.0 temp_diff=0.5 rh_diff=-3.0",
            "slot5_temp_offset_c=-0.5",
            "slot5_rh_offset_pct=3.0",
        ])
        #expect(r.proposals.map(\.key) == ["slot5_temp_offset_c", "slot5_rh_offset_pct"])
        #expect(ConfigSchema.validate(key: "slot5_temp_offset_c", value: "-0.5") == nil)
        #expect(ConfigSchema.validate(key: "slot4_rh_offset_pct", value: "11") != nil)
    }

    @Test func acceptsFrameLines() throws {
        let r = try CalibrationReply.parse(lines: ["BEGIN CALIB"] + Self.good)
        #expect(r.reference == "onboard")
    }

    @Test func missingReferenceThrows() {
        #expect(throws: ParseError.missingLine("REFERENCE")) {
            try CalibrationReply.parse(lines: Array(Self.good.dropFirst()))
        }
    }

    @Test func nanOffsetThrowsBadLine() {
        let line = "SENSOR slot1 hours=12 temp_offset=nan rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1"
        #expect(throws: ParseError.badLine(line)) {
            try CalibrationReply.parse(lines: ["REFERENCE onboard", line])
        }
    }

    @Test func badHoursThrowsBadLine() {
        let line = "SENSOR slot1 hours=x temp_offset=0.0 rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1"
        #expect(throws: ParseError.badLine(line)) {
            try CalibrationReply.parse(lines: ["REFERENCE onboard", line])
        }
    }

    @Test func plusSignAndHexFloatThrowBadLine() {
        for bad in ["+1.0", "0x1p3", "1e2", ".5", "1."] {
            let line = "SENSOR slot1 hours=12 temp_offset=\(bad) rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1"
            #expect(throws: ParseError.badLine(line)) {
                try CalibrationReply.parse(lines: ["REFERENCE onboard", line])
            }
        }
    }

    @Test func extraWordThrowsBadLine() {
        let line = "SENSOR slot1 hours=12 temp_offset=0.0 rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1 extra"
        #expect(throws: ParseError.badLine(line)) {
            try CalibrationReply.parse(lines: ["REFERENCE onboard", line])
        }
    }

    @Test func missingFieldThrowsBadLine() {
        let line = "SENSOR slot1 hours=12 temp_offset=0.0 rh_offset=0.0 temp_diff=0.4"
        #expect(throws: ParseError.badLine(line)) {
            try CalibrationReply.parse(lines: ["REFERENCE onboard", line])
        }
    }

    @Test func skipWithBadHoursThrows() {
        #expect(throws: ParseError.badLine("SKIP slot2 hours=-1")) {
            try CalibrationReply.parse(lines: ["REFERENCE onboard", "SKIP slot2 hours=-1"])
        }
    }

    @Test func outOfRangeProposalIsDropped() throws {
        let r = try CalibrationReply.parse(lines: [
            "REFERENCE onboard",
            "slot1_temp_offset_c=9.0",
            "slot1_rh_offset_pct=2.1",
            "unknown_key=1",
        ])
        #expect(r.proposals.map(\.key) == ["slot1_rh_offset_pct"])
    }

    @Test func outOfRangeProposalIsNotInBatch() throws {
        let r = try CalibrationReply.parse(lines: ["REFERENCE onboard", "slot1_temp_offset_c=9.0", "slot1_rh_offset_pct=2.1"])
        let changes = r.changes(selecting: Set(["slot1_temp_offset_c", "slot1_rh_offset_pct"]), current: [:])
        let batch = ConfigSchema.batch(for: changes)
        #expect(batch.map(\.line) == ["SET slot1_rh_offset_pct=2.1"])
    }

    @Test func selectionLimitsBatchAndSkipsUnchanged() throws {
        let r = try CalibrationReply.parse(lines: Self.good)
        let current = ["slot1_temp_offset_c": "-0.4"]
        let changes = r.changes(selecting: Set(["slot1_temp_offset_c", "slot1_rh_offset_pct", "slot3_temp_offset_c"]), current: current)
        let batch = ConfigBatch(changes: changes)
        #expect(batch.pending.map(\.line) == ["SET slot3_temp_offset_c=-0.3", "SET slot1_rh_offset_pct=2.1", "CONFIG"])
    }

    @Test func duplicateProposalKeepsLastValue() throws {
        let r = try CalibrationReply.parse(lines: ["REFERENCE onboard", "slot1_rh_offset_pct=1.0", "slot1_rh_offset_pct=2.0"])
        #expect(r.proposals == [CalibrationReply.Proposal(key: "slot1_rh_offset_pct", value: "2.0")])
    }

    @Test func unknownConfigKeepsEverySelectedProposal() throws {
        let r = try CalibrationReply.parse(lines: ["REFERENCE onboard", "slot1_temp_offset_c=0.0", "slot1_rh_offset_pct=2.0"])
        let keys = Set(["slot1_temp_offset_c", "slot1_rh_offset_pct"])
        #expect(r.changes(selecting: keys, current: nil).map(\.key) == ["slot1_temp_offset_c", "slot1_rh_offset_pct"])
        #expect(r.changes(selecting: keys, current: [:]).map(\.key) == ["slot1_rh_offset_pct"]) // 0.0 equals the default
    }

    @Test func calibCommandLine() {
        #expect(ConsoleCommand.calib(6).line == nil)
        #expect(ConsoleCommand.calib(5).line == "CALIB 5")
        #expect(ConsoleCommand.calib(4).line == "CALIB 4")
        #expect(ConsoleCommand.calib(-1).line == nil)
        #expect(ConsoleCommand.calib(0).line == "CALIB 0")
        #expect(ConsoleCommand.calib(2).line == "CALIB 2")
        #expect(ConsoleCommand.calib(3).line == "CALIB 3")
    }

    @Test func readOnlyErrorStopsBatch() throws {
        let r = try CalibrationReply.parse(lines: Self.good)
        var batch = ConfigBatch(changes: r.changes(selecting: Set(r.proposals.map(\.key)), current: [:]))
        _ = batch.next()
        batch.receive(status: "ERR READ_ONLY")
        #expect(batch.failure?.reply == "ERR READ_ONLY")
        #expect(batch.summary.contains("ERR READ_ONLY"))
    }
}

@Suite struct SaltReplyTests {
    static let good = [
        "SALT onboard hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3",
        "rh_offset_pct=2.3",
    ]

    @Test func parsesMeasuredReply() throws {
        let r = try SaltReply.parse(lines: ["BEGIN SALT"] + Self.good)
        guard case let .measured(m, proposal) = r.outcome else { Issue.record("not measured"); return }
        #expect(m.name == "onboard")
        #expect(m.hours == 6)
        #expect(m.rhMean == 73.0)
        #expect(m.rhSpan == 0.4)
        #expect(m.tempMean == 25.0)
        #expect(m.reference == 75.3)
        #expect(proposal == CalibrationReply.Proposal(key: "rh_offset_pct", value: "2.3"))
        #expect(r.proposal?.key == "rh_offset_pct")
    }

    @Test func parsesSlotKey() throws {
        let r = try SaltReply.parse(lines: [
            "SALT slot3 hours=6 rh_mean=73.0 rh_span=0.0 temp_mean=-1.0 reference=75.3", "slot3_rh_offset_pct=-2.0",
        ])
        #expect(r.proposal == CalibrationReply.Proposal(key: "slot3_rh_offset_pct", value: "-2.0"))
    }

    @Test func parsesSkipAndUnstable() throws {
        #expect(try SaltReply.parse(lines: ["SKIP slot2 hours=5"]).outcome == .skipped(name: "slot2", hours: 5))
        #expect(try SaltReply.parse(lines: ["UNSTABLE slot1 span=1.2"]).outcome == .unstable(name: "slot1", span: 1.2))
    }

    @Test func outOfRangeProposalIsDropped() throws {
        let r = try SaltReply.parse(lines: [Self.good[0], "rh_offset_pct=11.0"])
        #expect(r.proposal == nil)
    }

    @Test func proposalKeyMustMatchTheSensor() throws {
        let r = try SaltReply.parse(lines: [
            "SALT slot3 hours=6 rh_mean=73.0 rh_span=0.0 temp_mean=25.0 reference=75.3", "rh_offset_pct=2.3",
        ])
        #expect(r.proposal == nil)
    }

    @Test func badNumbersThrowBadLine() {
        let bad = [
            "SALT onboard hours=x rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3",
            "SALT onboard hours=6 rh_mean=nan rh_span=0.4 temp_mean=25.0 reference=75.3",
            "SALT onboard hours=6 rh_mean=73.0 rh_span=+0.4 temp_mean=25.0 reference=75.3",
            "SALT onboard hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0",
            "SALT onboard hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=1e2",
            "SKIP slot2 hours=-1",
            "SKIP slot2",
            "UNSTABLE slot1 span=abc",
            "UNSTABLE slot1",
        ]
        for line in bad {
            #expect(throws: ParseError.badLine(line)) { try SaltReply.parse(lines: [line]) }
        }
    }

    @Test func emptyOrUnknownFrameThrows() {
        #expect(throws: ParseError.missingLine("SALT")) { try SaltReply.parse(lines: []) }
        #expect(throws: ParseError.missingLine("SALT")) { try SaltReply.parse(lines: ["REFERENCE onboard"]) }
    }

    @Test func commandLine() {
        for n in 0...5 { #expect(ConsoleCommand.salt(n).line == "SALT \(n)") }
        #expect(ConsoleCommand.salt(6).line == nil)
        #expect(ConsoleCommand.salt(-1).line == nil)
    }

    @Test func changesGoThroughConfigBatch() throws {
        let r = try SaltReply.parse(lines: Self.good)
        let changes = r.changes(selected: true, current: [:])
        #expect(ConfigBatch(changes: changes).pending.map(\.line) == ["SET rh_offset_pct=2.3", "CONFIG"])
        #expect(r.changes(selected: false, current: [:]).isEmpty)
        #expect(r.changes(selected: true, current: ["rh_offset_pct": "2.3"]).isEmpty)
        #expect(r.changes(selected: true, current: nil).map(\.key) == ["rh_offset_pct"])
    }
}
