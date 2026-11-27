import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct CsvExportTests {
    /// Reads tests/fixtures/week_reply_sample.txt, the file of the Android phone tests.
    @Test func weekMatchesFixture() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/week_reply_sample.txt"))
        var assembler = ReplyAssembler()
        guard case .frame(let lines)? = assembler.append(data).first else { Issue.record("fixture is not a valid frame"); return }
        let rows = try WeekReply.parse(lines: lines)
        // The 9-column reply has no heat_h: the export appends an empty field to the header and each row.
        let expected = ([WeekReply.heatHeader] + lines.dropFirst(2).map { $0 + "," }).joined(separator: "\n") + "\n"
        let csv = CsvExport.week(rows)
        #expect(csv == expected)
        #expect(csv.contains("\n2,3,2026-11-14,,,,,,,\n"))
    }

    /// Reads tests/fixtures/week_heat_reply_sample.txt: the export keeps the 10-column header and heat_h.
    @Test func weekHeatMatchesFixture() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/week_heat_reply_sample.txt"))
        var assembler = ReplyAssembler()
        guard case .frame(let lines)? = assembler.append(data).first else { Issue.record("fixture is not a valid frame"); return }
        let csv = CsvExport.week(try WeekReply.parse(lines: lines))
        #expect(csv == lines.dropFirst(1).joined(separator: "\n") + "\n")
        #expect(csv.hasPrefix(WeekReply.heatHeader + "\n0,0,2026-11-17,15.0,19.5,40.0,55.0,0,0,1\n"))
        #expect(csv.contains("\n2,3,2026-11-14,,,,,,,\n"))
    }

    @Test func weekHeatHoursColumn() {
        let row = WeekRow(sensor: 1, day: 0, date: "", temperatureMinC: nil, temperatureMaxC: nil,
                          humidityMinPct: 40, humidityMaxPct: 60, moldHours: 1, dryHours: 2, heatHours: 7)
        #expect(CsvExport.week([row]) == WeekReply.heatHeader + "\n1,0,,,,40.0,60.0,1,2,7\n")
    }

    @Test func weekRoundsToOneDecimal() {
        let row = WeekRow(sensor: 1, day: 2, date: "", temperatureMinC: 15.04, temperatureMaxC: 19.5,
                          humidityMinPct: nil, humidityMaxPct: nil)
        #expect(CsvExport.week([row]) == WeekReply.heatHeader + "\n1,2,,15.0,19.5,,,,,\n")
        let mold = WeekRow(sensor: 1, day: 2, date: "", temperatureMinC: nil, temperatureMaxC: nil,
                           humidityMinPct: 40, humidityMaxPct: 60, moldHours: 9, dryHours: 5)
        #expect(CsvExport.week([mold]) == WeekReply.heatHeader + "\n1,2,,,,40.0,60.0,9,5,\n")
    }
}
