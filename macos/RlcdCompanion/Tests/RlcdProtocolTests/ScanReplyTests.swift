import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct ScanReplyTests {
    static let good = [
        "BEGIN SCAN",
        ScanReply.header,
        "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,45.0,87",
        "AA:BB:CC:DD:EE:02,METER,-45,-,19.0,50.5,",
        "AA:BB:CC:DD:EE:03,BTHOME,-80,3,--,--,",
        "AA:BB:CC:DD:EE:04,ATC,-45,2,22.0,40.0,100",
        "DROPPED,3",
    ]

    @Test func parsesEachFormat() throws {
        let r = try ScanReply.parse(lines: Self.good)
        #expect(r.rows.map(\.format) == ["WOIO", "METER", "BTHOME", "ATC"])
        #expect(r.rows[0].slot == 1)
        #expect(r.rows[0].batteryPct == 87)
        #expect(r.rows[0].temperatureC == 21.5)
        #expect(r.dropped == 3)
    }

    @Test func emptyBatterySlotDashAndMissingValues() throws {
        let r = try ScanReply.parse(lines: Self.good)
        #expect(r.rows[1].batteryPct == nil)
        #expect(r.rows[1].slot == nil)
        #expect(r.rows[2].temperatureC == nil)
    }

    @Test func noDroppedRowGivesZero() throws {
        #expect(try ScanReply.parse(lines: Array(Self.good.dropLast())).dropped == 0)
        #expect(try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header]).rows.isEmpty)
    }

    @Test func sortsStrongestRssiFirstAndKeepsReplyOrderOnTie() throws {
        let r = try ScanReply.parse(lines: Self.good)
        #expect(r.sortedByRssi.map { String($0.mac.suffix(2)) } == ["02", "04", "01", "03"])
    }

    @Test func rejectsBadReplies() {
        #expect(throws: ParseError.self) { try ScanReply.parse(lines: Array(Self.good.dropFirst())) }
        #expect(throws: ParseError.self) { try ScanReply.parse(lines: ["BEGIN SCAN"] + Self.good.dropFirst(2)) }
        let row = "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,45.0,87"
        for bad in [
            "AA:BB:CC:DD:EE,WOIO,-60,1,21.5,45.0,87",
            "AA:BB:CC:DD:EE:GG,WOIO,-60,1,21.5,45.0,87",
            "AA:BB:CC:DD:EE:01,XYZ,-60,1,21.5,45.0,87",
            "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,45.0",
            "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,45.0,87,1",
            "AA:BB:CC:DD:EE:01,WOIO,x,1,21.5,45.0,87",
            "AA:BB:CC:DD:EE:01,WOIO,-60,4,21.5,45.0,87",
            "DROPPED,x",
        ] {
            #expect(throws: ParseError.self) { try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header, row, bad]) }
        }
    }

    @Test func scanReplyPassesTheCrcCheck() {
        var a = ReplyAssembler()
        let body = Self.good.joined(separator: "\n") + "\n"
        let crc = String(format: "%08X", Checksums.crc32(Data(body.utf8)))
        let out = a.append(Data((body + "CRC32 \(crc)\n").utf8))
        #expect(out == [.frame(lines: Self.good)])
    }

    @Test func buildsSetLinePerSlotAndClearLine() {
        for n in 1...3 {
            #expect(SlotAssignment.command(slot: n, mac: "aa:bb:cc:dd:ee:0\(n)")?.line == "SET slot\(n)_mac=AA:BB:CC:DD:EE:0\(n)")
            #expect(SlotAssignment.command(slot: n, mac: nil)?.line == "SET slot\(n)_mac=")
        }
        #expect(SlotAssignment.command(slot: 4, mac: "AA:BB:CC:DD:EE:01") == nil)
        #expect(SlotAssignment.command(slot: 0, mac: nil) == nil)
        #expect(SlotAssignment.command(slot: 1, mac: "AA:BB") == nil)
    }

    @Test func emptyValueOnlyForSlotMacKeys() {
        #expect(ConsoleCommand.set(key: "slot2_mac", value: "").line == "SET slot2_mac=")
        #expect(ConsoleCommand.set(key: "slot4_mac", value: "").line == "SET slot4_mac=")
        #expect(ConsoleCommand.set(key: "slot6_mac", value: "").line == nil)
        #expect(ConsoleCommand.set(key: "temp_unit", value: "").line == nil)
        #expect(ConsoleCommand.scan.line == "SCAN")
    }

    @Test func reportsDuplicateMac() throws {
        #expect(SlotAssignment.conflicts(slot: 2, mac: "aa:bb:cc:dd:ee:01", config: try ConfigReply.parse(lines: ["slot1_mac=AA:BB:CC:DD:EE:01"])) == [1])
        let config = try ConfigReply.parse(lines: ["slot3_mac=AA:BB:CC:DD:EE:09"])
        #expect(SlotAssignment.conflicts(slot: 1, mac: "AA:BB:CC:DD:EE:09", config: config) == [3])
        #expect(SlotAssignment.warning(slot: 1, mac: "AA:BB:CC:DD:EE:09", config: config) != nil)
        #expect(SlotAssignment.warning(slot: 1, mac: "AA:BB:CC:DD:EE:77", config: config) == nil)
    }

    @Test func refusesZeroMacAndBadMac() {
        #expect(SlotAssignment.command(slot: 1, mac: "00:00:00:00:00:00") == nil)
        #expect(SlotAssignment.command(slot: 1, mac: "AA:BB:CC:DD:EE:0G") == nil)
        #expect(SlotAssignment.command(slot: 1, mac: "") == nil)
    }

    @Test func setLineLengthIs31Bytes() {
        // "SET slot1_mac=AA:BB:CC:DD:EE:01" is 31 bytes.
        #expect(SlotAssignment.command(slot: 1, mac: "AA:BB:CC:DD:EE:01")?.line?.utf8.count == 31)
    }

    @Test func conflictWithMixedCaseConfigMac() throws {
        let config = try ConfigReply.parse(lines: ["slot2_mac=aa:bb:cc:dd:ee:09"])
        #expect(SlotAssignment.conflicts(slot: 1, mac: "AA:BB:CC:DD:EE:09", config: config) == [2])
        #expect(SlotAssignment.conflicts(slot: 2, mac: "AA:BB:CC:DD:EE:09", config: config).isEmpty)
        #expect(SlotAssignment.conflicts(slot: 1, mac: "AA:BB:CC:DD:EE:09", config: nil).isEmpty)
    }

    @Test func rejectsNonFiniteValuesAndBadRssi() {
        let head = ["BEGIN SCAN", ScanReply.header]
        for bad in ["AA:BB:CC:DD:EE:01,WOIO,-60,1,nan,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,inf,87",
                    "AA:BB:CC:DD:EE:01,WOIO,9999,1,21.5,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,-60,1,150,45.0,87",
                    "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,101,87", "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,45.0,0x1p3",
                    "AA:BB:CC:DD:EE:01,WOIO,-60,1,abc,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,-60,1,1.2.3,45.0,87",
                    "AA:BB:CC:DD:EE:01,WOIO,-60,1,-,45.0,87",
                    "AA:BB:CC:DD:EE:01,WOIO,-60,1,5.,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,-60,1,.5,45.0,87",
                    "AA:BB:CC:DD:EE:01,WOIO,-60,1,1--2,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,+60,1,21.5,45.0,87",
                    "AA:BB:CC:DD:EE:01,WOIO,-128,1,21.5,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,1,1,21.5,45.0,87",
                    "AA:BB:CC:DD:EE:01,WOIO,-60,+1,21.5,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,-60,1,1-2,45.0,87", "AA:BB:CC:DD:EE:01,WOIO,5,1,21.5,45.0,87"] {
            #expect(throws: ParseError.self) { try ScanReply.parse(lines: head + [bad]) }
        }
    }

    @Test func acceptsRssiBoundsAndDroppedZero() throws {
        for rssi in ["0", "-127"] {
            let r = try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header, "AA:BB:CC:DD:EE:01,WOIO,\(rssi),1,21.5,45.0,87", "DROPPED,0"])
            #expect(r.rows.first?.rssi == Int(rssi))
            #expect(r.dropped == 0)
        }
        #expect(throws: ParseError.self) { try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header, "DROPPED,-1"]) }
        #expect(throws: ParseError.self) { try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header, "DROPPED,+1"]) }
    }

    @Test func rejectsDroppedBeforeRows() {
        let row = "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,45.0,87"
        #expect(throws: ParseError.self) { try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header, "DROPPED,1", row]) }
    }

    @Test func rejectsDuplicateRowsAndSecondDropped() {
        let row = "AA:BB:CC:DD:EE:01,WOIO,-60,1,21.5,45.0,87"
        #expect(throws: ParseError.self) { try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header, row, row.lowercased()]) }
        #expect(throws: ParseError.self) { try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header, "DROPPED,1", "DROPPED,2"]) }
    }

    @Test func scanTimeoutCoversTheFourSecondScan() {
        #expect(SlotAssignment.timeoutSeconds(for: .scan) == 10) // above the 4 s scan
        #expect(SlotAssignment.timeoutSeconds(for: .status) == 10)
        #expect(SlotAssignment.timeoutSeconds(for: .config) == 10)
    }

    @Test func refusesScanOnBle() {
        #expect(!SlotAssignment.scanAllowed(onUsb: false))
        #expect(SlotAssignment.scanAllowed(onUsb: true))
        #expect(SlotAssignment.scanTimeoutSeconds == 10)
    }
}
