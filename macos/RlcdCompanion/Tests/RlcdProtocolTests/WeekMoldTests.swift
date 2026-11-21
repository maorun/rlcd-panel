import Foundation
import Testing
@testable import RlcdProtocol

struct WeekMoldTests {
    /// Reads tests/fixtures/week_reply_sample.txt, the file of the Android phone tests.
    private func fixtureRows() throws -> [WeekRow] {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/week_reply_sample.txt"))
        var assembler = ReplyAssembler()
        guard case .frame(let lines)? = assembler.append(data).first else { Issue.record("fixture is not a valid frame"); return [] }
        return try WeekReply.parse(lines: lines)
    }

    @Test func pointsFollowDayOrderAndFixtureHours() throws {
        let points = WeekMold.points(rows: try fixtureRows(), sensor: 0)
        #expect(points.count == 7)
        #expect(points.map(\.day) == [6, 5, 4, 3, 2, 1, 0])
        #expect(points.map(\.hours) == [1, 0, 4, 3, 2, 7, 0])
        #expect(points.first?.label == "11-11")
        #expect(points.last?.label == "11-17")
    }

    @Test func gapDayGivesNilHours() throws {
        let rows = try fixtureRows()
        #expect(WeekMold.points(rows: rows, sensor: 1)[4].hours == nil)
        let gap = WeekMold.points(rows: rows, sensor: 2)[3]
        #expect(gap.day == 3 && gap.hours == nil && gap.label == "11-14")
    }

    @Test func sevenColumnReplyGivesOnlyNil() throws {
        let rows = try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,2026-11-17,15.0,19.5,40.0,55.0"])
        let points = WeekMold.points(rows: rows, sensor: 0)
        #expect(points.count == 7)
        #expect(points.allSatisfy { $0.hours == nil })
        #expect(WeekMold.points(rows: [], sensor: 0).allSatisfy { $0.hours == nil })
    }
}
