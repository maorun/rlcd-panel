import Testing
import Foundation
@testable import RlcdProtocol

@Suite struct TodayRoomPickerTests {
    @Test func commandLines() {
        #expect(ConsoleCommand.todaySensor(3).line == "TODAY 3")
        #expect(ConsoleCommand.todaySensor(0).line == "TODAY 0")
        #expect(ConsoleCommand.todaySensor(10).line == "TODAY 10")
        #expect(ConsoleCommand.today.line == "TODAY")
    }

    @Test func outOfRangeGivesNoLine() {
        #expect(ConsoleCommand.todaySensor(11).line == nil)
        #expect(ConsoleCommand.todaySensor(-1).line == nil)
    }

    @Test func badArgFallsBackToPlainToday() {
        #expect(TodayReply.retryAfterError(.todaySensor(3), text: "ERR BAD_ARG") == .today)
        #expect(TodayReply.retryAfterError(.todaySensor(3), text: "ERR NO_TIME") == nil)
        #expect(TodayReply.retryAfterError(.today, text: "ERR BAD_ARG") == nil)
        #expect(TodayReply.retryAfterError(.week, text: "ERR BAD_ARG") == nil)
    }

    @Test func nearMissTextGivesNoRetry() {
        #expect(TodayReply.retryAfterError(.todaySensor(3), text: "ERR BAD_ARG ") == nil)
        #expect(TodayReply.retryAfterError(.todaySensor(3), text: "err bad_arg") == nil)
    }

    @Test func keepsOnlyTheSelectedRoom() {
        let rows = [0, 1, 3].map {
            TodayRow(sensor: $0, name: "s\($0)", hours: 1, temperatureMinC: 1, temperatureMaxC: 2, temperatureMeanC: 1.5,
                     humidityMinPct: 40, humidityMaxPct: 41, humidityMeanPct: 40.5)
        }
        #expect(TodayReply.keepOnly(rows, sensor: 3).map(\.sensor) == [3])
        #expect(TodayReply.keepOnly(rows, sensor: 5).isEmpty)
        #expect(TodayReply.keepOnly(rows, sensor: nil).count == 3)
    }

    /// Reads tests/fixtures/today_one_reply_sample.txt (one row of slot 1).
    @Test func parsesSingleRowFixture() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/today_one_reply_sample.txt"))
        var assembler = ReplyAssembler()
        guard case .frame(let lines)? = assembler.append(data).first else { Issue.record("fixture is not a valid frame"); return }
        let rows = try TodayReply.parse(lines: lines)
        #expect(rows == [TodayRow(sensor: 1, name: "slot1", hours: 2, temperatureMinC: -3.5, temperatureMaxC: -2.5,
                                  temperatureMeanC: -3.0, humidityMinPct: 80.0, humidityMaxPct: 81.0, humidityMeanPct: 80.5)])
    }
}

@Suite struct TodayHoursTests {
    private func frame(_ body: String) -> [String] { body.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) }
    private let head = "BEGIN TODAY\n" + TodayReply.headerWithHours

    @Test func oldHeaderGivesNil() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/today_reply_sample.txt"))
        var assembler = ReplyAssembler()
        guard case .frame(let lines)? = assembler.append(data).first else { Issue.record("bad fixture"); return }
        let rows = try TodayReply.parse(lines: lines)
        #expect(rows.count == 2)
        #expect(rows.allSatisfy { $0.moldHours == nil && $0.dryHours == nil })
    }

    @Test func parsesNewRow() throws {
        let rows = try TodayReply.parse(lines: frame(head + "\n0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3,0"))
        #expect(rows[0].moldHours == 3)
        #expect(rows[0].dryHours == 0)
    }

    @Test func emptyDryFieldGivesNil() throws {
        let rows = try TodayReply.parse(lines: frame(head + "\n0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,2,"))
        #expect(rows[0].moldHours == 2)
        #expect(rows[0].dryHours == nil)
    }

    @Test func headerSelectsColumnCount() {
        let old = "BEGIN TODAY\n" + TodayReply.header
        #expect(throws: ParseError.self) { try TodayReply.parse(lines: frame(old + "\n0,onboard,5,1,2,1.5,40,41,42,1,0")) }
        #expect(throws: ParseError.self) { try TodayReply.parse(lines: frame(head + "\n0,onboard,5,1,2,1.5,40,41,42")) }
    }

    @Test func badNewRowsThrow() {
        for body in ["0,onboard,5,1,2,1.5,40,41,42,1", "0,onboard,5,1,2,1.5,40,41,42,7,0",
                     "0,onboard,24,1,2,1.5,40,41,42,25,0", "0,onboard,5,1,2,1.5,40,41,42,0,6", "0,onboard,5,1,2,1.5,40,41,42,x,0"] {
            #expect(throws: ParseError.self) { try TodayReply.parse(lines: frame(head + "\n" + body)) }
        }
    }
}

@Suite struct SharedFixtureTests {
    private func fixtureLines(_ name: String) throws -> [String] {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/\(name)"))
        var assembler = ReplyAssembler()
        guard case .frame(let lines)? = assembler.append(data).first else { Issue.record("\(name) is not a valid frame"); return [] }
        return lines
    }

    /// Reads tests/fixtures/today_hours_reply_sample.txt (11 columns, two rows).
    @Test func parsesHoursFixture() throws {
        let rows = try TodayReply.parse(lines: try fixtureLines("today_hours_reply_sample.txt"))
        #expect(rows.count == 2)
        #expect(rows.map(\.sensor) == [0, 1])
        #expect(rows.map(\.name) == ["onboard", "slot1"])
        #expect(rows.map(\.hours) == [5, 2])
        #expect(rows.map(\.moldHours) == [2, 2])
        #expect(rows.map(\.dryHours) == [3, 0])
    }

    /// Reads tests/fixtures/today_hours_one_reply_sample.txt (one row of slot 1).
    @Test func parsesHoursOneFixture() throws {
        let rows = try TodayReply.parse(lines: try fixtureLines("today_hours_one_reply_sample.txt"))
        #expect(rows.count == 1)
        #expect(rows[0].sensor == 1)
        #expect(rows[0].name == "slot1")
        #expect(rows[0].hours == 2)
        #expect(rows[0].moldHours == 2)
        #expect(rows[0].dryHours == 0)
    }

    /// Reads tests/fixtures/history_reply_sample.txt.
    @Test func parsesHistoryFixture() throws {
        let rows = try HistoryReply.parse(lines: try fixtureLines("history_reply_sample.txt"))
        #expect(rows.count == 6)
        #expect(rows[0] == HistoryRow(sensor: 0, ageHours: 0, temperatureC: 21.4, humidityPct: 45.0, noiseDb: 38))
        #expect(rows[1].noiseDb == nil)
        #expect(rows[5] == HistoryRow(sensor: 3, ageHours: 0, temperatureC: nil, humidityPct: 70.0, noiseDb: nil))
    }
}
