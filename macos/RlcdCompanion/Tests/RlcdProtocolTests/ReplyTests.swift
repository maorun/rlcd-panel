import Foundation
import Testing
@testable import RlcdProtocol

let statusText = """
SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK
SENSOR 1 Wohnzimmer temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK
SENSOR 2 Balkon temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY
SENSOR 3 Keller temp=15.0 rh=70.0 bat=9 rssi=-80 age=2000 state=BATLOW
SD status=OK age=300
RESET reason=POWERON abnormal=0
SHTC3 nack=0 short=0 crc=0 run=0
BLE rejected=2 last=AA:BB:CC:DD:EE:FF woio=1 meter=1 bthome=0 atc=0
POWER src=USB v=4.05 pct=88 alerts=09 muted=0
RUNTIME h=-- samples=3 cycles=1.2 scan_saved_ms=1500

"""
let historyText = """
BEGIN HISTORY
sensor,age_h,temp_c,rh_pct,noise_db
0,0,21.4,45.0,38
0,1,21.0,46.5,
1,0,22.1,48.5,
3,0,,70.0,

"""
let weekText = """
BEGIN WEEK
sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h
0,0,2026-11-14,19.5,22.0,40.5,55.0,6
0,1,2026-11-13,,,,,
1,0,,10.0,20.0,40.0,60.0,

"""
let eventsText = """
BEGIN EVENTS
time,event,detail
2026-11-14 10:00:00,EVENTS_DROPPED,3
2026-11-14 09:00:00,RESET,1
,SLOT_ASSIGN,513

"""
let configText = """
temp_offset_c=-1.9
rh_offset_pct=0.0
temp_unit=C
ble_console=2
SOURCE SD
REJECTED line=7

"""

/// A reply as the firmware sends it: the text and its `CRC32` line.
func framed(_ text: String) -> Data {
    Data((text + String(format: "CRC32 %08x\n", Checksums.crc32(Array(text.utf8)))).utf8)
}

func chunks(_ d: Data, size: Int) -> [Data] {
    stride(from: 0, to: d.count, by: size).map { d.subdata(in: $0..<min($0 + size, d.count)) }
}

struct ReplyAssemblerTests {
    @Test func recordedCrcValuesMatchZlib() {
        // Values computed with Python zlib.crc32 over the texts above.
        #expect(String(format: "%08x", Checksums.crc32(Array(statusText.utf8))) == "8e7ee74b")
        #expect(String(format: "%08x", Checksums.crc32(Array(historyText.utf8))) == "235c8b1b")
        #expect(String(format: "%08x", Checksums.crc32(Array(eventsText.utf8))) == "d25b5b4e")
        #expect(String(format: "%08x", Checksums.crc32(Array(configText.utf8))) == "c9d69afa")
    }

    @Test func correctCrcIsAccepted() throws {
        var a = ReplyAssembler()
        let r = a.append(framed(statusText))
        #expect(r.count == 1)
        guard case let .frame(lines) = try #require(r.first) else { Issue.record("no frame"); return }
        #expect(lines.count == 10)
        #expect(lines.first?.hasPrefix("SENSOR 0") == true)
    }

    @Test func changedByteIsRejected() throws {
        var data = framed(historyText)
        data[40] ^= 0x01
        var a = ReplyAssembler()
        let r = a.append(data)
        guard case let .corrupt(.badCrc(expected, actual)) = try #require(r.first) else { Issue.record("not corrupt"); return }
        #expect(expected != actual)
    }

    @Test func malformedCrcLine() {
        var a = ReplyAssembler()
        let r = a.append(Data((historyText + "CRC32 xyz\n").utf8))
        #expect(r == [.corrupt(.malformedCrcLine("CRC32 xyz"))])
    }

    @Test(arguments: [1, 7, 20, 185])
    func replySplitOverNotifications(size: Int) throws {
        var a = ReplyAssembler()
        var out: [ConsoleReply] = []
        for c in chunks(framed(statusText), size: size) { out += a.append(c) }
        #expect(out.count == 1)
        guard case let .frame(lines) = try #require(out.first) else { Issue.record("no frame"); return }
        #expect(lines.joined(separator: "\n") + "\n" == statusText.trimmingCharacters(in: .newlines) + "\n")
    }

    @Test func twoRepliesBackToBackAfterAFailure() {
        var a = ReplyAssembler()
        var bad = framed(eventsText)
        bad[20] ^= 0x04
        let r = a.append(bad + framed(configText))
        #expect(r.count == 2)
        if case .corrupt = r[0] {} else { Issue.record("first not corrupt") }
        if case .frame = r[1] {} else { Issue.record("second not a frame") }
    }

