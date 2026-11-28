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

    @Test func dryHoursOfSharedFixtureAndBadFields() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/status_reply_sample.txt"))
        var a = ReplyAssembler()
        guard case .frame(let lines)? = a.append(data).first else { Issue.record("fixture is not a frame"); return }
        let s = try StatusReply.parse(lines: lines)
        #expect(s.sensors.map(\.dryHours) == [2, nil, nil, nil, nil, nil])
        let five = lines.map { $0.replacingOccurrences(of: "dry_h=2", with: "dry_h=5") }
        #expect(try StatusReply.parse(lines: five).sensors[0].dryHours == 5)
        let without = lines.map { $0.replacingOccurrences(of: " dry_h=2", with: "") }
        #expect(try StatusReply.parse(lines: without).sensors[0].dryHours == nil)
        let bad = lines.map { $0.hasPrefix("SENSOR 0 ") ? $0.replacingOccurrences(of: "dry_h=2", with: "dry_h=x") : $0 }
        #expect(throws: ParseError.badLine(bad[0])) { try StatusReply.parse(lines: bad) }
        let negative = lines.map { $0.hasPrefix("SENSOR 0 ") ? $0.replacingOccurrences(of: "dry_h=2", with: "dry_h=-1") : $0 }
        #expect(throws: ParseError.badLine(negative[0])) { try StatusReply.parse(lines: negative) }
    }

    @Test func heatHoursOfSharedFixtureAndBadFields() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/status_reply_sample.txt"))
        var a = ReplyAssembler()
        guard case .frame(let lines)? = a.append(data).first else { Issue.record("fixture is not a frame"); return }
        let s = try StatusReply.parse(lines: lines)
        #expect(s.sensors.map(\.heatHours) == [3, nil, nil, nil, nil, nil])
        let seven = lines.map { $0.replacingOccurrences(of: "heat_h=3", with: "heat_h=7") }
        #expect(try StatusReply.parse(lines: seven).sensors[0].heatHours == 7)
        let without = lines.map { $0.replacingOccurrences(of: " heat_h=3", with: "") }
        #expect(try StatusReply.parse(lines: without).sensors[0].heatHours == nil)
        let bad = lines.map { $0.hasPrefix("SENSOR 0 ") ? $0.replacingOccurrences(of: "heat_h=3", with: "heat_h=x") : $0 }
        #expect(throws: ParseError.badLine(bad[0])) { try StatusReply.parse(lines: bad) }
        let negative = lines.map { $0.hasPrefix("SENSOR 0 ") ? $0.replacingOccurrences(of: "heat_h=3", with: "heat_h=-1") : $0 }
        #expect(throws: ParseError.badLine(negative[0])) { try StatusReply.parse(lines: negative) }
    }

    @Test func heatHoursTextHasWarningAboveZero() {
        func s(_ h: Int?) -> StatusSensor { StatusSensor(index: 1, name: "A", temperature: nil, humidity: nil, battery: nil, rssi: nil, ageSeconds: nil, state: "OK", heatHours: h) }
        #expect(s(nil).heatText == nil)
        #expect(s(0).heatText == "Heat 0 h")
        #expect(s(3).heatText == "Heat 3 h ⚠")
    }

    @Test func trendsOfSharedFixtureAndBadFields() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/status_reply_sample.txt"))
        var a = ReplyAssembler()
        guard case .frame(let lines)? = a.append(data).first else { Issue.record("fixture is not a frame"); return }
        let s = try StatusReply.parse(lines: lines)
        #expect(s.sensors.map(\.temperatureTrend) == [.rise, .steady, nil, nil, .fall, nil])
        #expect(s.sensors.map(\.humidityTrend) == [.fall, .steady, nil, nil, .rise, nil])
        #expect([SensorTrend.rise, .fall, .steady].map(\.symbol) == ["↑", "↓", "–"])
        let without = lines.map { $0.replacingOccurrences(of: #" (temp|rh)_trend=\S+"#, with: "", options: .regularExpression) }
        let old = try StatusReply.parse(lines: without).sensors
        #expect(old.allSatisfy { $0.temperatureTrend == nil && $0.humidityTrend == nil })
        let badTemp = lines.map { $0.hasPrefix("SENSOR 4 ") ? $0.replacingOccurrences(of: "temp_trend=FALL", with: "temp_trend=up") : $0 }
        #expect(throws: ParseError.badLine(badTemp[4])) { try StatusReply.parse(lines: badTemp) }
        let bad = lines.map { $0.hasPrefix("SENSOR 1 ") ? $0.replacingOccurrences(of: "rh_trend=STEADY", with: "rh_trend=UP") : $0 }
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
        #expect(ConsoleCommand.ackAll.line == "ACK ALL")
    }

    @Test func ackErrorTexts() {
        #expect(ConsoleCommand.ackMessage("ERR NOT_ACTIVE") == "The alert is off")
        #expect(ConsoleCommand.ackMessage("ERR READ_ONLY") == "Set ble_console=2 to apply")
        #expect(ConsoleCommand.ackMessage("OK ACK 1") == "OK ACK 1")
        #expect(ConsoleCommand.ackMessage("OK ACK ALL 22") == "Acknowledged: Humidity, Ventilate")
        #expect(ConsoleCommand.ackMessage("OK ACK ALL 81") == "Acknowledged: Freezer, Heat")
        #expect(ConsoleCommand.ackMessage("OK ACK ALL 00") == "OK ACK ALL 00")
        #expect(ConsoleCommand.ackMessage("OK ACK ALL zz") == "OK ACK ALL zz")
    }

    @Test func ntpSyncCommandAndTexts() {
        #expect(ConsoleCommand.ntpSync.line == "NTP SYNC")
        #expect(SlotAssignment.timeoutSeconds(for: .ntpSync) == 30)
        #expect(SlotAssignment.ntpSyncAllowed(onUsb: true))
        #expect(!SlotAssignment.ntpSyncAllowed(onUsb: false))
        #expect(ConsoleCommand.ntpSyncMessage("OK NTP SYNC") == "Clock synced")
        #expect(ConsoleCommand.ntpSyncMessage("ERR NO_WIFI") == "No Wi-Fi credentials on the panel")
        #expect(ConsoleCommand.ntpSyncMessage("ERR NTP_FAILED NTP_TIMEOUT") == "Clock sync failed: the time server did not answer")
        #expect(ConsoleCommand.ntpSyncMessage("ERR NTP_FAILED WIFI_FAILED") == "Clock sync failed: Wi-Fi did not connect")
        #expect(ConsoleCommand.ntpSyncMessage("ERR NTP_FAILED RTC_WRITE_FAILED") == "Clock sync failed: the clock chip did not accept the time")
        #expect(ConsoleCommand.ntpSyncMessage("ERR NOT_OVER_BLE") == "Clock sync needs the USB serial link")
        #expect(ConsoleCommand.ntpSyncMessage("ERR NTP_FAILED FOO") == "ERR NTP_FAILED FOO")
    }

    @Test func canAcknowledgeAllRule() {
        #expect(!ConsoleCommand.canAcknowledgeAll(alertMask: 0x00, ackedMask: 0x00))
        #expect(!ConsoleCommand.canAcknowledgeAll(alertMask: 0x02, ackedMask: 0x00))
        #expect(ConsoleCommand.canAcknowledgeAll(alertMask: 0x22, ackedMask: 0x00))
        #expect(!ConsoleCommand.canAcknowledgeAll(alertMask: 0x22, ackedMask: 0x02))
        // CHARGE_FULL (0x10) has no chime reminder, so it does not count as an open bit.
        #expect(!ConsoleCommand.canAcknowledgeAll(alertMask: 0x12, ackedMask: 0x00))
        #expect(ConsoleCommand.canAcknowledgeAll(alertMask: 0x32, ackedMask: 0x00))
    }

    @Test func identifyCommandAndReply() throws {
        #expect(ConsoleCommand.identify.line == "IDENTIFY")
        #expect(try IdentifyReply.parse("OK IDENTIFY 3F2A").code == "3F2A")
        #expect(try IdentifyReply.parse("OK IDENTIFY 3f2a").code == "3F2A")
        #expect(throws: ParseError.badLine("OK IDENTIFY 3F2")) { try IdentifyReply.parse("OK IDENTIFY 3F2") }
        #expect(throws: ParseError.badLine("OK IDENTIFY XYZW")) { try IdentifyReply.parse("OK IDENTIFY XYZW") }
        #expect(throws: ParseError.badLine("OK IDENTIFY 3F2A1")) { try IdentifyReply.parse("OK IDENTIFY 3F2A1") }
        #expect(ConsoleCommand.identifyMessage("OK IDENTIFY 3F2A") == "Panel shows RLCD 3F2A")
        #expect(ConsoleCommand.identifyMessage("ERR BAD_ARG") == "The panel rejected the command")
        #expect(ConsoleCommand.identifyMessage("OK IDENTIFY 3F2") == "OK IDENTIFY 3F2")
        #expect(ConsoleCommand.identifyMessage("ERR READ_ONLY") == "ERR READ_ONLY")
    }

    @Test func slotClearReplies() {
        var a = ReplyAssembler()
        #expect(a.append(Data("OK SLOT CLEAR 2\nERR FIXED_MAC\nERR BAD_ARG\n".utf8))
                == [.status("OK SLOT CLEAR 2"), .status("ERR FIXED_MAC"), .status("ERR BAD_ARG")])
    }

    @Test func slotSwapCommandAndMessages() {
        #expect(ConsoleCommand.slotSwap(2, 7).line == "SLOT SWAP 2 7")
        #expect(ConsoleCommand.slotSwap(3, 3).line == nil)
        #expect(ConsoleCommand.slotSwap(1, 11).line == nil)
        #expect(ConsoleCommand.slotSwap(0, 2).line == nil)
        #expect(ConsoleCommand.slotSwapMessage("ERR FIXED_MAC") == "A slot with a fixed MAC cannot be swapped")
        #expect(ConsoleCommand.slotSwapMessage("ERR SLOT_OFF") == "A disabled slot cannot be swapped")
        #expect(ConsoleCommand.slotSwapMessage("ERR READ_ONLY") == "Set ble_console=2 to apply")
        #expect(ConsoleCommand.slotSwapMessage("OK SLOT SWAP 2 7") == "OK SLOT SWAP 2 7")
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

    @Test func weekHeatFixtureKeepsZeroAndGap() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/week_heat_reply_sample.txt")
        let fixture = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
        let rows = try WeekReply.parse(lines: fixture)
        #expect(rows.count == 42)
        #expect(rows[0].heatHours == 1 && rows[0].dryHours == 0 && rows[0].moldHours == 0)
        #expect(rows.first { $0.sensor == 1 && $0.day == 0 }?.heatHours == 0)
        #expect(rows.first { $0.sensor == 2 && $0.day == 3 }?.heatHours == nil)
        #expect(rows[41].heatHours == 5)
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.moldHeader, "0,0,,1,2,3,4,5,6"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,,1,2,3,4,5,6,7"]) }
    }

    @Test func weekHeatColumn() throws {
        let rows = try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.heatHeader, "0,0,2026-11-14,19.5,22.0,40.5,55.0,6,5,3", "0,1,2026-11-13,,,,,,,", "1,0,,10.0,20.0,40.0,60.0,3,,24"])
        #expect(rows[0].heatHours == 3 && rows[0].dryHours == 5 && rows[1].heatHours == nil && rows[2].heatHours == 24)
        let nine = try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,5,6"])
        #expect(nine[0].heatHours == nil)
        for tail in ["25", "-1", "x", "+5"] {
            #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.heatHeader, "0,0,,1,2,3,4,5,6,\(tail)"]) }
        }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.heatHeader, "0,0,,1,2,3,4,5,6"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,5,6,7"]) }
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
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,2026-11-14,1,2,3"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,7,,,,,"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,14.11.2026,,,,"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.moldHeader, "0,0,,1,2,3,4,25"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.moldHeader, "0,0,,1,2,3,4,x"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.moldHeader, "0,0,,1,2,3,4,+5"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.moldHeader, "0,0,,1,2,3,4"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,,1,2,3,4,5"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,,1,2,3,4", "0,0,,1,2,3,4"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.legacyHeader, "0,0,,5,2,3,4"]) }
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
                                   humidityMinPct: 40.0, humidityMaxPct: 55.0, moldHours: 0, dryHours: 0))
        #expect(rows[1].moldHours == 7)
        #expect(rows[41] == WeekRow(sensor: 5, day: 6, date: "2026-11-11", temperatureMinC: 23.0, temperatureMaxC: 27.5,
                                    humidityMinPct: 56.0, humidityMaxPct: 71.0, moldHours: 24, dryHours: 3))
        let gaps = rows.filter { $0.temperatureMinC == nil }
        #expect(gaps.count == 1)
        #expect(gaps[0].sensor == 2 && gaps[0].day == 3 && gaps[0].date == "2026-11-14")
        #expect(gaps[0].temperatureMaxC == nil && gaps[0].humidityMinPct == nil && gaps[0].humidityMaxPct == nil)
        #expect(gaps[0].moldHours == nil && gaps[0].dryHours == nil)
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.moldHeader, "0,0,2026-11-14,1,2,3,4,5,6"]) }
    }

    @Test func weekNineColumnsGiveDryHours() throws {
        let rows = try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,2026-11-14,19.5,22.0,40.5,55.0,6,5",
                                               "0,1,2026-11-13,,,,,,", "1,0,,10.0,20.0,40.0,60.0,3,"])
        #expect(rows[0].moldHours == 6 && rows[0].dryHours == 5)
        #expect(rows[1].dryHours == nil)
        #expect(rows[2].moldHours == 3 && rows[2].dryHours == nil)
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,5,25"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,5,x"]) }
        #expect(throws: ParseError.self) { try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.header, "0,0,,1,2,3,4,5"]) }
    }

    /// A firmware without the `dry_h` column sends 8 columns: `dryHours` is `nil`.
    @Test func weekEightColumnsGiveNilDryHours() throws {
        let rows = try WeekReply.parse(lines: ["BEGIN WEEK", WeekReply.moldHeader, "0,0,2026-11-14,19.5,22.0,40.5,55.0,6", "0,1,2026-11-13,,,,,"])
        #expect(rows[0].moldHours == 6 && rows[0].dryHours == nil && rows[1].dryHours == nil)
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

    @Test func eventsNewestCommand() {
        #expect(ConsoleCommand.eventsNewest(1).line == "EVENTS 1")
        #expect(ConsoleCommand.eventsNewest(5).line == "EVENTS 5")
        #expect(ConsoleCommand.eventsNewest(16).line == "EVENTS 16")
        #expect(ConsoleCommand.eventsNewest(0).line == nil)
        #expect(ConsoleCommand.eventsNewest(17).line == nil)
        #expect(ConsoleCommand.eventsNewest(5).data == Data("EVENTS 5\n".utf8))
    }

    @Test func recentEventsFiveRows() throws {
        let plain = """
        BEGIN EVENTS
        time,event,detail
        2026-11-14 08:00:00,RESET,1
        2026-11-14 09:00:00,RESET,1
        2026-11-14 10:00:00,RESET,1
        2026-11-14 11:00:00,RESET,1
        ,SLOT_ASSIGN,513

        """
        let recent = RecentEvents.make(rows: try EventsReply.parse(lines: lines(plain)), config: nil)
        #expect(recent.lines.count == 5)
        #expect(!recent.lost)
        #expect(recent.lines == [
            "--  slot 2 METER",
            "2026-11-14 11:00:00  reset POWERON",
            "2026-11-14 10:00:00  reset POWERON",
            "2026-11-14 09:00:00  reset POWERON",
            "2026-11-14 08:00:00  reset POWERON",
        ])
    }

    @Test func recentEventsCutsLongReply() throws {
        let long = """
        BEGIN EVENTS
        time,event,detail
        2026-11-14 01:00:00,RESET,1
        2026-11-14 02:00:00,RESET,1
        2026-11-14 03:00:00,RESET,1
        2026-11-14 04:00:00,RESET,1
        2026-11-14 05:00:00,RESET,1
        2026-11-14 06:00:00,RESET,1
        2026-11-14 07:00:00,RESET,1

        """
        let recent = RecentEvents.make(rows: try EventsReply.parse(lines: lines(long)), config: nil)
        #expect(recent.lines.map { String($0.prefix(19)) } == [
            "2026-11-14 07:00:00", "2026-11-14 06:00:00", "2026-11-14 05:00:00",
            "2026-11-14 04:00:00", "2026-11-14 03:00:00",
        ])
        #expect(!recent.lost)
    }

    @Test func recentEventsLost() throws {
        let dropped = """
        BEGIN EVENTS
        time,event,detail
        2026-11-14 10:00:00,EVENTS_DROPPED,3
        2026-11-14 07:00:00,RESET,1
        2026-11-14 08:00:00,RESET,1
        2026-11-14 09:00:00,RESET,1
        2026-11-14 09:30:00,RESET,1
        ,SLOT_ASSIGN,513

        """
        let rows = try EventsReply.parse(lines: lines(dropped))
        #expect(rows.count == 6)
        let recent = RecentEvents.make(rows: rows, config: nil)
        #expect(recent.lost)
        #expect(recent.lines == [
            "--  slot 2 METER",
            "2026-11-14 09:30:00  reset POWERON",
            "2026-11-14 09:00:00  reset POWERON",
            "2026-11-14 08:00:00  reset POWERON",
            "2026-11-14 07:00:00  reset POWERON",
        ])
        #expect(RecentEvents.make(rows: [], config: nil) == .empty)
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

    @Test func buildSnoozeLine() {
        #expect(ConsoleCommand.snooze(60).line == "SNOOZE 60")
        #expect(ConsoleCommand.snooze(0).line == "SNOOZE 0")
        #expect(ConsoleCommand.snooze(720).line == "SNOOZE 720")
        #expect(ConsoleCommand.snooze(721).line == nil)
        #expect(ConsoleCommand.snooze(-1).line == nil)
    }
}


