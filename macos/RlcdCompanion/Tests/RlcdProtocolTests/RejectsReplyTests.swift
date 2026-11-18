import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct RejectsReplyTests {
    static let head = ["BEGIN REJECTS", RejectsReply.header]

    @Test func parsesFixture() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/rejects_reply_sample.txt")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
        let r = try RejectsReply.parse(lines: lines)
        #expect(r.rows.count == 4)
        #expect(r.rows.map(\.reason) == [.full, .unassigned, .jump, .disabled])
        #expect(r.rows.map(\.count) == [2, 1, 1, 255])
        #expect(r.rows.map(\.format) == ["METER", "ATC", "WOIO", "THERMOPRO"])
        #expect(r.dropped == 1)
    }

    @Test func emptyLogGivesNoRows() throws {
        let r = try RejectsReply.parse(lines: Self.head)
        #expect(r.rows.isEmpty)
        #expect(r.dropped == 0)
    }

    @Test func badLinesThrow() {
        let bad = [
            "AA:00:00:00:00:01,WOIO,BOGUS,1",
            "AA:00:00:00:00:01,WOIO,FULL,0",
            "AA:00:00:00:00:01,WOIO,FULL,256",
            "AA:00:00:00:00:01,WOIO,FULL,1,2",
            "AA:00:00:00:00:01,OTHER,FULL,1",
            "ZZ:00:00:00:00:01,WOIO,FULL,1",
        ]
        for line in bad {
            #expect(throws: ParseError.badLine(line)) { try RejectsReply.parse(lines: Self.head + [line]) }
        }
    }

    @Test func structuralErrorsThrow() {
        #expect(throws: ParseError.missingLine("BEGIN REJECTS")) { try RejectsReply.parse(lines: ["BEGIN SCAN", RejectsReply.header]) }
        #expect(throws: ParseError.missingLine(RejectsReply.header)) { try RejectsReply.parse(lines: ["BEGIN REJECTS", "mac,format"]) }
        let row = "AA:00:00:00:00:01,WOIO,FULL,1"
        #expect(throws: ParseError.badLine("DROPPED,1")) { try RejectsReply.parse(lines: Self.head + ["DROPPED,1", row]) }
        #expect(throws: ParseError.badLine(row)) { try RejectsReply.parse(lines: Self.head + [row, row]) }
        #expect(throws: ParseError.badLine("AA:00:00:00:00:01,woio,FULL,1")) {
            try RejectsReply.parse(lines: Self.head + ["AA:00:00:00:00:01,woio,FULL,1"])
        }
    }

    @Test func badDroppedRowsThrow() {
        for line in ["DROPPED,x", "DROPPED,-1", "DROPPED,1,2"] {
            #expect(throws: ParseError.badLine(line)) { try RejectsReply.parse(lines: Self.head + [line]) }
        }
    }

    @Test func lowercaseMacIsUpperCased() throws {
        let r = try RejectsReply.parse(lines: Self.head + ["aa:00:00:00:00:01,WOIO,JUMP,3"])
        #expect(r.rows[0].mac == "AA:00:00:00:00:01")
    }

    @Test func fixHints() {
        #expect(RejectReason.full.fixHint == "Free a slot")
        #expect(RejectReason.unassigned.fixHint == "Assign to slot")
        #expect(RejectReason.disabled.fixHint == "Set slot<N>_enabled=1")
        #expect(RejectReason.jump.fixHint == "Check the sensor position")
        #expect(RejectReason.weak.fixHint == "Move the sensor nearer or lower auto_assign_min_rssi")
    }

    @Test func weakReasonParses() throws {
        let r = try RejectsReply.parse(lines: Self.head + ["AA:00:00:00:00:02,METER,WEAK,4"])
        #expect(r.rows[0].reason == .weak && r.rows[0].count == 4)
    }

    @Test func commandLine() {
        #expect(ConsoleCommand.rejects.line == "REJECTS")
    }
}