    @Test func statusLineReplies() {
        var a = ReplyAssembler()
        #expect(a.append(Data("OK SET temp_unit\nERR READ_ONLY\n".utf8)) == [.status("OK SET temp_unit"), .status("ERR READ_ONLY")])
    }

    private func statusLines(power: String) -> [String] {
        ["SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK", power]
    }

    @Test func sdFreeMbParsing() throws {
        let power = "POWER src=USB v=4.05 pct=88 alerts=0A muted=0"
        func parse(_ sd: String) throws -> StatusReply {
            try StatusReply.parse(lines: ["SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK", sd, power])
        }
        let ok = try parse("SD status=OK age=30 free_mb=14820")
        #expect(ok.sdFreeMb == 14820 && ok.sdStatus == "OK")
        #expect(try parse("SD status=OK age=30 free_mb=--").sdFreeMb == nil)
        #expect(try parse("SD status=OK age=30").sdFreeMb == nil)
        let bad = "SD status=OK age=30 free_mb=x"
        #expect(throws: ParseError.badLine(bad)) { try parse(bad) }
    }

    @Test func ackedMaskOfSharedFixtureIsZero() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/status_reply_sample.txt"))
        var a = ReplyAssembler()
        guard case .frame(let lines)? = a.append(data).first else { Issue.record("fixture is not a frame"); return }
        #expect(try StatusReply.parse(lines: lines).ackedMask == 0)
    }

    @Test func moldHoursOfSharedFixtureAndBadFields() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/status_reply_sample.txt"))
        var a = ReplyAssembler()
        guard case .frame(let lines)? = a.append(data).first else { Issue.record("fixture is not a frame"); return }
        let s = try StatusReply.parse(lines: lines)
        #expect(s.sensors.map(\.moldHours) == [24, 3, nil, nil, 5, nil])
        let without = lines.map { $0.replacingOccurrences(of: " mold_h=3", with: "") }
        #expect(try StatusReply.parse(lines: without).sensors[1].moldHours == nil)
        let bad = lines.map { $0.hasPrefix("SENSOR 1 ") ? $0.replacingOccurrences(of: "mold_h=3", with: "mold_h=x") : $0 }
        #expect(throws: ParseError.badLine(bad[1])) { try StatusReply.parse(lines: bad) }
    }

    @Test func ackedMaskParsing() throws {
        let acked = try StatusReply.parse(lines: statusLines(power: "POWER src=USB v=4.05 pct=88 alerts=0A muted=0 acked=02"))
        #expect(acked.ackedMask == 2)
        let missing = try StatusReply.parse(lines: statusLines(power: "POWER src=USB v=4.05 pct=88 alerts=0A muted=0"))
        #expect(missing.ackedMask == 0)
        let bad = "POWER src=USB v=4.05 pct=88 alerts=0A muted=0 acked=zz"
        #expect(throws: ParseError.badLine(bad)) { try StatusReply.parse(lines: statusLines(power: bad)) }
    }

    @Test func ackCommandLines() {
        #expect(ConsoleCommand.ack(1).line == "ACK 1")
        for bit in 0...7 { #expect(ConsoleCommand.ack(bit).line == "ACK \(bit)") }
        #expect(ConsoleCommand.ack(8).line == nil)
        #expect(ConsoleCommand.ack(-1).line == nil)
    }

    @Test func ackErrorTexts() {
        #expect(ConsoleCommand.ackMessage("ERR NOT_ACTIVE") == "The alert is off")
        #expect(ConsoleCommand.ackMessage("ERR READ_ONLY") == "Set ble_console=2 to apply")
        #expect(ConsoleCommand.ackMessage("OK ACK 1") == "OK ACK 1")
    }

    @Test func slotClearReplies() {
        var a = ReplyAssembler()
        #expect(a.append(Data("OK SLOT CLEAR 2\nERR FIXED_MAC\nERR BAD_ARG\n".utf8))
                == [.status("OK SLOT CLEAR 2"), .status("ERR FIXED_MAC"), .status("ERR BAD_ARG")])
    }

    @Test func oversizedInputIsRejectedAndAssemblerRecovers() {
        var a = ReplyAssembler()
        let r = a.append(Data(repeating: 0x41, count: ReplyAssembler.maxLineBytes + 1))
        #expect(r == [.corrupt(.tooLarge)])
        var many = Data()
        for _ in 0...ReplyAssembler.maxLines { many.append(Data("x\n".utf8)) }
        #expect(a.append(many) == [.corrupt(.tooLarge)])
        if case .frame = a.append(framed(configText)).first {} else { Issue.record("no frame after recovery") }
    }

    @Test func resetDropsPartialReply() {
        var a = ReplyAssembler()
        _ = a.append(Data("BEGIN HISTORY\nsens".utf8))
        a.reset()
        let r = a.append(framed(configText))
        if case .frame = r.first {} else { Issue.record("no frame") }
    }

    @Test func crLfStreamIsRejected() {
        // The CRC covers the LF-only text, so a CRLF stream does not match.
        let crlf = configText.replacingOccurrences(of: "\n", with: "\r\n")
        var a = ReplyAssembler()
        let r = a.append(Data((crlf + String(format: "CRC32 %08x\r\n", Checksums.crc32(Array(configText.utf8)))).utf8))
        if case .corrupt = r.first {} else { Issue.record("not corrupt") }
    }
}

