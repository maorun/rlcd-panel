import Foundation
import Testing
@testable import RlcdProtocol

struct DailyLogTests {
    private static let sensors = ["onboard", "s1", "s2", "s3"]
    private static let columns = ["temp_min_c", "temp_max_c", "temp_mean_c", "rh_min", "rh_max", "rh_mean"]

    /// Header of `SdLogCodec::formatDailyHeader` (v2) or the older v1 header without the noise peak columns.
    private func header(v2: Bool) -> String {
        var names = ["date"] + Self.sensors.flatMap { s in Self.columns.map { "\(s)_\($0)" } }
        names += ["noise_min_db", "noise_max_db"]
        if v2 { names += ["noise_peak_db", "noise_peak_hour", "noise_events_sum"] }
        return names.joined(separator: ",")
    }

    /// Row of `SdLogCodec::formatDailyRow`: onboard sensor with values, the three slots empty.
    private func row(v2: Bool, onboard: String = "20.1,24.8,22.3,40.0,55.5,47.2", noise: String = "35,62") -> String {
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        var text = "2026-11-14,\(onboard),\(empty),\(empty),\(empty),\(noise)"
        if v2 { text += ",71,18,12" }
        return text
    }

    @Test func parsesDailyV1() throws {
        let log = try DailyLog.parse(text: header(v2: false) + "\n" + row(v2: false) + "\n")
        #expect(log.format == .v1)
        #expect(log.rows.count == 1)
        let r = log.rows[0]
        #expect(r.date == "2026-11-14")
        #expect(r.sensors.count == 4)
        #expect(r.sensors[0].tempMinC == 20.1)
        #expect(r.sensors[0].tempMaxC == 24.8)
        #expect(r.sensors[0].tempMeanC == 22.3)
        #expect(r.sensors[0].rhMin == 40.0)
        #expect(r.sensors[0].rhMax == 55.5)
        #expect(r.sensors[0].rhMean == 47.2)
        #expect(r.noiseMinDb == 35)
        #expect(r.noiseMaxDb == 62)
        #expect(r.noisePeakDb == nil)
        #expect(r.noisePeakHour == nil)
        #expect(r.noiseEventsSum == nil)
    }

    @Test func parsesDailyV2() throws {
        let log = try DailyLog.parse(text: header(v2: true) + "\n" + row(v2: true) + "\n")
        #expect(log.format == .v2)
        let r = log.rows[0]
        #expect(r.sensors[0].tempMeanC == 22.3)
        #expect(r.noisePeakDb == 71)
        #expect(r.noisePeakHour == 18)
        #expect(r.noiseEventsSum == 12)
    }

    @Test func emptyFieldGivesNil() throws {
        let text = header(v2: true) + "\n" + row(v2: true, onboard: ",,,,,", noise: ",") + "\n"
        let r = try DailyLog.parse(text: text).rows[0]
        #expect(r.sensors[0].tempMinC == nil)
        #expect(r.sensors[0].rhMean == nil)
        #expect(r.sensors[1].tempMaxC == nil)
        #expect(r.noiseMinDb == nil)
        #expect(r.noiseMaxDb == nil)
    }

    @Test func wrongColumnCountThrowsBadLine() {
        var fields = row(v2: true).split(separator: ",", omittingEmptySubsequences: false)
        fields.removeLast()
        #expect(fields.count == 29)
        let text = header(v2: true) + "\n" + fields.joined(separator: ",") + "\n"
        #expect(throws: ParseError.badLine(fields.joined(separator: ","))) { try DailyLog.parse(text: text) }
    }

    @Test func nonNumericFieldThrowsBadLine() {
        let line = row(v2: false, onboard: "warm,24.8,22.3,40.0,55.5,47.2")
        #expect(throws: ParseError.badLine(line)) { try DailyLog.parse(text: header(v2: false) + "\n" + line + "\n") }
    }

    @Test func unknownHeaderThrowsMissingLine() {
        #expect(throws: ParseError.missingLine("header")) { try DailyLog.parse(text: "date,foo,bar\n2026-11-14,1,2\n") }
        #expect(throws: ParseError.missingLine("header")) { try DailyLog.parse(text: "") }
    }

    @Test func toleratesCarriageReturnAndBlankLines() throws {
        let text = header(v2: false) + "\r\n" + row(v2: false) + "\r\n\r\n"
        #expect(try DailyLog.parse(text: text).rows.count == 1)
    }

    @Test func loadsAllDailyFilesOfFolderSortedByDate() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-daily-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let older = row(v2: false).replacingOccurrences(of: "2026-11-14", with: "2025-12-31")
        try (header(v2: false) + "\n" + older + "\n").write(to: dir.appendingPathComponent("daily_v1_2025.csv"), atomically: true, encoding: .utf8)
        try (header(v2: true) + "\n" + row(v2: true) + "\n").write(to: dir.appendingPathComponent("daily_v2_2026.csv"), atomically: true, encoding: .utf8)
        try "ignored".write(to: dir.appendingPathComponent("climate_v4_202611.csv"), atomically: true, encoding: .utf8)
        try "broken".write(to: dir.appendingPathComponent("daily_v2_2027.csv"), atomically: true, encoding: .utf8)
        let result = DailyLog.load(from: dir)
        #expect(result.rows.map(\.date) == ["2025-12-31", "2026-11-14"])
        #expect(result.failedFiles == ["daily_v2_2027.csv"])
    }

    @Test func acceptsBomAndEmptyPeakFields() throws {
        let line = row(v2: true).replacingOccurrences(of: ",71,18,12", with: ",,,")
        let r = try DailyLog.parse(text: "\u{FEFF}" + header(v2: true) + "\n" + line + "\n").rows[0]
        #expect(r.noisePeakDb == nil)
        #expect(r.noisePeakHour == nil)
        #expect(r.noiseEventsSum == nil)
        #expect(r.noiseMaxDb == 62)
    }

    @Test func invalidDateThrowsBadLine() {
        let line = row(v2: false).replacingOccurrences(of: "2026-11-14", with: "not-a-date")
        #expect(throws: ParseError.badLine(line)) { try DailyLog.parse(text: header(v2: false) + "\n" + line + "\n") }
    }

    @Test func filtersDailyFileNames() {
        #expect(DailyLog.isDailyFileName("daily_v1_2025.csv"))
        #expect(DailyLog.isDailyFileName("daily_v2_2026.csv"))
        #expect(!DailyLog.isDailyFileName("daily_v3_2026.csv"))
        #expect(!DailyLog.isDailyFileName("climate_v4_202611.csv"))
        #expect(!DailyLog.isDailyFileName("events_v1_2026.csv"))
        #expect(!DailyLog.isDailyFileName("../daily_v2_2026.csv"))
    }

    @Test func v2RowWinsForSameDateAcrossFiles() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-daily-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try (header(v2: false) + "\n" + row(v2: false) + "\n").write(to: dir.appendingPathComponent("daily_v1_2026.csv"), atomically: true, encoding: .utf8)
        try (header(v2: true) + "\n" + row(v2: true) + "\n").write(to: dir.appendingPathComponent("daily_v2_2026.csv"), atomically: true, encoding: .utf8)
        let rows = DailyLog.load(from: dir).rows
        #expect(rows.count == 1)
        #expect(rows[0].noisePeakDb == 71)
    }
}
