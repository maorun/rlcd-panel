import Foundation

/// Readable texts for the rows of the event log (`EventCode` of `sd_logger.h`). A row that the app cannot
/// decode (unknown event name, detail outside the range) gives the raw text `<event>,<detail>`.
public enum EventText {
    /// Alert names by bit mask (`ALERT_*` of `audio_alert.h`); the firmware stores the mask, not the bit index.
    static let alertNames: [Int: String] = [
        1: "FREEZER", 2: "HUMIDITY", 4: "NOISE", 8: "SENSOR_LOST",
        16: "CHARGE_FULL", 32: "VENTILATE", 64: "SENSOR_BATTERY", 128: "HEAT",
    ]
    static let ntpResults = ["OK", "WIFI_FAILED", "NTP_TIMEOUT", "RTC_WRITE_FAILED"]
    /// `ResetReason` values 0 to 10 (`ResetReasonCodec::text`).
    static let resetReasons = [
        "UNKNOWN", "POWERON", "EXT", "SW", "PANIC", "INT_WDT", "TASK_WDT", "WDT", "DEEPSLEEP", "BROWNOUT", "SDIO",
    ]
    /// `BleFormat` values 0 to 4.
    static let bleFormats = ["WOIO", "METER", "BTHOME", "ATC", "THERMOPRO"]
    /// `SdStatus` values 1 to 5 (`SdStatusCodec::name`); 0 is `UNKNOWN`, which the firmware never records.
    static let sdStatuses = [1: "OK", 2: "NO_CARD", 3: "MOUNT_FAILED", 4: "WRITE_FAILED", 5: "FULL"]

    public static func describe(_ row: EventRow) -> String {
        let d = row.detail
        let raw = "\(row.event),\(d)"
        switch row.event {
        case "ALERT_RISE", "ALERT_FALL":
            guard let name = alertNames[d] else { return raw }
            return "\(name) alert \(row.event == "ALERT_RISE" ? "on" : "off")"
        case "ALERT_ACK":
            guard let name = alertNames[d] else { return raw }
            return "Alert \(name) acknowledged"
        case "CONFIG_LOAD":
            guard d >= 0 else { return raw }
            return "config loaded, \(d) rejected line\(d == 1 ? "" : "s")"
        case "NTP_SYNC":
            guard ntpResults.indices.contains(d) else { return raw }
            return "NTP sync \(ntpResults[d])"
        case "BATTERY_LOW_SET", "BATTERY_LOW_CLEAR":
            guard (0...100).contains(d) else { return raw }
            return "battery low \(row.event == "BATTERY_LOW_SET" ? "set" : "clear") at \(d) %"
        case "EMPTY_PROTECTION":
            guard d == 0 || d == 1 else { return raw }
            return "empty battery protection \(d == 1 ? "set" : "release")"
        case "RESET":
            guard resetReasons.indices.contains(d) else { return raw }
            let reason = resetReasons[d]
            return "reset \(reason)" + (ResetMetrics.isAbnormal(reason: reason) ? " (abnormal)" : "")
        case "SLOT_ASSIGN":
            let slot = d >> 8, format = d & 0xFF
            guard (0...0xFFFF).contains(d), (1...10).contains(slot), bleFormats.indices.contains(format) else { return raw }
            return "slot \(slot) \(bleFormats[format])"
        case "SLOT_RELEASE":
            // Low byte is the slot; the high byte 1 marks a manual release (`SLOT CLEAR`), 0 the timer (`slot_release_h`).
            let slot = d & 0xFF, manual = d >> 8
            guard (0...0xFFFF).contains(d), (1...10).contains(slot), manual <= 1 else { return raw }
            return manual == 1 ? "Slot \(slot) released by the user" : "slot \(slot) released"
        case "SLOT_STALE":
            // Low byte is the slot; the high byte 0 marks a lost slot, 1 a slot that is back.
            let slot = d & 0xFF, back = d >> 8
            guard (0...0xFFFF).contains(d), (1...10).contains(slot), back <= 1 else { return raw }
            return back == 1 ? "Slot \(slot) back" : "Slot \(slot) lost"
        case "JUMP_DROP":
            guard (1...10).contains(d) else { return raw }
            return "Slot \(d) reading jump dropped"
        case "POWER_SOURCE":
            guard d == 0 || d == 1 else { return raw }
            return d == 1 ? "USB power connected" : "Battery power"
        case "MUTE_CHANGE":
            // Low byte is the state (1 muted, 0 unmuted); the high byte is the source (0 BOOT, 1 USB, 2 BLE).
            let state = d & 0xFF, source = d >> 8
            let sources = ["BOOT", "USB", "BLE"]
            guard (0...0xFFFF).contains(d), state <= 1, source < sources.count else { return raw }
            return state == 1 ? "Chime muted (\(sources[source]))" : "Chime on (\(sources[source]))"
        case "CLOCK_SET":
            // Low byte is the action (0 TIME SET, 1 RTC TRIM); the high byte is the transport (0 USB, 1 BLE).
            let action = d & 0xFF, transport = d >> 8
            guard (0...0xFFFF).contains(d), action <= 1, transport <= 1 else { return raw }
            let name = transport == 0 ? "USB" : "BLE"
            return action == 0 ? "Clock set (\(name))" : "RTC trimmed (\(name))"
        case "SHTC3_FAULT":
            // Low byte 1 is a fault, 0 is back; the high byte is the Shtc3Result of the third failed read (1 NACK, 2 SHORT, 3 CRC), 0 for back.
            let state = d & 0xFF, cause = d >> 8
            guard (0...0xFFFF).contains(d) else { return raw }
            if state == 0, cause == 0 { return "Onboard sensor back" }
            guard state == 1, (1...3).contains(cause) else { return raw }
            return "Onboard sensor fault (\(["NACK", "SHORT", "CRC"][cause - 1]))"
        case "SENSOR_BATTERY":
            // Low byte is the slot; the high byte 1 marks a low battery, 0 an OK battery.
            let slot = d & 0xFF, low = d >> 8
            guard (0...0xFFFF).contains(d), (1...10).contains(slot), low <= 1 else { return raw }
            return low == 1 ? "Slot \(slot) battery low" : "Slot \(slot) battery OK"
        case "CONFIG_SET":
            let index = d & 0xFF, transport = d >> 8
            guard (0...0xFFFF).contains(d), transport <= 1 else { return raw }
            guard ConfigSchema.firmwareKeys.indices.contains(index) else { return "Config set: key #\(index)" }
            return "Config set: \(ConfigSchema.firmwareKeys[index]) (\(transport == 0 ? "USB" : "BLE"))"
        case "SD_FAULT":
            guard let status = sdStatuses[d] else { return raw }
            return d == 1 ? "SD card OK" : "SD card fault: \(status)"
        default:
            return raw
        }
    }

    /// True for a `RESET` row with an abnormal reason (`ResetMetrics.isAbnormal`).
    public static func isAbnormal(_ row: EventRow) -> Bool {
        guard row.event == "RESET", resetReasons.indices.contains(row.detail) else { return false }
        return ResetMetrics.isAbnormal(reason: resetReasons[row.detail])
    }
}

extension EventsReply {
    /// Parses a downloaded `events_v1_YYYY.csv` (header line, then rows; no `BEGIN`/`CRC32` lines).
    public static func parse(file text: String) throws -> [EventRow] {
        let lines = text.split(whereSeparator: \.isNewline).map(String.init)
        guard lines.first == header else { throw ParseError.missingLine(header) }
        return try parse(lines: ["BEGIN EVENTS"] + lines)
    }
}
