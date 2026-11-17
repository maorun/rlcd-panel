import Foundation
import Testing
@testable import RlcdProtocol

private func row(_ event: String, _ detail: Int) -> EventRow {
    EventRow(time: "2026-11-14 10:00:00", event: event, detail: detail)
}

@Suite struct EventTextTests {
    @Test func alertRiseAndFallUseTheBitMask() {
        #expect(EventText.describe(row("ALERT_RISE", 128)) == "HEAT alert on")
        #expect(EventText.describe(row("ALERT_RISE", 1)) == "FREEZER alert on")
        #expect(EventText.describe(row("ALERT_FALL", 2)) == "HUMIDITY alert off")
        #expect(EventText.describe(row("ALERT_RISE", 4)) == "NOISE alert on")
        #expect(EventText.describe(row("ALERT_FALL", 8)) == "SENSOR_LOST alert off")
        #expect(EventText.describe(row("ALERT_RISE", 16)) == "CHARGE_FULL alert on")
        #expect(EventText.describe(row("ALERT_RISE", 32)) == "VENTILATE alert on")
        #expect(EventText.describe(row("ALERT_FALL", 64)) == "SENSOR_BATTERY alert off")
    }

    @Test func alertDetailOutsideThePowersOfTwoGivesRawText() {
        #expect(EventText.describe(row("ALERT_RISE", 7)) == "ALERT_RISE,7")
        #expect(EventText.describe(row("ALERT_RISE", 9)) == "ALERT_RISE,9")
        #expect(EventText.describe(row("ALERT_FALL", 255)) == "ALERT_FALL,255")
        #expect(EventText.describe(row("ALERT_FALL", 0)) == "ALERT_FALL,0")
        #expect(EventText.describe(row("ALERT_RISE", 256)) == "ALERT_RISE,256")
    }

    @Test func configLoadGivesRejectedCount() {
        #expect(EventText.describe(row("CONFIG_LOAD", 0)) == "config loaded, 0 rejected lines")
        #expect(EventText.describe(row("CONFIG_LOAD", 1)) == "config loaded, 1 rejected line")
        #expect(EventText.describe(row("CONFIG_LOAD", 3)) == "config loaded, 3 rejected lines")
        #expect(EventText.describe(row("CONFIG_LOAD", -1)) == "CONFIG_LOAD,-1")
    }

    @Test func ntpSyncGivesResultName() {
        #expect(EventText.describe(row("NTP_SYNC", 0)) == "NTP sync OK")
        #expect(EventText.describe(row("NTP_SYNC", 1)) == "NTP sync WIFI_FAILED")
        #expect(EventText.describe(row("NTP_SYNC", 2)) == "NTP sync NTP_TIMEOUT")
        #expect(EventText.describe(row("NTP_SYNC", 3)) == "NTP sync RTC_WRITE_FAILED")
        #expect(EventText.describe(row("NTP_SYNC", 4)) == "NTP_SYNC,4")
    }

    @Test func batteryLowGivesPercentage() {
        #expect(EventText.describe(row("BATTERY_LOW_SET", 15)) == "battery low set at 15 %")
        #expect(EventText.describe(row("BATTERY_LOW_CLEAR", 22)) == "battery low clear at 22 %")
        #expect(EventText.describe(row("BATTERY_LOW_SET", 101)) == "BATTERY_LOW_SET,101")
    }

    @Test func emptyProtectionGivesSetOrRelease() {
        #expect(EventText.describe(row("EMPTY_PROTECTION", 1)) == "empty battery protection set")
        #expect(EventText.describe(row("EMPTY_PROTECTION", 0)) == "empty battery protection release")
        #expect(EventText.describe(row("EMPTY_PROTECTION", 2)) == "EMPTY_PROTECTION,2")
    }