struct ReplyParserTests {
    private func lines(_ text: String) -> [String] {
        text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
    }

    @Test func status() throws {
        let s = try StatusReply.parse(lines: lines(statusText))
        #expect(s.sensors.count == 4)
        #expect(s.sensors[0].temperature == 21.4)
        #expect(s.sensors[0].battery == nil)
        #expect(s.sensors[1].battery == 87)
        #expect(s.sensors[1].rssi == -61)
        #expect(s.sensors[2].temperature == nil)
        #expect(s.sensors[3].state == "BATLOW")
        #expect(s.sdStatus == "OK")
        #expect(s.resetReason == "POWERON")
        #expect(s.isUsbPower)
        #expect(s.cellVolts == 4.05)
        #expect(s.cellPercent == 88)
        #expect(s.alertMask == 0x09)
        #expect(!s.muted)
        #expect(s.otherLines["SHTC3"] != nil)
        #expect(s.otherLines["BLE"] != nil)
        #expect(s.otherLines["RUNTIME"] != nil)
    }

    @Test func badStatusAndEventRowsFail() {
        #expect(throws: ParseError.self) { try StatusReply.parse(lines: ["SENSOR x Onboard temp=1"]) }
        #expect(throws: ParseError.self) { try EventsReply.parse(lines: ["BEGIN EVENTS", EventsReply.header, "t,RESET,abc"]) }
        #expect(throws: ParseError.self) { try EventsReply.parse(lines: ["BEGIN EVENTS", EventsReply.header, "RESET,1"]) }
    }

