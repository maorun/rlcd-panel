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
        let expected = lines.dropFirst(1).joined(separator: "\n") + "\n"
        let csv = CsvExport.week(rows)
        #expect(csv == expected)
        #expect(csv.contains("\n2,3,2026-11-14,,,,\n"))
    }

    @Test func weekRoundsToOneDecimal() {
        let row = WeekRow(sensor: 1, day: 2, date: "", temperatureMinC: 15.04, temperatureMaxC: 19.5,
                          humidityMinPct: nil, humidityMaxPct: nil)
        #expect(CsvExport.week([row]) == WeekReply.header + "\n1,2,,15.0,19.5,,\n")
    }
}