    @Test func resetGivesReasonAndAbnormalMark() {
        #expect(EventText.describe(row("RESET", 4)) == "reset PANIC (abnormal)")
        #expect(EventText.isAbnormal(row("RESET", 4)))
        #expect(EventText.describe(row("RESET", 1)) == "reset POWERON")
        #expect(!EventText.isAbnormal(row("RESET", 1)))
        #expect(EventText.describe(row("RESET", 9)) == "reset BROWNOUT (abnormal)")
        #expect(EventText.describe(row("RESET", 0)) == "reset UNKNOWN")
        #expect(EventText.describe(row("RESET", 11)) == "RESET,11")
        #expect(!EventText.isAbnormal(row("RESET", 11)))
    }

    @Test func everyResetReasonHasItsTextAndAbnormalMark() {
        let expected = ["UNKNOWN", "POWERON", "EXT", "SW", "PANIC", "INT_WDT", "TASK_WDT", "WDT", "DEEPSLEEP", "BROWNOUT", "SDIO"]
        let abnormal: Set<Int> = [4, 5, 6, 7, 9]
        for (code, name) in expected.enumerated() {
            let r = row("RESET", code)
            #expect(EventText.describe(r) == "reset \(name)" + (abnormal.contains(code) ? " (abnormal)" : ""))
            #expect(EventText.isAbnormal(r) == abnormal.contains(code))
        }
    }

    @Test func slotAssignGivesSlotAndFormat() {
        #expect(EventText.describe(row("SLOT_ASSIGN", 514)) == "slot 2 BTHOME")
        #expect(EventText.describe(row("SLOT_ASSIGN", 0x0100)) == "slot 1 WOIO")
        #expect(EventText.describe(row("SLOT_ASSIGN", 0x0503)) == "slot 5 ATC")
        #expect(EventText.describe(row("SLOT_ASSIGN", 0x0104)) == "slot 1 THERMOPRO")
        #expect(EventText.describe(row("SLOT_ASSIGN", 0x0105)) == "SLOT_ASSIGN,261")
        #expect(EventText.describe(row("SLOT_ASSIGN", 0x0002)) == "SLOT_ASSIGN,2")
        #expect(EventText.describe(row("SLOT_ASSIGN", 0x0602)) == "SLOT_ASSIGN,1538")
    }

    @Test func slotReleaseGivesSlotNumber() {
        #expect(EventText.describe(row("SLOT_RELEASE", 3)) == "slot 3 released")
        #expect(EventText.describe(row("SLOT_RELEASE", 0)) == "SLOT_RELEASE,0")
        #expect(EventText.describe(row("SLOT_RELEASE", 6)) == "SLOT_RELEASE,6")
    }

