import Foundation
import Testing
@testable import RlcdProtocol

private func row(_ event: String, _ detail: Int) -> EventRow {
    EventRow(time: "2026-11-14 10:00:00", event: event, detail: detail)
}

@Suite struct EventTextTests {
    private let named = ConfigReply(values: ["onboard_name": "Office", "slot1_mac": "AA:BB:CC:DD:EE:01", "slot1_name": "Kitchen"])

    @Test func windowOpenAndClosedUseTheRoomName() {
        #expect(EventText.describe(row("WINDOW_OPEN", 0x100), config: named) == "Window open in Office")
        #expect(EventText.describe(row("WINDOW_OPEN", 0x001), config: named) == "Window closed in Kitchen")
        #expect(EventText.describe(row("WINDOW_OPEN", 0x103)) == "Window open in Slot 3")
        #expect(EventText.describe(row("WINDOW_OPEN", 0x000)) == "Window closed in Onboard")
    }

    @Test func windowOpenWithUnknownHighByteOrTileGivesRawText() {
        #expect(EventText.describe(row("WINDOW_OPEN", 0x200), config: named) == "WINDOW_OPEN,512")
        #expect(EventText.describe(row("WINDOW_OPEN", 0x10B), config: named) == "WINDOW_OPEN,267")
        #expect(EventText.describe(row("WINDOW_OPEN", -1), config: named) == "WINDOW_OPEN,-1")
    }

    @Test func windowOpenIsClimate() {
        #expect(EventFilter.category(of: "WINDOW_OPEN") == .climate)
    }

    @Test func windowWatchReportsOnlyNewOpenEvents() {
        var watch = WindowEventWatch()
        let old = row("WINDOW_OPEN", 0x101)
        #expect(watch.newOpenTiles([old]).isEmpty)
        let opened = EventRow(time: "2026-11-14 10:05:00", event: "WINDOW_OPEN", detail: 0x102)
        let closed = EventRow(time: "2026-11-14 10:06:00", event: "WINDOW_OPEN", detail: 0x003)
        #expect(watch.newOpenTiles([old, opened, closed]) == [2])
        #expect(watch.newOpenTiles([old, opened, closed]).isEmpty)
    }

    @Test func windowWatchSkipsAnOpenRowThatALaterRowClosed() {
        var watch = WindowEventWatch()
        _ = watch.newOpenTiles([])
        let open1 = EventRow(time: "2026-11-14 10:05:00", event: "WINDOW_OPEN", detail: 0x101)
        let close1 = EventRow(time: "2026-11-14 10:09:00", event: "WINDOW_OPEN", detail: 0x001)
        let open2 = EventRow(time: "2026-11-14 10:10:00", event: "WINDOW_OPEN", detail: 0x102)
        #expect(watch.newOpenTiles([open1, close1, open2]) == [2])
    }

    @Test func windowWatchCountsEqualRowsWithEmptyTimeOnce() {
        var watch = WindowEventWatch()
        let open = EventRow(time: "", event: "WINDOW_OPEN", detail: 0x101)
        _ = watch.newOpenTiles([open])
        #expect(watch.newOpenTiles([open]).isEmpty)
        #expect(watch.newOpenTiles([open, open]) == [1])
    }

