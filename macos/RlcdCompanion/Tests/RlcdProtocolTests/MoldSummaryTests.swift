import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct MoldSummaryTests {
    private static func row(_ date: String, _ mold: [Int?]) -> DailyRow {
        DailyRow(date: date, sensors: [], noiseMinDb: nil, noiseMaxDb: nil, noisePeakDb: nil,
                 noisePeakHour: nil, noiseEventsSum: nil, moldHours: mold)
    }

    @Test func fixtureHasNoMoldValues() throws {
        let rows = try DaysReply.parse(lines: DaysReplyTests.fixtureLines()).rows
        let all = MoldSummary.summarize(rows: rows)
        #expect(all.count == 11)
        #expect(all.allSatisfy { $0.totalHours == nil && $0.moldDays == 0 && $0.worstDate == nil && $0.worstHours == nil })
        #expect(all.allSatisfy { MoldSummary.lineText($0) == nil })
    }

    @Test func sumsDaysAndWorstDay() {
        let rows = [Self.row("2026-01-01", [3]), Self.row("2026-01-02", [0]), Self.row("2026-01-03", [5]), Self.row("2026-01-04", [nil])]
        let s = MoldSummary.summarize(rows: rows, sensor: 0)
        #expect(s.totalHours == 8 && s.moldDays == 2 && s.worstDate == "2026-01-03" && s.worstHours == 5)
        #expect(MoldSummary.lineText(s) == "Mold 8 h, 2 days, worst 01-03 5 h")
    }

    @Test func tieSelectsNewestDay() {
        let rows = [Self.row("2026-01-01", [4]), Self.row("2026-01-02", [1]), Self.row("2026-01-03", [4])]
        let s = MoldSummary.summarize(rows: rows, sensor: 0)
        #expect(s.worstDate == "2026-01-03" && s.worstHours == 4 && s.totalHours == 9)
        // The newest day wins whatever the row order.
        let shuffled = [rows[2], rows[1], rows[0]]
        #expect(MoldSummary.summarize(rows: shuffled, sensor: 0).worstDate == "2026-01-03")
    }

    @Test func singleMoldDayUsesSingularText() {
        let s = MoldSummary.summarize(rows: [Self.row("2026-01-01", [3]), Self.row("2026-01-02", [0])], sensor: 0)
        #expect(MoldSummary.lineText(s) == "Mold 3 h, 1 day, worst 01-01 3 h")
    }

    @Test func zeroHoursGiveNoMoldText() {
        let s = MoldSummary.summarize(rows: [Self.row("2026-01-01", [0]), Self.row("2026-01-02", [0])], sensor: 0)
        #expect(s.totalHours == 0 && s.moldDays == 0 && s.worstDate == nil)
        #expect(MoldSummary.lineText(s) == "No mold hours")
    }

    @Test func dailyV3WithoutMoldColumnsGivesNil() throws {
        let stats = (0..<36).map { _ in "" }
        let columns = ["temp_min_c", "temp_max_c", "temp_mean_c", "rh_min", "rh_max", "rh_mean"]
        let sensors = ["onboard", "s1", "s2", "s3", "s4", "s5"]
        let header = (["date"] + sensors.flatMap { s in columns.map { "\(s)_\($0)" } }
            + ["noise_min_db", "noise_max_db", "noise_peak_db", "noise_peak_hour", "noise_events_sum"]).joined(separator: ",")
        let line = (["2026-01-01"] + stats + ["", "", "", "", ""]).joined(separator: ",")
        let log = try DailyLog.parse(text: header + "\n" + line)
        #expect(log.format == .v3 && log.rows.count == 1)
        #expect(MoldSummary.summarize(rows: log.rows, sensor: 0).totalHours == nil)
        #expect(MoldSummary.summarize(rows: log.rows).allSatisfy { $0.totalHours == nil })
    }

    @Test func sensorBeyondRowWidthGivesNil() {
        #expect(MoldSummary.summarize(rows: [Self.row("2026-01-01", [2])], sensor: 3).totalHours == nil)
    }

    // MARK: heat hours

    private static func fixtureLines(_ name: String) throws -> [String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/\(name)")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
        return lines
    }

    private static func fixtureRows(_ name: String) throws -> [DailyRow] {
        try DaysReply.parse(lines: fixtureLines(name)).rows
    }

    private static func heatRow(_ date: String, _ heat: [Int?]) -> DailyRow {
        DailyRow(date: date, sensors: [], noiseMinDb: nil, noiseMaxDb: nil, noisePeakDb: nil,
                 noisePeakHour: nil, noiseEventsSum: nil, moldHours: [], dryHours: [], heatHours: heat)
    }

    @Test func v7FixtureWithoutHeatValuesGivesDashes() throws {
        let all = MoldSummary.summarize(rows: try Self.fixtureRows("days_reply_v7_sample.txt"))
        #expect(all.allSatisfy { $0.heatHours == nil && MoldSummary.heatText($0) == "Heat --" })
    }

    @Test func v6FixtureGivesDashes() throws {
        let all = MoldSummary.summarize(rows: try Self.fixtureRows("days_reply_v6_sample.txt"))
        #expect(all.allSatisfy { $0.heatHours == nil && MoldSummary.heatText($0) == "Heat --" })
    }

    @Test func heatSumCountsOnlyDaysWithAValue() {
        let rows = [Self.heatRow("2026-01-01", [3]), Self.heatRow("2026-01-02", [nil]), Self.heatRow("2026-01-03", [5])]
        let s = MoldSummary.summarize(rows: rows, sensor: 0)
        #expect(s.heatHours == 8 && MoldSummary.heatText(s) == "Heat 8 h")
        #expect(s.totalHours == nil) // no mold value: the mold line stays hidden
    }

    @Test func zeroHeatHoursAreNotDashes() {
        let s = MoldSummary.summarize(rows: [Self.heatRow("2026-01-01", [0])], sensor: 0)
        #expect(s.heatHours == 0 && MoldSummary.heatText(s) == "Heat 0 h")
    }

    @Test func sensorBeyondHeatWidthGivesNilHeat() {
        #expect(MoldSummary.summarize(rows: [Self.heatRow("2026-01-01", [2])], sensor: 3).heatHours == nil)
    }

    @Test func chartLineCombinesMoldAndHeat() {
        let mold = MoldRoomSummary(sensor: 0, totalHours: 3, moldDays: 1, worstDate: "2026-01-01", worstHours: 3, heatHours: 4)
        #expect(MoldSummary.chartLine(mold) == "Mold 3 h, 1 day, worst 01-01 3 h, Heat 4 h")
        let moldOnly = MoldRoomSummary(sensor: 0, totalHours: 3, moldDays: 1, worstDate: "2026-01-01", worstHours: 3)
        #expect(MoldSummary.chartLine(moldOnly) == "Mold 3 h, 1 day, worst 01-01 3 h, Heat --")
        let heatOnly = MoldRoomSummary(sensor: 0, totalHours: nil, moldDays: 0, worstDate: nil, worstHours: nil, heatHours: 6)
        #expect(MoldSummary.chartLine(heatOnly) == "Heat 6 h")
        let none = MoldRoomSummary(sensor: 0, totalHours: nil, moldDays: 0, worstDate: nil, worstHours: nil)
        #expect(MoldSummary.chartLine(none) == nil)
    }

    /// `daily_v7` has 105 columns; the heat hours start after the 94 columns of `daily_v6`.
    private static let dailyV7Columns = 105
    private static let firstHeatColumn = 94

    /// The `daily_v7` fixture with real onboard heat hours (3 and 5 on its last two days).
    @Test func parsesHeatColumnsOfAV7Row() throws {
        var heat = ["", "3", "5"].makeIterator()
        let lines = try Self.fixtureLines("days_reply_v7_sample.txt").enumerated().map { index, line -> String in
            guard index >= 2 else { return line }
            var columns = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            #expect(columns.count == Self.dailyV7Columns)
            columns[Self.firstHeatColumn] = heat.next() ?? ""
            return columns.joined(separator: ",")
        }
        let rows = try DaysReply.parse(lines: lines).rows
        #expect(rows.map { $0.heatHours[0] } == [nil, 3, 5])
        #expect(MoldSummary.summarize(rows: rows, sensor: 0).heatHours == 8)
        #expect(MoldSummary.summarize(rows: rows, sensor: 1).heatHours == nil)
    }
}
