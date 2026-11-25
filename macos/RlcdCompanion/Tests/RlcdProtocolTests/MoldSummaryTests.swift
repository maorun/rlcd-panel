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
}