    @Test func slotReleaseByUserGivesSlotNumber() {
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0102)) == "Slot 2 released by the user")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0002)) == "slot 2 released")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0100)) == "SLOT_RELEASE,256")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0106)) == "SLOT_RELEASE,262")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0202)) == "SLOT_RELEASE,514")
    }

    @Test func configSetGivesKeyAndTransport() {
        // Literal indices of `DeviceConfigParser::KEYS`, not derived from the schema order.
        #expect(EventText.describe(row("CONFIG_SET", 12)) == "Config set: wake_interval_s (USB)")
        #expect(EventText.describe(row("CONFIG_SET", 0x0100 | 12)) == "Config set: wake_interval_s (BLE)")
        #expect(EventText.describe(row("CONFIG_SET", 0x0100)) == "Config set: temp_offset_c (BLE)")
        #expect(EventText.describe(row("CONFIG_SET", 0x0100 | 48)) == "Config set: usb_temp_offset_c (BLE)")
        #expect(EventText.describe(row("CONFIG_SET", 49)) == "Config set: dry_alert_limit_rh (USB)")
        #expect(EventText.describe(row("CONFIG_SET", 50)) == "Config set: rh_gain (USB)")
        #expect(EventText.describe(row("CONFIG_SET", 55)) == "Config set: slot5_rh_gain (USB)")
        #expect(EventText.describe(row("CONFIG_SET", 56)) == "Config set: door_ref_slot (USB)")
        #expect(EventText.describe(row("CONFIG_SET", 57)) == "Config set: key #57")
        #expect(EventText.describe(row("CONFIG_SET", 0x0100 | 200)) == "Config set: key #200")
        #expect(EventText.describe(row("CONFIG_SET", 0x0200)) == "CONFIG_SET,512")
    }

    @Test func sdFaultGivesStatusText() {
        #expect(EventText.describe(row("SD_FAULT", 1)) == "SD card OK")
        #expect(EventText.describe(row("SD_FAULT", 2)) == "SD card fault: NO_CARD")
        #expect(EventText.describe(row("SD_FAULT", 3)) == "SD card fault: MOUNT_FAILED")
        #expect(EventText.describe(row("SD_FAULT", 4)) == "SD card fault: WRITE_FAILED")
        #expect(EventText.describe(row("SD_FAULT", 5)) == "SD card fault: FULL")
        #expect(EventText.describe(row("SD_FAULT", 0)) == "SD_FAULT,0")
        #expect(EventText.describe(row("SD_FAULT", 6)) == "SD_FAULT,6")
        #expect(EventText.describe(row("SD_FAULT", -1)) == "SD_FAULT,-1")
    }

    @Test func firmwareKeyOrderMatchesDeviceConfigHeader() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let header = try String(contentsOf: url.appendingPathComponent("rlcd_panel/device_config.h"), encoding: .utf8)
        let start = try #require(header.range(of: "KEYS[KEY_COUNT] = {"))
        let tail = header[start.upperBound...]
        let block = tail[..<(try #require(tail.range(of: "};"))).lowerBound]
        let keys = block.split(whereSeparator: { !($0.isLetter || $0.isNumber || $0 == "_") }).map(String.init)
        #expect(ConfigSchema.firmwareKeys == keys)
        #expect(Set(ConfigSchema.firmwareKeys) == Set(ConfigSchema.keys))
    }

    @Test func unknownEventGivesRawText() {
        #expect(EventText.describe(row("FOO", 5)) == "FOO,5")
        #expect(EventText.describe(row("EVENTS_DROPPED", 3)) == "EVENTS_DROPPED,3")
        #expect(!EventText.isAbnormal(row("FOO", 4)))
    }

    @Test func eventsFileWithDroppedRowParses() throws {
        let text = """
        time,event,detail
        2026-11-14 10:00:00,EVENTS_DROPPED,3
        2026-11-14 10:01:00,ALERT_RISE,128
        ,RESET,4

        """
        let rows = try EventsReply.parse(file: text)
        #expect(rows.count == 3)
        #expect(rows[0] == EventRow(time: "2026-11-14 10:00:00", event: "EVENTS_DROPPED", detail: 3))
        #expect(rows[2] == EventRow(time: "", event: "RESET", detail: 4))
        #expect(EventText.describe(rows[1]) == "HEAT alert on")
    }

    @Test func eventsFileWithWrongHeaderOrRowThrows() {
        #expect(throws: ParseError.self) { try EventsReply.parse(file: "a,b,c\n") }
        #expect(throws: ParseError.self) { try EventsReply.parse(file: "time,event,detail\nx,y\n") }
        #expect(throws: ParseError.self) { try EventsReply.parse(file: "") }
    }

    @Test func eventsFileAcceptsCrlfAndRejectsBadDetail() throws {
        let rows = try EventsReply.parse(file: "time,event,detail\r\n2026-11-14 10:00:00,RESET,4\r\n")
        #expect(rows == [EventRow(time: "2026-11-14 10:00:00", event: "RESET", detail: 4)])
        #expect(throws: ParseError.self) { try EventsReply.parse(file: "time,event,detail\nt,RESET,x\n") }
        #expect(throws: ParseError.self) { try EventsReply.parse(file: "time,event,detail\nt,RESET,4,5\n") }
        #expect(try EventsReply.parse(file: "time,event,detail\n").isEmpty)
    }
}