    @Test func statusWithoutPowerLineFails() {
        #expect(throws: ParseError.missingLine("POWER")) {
            try StatusReply.parse(lines: ["SENSOR 0 Onboard temp=1 rh=2 bat=-- rssi=-- age=1 state=OK"])
        }
        #expect(throws: ParseError.self) {
            try StatusReply.parse(lines: ["POWER src=USB v=4.0 pct=1 alerts=zz muted=0"])
        }
    }

    @Test func history() throws {
        let rows = try HistoryReply.parse(lines: lines(historyText))
        #expect(rows.count == 4)
        #expect(rows[0] == HistoryRow(sensor: 0, ageHours: 0, temperatureC: 21.4, humidityPct: 45.0, noiseDb: 38))
        #expect(rows[1].noiseDb == nil)
        #expect(rows[3].temperatureC == nil)
        #expect(rows[3].humidityPct == 70.0)
        #expect(throws: ParseError.self) { try HistoryReply.parse(lines: ["BEGIN HISTORY", "wrong"]) }
        #expect(throws: ParseError.self) { try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, "0,x,1,2,3"]) }
        // Slots 4 to 10 rows (sensor 4 to 10) parse. Sensor 11 is out of range.
        let wide = try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, "4,0,11.6,57.0,", "10,23,27.3,36.0,"])
        #expect(wide.map(\.sensor) == [4, 10])
        #expect(throws: ParseError.self) { try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, "11,0,1,2,"]) }
        for bad in ["-1,0,1,2,3", "11,0,1,2,3", "0,-1,1,2,3", "0,24,1,2,3"] {
            #expect(throws: ParseError.self) { try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, bad]) }
        }
        #expect(try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, "10,23,1,2,3"]).count == 1)
    }

    @Test func week() throws {
        let rows = try WeekReply.parse(lines: lines(weekText))
        #expect(rows.count == 3)
        #expect(rows[0] == WeekRow(sensor: 0, day: 0, date: "2026-11-14", temperatureMinC: 19.5, temperatureMaxC: 22.0,
                                   humidityMinPct: 40.5, humidityMaxPct: 55.0, moldHours: 6))
        #expect(rows[0].moldHours == 6 && rows[1].moldHours == nil && rows[2].moldHours == nil) // a gap day and an empty field
        #expect(rows[1].temperatureMinC == nil && rows[1].humidityMaxPct == nil && rows[1].date == "2026-11-13")
        #expect(rows[2].date.isEmpty && rows[2].temperatureMaxC == 20.0)
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", "wrong"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,2026-11-14,1,2,3"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,7,,,,,"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,14.11.2026,,,,"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,25"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,x"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,+5"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,,1,2,3,4,5"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4", "0,0,,1,2,3,4"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,5,2,3,4"]) }
        #expect(ConsoleCommand.week.line == "WEEK")
    }

    /// Reads tests/fixtures/week_reply_sample.txt, the file of the Android phone tests.
    @Test func weekSharedFixture() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/week_reply_sample.txt"))
        var assembler = ReplyAssembler()
        guard case .frame(let fixtureLines)? = assembler.append(data).first else { Issue.record("fixture is not a valid frame"); return }
        let rows = try WeekReply.parse(lines: fixtureLines)
        #expect(rows.count == 42)
        #expect(rows[0] == WeekRow(sensor: 0, day: 0, date: "2026-11-17", temperatureMinC: 15.0, temperatureMaxC: 19.5,
                                   humidityMinPct: 40.0, humidityMaxPct: 55.0, moldHours: 0))
        #expect(rows[1].moldHours == 7)
        #expect(rows[41] == WeekRow(sensor: 5, day: 6, date: "2026-11-11", temperatureMinC: 23.0, temperatureMaxC: 27.5,
                                    humidityMinPct: 56.0, humidityMaxPct: 71.0, moldHours: 24))
        let gaps = rows.filter { $0.temperatureMinC == nil }
        #expect(gaps.count == 1)
        #expect(gaps[0].sensor == 2 && gaps[0].day == 3 && gaps[0].date == "2026-11-14")
        #expect(gaps[0].temperatureMaxC == nil && gaps[0].humidityMinPct == nil && gaps[0].humidityMaxPct == nil)
        #expect(gaps[0].moldHours == nil)
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,2026-11-14,1,2,3,4,5,6"]) }
    }

    /// A firmware without the `mold_h` column sends 7 columns: `moldHours` is `nil`.
    @Test func weekSevenColumnsGiveNilMoldHours() throws {
        let rows = try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,2026-11-14,19.5,22.0,40.5,55.0"])
        #expect(rows.count == 1 && rows[0].moldHours == nil && rows[0].humidityMaxPct == 55.0)
    }

    @Test func events() throws {
        let rows = try EventsReply.parse(lines: lines(eventsText))
        #expect(rows.count == 3)
        #expect(rows[0] == EventRow(time: "2026-11-14 10:00:00", event: "EVENTS_DROPPED", detail: 3))
        #expect(rows[2] == EventRow(time: "", event: "SLOT_ASSIGN", detail: 513))
        #expect(try EventsReply.parse(lines: ["BEGIN EVENTS", EventsReply.header]).isEmpty)
    }

    @Test func config() throws {
        let c = try ConfigReply.parse(lines: lines(configText))
        #expect(c.values["temp_offset_c"] == "-1.9")
        #expect(c.keys.first == "temp_offset_c")
        #expect(c.source == "SD")
        #expect(c.rejectedLines == [7])
        #expect(c.bleConsoleLevel == 2)
        let without = try ConfigReply.parse(lines: ["temp_unit=C"])
        #expect(without.bleConsoleLevel == 0)
        #expect(without.temperatureUnit == "C")
        #expect(try ConfigReply.parse(lines: ["temp_unit=F"]).temperatureUnit == "F")
        #expect(try ConfigReply.parse(lines: ["temp_unit=K"]).temperatureUnit == nil)
        #expect(try ConfigReply.parse(lines: ["x=1"]).temperatureUnit == nil)
        #expect(throws: ParseError.self) { try ConfigReply.parse(lines: ["garbage"]) }
    }

    @Test func setCommand() {
        #expect(ConsoleCommand.set(key: "temp_unit", value: "F").line == "SET temp_unit=F")
        #expect(ConsoleCommand.set(key: "temp_unit", value: "F").data == Data("SET temp_unit=F\n".utf8))
        #expect(ConsoleCommand.set(key: "a b", value: "1").line == nil)
        #expect(ConsoleCommand.set(key: "k", value: "").line == nil)
        #expect(ConsoleCommand.set(key: "k=", value: "1").line == nil)
        #expect(ConsoleCommand.set(key: "k", value: "1\nSET x=1").line == nil)
        #expect(ConsoleCommand.set(key: String(repeating: "k", count: 40), value: "1").line == nil)
        // MAX_LINE_SIZE of the firmware is 40 bytes without the line end: "SET " + key + "=" + value.
        let edge40 = ConsoleCommand.set(key: String(repeating: "k", count: 34), value: "1")
        #expect(edge40.line?.utf8.count == 40)
        #expect(ConsoleCommand.set(key: String(repeating: "k", count: 35), value: "1").line == nil)
        #expect(ConsoleCommand.history.line == "HISTORY")
    }
}
