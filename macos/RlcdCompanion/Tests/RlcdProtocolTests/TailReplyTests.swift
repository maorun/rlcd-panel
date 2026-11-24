import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct TailReplyTests {
    static func fixtureLines() throws -> [String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/tail_reply_sample.txt")
        return try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
    }

    @Test func parsesFixture() throws {
        let reply = try TailReply.parse(lines: Self.fixtureLines())
        #expect(reply.rows.count == 3)
        #expect(reply.rows.first?.timestamp == "2026-03-28 23:00:00")
        #expect(reply.rows.last?.timestamp == "2026-03-01 04:00:00")
        let row = reply.rows[0]
        #expect(row.batteryPct == 87)
        #expect(row.usb == true)
        #expect(row.sensors.count == 11)
        #expect(row.sensors[0].tempC == 21.5)
        #expect(row.sensors[1].tempC == -3.5)
        #expect(row.sensors[1].batteryPct == 90)
        #expect(row.sensors[2...].allSatisfy { $0.tempC == nil && $0.rh == nil && $0.batteryPct == nil })
        #expect(row.noiseRms == nil && row.noiseDb == nil && row.noiseBand == nil)
        #expect(row.noiseEvents == nil && row.noiseL10 == nil && row.noiseL90 == nil)
    }

    @Test func emptyReplyGivesNoRows() throws {
        let lines = try Self.fixtureLines()
        let reply = try TailReply.parse(lines: ["BEGIN TAIL 0", lines[1]])
        #expect(reply.rows.isEmpty)
    }

    @Test func wrongColumnCountThrows() throws {
        var lines = try Self.fixtureLines()
        let bad = "2026-03-28 23:00:00,87,4.00"
        lines[2] = bad
        #expect(throws: ParseError.badLine(bad)) { try TailReply.parse(lines: lines) }
    }

    @Test func badCountThrows() throws {
        var lines = try Self.fixtureLines()
        lines[0] = "BEGIN TAIL x"
        #expect(throws: ParseError.badLine("BEGIN TAIL x")) { try TailReply.parse(lines: lines) }
        lines[0] = "BEGIN TAIL 2" // three rows follow
        #expect(throws: ParseError.badLine("BEGIN TAIL 2")) { try TailReply.parse(lines: lines) }
        lines[0] = "BEGIN TAIL 73"
        #expect(throws: ParseError.badLine("BEGIN TAIL 73")) { try TailReply.parse(lines: lines) }
    }

    @Test func structuralErrorsThrow() throws {
        let lines = try Self.fixtureLines()
        #expect(throws: ParseError.missingLine("BEGIN TAIL")) { try TailReply.parse(lines: ["BEGIN WEEK"] + lines.dropFirst()) }
        #expect(throws: ParseError.missingLine("header")) { try TailReply.parse(lines: ["BEGIN TAIL 0"]) }
        #expect(throws: ParseError.missingLine("header")) { try TailReply.parse(lines: ["BEGIN TAIL 0", "timestamp,battery_pct"]) }
    }

    @Test func commandAcceptsOnlyOneTo72() {
        #expect(ConsoleCommand.tail(0).line == nil)
        #expect(ConsoleCommand.tail(73).line == nil)
        #expect(ConsoleCommand.tail(-1).line == nil)
        #expect(ConsoleCommand.tail(1).line == "TAIL 1")
        #expect(ConsoleCommand.tail(72).line == "TAIL 72")
        #expect(TailReply.rowChoices.allSatisfy { ConsoleCommand.tail($0).line == "TAIL \($0)" })
    }

    @Test func errorTexts() {
        #expect(TailReply.message("ERR NO_CARD") == "No MicroSD card in the panel")
        #expect(TailReply.message("ERR NO_FILE") == "No log file yet (the panel clock may be invalid)")
        #expect(TailReply.message("ERR BAD_ARG") == "The panel rejected the row count")
        #expect(TailReply.message("ERR READ_FAILED") == "The panel could not read the log file")
        #expect(TailReply.message("ERR OTHER") == "ERR OTHER")
    }
}
