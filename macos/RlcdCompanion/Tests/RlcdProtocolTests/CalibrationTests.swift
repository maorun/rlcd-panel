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

    static let usbReply = [
        "REFERENCE slot1",
        "SENSOR onboard hours=12 temp_offset=-1.0 rh_offset=0.0 temp_diff=-1.6 rh_diff=0.0",
        "USB hours=6 bat_hours=6 usb_temp_offset=-2.0 usb_temp_diff=-1.2",
        "temp_offset_c=0.0",
        "rh_offset_pct=0.0",
        "usb_temp_offset_c=-0.8",
    ]

    @Test func parsesUsbLineAndProposal() throws {
        let r = try CalibrationReply.parse(lines: Self.usbReply)
        #expect(r.usb == CalibrationReply.UsbSplit(usbHours: 6, batHours: 6, usbTempOffset: -2.0, usbTempDiff: -1.2))
        #expect(r.proposals.map(\.key) == ["temp_offset_c", "rh_offset_pct", "usb_temp_offset_c"])
        #expect(r.proposals[2].value == "-0.8")
        #expect(r.changes(selecting: ["usb_temp_offset_c"], current: ["usb_temp_offset_c": "-2.0"]).map(\.key) == ["usb_temp_offset_c"])
    }

    @Test func usbDashGivesNil() throws {
        let r = try CalibrationReply.parse(lines: [
            "REFERENCE slot1",
            "SENSOR onboard hours=8 temp_offset=0.0 rh_offset=0.0 temp_diff=-1.0 rh_diff=0.0",
            "USB hours=2 bat_hours=6 usb_temp_offset=0.0 usb_temp_diff=--",
        ])
        #expect(r.usb?.usbTempDiff == nil)
        #expect(r.usb?.usbHours == 2)
        #expect(!r.proposals.contains { $0.key == "usb_temp_offset_c" })
    }

    @Test func missingUsbLineGivesNil() throws {
        #expect(try CalibrationReply.parse(lines: Self.good).usb == nil)
    }

    @Test func badUsbLineThrows() {
        for bad in ["USB usb_hours=x bat_hours=6 usb_temp_offset=0.0 usb_temp_diff=--",
                    "USB hours=x bat_hours=6 usb_temp_offset=0.0 usb_temp_diff=--",
                    "USB hours=2 bat_hours=6 usb_temp_offset=0.0 usb_temp_diff=nan",
                    "USB hours=2 bat_hours=6 usb_temp_offset=0.0"] {
            #expect(throws: ParseError.badLine(bad)) {
                try CalibrationReply.parse(lines: ["REFERENCE slot1", bad])
            }
        }
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

@Suite struct TwoPointCalibrationTests {
    private static func reply(_ name: String, mean: Double, reference: Double) throws -> SaltReply {
        try SaltReply.parse(lines: ["SALT \(name) hours=6 rh_mean=\(String(format: "%.1f", mean)) rh_span=0.2 temp_mean=25.0 reference=\(String(format: "%.1f", reference))"])
    }