@Suite struct TodayReplyTests {
    private func frame(_ body: String) -> [String] { body.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) }
    private let head = "BEGIN TODAY\n" + TodayReply.header

    /// Reads tests/fixtures/today_reply_sample.txt, the file of the Android phone tests (2 rows).
    @Test func parsesSharedFixture() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/today_reply_sample.txt"))
        var assembler = ReplyAssembler()
        guard case .frame(let lines)? = assembler.append(data).first else { Issue.record("fixture is not a valid frame"); return }
        let rows = try TodayReply.parse(lines: lines)
        #expect(rows.count == 2)
        #expect(rows[0] == TodayRow(sensor: 0, name: "onboard", hours: 5, temperatureMinC: 13.0, temperatureMaxC: 17.0,
                                    temperatureMeanC: 15.0, humidityMinPct: 43.0, humidityMaxPct: 47.0, humidityMeanPct: 45.0))
        #expect(rows[1].temperatureMinC == -3.5)
        #expect(rows[1].humidityMeanPct == 80.5)
    }

    @Test func emptyFieldGivesNil() throws {
        let rows = try TodayReply.parse(lines: frame(head + "\n3,slot3,0,,,,,,\n4,slot4,2,1.0,,1.5,40.0,41.0,"))
        #expect(rows[0].hours == 0)
        #expect(rows[0].temperatureMinC == nil && rows[0].humidityMeanPct == nil)
        #expect(rows[1].temperatureMaxC == nil && rows[1].humidityMeanPct == nil)
        #expect(rows[1].temperatureMinC == 1.0)
    }

    @Test func badRowsThrow() {
        for body in ["0,onboard,5,1.0,2.0", "0,onboard,5,1.0,2.0,1.5,40,41,42,9", "0,onboard,x,1.0,2.0,1.5,40,41,42",
                     "0,onboard,25,1.0,2.0,1.5,40,41,42", "11,x,1,1.0,2.0,1.5,40,41,42", "0,onboard,1,a,2.0,1.5,40,41,42"] {
            #expect(throws: ParseError.self) { try TodayReply.parse(lines: frame(head + "\n" + body)) }
        }
        #expect(throws: ParseError.self) {
            try TodayReply.parse(lines: frame(head + "\n0,a,1,1,1,1,1,1,1\n0,b,1,1,1,1,1,1,1"))
        }
    }

    @Test func badHeaderThrows() {
        #expect(throws: ParseError.self) { try TodayReply.parse(lines: ["BEGIN WEEK"]) }
        #expect(throws: ParseError.self) { try TodayReply.parse(lines: ["BEGIN TODAY", "sensor,name"]) }
    }

    @Test func noTimeHasClearText() {
        #expect(TodayReply.message("ERR NO_TIME") == "Panel clock not set")
        #expect(TodayReply.message("ERR OTHER") == "ERR OTHER")
    }

    @Test func commandLine() {
        #expect(ConsoleCommand.today.line == "TODAY")
    }
}

