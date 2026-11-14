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
        // Slot 4 and slot 5 rows (sensor 4 and 5) parse. Sensor 6 is out of range.
        let wide = try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, "4,0,11.6,57.0,", "5,23,27.3,36.0,"])
        #expect(wide.map(\.sensor) == [4, 5])
        #expect(throws: ParseError.self) { try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, "6,0,1,2,"]) }
        for bad in ["-1,0,1,2,3", "6,0,1,2,3", "0,-1,1,2,3", "0,24,1,2,3"] {
            #expect(throws: ParseError.self) { try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, bad]) }
        }
        #expect(try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header, "5,23,1,2,3"]).count == 1)
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
