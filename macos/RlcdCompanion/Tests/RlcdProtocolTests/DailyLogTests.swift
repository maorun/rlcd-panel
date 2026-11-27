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

    /// Header of `SdLogCodec::formatDailyHeader` (v3): six sensors, then the noise columns.
    private func headerV3() -> String {
        var names = ["date"] + (Self.sensors + ["s4", "s5"]).flatMap { s in Self.columns.map { "\(s)_\($0)" } }
        names += ["noise_min_db", "noise_max_db", "noise_peak_db", "noise_peak_hour", "noise_events_sum"]
        return names.joined(separator: ",")
    }

    @Test func parsesDailyV3WithSlots4And5() throws {
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        let slot5 = "10.0,20.0,15.0,40.0,60.0,50.0"
        let line = "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2,\(empty),\(empty),\(empty),\(empty),\(slot5),35,62,71,18,12"
        let log = try DailyLog.parse(text: headerV3() + "\n" + line + "\n")
        #expect(log.format == .v3)
        #expect(log.rows.count == 1)
        #expect(log.rows[0].sensors.count == 6)
        #expect(log.rows[0].sensors[4].tempMinC == nil)
        #expect(log.rows[0].sensors[5].tempMinC == 10.0)
        #expect(log.rows[0].sensors[5].rhMean == 50.0)
        #expect(log.rows[0].noisePeakDb == 71)
        #expect(log.rows[0].noiseEventsSum == 12)
        #expect(throws: ParseError.self) { try DailyLog.parse(text: headerV3() + "\n" + row(v2: true) + "\n") }
        #expect(DailyLog.isDailyFileName("daily_v3_2026.csv"))
    }

    /// Header of `SdLogCodec::formatDailyHeader` (v4): the v3 header plus `mold_h_0` to `mold_h_5`.
    private func headerV4() -> String {
        headerV3() + "," + (0..<6).map { "mold_h_\($0)" }.joined(separator: ",")
    }

    @Test func parsesDailyV4WithMoldHours() throws {
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        let line = "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2,\(empty),\(empty),\(empty),\(empty),\(empty),35,62,71,18,12,5,,,,,0"
        let log = try DailyLog.parse(text: headerV4() + "\n" + line + "\n")
        #expect(log.format == .v4)
        #expect(log.rows.count == 1)
        #expect(log.rows[0].sensors.count == 6)
        #expect(log.rows[0].noiseEventsSum == 12)
        #expect(log.rows[0].moldHours == [5, nil, nil, nil, nil, 0])
        #expect(DailyLog.isDailyFileName("daily_v4_2026.csv"))
        // 47 columns: the last mold column is missing.
        let short = String(line.dropLast(2))
        #expect(throws: ParseError.badLine(short)) { try DailyLog.parse(text: headerV4() + "\n" + short + "\n") }
        // A v3 row under the v4 header is wrong too.
        #expect(throws: ParseError.self) { try DailyLog.parse(text: headerV4() + "\n" + row(v2: true) + "\n") }
        // v3 rows keep an empty mold list.
        let v3 = "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2,\(empty),\(empty),\(empty),\(empty),\(empty),35,62,71,18,12"
        #expect(try DailyLog.parse(text: headerV3() + "\n" + v3 + "\n").rows[0].moldHours.isEmpty)
    }

    /// Header of `SdLogCodec::formatDailyHeader` (v5): eleven sensors, then noise, then mold_h_0 to mold_h_10.
    private func headerV5() -> String {
        var names = ["date"] + DailyLog.sensorNames.flatMap { s in Self.columns.map { "\(s)_\($0)" } }
        names += ["noise_min_db", "noise_max_db", "noise_peak_db", "noise_peak_hour", "noise_events_sum"]
        names += (0..<DailyLog.sensorNames.count).map { "mold_h_\($0)" }
        return names.joined(separator: ",")
    }

    @Test func parsesDailyV5WithTenSlots() throws {
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        let moldEmpty = Array(repeating: "", count: 10).joined(separator: ",")
        let line = "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2" + String(repeating: ",\(empty)", count: 10) + ",35,62,71,18,12,7,\(moldEmpty)"
        let log = try DailyLog.parse(text: headerV5() + "\n" + line + "\n")
        #expect(log.format == .v5)
        #expect(log.rows.count == 1)
        #expect(log.rows[0].sensors.count == 11)
        #expect(log.rows[0].noiseEventsSum == 12)
        #expect(log.rows[0].moldHours == [7, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil])
        #expect(DailyLog.isDailyFileName("daily_v5_2026.csv"))
    }

    /// Header of `SdLogCodec::formatDailyHeader` (v6): the v5 header plus dry_h_0 to dry_h_10.
    private func headerV6() -> String {
        headerV5() + "," + (0..<DailyLog.sensorNames.count).map { "dry_h_\($0)" }.joined(separator: ",")
    }

    private func v6Line(dry: [String]) -> String {
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        let moldEmpty = Array(repeating: "", count: 10).joined(separator: ",")
        return "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2" + String(repeating: ",\(empty)", count: 10)
            + ",35,62,71,18,12,7,\(moldEmpty)," + dry.joined(separator: ",")
    }

    @Test func parsesDailyV6WithDryHours() throws {
        var dry = Array(repeating: "", count: 11)
        dry[2] = "5"
        let log = try DailyLog.parse(text: headerV6() + "\n" + v6Line(dry: dry) + "\n")
        #expect(log.format == .v6)
        #expect(log.rows[0].sensors.count == 11)
        #expect(log.rows[0].moldHours[0] == 7)
        #expect(log.rows[0].dryHours.count == 11)
        #expect(log.rows[0].dryHours[2] == 5)
        #expect(log.rows[0].dryHours[0] == nil) // an empty field gives nil
        #expect(DailyLog.isDailyFileName("daily_v6_2026.csv"))
    }

    /// Header of `SdLogCodec::formatDailyHeader` (v7): the v6 header plus heat_h_0 to heat_h_10.
    private func headerV7() -> String {
        headerV6() + "," + (0..<DailyLog.sensorNames.count).map { "heat_h_\($0)" }.joined(separator: ",")
    }

    private func v7Line(dry: [String], heat: [String]) -> String {
        v6Line(dry: dry) + "," + heat.joined(separator: ",")
    }

    @Test func parsesDailyV7WithHeatColumns() throws {
        var dry = Array(repeating: "", count: 11)
        dry[2] = "5"
        var heat = Array(repeating: "", count: 11)
        heat[0] = "4"
        let log = try DailyLog.parse(text: headerV7() + "\n" + v7Line(dry: dry, heat: heat) + "\n")
        #expect(log.format == .v7)
        #expect(log.rows.count == 1)
        #expect(log.rows[0].sensors.count == 11)
        #expect(log.rows[0].moldHours[0] == 7)
        #expect(log.rows[0].dryHours[2] == 5)
        #expect(log.rows[0].dryHours[0] == nil)
        #expect(log.rows[0].heatHours.count == 11)
        #expect(log.rows[0].heatHours[0] == 4)
        #expect(log.rows[0].heatHours[1] == nil)
        #expect(DailyLog.isDailyFileName("daily_v7_2026.csv"))
        #expect(headerV7().split(separator: ",").count == 105)
        #expect(headerV7().hasSuffix(",heat_h_10"))
    }

    @Test func dailyV7RowWith104ColumnsIsBad() throws {
        let short = v7Line(dry: Array(repeating: "", count: 11), heat: Array(repeating: "", count: 10))
        #expect(throws: ParseError.badLine(short)) { try DailyLog.parse(text: headerV7() + "\n" + short + "\n") }
        // A v6 row (94 columns) under the v7 header is wrong, and a v7 row under the v6 header too.
        let v6 = v6Line(dry: Array(repeating: "", count: 11))
        #expect(throws: ParseError.badLine(v6)) { try DailyLog.parse(text: headerV7() + "\n" + v6 + "\n") }
        let v7 = v7Line(dry: Array(repeating: "", count: 11), heat: Array(repeating: "", count: 11))
        #expect(throws: ParseError.badLine(v7)) { try DailyLog.parse(text: headerV6() + "\n" + v7 + "\n") }
    }

    @Test func dailyV7RowReplacesV6RowOfSameDate() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-daily-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        var dry = Array(repeating: "", count: 11)
        dry[2] = "5"
        var newDry = Array(repeating: "", count: 11)
        newDry[2] = "9"
        try (headerV6() + "\n" + v6Line(dry: dry) + "\n").write(to: dir.appendingPathComponent("daily_v6_2026.csv"), atomically: true, encoding: .utf8)
        try (headerV7() + "\n" + v7Line(dry: newDry, heat: Array(repeating: "", count: 11)) + "\n")
            .write(to: dir.appendingPathComponent("daily_v7_2026.csv"), atomically: true, encoding: .utf8)
        let result = DailyLog.load(from: dir)
        #expect(result.rows.count == 1)
        #expect(result.rows[0].dryHours[2] == 9)
        #expect(result.failedFiles.isEmpty)
    }

    @Test func dailyV6RowReplacesV5RowOfSameDate() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-daily-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        let moldEmpty = Array(repeating: "", count: 10).joined(separator: ",")
        let v5 = "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2" + String(repeating: ",\(empty)", count: 10) + ",35,62,71,18,12,7,\(moldEmpty)"
        var dry = Array(repeating: "", count: 11)
        dry[2] = "5"
        try (headerV5() + "\n" + v5 + "\n").write(to: dir.appendingPathComponent("daily_v5_2026.csv"), atomically: true, encoding: .utf8)
        try (headerV6() + "\n" + v6Line(dry: dry) + "\n").write(to: dir.appendingPathComponent("daily_v6_2026.csv"), atomically: true, encoding: .utf8)
        let result = DailyLog.load(from: dir)
        #expect(result.rows.count == 1)
        #expect(result.rows[0].dryHours[2] == 5)
        #expect(result.failedFiles.isEmpty)
    }

    @Test func dailyV6HasNoHeatHours() throws {
        let line = v6Line(dry: Array(repeating: "", count: 11))
        #expect(try DailyLog.parse(text: headerV6() + "\n" + line + "\n").rows[0].heatHours.isEmpty)
    }

    @Test func dailyV5HasNoDryHours() throws {
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        let moldEmpty = Array(repeating: "", count: 10).joined(separator: ",")
        let line = "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2" + String(repeating: ",\(empty)", count: 10) + ",35,62,71,18,12,7,\(moldEmpty)"
        #expect(try DailyLog.parse(text: headerV5() + "\n" + line + "\n").rows[0].dryHours.isEmpty)
    }

    @Test func dailyV6RowWith93ColumnsIsBad() throws {
        let short = v6Line(dry: Array(repeating: "", count: 10))
        #expect(throws: ParseError.badLine(short)) { try DailyLog.parse(text: headerV6() + "\n" + short + "\n") }
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

    @Test func daily4RowReplacesDaily3RowOfSameDate() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-daily-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let empty = Array(repeating: "", count: 6).joined(separator: ",")
        let v3 = "2026-11-14,20.1,24.8,22.3,40.0,55.5,47.2,\(empty),\(empty),\(empty),\(empty),\(empty),35,62,71,18,12"
        try (headerV3() + "\n" + v3 + "\n").write(to: dir.appendingPathComponent("daily_v3_2026.csv"), atomically: true, encoding: .utf8)
        try (headerV4() + "\n" + v3 + ",7,,,,,\n").write(to: dir.appendingPathComponent("daily_v4_2026.csv"), atomically: true, encoding: .utf8)
        let result = DailyLog.load(from: dir)
        #expect(result.rows.count == 1)
        #expect(result.rows[0].moldHours == [7, nil, nil, nil, nil, nil])
        #expect(result.failedFiles.isEmpty)
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
        #expect(DailyLog.isDailyFileName("daily_v3_2026.csv"))
        #expect(DailyLog.isDailyFileName("daily_v4_2026.csv"))
        #expect(DailyLog.isDailyFileName("daily_v5_2026.csv"))
        #expect(DailyLog.isDailyFileName("daily_v6_2026.csv"))
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