struct ConfigRejectedLineFormTests {
    @Test func parsesWarnLines() throws {
        let reply = try ConfigReply.parse(lines: [
            "temp_unit=C", "WARN key=wall_frsi_pct reason=WALL_WITHOUT_OUTDOOR", "WARN reason=NIGHT_SHORTER key=night_wake_interval_s",
            "WARN key=only_key", "WARN key= reason=X",
        ])
        #expect(reply.warnings == [ConfigWarning(key: "wall_frsi_pct", reason: "WALL_WITHOUT_OUTDOOR"),
                                   ConfigWarning(key: "night_wake_interval_s", reason: "NIGHT_SHORTER")])
        #expect(reply.values.count == 1)
    }

    @Test func replyWithoutWarnLinesHasNoWarnings() throws {
        let reply = try ConfigReply.parse(lines: ["temp_unit=C", "SOURCE SD", "REJECTED line=7 reason=BAD_VALUE"])
        #expect(reply.warnings.isEmpty)
    }

    @Test func warnLinesAreNoSettings() throws {
        let reply = try ConfigReply.parse(lines: [
            "temp_unit=C", "SOURCE SD", "REJECTED line=7 reason=BAD_VALUE",
            "WARN key=wall_frsi_pct reason=WALL_WITHOUT_OUTDOOR",
        ])
        #expect(reply.values.count == 1)
        #expect(reply.values["WARN key"] == nil)
        #expect(reply.keys == ["temp_unit"])
        #expect(reply.rejectedLines == [7])
    }

    @Test func parsesOldAndNewRejectedLineForms() throws {
        let reply = try ConfigReply.parse(lines: [
            "temp_unit=C", "SOURCE SD", "REJECTED line=7",
            "REJECTED line=9 reason=UNKNOWN_KEY", "REJECTED line=12 reason=DUPLICATE_MAC",
        ])
        #expect(reply.rejectedLines == [7, 9, 12])
        #expect(reply.rejectedReasons == [nil, "UNKNOWN_KEY", "DUPLICATE_MAC"])
        #expect(reply.values["temp_unit"] == "C")
    }

    @Test func toleratesMalformedRejectedLine() throws {
        let reply = try ConfigReply.parse(lines: [
            "temp_unit=C", "REJECTED line=9 reason=", "REJECTED line=x", "REJECTED line=11 reason=BAD_VALUE extra=1",
        ])
        #expect(reply.rejectedLines == [9, 11])
        #expect(reply.rejectedReasons == [nil, "BAD_VALUE"])
    }
}
