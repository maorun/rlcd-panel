import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct DaysReplyTests {
    static func fixtureLines() throws -> [String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/days_reply_sample.txt")
        return try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
    }

    @Test func parsesFixture() throws {
        let reply = try DaysReply.parse(lines: Self.fixtureLines())
        #expect(reply.rows.count == 3)
        #expect(reply.rows.map(\.date) == ["2025-12-31", "2026-01-01", "2026-01-02"])
        let row = reply.rows[0]
        #expect(row.sensors.count == 11)
        #expect(row.sensors[0].tempMinC == 18.0)
        #expect(row.sensors[0].tempMaxC == 24.5)
        #expect(row.sensors[0].tempMeanC == 21.2)
        #expect(row.sensors[0].rhMin == 40.0)
        #expect(row.sensors[0].rhMax == 55.5)
        #expect(row.sensors[0].rhMean == 47.8)
        #expect(reply.rows.allSatisfy { $0.dryHours.isEmpty }) // a daily_v5 reply holds no dry hours
    }

    /// The `daily_v6` reply of the firmware: 94 columns, the dry hours stay empty in the sample.
    @Test func parsesDailyV6Fixture() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/days_reply_v6_sample.txt")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
        let reply = try DaysReply.parse(lines: lines)
        #expect(reply.rows.count == 3)
        #expect(reply.rows.allSatisfy { $0.dryHours.count == 11 })
        #expect(reply.rows.allSatisfy { $0.dryHours.allSatisfy { $0 == nil } }) // the sample leaves the dry fields empty
    }

    /// The `daily_v7` reply of the firmware: 105 columns, the dry and heat hours stay empty in the sample.
    @Test func parsesDailyV7Fixture() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/days_reply_v7_sample.txt")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
        #expect(lines[1].hasSuffix(",dry_h_10,heat_h_0,heat_h_1,heat_h_2,heat_h_3,heat_h_4,heat_h_5,heat_h_6,heat_h_7,heat_h_8,heat_h_9,heat_h_10"))
        let reply = try DaysReply.parse(lines: lines)
        #expect(reply.rows.map(\.date) == ["2025-12-31", "2026-01-01", "2026-01-02"])
        #expect(reply.rows[0].sensors[0].tempMinC == 18.0)
        #expect(reply.rows[0].sensors[0].rhMax == 55.5)
        #expect(reply.rows.allSatisfy { $0.dryHours.count == 11 && $0.dryHours.allSatisfy { $0 == nil } })
        #expect(reply.rows.allSatisfy { $0.heatHours.count == 11 && $0.heatHours.allSatisfy { $0 == nil } })
    }

    @Test func emptyPairGivesNil() throws {
        let row = try DaysReply.parse(lines: Self.fixtureLines()).rows[0]
        let empty = DailySensorStats(tempMinC: nil, tempMaxC: nil, tempMeanC: nil, rhMin: nil, rhMax: nil, rhMean: nil)
        #expect(row.sensors[1...].allSatisfy { $0 == empty })
        #expect(row.moldHours.count == 11)
        #expect(row.moldHours.allSatisfy { $0 == nil })
        #expect(row.noiseMinDb == nil && row.noisePeakDb == nil && row.noiseEventsSum == nil)
    }

    @Test func wrongColumnCountThrowsBadLine() throws {
        var lines = try Self.fixtureLines()
        let bad = lines[2] + ",1"
        lines[2] = bad
        #expect(throws: ParseError.badLine(bad)) { try DaysReply.parse(lines: lines) }
    }

    @Test func badCountOrHeaderIsRejected() throws {
        var lines = try Self.fixtureLines()
        lines[0] = "BEGIN DAYS x"
        #expect(throws: ParseError.badLine("BEGIN DAYS x")) { try DaysReply.parse(lines: lines) }
        lines[0] = "BEGIN DAYS 2" // three rows follow
        #expect(throws: ParseError.badLine("BEGIN DAYS 2")) { try DaysReply.parse(lines: lines) }
        lines[0] = "BEGIN DAYS 32"
        #expect(throws: ParseError.badLine("BEGIN DAYS 32")) { try DaysReply.parse(lines: lines) }
        lines[0] = "BEGIN TAIL 3"
        #expect(throws: ParseError.missingLine("BEGIN DAYS")) { try DaysReply.parse(lines: lines) }
        #expect(throws: ParseError.missingLine("header")) { try DaysReply.parse(lines: ["BEGIN DAYS 0"]) }
        #expect(throws: ParseError.missingLine("header")) { try DaysReply.parse(lines: ["BEGIN DAYS 0", "date,x"]) }
    }

    @Test func zeroRowsGiveAnEmptyReply() throws {
        let header = try Self.fixtureLines()[1]
        #expect(try DaysReply.parse(lines: ["BEGIN DAYS 0", header]).rows.isEmpty)
    }

    @Test func tooFewColumnsAndBadValuesThrowBadLine() throws {
        var lines = try Self.fixtureLines()
        let short = lines[2].split(separator: ",", omittingEmptySubsequences: false).dropLast().joined(separator: ",")
        lines[2] = short
        #expect(throws: ParseError.badLine(short)) { try DaysReply.parse(lines: lines) }
        lines = try Self.fixtureLines()
        let bad = lines[2].replacingOccurrences(of: "18.0", with: "abc")
        lines[2] = bad
        #expect(throws: ParseError.badLine(bad)) { try DaysReply.parse(lines: lines) }
    }

    @Test func noiseAndMoldColumnsKeepTheirPlace() throws {
        var fields = try Self.fixtureLines()[2].split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        #expect(fields.count == 83)
        fields[1 + 10 * 6] = "5.5" // sensor 10 temp_min_c
        fields[67] = "41"; fields[68] = "62"; fields[69] = "58"; fields[70] = "14"; fields[71] = "9" // noise columns
        fields[72] = "7"; fields[82] = "12" // mold_h_0 and mold_h_10
        let line = fields.joined(separator: ",")
        let reply = try DaysReply.parse(lines: ["BEGIN DAYS 1", try Self.fixtureLines()[1], line])
        let row = reply.rows[0]
        #expect(row.sensors[10].tempMinC == 5.5)
        #expect(row.noiseMinDb == 41 && row.noiseMaxDb == 62 && row.noisePeakDb == 58)
        #expect(row.noisePeakHour == 14 && row.noiseEventsSum == 9)
        #expect(row.moldHours[0] == 7 && row.moldHours[10] == 12)
        #expect(row.moldHours[1...9].allSatisfy { $0 == nil })
    }

    @Test func commandAcceptsOneToThirtyOne() {
        #expect(ConsoleCommand.days(0).line == nil)
        #expect(ConsoleCommand.days(32).line == nil)
        #expect(ConsoleCommand.days(-1).line == nil)
        #expect(ConsoleCommand.days(1).line == "DAYS 1")
        #expect(ConsoleCommand.days(31).line == "DAYS 31")
        #expect(DaysReply.rowChoices.allSatisfy { ConsoleCommand.days($0).line == "DAYS \($0)" })
    }

    @Test func errorTexts() {
        #expect(DaysReply.message("ERR NO_CARD") == "No MicroSD card in the panel")
        #expect(DaysReply.message("ERR NO_FILE") == "No daily file yet (the panel clock may be invalid)")
        #expect(DaysReply.message("ERR BAD_ARG") == "The panel rejected the day count")
        #expect(DaysReply.message("ERR READ_FAILED") == "The panel could not read the daily file")
        #expect(DaysReply.message("ERR OTHER") == "ERR OTHER")
    }

    @Test func rowsEqualTheRowsOfADailyFile() throws {
        let lines = try Self.fixtureLines()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("days-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = lines.dropFirst().joined(separator: "\n") + "\n"
        try file.write(to: folder.appendingPathComponent("daily_v5_2026.csv"), atomically: true, encoding: .utf8)
        let loaded = DailyLog.load(from: folder)
        #expect(loaded.failedFiles.isEmpty)
        let reply = try DaysReply.parse(lines: lines)
        #expect(reply.rows.count == 3)
        #expect(reply.rows == loaded.rows)
    }
}
