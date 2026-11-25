import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct ReceptionReplyTests {
    static let head = ["BEGIN RECEPTION", ReceptionReply.header]
    static let mac1 = "A1:A1:A1:A1:A1:A1"
    static let mac2 = "A2:A2:A2:A2:A2:A2"

    @Test func parsesFixture() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/reception_reply_sample.txt")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
        let r = try ReceptionReply.parse(lines: lines)
        #expect(r.rows.map(\.slot) == [1, 2])
        #expect(r.rows[0].name == "")
        #expect(r.rows[0].scans == 24)
        #expect(r.rows[0].received == 12)
        #expect(r.rows[0].cells == "110100101101001011010010".map { $0 == "1" })
        #expect(r.rows[1].name == "Cellar")
        #expect(r.rows[1].mac == Self.mac2)
        #expect(r.rows[1].received == 4)
        #expect(r.rows[1].isWeak == false)
    }

    @Test func cellsFollowTheMaskOldestFirst() throws {
        let r = try ReceptionReply.parse(lines: Self.head + ["2,\(Self.mac2),Cellar,6,4,101101"])
        #expect(r.rows[0].cells == [true, false, true, true, false, true])
        #expect(r.rows[0].percent == 66)
    }

    @Test func weakBelowHalf() throws {
        let r = try ReceptionReply.parse(lines: Self.head + [
            "1,\(Self.mac1),,6,2,010001", "2,\(Self.mac2),,6,3,010101", "3,A3:A3:A3:A3:A3:A3,,0,0,",
        ])
        #expect(r.rows.map(\.isWeak) == [true, false, false])
        #expect(r.rows[2].cells.isEmpty)
    }

    @Test func emptyReplyGivesNoRows() throws {
        #expect(try ReceptionReply.parse(lines: Self.head).rows.isEmpty)
    }

    @Test func badLinesThrow() {
        let bad = [
            "1,\(Self.mac1),,4,2,10a1",                 // bad mask character
            "1,\(Self.mac1),,6,3,10110",                // 5 characters for 6 scans
            "1,\(Self.mac1),,6,3,101101",               // received does not match the mask (4 ones)
            "1,\(Self.mac1),,6,4,101101,extra",         // wrong column count
            "1,\(Self.mac1),,25,0,0000000000000000000000000", // scans above 24
            "11,\(Self.mac1),,6,4,101101",              // slot above 10
            "1,ZZ,,6,4,101101",                         // bad MAC
            "1,\(Self.mac1),,4,2,101101",              // mask longer than scans
            "x,\(Self.mac1),,6,4,101101",              // non-numeric slot
            "-1,\(Self.mac1),,6,4,101101",             // negative slot
            "01,\(Self.mac1),,6,4,101101",             // leading zero
            "1,\(Self.mac1),,-0,0,",                   // signed zero
            "1,\(Self.mac1),,-1,0,",                   // negative scans
            "1,\(Self.mac1),,6,7,101101",              // received above scans
            "1,\(Self.mac1),,6,4",                     // short column count
        ]
        for line in bad {
            #expect(throws: ParseError.badLine(line)) { try ReceptionReply.parse(lines: Self.head + [line]) }
        }
    }

    @Test func repeatedSlotThrows() {
        let dup = "1,\(Self.mac2),,6,4,101101"
        #expect(throws: ParseError.badLine(dup)) {
            try ReceptionReply.parse(lines: Self.head + ["1,\(Self.mac1),,6,4,101101", dup])
        }
    }

    @Test func missingHeaderThrows() {
        #expect(throws: ParseError.missingLine("BEGIN RECEPTION")) { try ReceptionReply.parse(lines: []) }
        #expect(throws: ParseError.missingLine(ReceptionReply.header)) { try ReceptionReply.parse(lines: ["BEGIN RECEPTION"]) }
    }

    @Test func commandText() {
        #expect(ConsoleCommand.reception.line == "RECEPTION")
    }
}
