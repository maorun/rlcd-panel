import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct CalibrationReportTests {
    static let generated = Date(timeIntervalSince1970: 1_795_000_000)
    static let read = Date(timeIntervalSince1970: 1_794_990_000)

    static func version() throws -> VersionReply {
        try VersionReply.parse(lines: ["VERSION fw=1.0.0 git=abc123 core=3.3.12 state=88 built=2026-11-28"])
    }

    static func changed() -> [ConfigChangedEntry] {
        [ConfigChangedEntry(key: "temp_offset_c", value: "2.25"),
         ConfigChangedEntry(key: "mold_alert_limit_rh", value: "65"),
         ConfigChangedEntry(key: "slot1_rh_gain", value: "1.02"),
         ConfigChangedEntry(key: "usb_temp_offset_c", value: "-0.4")]
    }

    static func spread() throws -> SpreadReply {
        try SpreadReply.parse(lines: [
            "SPREAD sensors=3 temp_median=20.0 rh_median=50.0",
            "SENSOR onboard temp_dev=0.0 rh_dev=0.0 OK",
            "SENSOR slot1 temp_dev=0.0 rh_dev=0.0 OK",
            "SENSOR slot2 temp_dev=1.0 rh_dev=5.0 OUTLIER",
            "slot2_temp_offset_c=-1.0 slot2_rh_offset_pct=-5.0",
        ])
    }

    static func salt(_ line: String) throws -> SaltReply { try SaltReply.parse(lines: [line]) }

    @Test func fullReportListsEveryPartWithItsDate() throws {
        let nacl = try Self.salt("SALT slot1 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=21.0 reference=75.5")
        let text = CalibrationReport.render(
            generated: Self.generated,
            version: .init(try Self.version(), date: Self.read),
            changed: .init(Self.changed(), date: Self.read),
            spread: .init(try Self.spread(), date: Self.read),
            salt: .init(nacl, date: Self.read),
            saltMagnesium: .init(try Self.salt("UNSTABLE slot1 span=1.5"), date: Self.read))
        let stamp = CalibrationReport.dateText(Self.read)
        #expect(text.hasPrefix("# Calibration report\n\nGenerated: \(CalibrationReport.dateText(Self.generated))\n"))
        #expect(text.contains("- Firmware: 1.0.0"))
        #expect(text.contains("| temp_offset_c | 2.25 |"))
        #expect(text.contains("| slot1_rh_gain | 1.02 |"))
        #expect(text.contains("| usb_temp_offset_c | -0.4 |"))
        #expect(!text.contains("mold_alert_limit_rh"))
        #expect(text.contains("| slot2 | 1.0 | 5.0 | OUTLIER |"))
        #expect(text.contains("- RH mean: 73.0 %RH"))
        #expect(text.contains("Unstable: slot1 has a span of 1.5 %RH."))
        #expect(text.components(separatedBy: "Read: \(stamp)").count - 1 == 5)
        let order = ["## Firmware", "## Offsets and gains", "## Co-location spread", "## Salt check", "### NaCl", "### MgCl2"]
        let positions = order.map { text.range(of: $0)!.lowerBound }
        #expect(positions == positions.sorted())
        // Each heading is followed at once by the time of its read.
        for heading in ["## Firmware", "## Offsets and gains", "## Co-location spread"] {
            #expect(text.contains("\(heading)\n\nRead: \(stamp)\n"))
        }
        for heading in ["### NaCl (75 %RH)", "### MgCl2 (33 %RH)"] { #expect(text.contains("\(heading)\n\nRead: \(stamp)\n")) }
        #expect(!text.contains("\n--\n"))
        #expect(!text.contains("Read: --"))
    }

    @Test func missingPartsShowDashes() {
        let text = CalibrationReport.render(
            generated: Self.generated, version: .init(nil, date: nil), changed: .init(nil, date: nil),
            spread: .init(nil, date: nil), salt: .init(nil, date: nil), saltMagnesium: .init(nil, date: nil))
        #expect(text.components(separatedBy: "Read: --").count - 1 == 5)
        #expect(text.components(separatedBy: "\n--\n").count - 1 == 5)
    }

    @Test func valueWithoutDateShowsDateDashOnly() throws {
        let text = CalibrationReport.render(
            generated: Self.generated, version: .init(try Self.version(), date: nil), changed: .init(nil, date: Self.read),
            spread: .init(nil, date: nil), salt: .init(nil, date: nil), saltMagnesium: .init(nil, date: nil))
        #expect(text.contains("## Firmware\n\nRead: --\n\n- Firmware: 1.0.0"))
        // A date without a value is not shown: the part is missing.
        #expect(text.contains("## Offsets and gains\n\nRead: --\n\n--"))
    }

    @Test func emptyKeyListAndSkippedSpread() throws {
        let text = CalibrationReport.render(
            generated: Self.generated, version: .init(nil, date: nil),
            changed: .init([ConfigChangedEntry(key: "mold_alert_limit_rh", value: "65")], date: Self.read),
            spread: .init(SpreadReply(outcome: .skipped(sensors: 2)), date: Self.read),
            salt: .init(try Self.salt("SKIP slot1 hours=3"), date: Self.read), saltMagnesium: .init(nil, date: nil))
        #expect(text.contains("No offset or gain differs from the default."))
        #expect(text.contains("Skipped: 2 sensor(s) with enough samples."))
        #expect(text.contains("Skipped: slot1 has 3 hour(s)."))
    }

    @Test func calibrationKeySelection() {
        for key in ["temp_offset_c", "rh_offset_pct", "slot5_temp_offset_c", "slot3_rh_offset_pct", "usb_temp_offset_c",
                    "rh_gain", "slot2_rh_gain"] { #expect(CalibrationReport.isCalibrationKey(key)) }
        for key in ["battery_offset_mv", "wake_interval_s", "slot1_name"] { #expect(!CalibrationReport.isCalibrationKey(key)) }
    }

    @Test func missingReportHasFixedText() {
        let text = CalibrationReport.render(
            generated: Self.generated, version: .init(nil, date: nil), changed: .init(nil, date: nil),
            spread: .init(nil, date: nil), salt: .init(nil, date: nil), saltMagnesium: .init(nil, date: nil))
        let part = "Read: --\n\n--\n\n"
        #expect(text == "# Calibration report\n\nGenerated: \(CalibrationReport.dateText(Self.generated))\n\n"
            + "## Firmware\n\n" + part + "## Offsets and gains\n\n" + part + "## Co-location spread\n\n" + part
            + "## Salt check\n\n### NaCl (75 %RH)\n\n" + part + "### MgCl2 (33 %RH)\n\n" + part.dropLast() + "\n")
    }

    @Test func tableCellsEscapeBarAndBackslash() throws {
        let text = CalibrationReport.render(
            generated: Self.generated, version: .init(nil, date: nil),
            changed: .init([ConfigChangedEntry(key: "slot1_rh_gain", value: "a|b\\c")], date: Self.read),
            spread: .init(nil, date: nil), salt: .init(nil, date: nil), saltMagnesium: .init(nil, date: nil))
        #expect(text.contains("| slot1_rh_gain | a\\|b\\\\c |"))
    }

    @Test func readLogKeepsTheNewestTimeOfEachPart() {
        var log = CalibrationReadLog()
        #expect(log.date(.spread) == nil)
        log.record(.spread, at: Self.read)
        log.record(.spread, at: Self.generated)
        log.record(.salt, at: Self.read)
        #expect(log.date(.spread) == Self.generated)
        #expect(log.date(.salt) == Self.read)
        #expect(log.date(.version) == nil)
    }
}
