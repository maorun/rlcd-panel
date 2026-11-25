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