    @Test func commandLine() {
        for n in 0...5 { #expect(ConsoleCommand.saltMagnesium(n).line == "SALT \(n) MGCL2") }
        #expect(ConsoleCommand.saltMagnesium(6).line == nil)
        #expect(ConsoleCommand.saltMagnesium(-1).line == nil)
    }

    @Test func parsesReplyWithoutProposal() throws {
        let r = try Self.reply("slot1", mean: 30.0, reference: 32.8)
        guard case let .measured(m, proposal) = r.outcome else { Issue.record("not measured"); return }
        #expect(m.reference == 32.8 && proposal == nil)
    }

    /// Active gain 1.0 and offset 0.0: the history means are the raw means 70.0 (NaCl) and 30.0 (MgCl2).
    @Test func twoPointResultForKnownRawMeans() throws {
        let na = try Self.reply("slot2", mean: 70.0, reference: 75.4)
        let mg = try Self.reply("slot2", mean: 30.0, reference: 32.8)
        let r = try TwoPointCalibration.compute(naCl: na, mgCl2: mg, current: [:])
        #expect(r.gainKey == "slot2_rh_gain" && r.offsetKey == "slot2_rh_offset_pct")
        // slope (75.4 - 32.8) / 40.0 = 1.065 -> 1.066; residuals at gain 1.066: 0.78 and 0.82 -> offset 0.8
        #expect(r.gain == "1.066")
        #expect(r.offset == "0.8")
        #expect(r.rawNaCl == 70.0 && r.rawMgCl2 == 30.0)
        #expect(r.changes(current: [:]).map(\.key) == ["slot2_rh_offset_pct", "slot2_rh_gain"])
    }

    @Test func rawMeansUseTheActiveGainAndOffset() throws {
        // active gain 1.05, offset -2.0: raw = (mean + 2.0) / 1.05
        let na = try Self.reply("onboard", mean: 73.0, reference: 75.3)
        let mg = try Self.reply("onboard", mean: 31.0, reference: 32.8)
        let r = try TwoPointCalibration.compute(naCl: na, mgCl2: mg, current: ["rh_gain": "1.05", "rh_offset_pct": "-2.0"])
        #expect(abs(r.rawNaCl - 71.428571) < 0.001 && abs(r.rawMgCl2 - 31.428571) < 0.001)
        #expect(r.gainKey == "rh_gain" && r.offsetKey == "rh_offset_pct")
        #expect(r.gain == "1.062")
    }

    @Test func gainOutsideTheKeyRangeGivesAnErrorAndNoSet() throws {
        // raw means 60.0 and 30.0 against 75.3 / 32.8: slope 1.417
        let na = try Self.reply("onboard", mean: 60.0, reference: 75.3)
        let mg = try Self.reply("onboard", mean: 30.0, reference: 32.8)
        #expect(throws: TwoPointCalibration.Failure.self) { try TwoPointCalibration.compute(naCl: na, mgCl2: mg, current: [:]) }
        // gain of 1.2 exactly
        let na2 = try Self.reply("onboard", mean: 60.0, reference: 72.0)
        let mg2 = try Self.reply("onboard", mean: 30.0, reference: 36.0)
        do {
            _ = try TwoPointCalibration.compute(naCl: na2, mgCl2: mg2, current: [:])
            Issue.record("gain 1.2 must fail")
        } catch let failure as TwoPointCalibration.Failure {
            #expect(failure == .gainOutOfRange(1.2))
            #expect(failure.text == "Gain 1.200 is outside 0.90 to 1.10, no SET sent")
        }
    }

    @Test func otherFailures() throws {
        let na = try Self.reply("slot1", mean: 70.0, reference: 75.3)
        let mg = try Self.reply("slot2", mean: 30.0, reference: 32.8)
        #expect(throws: TwoPointCalibration.Failure.differentSensors) { try TwoPointCalibration.compute(naCl: na, mgCl2: mg, current: [:]) }
        #expect(throws: TwoPointCalibration.Failure.notMeasured) { try TwoPointCalibration.compute(naCl: na, mgCl2: nil, current: [:]) }
        let skipped = try SaltReply.parse(lines: ["SKIP slot1 hours=3"])
        #expect(throws: TwoPointCalibration.Failure.notMeasured) { try TwoPointCalibration.compute(naCl: na, mgCl2: skipped, current: [:]) }
        let mg1 = try Self.reply("slot1", mean: 30.0, reference: 32.8)
        #expect(throws: TwoPointCalibration.Failure.configUnknown) { try TwoPointCalibration.compute(naCl: na, mgCl2: mg1, current: nil) }
        let close = try Self.reply("slot1", mean: 70.5, reference: 32.8)
        #expect(throws: TwoPointCalibration.Failure.pointsTooClose) { try TwoPointCalibration.compute(naCl: na, mgCl2: close, current: [:]) }
    }

    @Test func resultGoesThroughConfigBatch() throws {
        let na = try Self.reply("onboard", mean: 70.0, reference: 75.3)
        let mg = try Self.reply("onboard", mean: 30.0, reference: 32.8)
        let r = try TwoPointCalibration.compute(naCl: na, mgCl2: mg, current: [:])
        let lines = ConfigBatch(changes: r.changes(current: [:])).pending.map(\.line)
        #expect(lines.last == "CONFIG" && lines.contains("SET rh_gain=1.062"))
    }

    @Test func offsetOutOfRangeAndBadActiveValues() throws {
        // raw means 70 and 30 with references 85 and 45.5: slope 0.9875, offset 15.9 -> outside -10 to 10
        let na = try Self.reply("onboard", mean: 70.0, reference: 85.0)
        let mg = try Self.reply("onboard", mean: 30.0, reference: 45.5)
        do {
            _ = try TwoPointCalibration.compute(naCl: na, mgCl2: mg, current: [:])
            Issue.record("offset beyond 10 must fail")
        } catch let failure as TwoPointCalibration.Failure {
            guard case .offsetOutOfRange = failure else { Issue.record("wrong failure \(failure)"); return }
        }
        let ok1 = try Self.reply("onboard", mean: 70.0, reference: 75.3)
        let ok2 = try Self.reply("onboard", mean: 30.0, reference: 32.8)
        #expect(throws: TwoPointCalibration.Failure.badActiveValues) {
            try TwoPointCalibration.compute(naCl: ok1, mgCl2: ok2, current: ["rh_gain": "abc"])
        }
        #expect(throws: TwoPointCalibration.Failure.badActiveValues) {
            try TwoPointCalibration.compute(naCl: ok1, mgCl2: ok2, current: ["rh_gain": "0"])
        }
    }

    @Test func clampedMeanGivesAnError() throws {
        let na = try Self.reply("onboard", mean: 100.0, reference: 75.3)
        let mg = try Self.reply("onboard", mean: 30.0, reference: 32.8)
        #expect(throws: TwoPointCalibration.Failure.clampedSample) { try TwoPointCalibration.compute(naCl: na, mgCl2: mg, current: [:]) }
        let low = try Self.reply("onboard", mean: 0.0, reference: 32.8)
        let ok = try Self.reply("onboard", mean: 70.0, reference: 75.3)
        #expect(throws: TwoPointCalibration.Failure.clampedSample) { try TwoPointCalibration.compute(naCl: ok, mgCl2: low, current: [:]) }
    }
}