    @Test func openedWindowTileRejectsTileAboveTenAndOtherRows() {
        #expect(EventText.openedWindowTile(row("WINDOW_OPEN", 0x10A)) == 10)
        #expect(EventText.openedWindowTile(row("WINDOW_OPEN", 0x10B)) == nil)
        #expect(EventText.openedWindowTile(row("WINDOW_OPEN", 0x000)) == nil)
        #expect(EventText.openedWindowTile(row("SLOT_STALE", 0x101)) == nil)
    }

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
        #expect(EventText.describe(row("SLOT_ASSIGN", 0x0B02)) == "SLOT_ASSIGN,2818")
    }

    @Test func slotReleaseGivesSlotNumber() {
        #expect(EventText.describe(row("SLOT_RELEASE", 3)) == "slot 3 released")
        #expect(EventText.describe(row("SLOT_RELEASE", 0)) == "SLOT_RELEASE,0")
        #expect(EventText.describe(row("SLOT_RELEASE", 11)) == "SLOT_RELEASE,11")
    }

    @Test func slotReleaseByUserGivesSlotNumber() {
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0102)) == "Slot 2 released by the user")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0002)) == "slot 2 released")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0100)) == "SLOT_RELEASE,256")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x010B)) == "SLOT_RELEASE,267")
        #expect(EventText.describe(row("SLOT_RELEASE", 0x0202)) == "SLOT_RELEASE,514")
    }

    @Test func powerSourceGivesUsbAndBattery() {
        #expect(EventText.describe(row("POWER_SOURCE", 1)) == "USB power connected")
        #expect(EventText.describe(row("POWER_SOURCE", 0)) == "Battery power")
        #expect(EventText.describe(row("POWER_SOURCE", 2)) == "POWER_SOURCE,2")
    }

    @Test func muteChangeGivesStateAndSource() {
        #expect(EventText.describe(row("MUTE_CHANGE", 0x0001)) == "Chime muted (BOOT)")
        #expect(EventText.describe(row("MUTE_CHANGE", 0x0101)) == "Chime muted (USB)")
        #expect(EventText.describe(row("MUTE_CHANGE", 0x0200)) == "Chime on (BLE)")
        #expect(EventText.describe(row("MUTE_CHANGE", 0x0102)) == "Chime snoozed (USB)")
        #expect(EventText.describe(row("MUTE_CHANGE", 0x0302)) == "MUTE_CHANGE,770")
        #expect(EventText.describe(row("MUTE_CHANGE", 0x0003)) == "MUTE_CHANGE,3")
        #expect(EventText.describe(row("CLOCK_SET", 0x0000)) == "Clock set (USB)")
        #expect(EventText.describe(row("CLOCK_SET", 0x0101)) == "RTC trimmed (BLE)")
        #expect(EventText.describe(row("CLOCK_SET", 0x0002)) == "CLOCK_SET,2")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0101)) == "Onboard sensor fault (NACK)")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0201)) == "Onboard sensor fault (SHORT)")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0301)) == "Onboard sensor fault (CRC)")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0000)) == "Onboard sensor back")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0401)) == "SHTC3_FAULT,1025")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0001)) == "SHTC3_FAULT,1")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0100)) == "SHTC3_FAULT,256")
        #expect(EventText.describe(row("SHTC3_FAULT", 0x0002)) == "SHTC3_FAULT,2")
        #expect(EventText.describe(row("SENSOR_BATTERY", 260)) == "Slot 4 battery low")
        #expect(EventText.describe(row("SENSOR_BATTERY", 4)) == "Slot 4 battery OK")
        #expect(EventText.describe(row("SENSOR_BATTERY", 11)) == "SENSOR_BATTERY,11")
        #expect(EventText.describe(row("SENSOR_BATTERY", 516)) == "SENSOR_BATTERY,516")
    }

    @Test func scanFaultGivesFailedAndBack() {
        #expect(EventText.describe(row("SCAN_FAULT", 1)) == "BLE scan start failed")
        #expect(EventText.describe(row("SCAN_FAULT", 0)) == "BLE scan works again")
        #expect(EventText.describe(row("SCAN_FAULT", 2)) == "SCAN_FAULT,2")
        #expect(EventText.describe(row("SCAN_FAULT", 256)) == "SCAN_FAULT,256")
        #expect(EventText.describe(row("WINDOW_OPEN", 523)) == "WINDOW_OPEN,523")
    }

    @Test func receptionLowGivesWeakAndGood() {
        #expect(EventText.describe(row("RECEPTION_LOW", 0x0103)) == "Slot 3 weak signal")
        #expect(EventText.describe(row("RECEPTION_LOW", 0x0003)) == "Slot 3 signal good")
        #expect(EventText.describe(row("RECEPTION_LOW", 0x0100)) == "RECEPTION_LOW,256")
        #expect(EventText.describe(row("RECEPTION_LOW", 0x020B)) == "RECEPTION_LOW,523")
        #expect(EventFilter.category(of: "RECEPTION_LOW") == .sensors)
        #expect(EventText.describe(row("WALL_DAMP", 0x0103)) == "Wall damp (Slot 3)")
        #expect(EventText.describe(row("WALL_DAMP", 0x0000)) == "Wall dry again (Onboard)")
        #expect(EventText.describe(row("DRY_AIR", 0x010A)) == "Dry air (Slot 10)")
        #expect(EventText.describe(row("DRY_AIR", 0x0001)) == "Air humidity good (Slot 1)")
        #expect(EventText.describe(row("WALL_DAMP", 0x0200)) == "WALL_DAMP,512")
        #expect(EventText.describe(row("DRY_AIR", 0x000B)) == "DRY_AIR,11")
        #expect(EventText.describe(row("HEAT_TILE", 0x010A)) == "Heat (Slot 10)")
        #expect(EventText.describe(row("HEAT_TILE", 0x0001)) == "Heat good again (Slot 1)")
        #expect(EventText.describe(row("HEAT_TILE", 0x000B)) == "HEAT_TILE,11")
        #expect(EventText.describe(row("HEAT_TILE", 0x010B)) == "HEAT_TILE,267")
        #expect(EventText.describe(row("HEAT_TILE", 0x0200)) == "HEAT_TILE,512")
        #expect(EventText.describe(row("SENSOR_DRIFT", 0x0103)) == "Sensor drift (Slot 3)")
        #expect(EventText.describe(row("SENSOR_DRIFT", 0x0000)) == "Sensor drift gone (Onboard)")
        #expect(EventText.describe(row("SENSOR_DRIFT", 0x000B)) == "SENSOR_DRIFT,11")
        #expect(EventText.describe(row("SENSOR_DRIFT", 0x0200)) == "SENSOR_DRIFT,512")
        #expect(EventFilter.category(of: "SENSOR_DRIFT") == .sensors)
        #expect(EventText.describe(row("SENSOR_STUCK", 0x0103)) == "Sensor frozen (Slot 3)")
        #expect(EventText.describe(row("SENSOR_STUCK", 0x0000)) == "Sensor good again (Onboard)")
        #expect(EventText.describe(row("SENSOR_STUCK", 0x000B)) == "SENSOR_STUCK,11")
        #expect(EventText.describe(row("SENSOR_STUCK", 0x0200)) == "SENSOR_STUCK,512")
        #expect(EventText.describe(row("SENSOR_STUCK", 0x0102), config: named) == "Sensor frozen (Slot 2)")
        #expect(EventText.describe(row("SENSOR_STUCK", 0x0001), config: named) == "Sensor good again (Kitchen)")
    }

    @Test func slotStaleGivesLostAndBack() {
        #expect(EventText.describe(row("SLOT_STALE", 0x0002)) == "Slot 2 lost")
        #expect(EventText.describe(row("SLOT_STALE", 0x0102)) == "Slot 2 back")
        #expect(EventText.describe(row("SLOT_STALE", 0x0100)) == "SLOT_STALE,256")
        #expect(EventText.describe(row("SLOT_STALE", 0x010B)) == "SLOT_STALE,267")
        #expect(EventText.describe(row("SLOT_STALE", 0x0202)) == "SLOT_STALE,514")
        #expect(EventText.describe(row("JUMP_DROP", 3)) == "Slot 3 reading jump dropped")
        #expect(EventText.describe(row("JUMP_DROP", 10)) == "Slot 10 reading jump dropped")
        #expect(EventText.describe(row("JUMP_DROP", 0)) == "JUMP_DROP,0")
        #expect(EventText.describe(row("JUMP_DROP", 11)) == "JUMP_DROP,11")
        #expect(EventText.describe(row("JUMP_DROP", 259)) == "JUMP_DROP,259")
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
        #expect(EventText.describe(row("CONFIG_SET", 57)) == "Config set: slot6_name (USB)")
        #expect(EventText.describe(row("CONFIG_SET", 82)) == "Config set: wall_frsi_pct (USB)")
        #expect(EventText.describe(row("CONFIG_SET", 0x0100 | 200)) == "Config set: key #200")
        #expect(EventText.describe(row("CONFIG_SET", 0x0200)) == "CONFIG_SET,512")
    }

    @Test func alertAckGivesName() {
        #expect(EventText.describe(row("ALERT_ACK", 2)) == "Alert HUMIDITY acknowledged")
        #expect(EventText.describe(row("ALERT_ACK", 128)) == "Alert HEAT acknowledged")
        #expect(EventText.describe(row("ALERT_ACK", 3)) == "ALERT_ACK,3")
        #expect(EventText.describe(row("ALERT_ACK", 0)) == "ALERT_ACK,0")
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

    @Test func sharedEventsFixtureGivesTheTexts() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/events_reply_sample.txt")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
        let rows = try EventsReply.parse(lines: lines)
        #expect(rows.count == 24)
        #expect(rows[0] == EventRow(time: "2026-11-18 09:00:00", event: "EVENTS_DROPPED", detail: 0))
        let texts = rows.dropFirst().map(EventText.describe)
        #expect(texts[0] == "reset PANIC (abnormal)")
        #expect(texts[3] == "Slot 3 back")
        #expect(texts[4] == "Slot 2 released by the user")
        #expect(texts[5] == "SD card fault: NO_CARD")
        #expect(texts[11] == "USB power connected")
        #expect(texts[12] == "Battery power")
        #expect(texts[13] == "Clock set (USB)")
        #expect(texts[14] == "RTC trimmed (BLE)")
        #expect(texts[15] == "Onboard sensor fault (NACK)")
        #expect(texts[16] == "Onboard sensor back")
        #expect(texts[17] == "Slot 4 battery low")
        #expect(texts[18] == "Slot 4 battery OK")
        #expect(texts[19] == "Slot 3 reading jump dropped")
        #expect(texts[20] == "BLE scan start failed")
        #expect(texts[21] == "Window open in Slot 1")
        #expect(texts[22] == "FUTURE_EVENT,7")
    }
}
