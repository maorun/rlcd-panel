import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct ConsoleCommandTests {
    struct HelpRow: Equatable {
        let word: String
        let ble: Int
    }

    static func helpRows() throws -> [HelpRow] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/help_reply_sample.txt")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
        #expect(lines.first == "BEGIN HELP")
        #expect(lines.dropFirst().first == "command,usb,ble,write,card")
        return lines.dropFirst(2).filter { !$0.hasPrefix("CRC32") }.map { line in
            let fields = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            #expect(fields.count == 5)
            return HelpRow(word: fields[0], ble: Int(fields[2]) ?? -1)
        }
    }

    /// One value of each `ConsoleCommand` case. `ConsoleCommand` is not `CaseIterable`: add a sample for each new case.
    static let samples: [ConsoleCommand] = [
        .status, .history, .events, .week, .config, .version, .scan, .screen,
        .historySensor(1), .historySensorHours(1, 6), .eventsNewest(4), .rejects, .alerts, .reception,
        .configChanged, .selfTest, .list, .dump("climate_v5_202601.csv"), .time,
        .timeSet(Date(timeIntervalSince1970: 1_800_000_000)), .beep(1), .pattern(1), .mute(true), .snooze(5),
        .ack(1), .ackAll, .rtcTrim, .ntpSync, .identify, .slotClear(1), .slotSwap(1, 2),
        .set(key: "display_invert", value: "0"), .setBegin, .setCommit, .setAbort, .calib(1), .salt(1), .saltMagnesium(1),
        .frsi(room: 0, wall: 1, hours: 6), .spread(hours: 12), .tail(3), .days(3), .today, .todaySensor(1), .help, .reboot,
    ]

    /// The longest fixture word that starts the command text.
    static func word(of line: String, in rows: [HelpRow]) -> HelpRow? {
        rows.filter { line == $0.word || line.hasPrefix($0.word + " ") }.max { $0.word.count < $1.word.count }
    }

    @Test func helpLine() {
        #expect(ConsoleCommand.help.line == "HELP")
    }

    @Test func fixtureHasEachCommandWord() throws {
        let rows = try Self.helpRows()
        #expect(Set(rows.map(\.word)).count == rows.count)
        for command in Self.samples {
            let line = try #require(command.line)
            #expect(Self.word(of: line, in: rows) != nil, "no HELP row for \(line)")
        }
    }

    /// The `ble` rule of each command word (0 not over BLE, 1 each level, 2 only with `ble_console` 2), in fixture order.
    static let expectedBle: [(String, Int)] = [
        ("LIST", 1), ("INFO", 1), ("CONFIG", 1), ("CALIB", 1), ("STATUS", 1), ("BEEP", 1), ("TIME", 1), ("TIME SET", 2),
        ("HISTORY", 1), ("VERSION", 1), ("SET", 2), ("SET BEGIN", 2), ("SET ABORT", 2), ("SET COMMIT", 2), ("EVENTS", 1), ("PATTERN", 1), ("SCAN", 0), ("MUTE", 2), ("SCREEN", 0),
        ("SALT", 1), ("RTC TRIM", 2), ("WEEK", 1), ("SLOT CLEAR", 2), ("SLOT SWAP", 2), ("SLOT REPLACE", 2), ("REJECTS", 1),
        ("ACK", 2), ("IDENTIFY", 1), ("SNOOZE", 2), ("NTP SYNC", 0), ("FRSI", 1), ("SELFTEST", 1), ("SPREAD", 1),
        ("TAIL", 1), ("TODAY", 1), ("DAYS", 1), ("RECEPTION", 1), ("ALERTS", 1), ("DUMP", 0), ("VERIFY", 0), ("HELP", 1), ("REBOOT", 0),
    ]

    @Test func fixtureRowsKeepOrderAndBleRules() throws {
        let rows = try Self.helpRows()
        #expect(rows.map(\.word) == Self.expectedBle.map(\.0))
        #expect(rows.map(\.ble) == Self.expectedBle.map(\.1))
    }

    @Test func bleRuleOfEachSample() throws {
        let rows = try Self.helpRows()
        let expected = Dictionary(uniqueKeysWithValues: Self.expectedBle)
        for command in Self.samples {
            let line = try #require(command.line)
            let row = try #require(Self.word(of: line, in: rows))
            #expect(expected[row.word] == row.ble, "wrong ble rule for \(line)")
        }
    }
}
