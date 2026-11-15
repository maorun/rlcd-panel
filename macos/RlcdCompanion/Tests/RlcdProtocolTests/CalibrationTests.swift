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
        #expect(ConsoleCommand.calib(4).line == nil)
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
