import Foundation

public enum ParseError: Error, Equatable {
    case missingLine(String)
    case badLine(String)
}

private func fields(_ line: String) -> [String: String] {
    var out: [String: String] = [:]
    for part in line.split(separator: " ").dropFirst() {
        if let eq = part.firstIndex(of: "=") {
            out[String(part[..<eq])] = String(part[part.index(after: eq)...])
        }
    }
    return out
}

private func number(_ s: String?) -> Double? {
    guard let s, s != "--", !s.isEmpty else { return nil }
    return Double(s)
}

func sourceFormat(_ s: String?) -> String? {
    guard let s, s != "--", !s.isEmpty else { return nil }
    return s
}

/// `SENSOR <index> <name> temp=<C> rh=<%RH> bat=<%> rssi=<dBm> age=<s> state=<...> format=<...> mac=<...> mold_h=<N|-->`.
public struct StatusSensor: Equatable {
    public let index: Int
    public let name: String
    public let temperature: Double?
    public let humidity: Double?
    public let battery: Double?
    public let rssi: Double?
    public let ageSeconds: Double?
    public let state: String
    /// `format=` of the `SENSOR` line (`SHTC3`, `WOIO`, ...); `nil` for a missing field, `--` or an empty text.
    public let format: String?
    /// `mac=` of the `SENSOR` line; `nil` for a missing field, `--` or an empty text.
    public let mac: String?
    /// `mold_h=` of the `SENSOR` line: hourly samples above `mold_alert_limit_rh`; `nil` for `--` and a missing field.
    public let moldHours: Int?

    public init(
        index: Int,
        name: String,
        temperature: Double?,
        humidity: Double?,
        battery: Double?,
        rssi: Double?,
        ageSeconds: Double?,
        state: String,
        format: String? = nil,
        mac: String? = nil,
        moldHours: Int? = nil
    ) {
        self.index = index
        self.name = name
        self.temperature = temperature
        self.humidity = humidity
        self.battery = battery
        self.rssi = rssi
        self.ageSeconds = ageSeconds
        self.state = state
        self.format = format
        self.mac = mac
        self.moldHours = moldHours
    }

    /// The protocol source badge of `format`, `nil` without a known format.
    public var badge: String? { SensorHealth.badge(forFormat: format) }
}

/// Parsed `STATUS` reply. Unknown lines are kept in `otherLines`, so a newer firmware does not break the parser.
public struct StatusReply: Equatable {
    public let sensors: [StatusSensor]
    public let sdStatus: String?
    /// `free_mb=<N|-->` of the `SD` line: free card space in MB; `nil` for `--` and for older firmware.
    public let sdFreeMb: Int?
    public let resetReason: String?
    public let powerSource: String?
    public let cellVolts: Double?
    public let cellPercent: Double?
    public let alertMask: UInt8
    public let muted: Bool
    /// Acknowledged alert bits (`acked=<hex>` of the `POWER` line, 0 for older firmware).
    public let ackedMask: UInt8
    /// Lines in the reply that this parser does not read in full (SHTC3, BLE, RUNTIME, ...), by first word. `RESET` is also here: `resetReason` reads only the reason, `DiagnosticsReply` reads the abnormal count.
    public let otherLines: [String: String]
    /// The raw `SENSOR` lines in reply order, for the strict parser of `SensorHealth`.
    public let sensorLines: [String]

    public var isUsbPower: Bool { powerSource == "USB" }

    public static func parse(lines: [String]) throws -> StatusReply {
        var sensors: [StatusSensor] = []
        var sd: String?
        var sdFree: Int?
        var reset: String?
        var src: String?
        var volts: Double?
        var pct: Double?
        var mask: UInt8?
        var muted = false
        var acked: UInt8 = 0
        var other: [String: String] = [:]
        var sensorLines: [String] = []
        for line in lines {
            let words = line.split(separator: " ", omittingEmptySubsequences: true)
            guard let head = words.first else { continue }
            let f = fields(line)
            switch head {
            case "SENSOR":
                sensorLines.append(line)
                guard words.count >= 3, let idx = Int(words[1]) else { throw ParseError.badLine(line) }
                var moldHours: Int?
                if let v = f["mold_h"], v != "--" {
                    guard let n = Int(v), n >= 0 else { throw ParseError.badLine(line) }
                    moldHours = n
                }
                sensors.append(StatusSensor(
                    index: idx, name: String(words[2]), temperature: number(f["temp"]),
                    humidity: number(f["rh"]), battery: number(f["bat"]), rssi: number(f["rssi"]),
                    ageSeconds: number(f["age"]), state: f["state"] ?? "", format: sourceFormat(f["format"]),
                    mac: sourceFormat(f["mac"]), moldHours: moldHours))
            case "SD":
                sd = f["status"]
                if let v = f["free_mb"], v != "--" {
                    guard let mb = Int(v) else { throw ParseError.badLine(line) }
                    sdFree = mb
                }
            case "RESET":
                reset = f["reason"]
                other["RESET"] = line // `DiagnosticsReply` reads the abnormal count
            case "POWER":
                src = f["src"]
                volts = number(f["v"])
                pct = number(f["pct"])
                guard let m = f["alerts"].flatMap({ UInt8($0, radix: 16) }) else { throw ParseError.badLine(line) }
                mask = m
                muted = f["muted"] == "1"
                if let a = f["acked"] {
                    guard let value = UInt8(a, radix: 16) else { throw ParseError.badLine(line) }
                    acked = value
                }
            default:
                other[String(head)] = line
            }
        }
        guard let mask else { throw ParseError.missingLine("POWER") }
        guard !sensors.isEmpty else { throw ParseError.missingLine("SENSOR") }
        return StatusReply(
            sensors: sensors, sdStatus: sd, sdFreeMb: sdFree, resetReason: reset, powerSource: src, cellVolts: volts,
            cellPercent: pct, alertMask: mask, muted: muted, ackedMask: acked, otherLines: other, sensorLines: sensorLines)
    }
}

/// Parsed `VERSION` reply: `VERSION fw=<version> git=<hash> core=<core> state=<version> built=<timestamp>`.
public struct VersionReply: Equatable {
    public let firmware: String
    public let git: String
    public let core: String
    public let state: String
    /// Build time as the panel reports it (`unknown` when the build has none).
    public let built: String

    /// `lines` hold the `VERSION` line (the lines before the `CRC32` line).
    public static func parse(lines: [String]) throws -> VersionReply {
        guard let line = lines.first(where: { $0.hasPrefix("VERSION ") }) else { throw ParseError.missingLine("VERSION") }
        let f = fields(line)
        guard let fw = f["fw"], let git = f["git"], let core = f["core"], let state = f["state"], let built = f["built"],
              !fw.isEmpty, !git.isEmpty, !core.isEmpty, !state.isEmpty, !built.isEmpty else { throw ParseError.badLine(line) }
        return VersionReply(firmware: fw, git: git, core: core, state: state, built: built)
    }
}

/// Parsed `TIME` reply: `TIME <YYYY-MM-DDTHH:MM:SS> valid=<0|1> osf=<0|1>`.
public struct TimeReply: Equatable {
    public let timeString: String
    public let date: Date?
    public let valid: Bool
    public let osf: Bool

    public init(timeString: String, date: Date?, valid: Bool, osf: Bool) {
        self.timeString = timeString
        self.date = date
        self.valid = valid
        self.osf = osf
    }

    public static func parse(_ line: String) throws -> TimeReply {
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 4, parts[0] == "TIME" else {
            throw ParseError.badLine(line)
        }
        let timeStr = String(parts[1])
        var valid: Bool?
        var osf: Bool?
        for part in parts.dropFirst(2) {
            if let eq = part.firstIndex(of: "=") {
                let key = part[..<eq]
                let val = part[part.index(after: eq)...]
                if key == "valid" {
                    valid = val == "1"
                } else if key == "osf" {
                    osf = val == "1"
                }
            }
        }
        guard let valid, let osf else {
            throw ParseError.badLine(line)
        }
        let date = PanelClock.parse(text: timeStr)
        return TimeReply(timeString: timeStr, date: date, valid: valid, osf: osf)
    }

    public static func parse(line: String) throws -> TimeReply {
        try parse(line)
    }

    public static func parse(lines: [String]) throws -> TimeReply {
        guard let line = lines.first(where: { $0.hasPrefix("TIME ") || $0 == "TIME" }) else {
            throw ParseError.missingLine("TIME")
        }
        return try parse(line)
    }
}

/// One `HISTORY` row. A missing value is `nil`.
public struct HistoryRow: Hashable {
    public let sensor: Int
    public let ageHours: Int
    public let temperatureC: Double?
    public let humidityPct: Double?
    public let noiseDb: Double?
}

public enum HistoryReply {
    public static let header = "sensor,age_h,temp_c,rh_pct,noise_db"

    /// `lines` hold `BEGIN HISTORY`, the header and the rows.
    public static func parse(lines: [String]) throws -> [HistoryRow] {
        guard lines.first == "BEGIN HISTORY" else { throw ParseError.missingLine("BEGIN HISTORY") }
        guard lines.count >= 2, lines[1] == header else { throw ParseError.missingLine(header) }
        return try lines.dropFirst(2).map { line in
            let c = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard c.count == 5, let s = Int(c[0]), let a = Int(c[1]),
                  (0...10).contains(s), (0...23).contains(a) else { throw ParseError.badLine(line) }
            return HistoryRow(sensor: s, ageHours: a, temperatureC: number(c[2]), humidityPct: number(c[3]), noiseDb: number(c[4]))
        }
    }
}

/// One `WEEK` row: the daily minimum and maximum of one sensor. Day 0 is the day before the stamp.
/// `date` is empty without a stamp. A gap day has `nil` values.
public struct WeekRow: Hashable {
    public let sensor: Int
    public let day: Int
    public let date: String
    public let temperatureMinC: Double?
    public let temperatureMaxC: Double?
    public let humidityMinPct: Double?
    public let humidityMaxPct: Double?
    /// Hours above `mold_alert_limit_rh` (0 to 24). `nil` for a gap day, an empty field and a reply with 7 columns.
    public var moldHours: Int? = nil
}

/// An empty date (no stamp) or `YYYY-MM-DD`.
private func isWeekDate(_ s: String) -> Bool {
    if s.isEmpty { return true }
    let u = Array(s.utf8)
    guard u.count == 10, u[4] == 0x2D, u[7] == 0x2D else { return false }
    return u.enumerated().allSatisfy { i, b in i == 4 || i == 7 || (0x30...0x39).contains(b) }
}

public enum WeekReply {
    public static let header = "sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h"
    /// Header of a firmware without the `mold_h` column.
    public static let legacyHeader = "sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct"

    /// `lines` hold `BEGIN WEEK`, the header and the rows. The header `mold_h` gives 8 columns, the old header 7.
    public static func parse(lines: [String]) throws -> [WeekRow] {
        guard lines.first == "BEGIN WEEK" else { throw ParseError.missingLine("BEGIN WEEK") }
        guard lines.count >= 2, lines[1] == header || lines[1] == legacyHeader else { throw ParseError.missingLine(header) }
        var seen = Set<Int>()
        return try lines.dropFirst(2).map { line in
            let c = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard c.count == (lines[1] == header ? 8 : 7), let s = Int(c[0]), let d = Int(c[1]),
                  (0...10).contains(s), (0...6).contains(d), isWeekDate(c[2]),
                  seen.insert(s * 7 + d).inserted else { throw ParseError.badLine(line) }
            var mold: Int? = nil
            if c.count == 8, !c[7].isEmpty {
                guard c[7].utf8.allSatisfy({ (0x30...0x39).contains($0) }), let hours = Int(c[7]), (0...24).contains(hours) else { throw ParseError.badLine(line) }
                mold = hours
            }
            let row = WeekRow(sensor: s, day: d, date: c[2], temperatureMinC: number(c[3]), temperatureMaxC: number(c[4]),
                              humidityMinPct: number(c[5]), humidityMaxPct: number(c[6]), moldHours: mold)
            // The firmware sends each value pair or none; a minimum above the maximum is a bad row.
            if let lo = row.temperatureMinC, let hi = row.temperatureMaxC, lo > hi { throw ParseError.badLine(line) }
            if let lo = row.humidityMinPct, let hi = row.humidityMaxPct, lo > hi { throw ParseError.badLine(line) }
            return row
        }
    }
}

/// One `EVENTS` row. `time` is empty when the RTC time was invalid.
public struct EventRow: Equatable {
    public let time: String
    public let event: String
    public let detail: Int
}

public enum EventsReply {
    public static let header = "time,event,detail"

    public static func parse(lines: [String]) throws -> [EventRow] {
        guard lines.first == "BEGIN EVENTS" else { throw ParseError.missingLine("BEGIN EVENTS") }
        guard lines.count >= 2, lines[1] == header else { throw ParseError.missingLine(header) }
        return try lines.dropFirst(2).map { line in
            let c = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard c.count == 3, let d = Int(c[2]) else { throw ParseError.badLine(line) }
            return EventRow(time: c[0], event: c[1], detail: d)
        }
    }
}

/// Digits with an optional leading minus; no plus sign, no space.
func plainInt(_ s: String) -> Int? {
    let digits = s.hasPrefix("-") ? s.dropFirst() : Substring(s)
    guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
    return Int(s)
}

/// `12`, `-3`, `21.5`: digits, one optional fraction and an optional leading minus.
private func plainDecimal(_ s: String) -> Double? {
    let body = s.hasPrefix("-") ? s.dropFirst() : Substring(s)
    let parts = body.split(separator: ".", omittingEmptySubsequences: false)
    guard (1...2).contains(parts.count),
          parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0.isASCII && $0.isNumber } }) else { return nil }
    return Double(s)
}

/// One `SCAN` row: one sensor in range.
public struct ScanRow: Equatable, Identifiable {
    public var id: String { mac }
    public let mac: String
    public let format: String
    public let rssi: Int
    /// Registry slot 1 to 5 that holds the sensor, `nil` for `-`.
    public let slot: Int?
    public let temperatureC: Double?
    public let humidityPct: Double?
    public let batteryPct: Double?
}

/// Parsed `SCAN` reply.
public struct ScanReply: Equatable {
    public static let header = "mac,format,rssi,slot,temp_c,rh_pct,battery_pct"
    public static let formats: Set<String> = ["WOIO", "METER", "BTHOME", "ATC", "THERMOPRO"]

    /// Rows in the order of the reply.
    public let rows: [ScanRow]
    /// MACs that the firmware dropped (`DROPPED,<N>` row), 0 without that row.
    public let dropped: Int
    /// Rows with the strongest signal first; equal RSSI keeps the order of the reply.
    public var sortedByRssi: [ScanRow] {
        rows.enumerated().sorted { $0.element.rssi != $1.element.rssi ? $0.element.rssi > $1.element.rssi : $0.offset < $1.offset }.map(\.element)
    }

    /// `lines` hold `BEGIN SCAN`, the header and the rows.
    public static func parse(lines: [String]) throws -> ScanReply {
        guard lines.first == "BEGIN SCAN" else { throw ParseError.missingLine("BEGIN SCAN") }
        guard lines.count >= 2, lines[1] == header else { throw ParseError.missingLine(header) }
        var rows: [ScanRow] = []
        var dropped = 0
        var seenDropped = false
        var seen = Set<String>()
        for line in lines.dropFirst(2) {
            let c = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            if c.first == "DROPPED" {
                guard line == lines.last else { throw ParseError.badLine(line) } // the firmware sends it last
                guard c.count == 2, let n = plainInt(c[1]), n >= 0, !seenDropped else { throw ParseError.badLine(line) }
                seenDropped = true
                dropped = n
                continue
            }
            guard c.count == 7, SlotAssignment.isValidMac(c[0]), formats.contains(c[1]), let rssi = plainInt(c[2]),
                  (-127...0).contains(rssi) else {
                throw ParseError.badLine(line)
            }
            guard seen.insert(c[0].uppercased()).inserted else { throw ParseError.badLine(line) } // one row per MAC
            var slot: Int?
            if c[3] != "-" {
                guard let n = plainInt(c[3]), SlotAssignment.slots.contains(n) else { throw ParseError.badLine(line) }
                slot = n
            }
            // A value is a plain number or empty/`--`; "nan" and "inf" are not.
            let ranges: [ClosedRange<Double>] = [-50...100, 0...100, 0...100] // C, %RH, % as the firmware accepts them
            let values = try zip(c[4...6], ranges).map { text, range -> Double? in
                if text.isEmpty || text == "--" { return nil }
                // Plain decimal text only: no hex float, nan, inf, "5.", ".5" or "1--2".
                guard let v = plainDecimal(text), range.contains(v) else {
                    throw ParseError.badLine(line)
                }
                return v
            }
            rows.append(ScanRow(
                mac: c[0].uppercased(), format: c[1], rssi: rssi, slot: slot,
                temperatureC: values[0], humidityPct: values[1], batteryPct: values[2]))
        }
        return ScanReply(rows: rows, dropped: dropped)
    }
}

/// Parsed `CONFIG` reply.
public struct ConfigReply: Equatable {
    public let values: [String: String]
    /// Keys in the order of the reply.
    public let keys: [String]
    /// `SD` or `DEFAULT`.
    public let source: String?
    public let rejectedLines: [Int]

    public init(values: [String: String], keys: [String] = [], source: String? = nil, rejectedLines: [Int] = []) {
        self.values = values
        self.keys = keys.isEmpty ? Array(values.keys) : keys
        self.source = source
        self.rejectedLines = rejectedLines
    }

    public static func parse(lines: [String]) throws -> ConfigReply {
        var values: [String: String] = [:]
        var keys: [String] = []
        var source: String?
        var rejected: [Int] = []
        for line in lines {
            if line.hasPrefix("SOURCE ") {
                source = String(line.dropFirst(7))
            } else if line.hasPrefix("REJECTED line="), let n = Int(line.dropFirst(14)) {
                rejected.append(n)
            } else if let eq = line.firstIndex(of: "=") {
                let k = String(line[..<eq])
                if values[k] == nil { keys.append(k) }
                values[k] = String(line[line.index(after: eq)...])
            } else {
                throw ParseError.badLine(line)
            }
        }
        guard !values.isEmpty else { throw ParseError.missingLine("key=value") }
        return ConfigReply(values: values, keys: keys, source: source, rejectedLines: rejected)
    }

    /// Unit of the `STATUS` temperatures (`temp_unit`): `C` or `F`; `nil` when the reply has no valid key.
    /// `HISTORY`, the telemetry frame and the card files stay in Celsius.
    public var temperatureUnit: String? {
        values["temp_unit"].flatMap { $0 == "C" || $0 == "F" ? $0 : nil }
    }

    /// `ble_console` value; 0 when the firmware does not report the key.
    public var bleConsoleLevel: Int { Int(values["ble_console"] ?? "") ?? 0 }

    /// Sensor name for tile index (0 = onboard_name). A slot tile (1..10) shows the name that is bound to
    /// `activeMac`, the MAC of the sensor in the slot; the telemetry frame carries no MAC, so the default is
    /// the fixed MAC of the slot (`slotN_mac`). A slot without a known MAC has no name: `slotN_name` never
    /// shows for another sensor.
    public func name(forSlot slot: Int, activeMac: String? = nil) -> String? {
        if slot == 0 {
            if let n = values["onboard_name"], !n.isEmpty { return n }
            return nil
        }
        guard (1...10).contains(slot) else { return nil }
        return name(forMac: activeMac ?? mac(forSlot: slot))
    }

    /// Name of the sensor with `mac`: the `slotN_name` of the slot whose fixed MAC (`slotN_mac`) equals
    /// `mac`, whatever slot the sensor sits in. `nil` for an unknown or zero MAC and for a fixed MAC without a name.
    public func name(forMac mac: String?) -> String? {
        guard let wanted = Self.normalizedMac(mac) else { return nil }
        for slot in 1...10 where Self.normalizedMac(values["slot\(slot)_mac"]) == wanted {
            if let n = values["slot\(slot)_name"], !n.isEmpty { return n }
            return nil
        }
        return nil
    }

    /// Upper-case hex pairs separated by colons; `nil` for a malformed or all-zero MAC.
    static func normalizedMac(_ mac: String?) -> String? {
        guard let mac else { return nil }
        let hex = mac.filter { $0 != ":" && $0 != "-" }.uppercased()
        guard hex.count == 12, hex.allSatisfy({ $0.isHexDigit }), hex.contains(where: { $0 != "0" }) else { return nil }
        var out: [String] = []
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            out.append(String(hex[i..<j]))
            i = j
        }
        return out.joined(separator: ":")
    }

    /// True when the config names or fixes the slot (`slotN_name` or `slotN_mac`): the firmware keeps a tile for it
    /// even without a reading, although a name alone shows only the default title.
    public func isConfigured(slot: Int) -> Bool {
        guard (1...10).contains(slot) else { return slot == 0 }
        return !(values["slot\(slot)_name"] ?? "").isEmpty || mac(forSlot: slot) != nil
    }

    /// Fixed MAC for slot index (1..10 = slot1_mac..slot10_mac).
    public func mac(forSlot slot: Int) -> String? {
        guard (1...10).contains(slot) else { return nil }
        if let m = values["slot\(slot)_mac"], !m.isEmpty { return m }
        return nil
    }
}

/// The console commands that the app may send. The app sends one only after a user action.
public enum ConsoleCommand: Equatable {
    case status, history, events, week, config, version, scan, screen
    /// `REJECTS`: the packets that the last BLE scan rejected (works on USB and on BLE).
    case rejects
    case list
    case dump(String)
    case time
    case timeSet(Date)
    case beep(Int)
    case pattern(Int)
    case mute(Bool)
    /// `ACK N` (N 0 to 7): stops the chime reminder of one active alert. The firmware answers `ERR NOT_ACTIVE` for an alert that is off.
    case ack(Int)
    /// `ACK ALL`: stops the chime reminder of each active alert at once. The firmware answers `OK ACK ALL <2 hex digits>` or `ERR NOT_ACTIVE`.
    case ackAll
    case rtcTrim
    /// `SLOT CLEAR N` (N 1 to 10): frees one registry slot at once. The firmware answers `ERR FIXED_MAC` for a slot with a fixed MAC.
    case slotClear(Int)
    case set(key: String, value: String)
    case calib(Int)
    case salt(Int)
    /// `SALT N MGCL2`: the same check against saturated magnesium chloride.
    case saltMagnesium(Int)

    public static func beep(bit: Int) -> ConsoleCommand { .beep(bit) }
    public static func pattern(n: Int) -> ConsoleCommand { .pattern(n) }

    /// Text for the replies of `ACK` and `ACK ALL`; other text stays as it is.
    public static func ackMessage(_ reply: String) -> String {
        if reply.hasPrefix("OK ACK ALL ") {
            let hex = String(reply.dropFirst("OK ACK ALL ".count))
            if hex.utf8.count == 2,
               hex.utf8.allSatisfy({ ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) || ($0 >= 0x41 && $0 <= 0x46) }),
               let mask = UInt8(hex, radix: 16), mask != 0 {
                let names = (0..<8).compactMap { bit -> String? in
                    guard mask & (1 << UInt8(bit)) != 0 else { return nil }
                    return ackAlertName(bit)
                }
                if !names.isEmpty {
                    return "Acknowledged: " + names.joined(separator: ", ")
                }
            }
        }
        switch reply {
        case "ERR NOT_ACTIVE": return "The alert is off"
        case "ERR READ_ONLY": return "Set ble_console=2 to apply"
        default: return reply
        }
    }

    /// True when two or more repeatable active alert bits (all bits except `CHARGE_FULL`, bit 4) are not acknowledged yet.
    public static func canAcknowledgeAll(alertMask: UInt8, ackedMask: UInt8) -> Bool {
        let open = alertMask & ~ackedMask & ~AlertMask.chargeFull.rawValue
        return open.nonzeroBitCount >= 2
    }

    private static func ackAlertName(_ bit: Int) -> String? {
        switch bit {
        case 0: return "Freezer"
        case 1: return "Humidity"
        case 2: return "Noise high"
        case 3: return "Sensor lost"
        case 4: return "Charge full"
        case 5: return "Ventilate"
        case 6: return "Sensor battery"
        case 7: return "Heat"
        default: return nil
        }
    }

    /// Keys whose empty value the firmware accepts (no fixed MAC).
    static let emptyAllowedKeys: Set<String> = Set((1...10).map { "slot\($0)_mac" })

    /// Text without the line end. Returns `nil` for a key or value that the firmware cannot take
    /// (empty value except for `slotN_mac`, white space, `=` in the key, or a line above 40 bytes).
    public var line: String? {
        switch self {
        case .status: return "STATUS"
        case .history: return "HISTORY"
        case .events: return "EVENTS"
        case .week: return "WEEK"
        case .config: return "CONFIG"
        case .version: return "VERSION"
        case .scan: return "SCAN"
        case .rejects: return "REJECTS"
        case .screen: return "SCREEN"
        case .list: return "LIST"
        case let .dump(name): return LogArchive.isValidName(name) ? "DUMP \(name)" : nil
        case .time: return "TIME"
        case let .timeSet(date):
            guard let formatted = PanelClock.format(date: date) else { return nil }
            return "TIME SET \(formatted)"
        case let .beep(bit):
            guard (0...7).contains(bit) else { return nil }
            return "BEEP \(bit)"
        case let .pattern(n):
            guard (0...2).contains(n) else { return nil }
            return "PATTERN \(n)"
        case let .mute(on): return on ? "MUTE 1" : "MUTE 0"
        case let .ack(bit):
            guard (0...7).contains(bit) else { return nil }
            return "ACK \(bit)"
        case .ackAll: return "ACK ALL"
        case .rtcTrim: return "RTC TRIM"
        case let .slotClear(slot):
            guard SlotAssignment.slots.contains(slot) else { return nil }
            return "SLOT CLEAR \(slot)"
        case let .calib(reference):
            guard (0...10).contains(reference) else { return nil }
            return "CALIB \(reference)"
        case let .salt(reference):
            guard (0...10).contains(reference) else { return nil }
            return "SALT \(reference)"
        case let .saltMagnesium(reference):
            guard (0...10).contains(reference) else { return nil }
            return "SALT \(reference) MGCL2"
        case let .set(key, value):
            let text = "SET \(key)=\(value)"
            let bad = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
            guard !key.isEmpty, !value.isEmpty || Self.emptyAllowedKeys.contains(key), !key.contains("="),
                  key.rangeOfCharacter(from: bad) == nil, value.rangeOfCharacter(from: bad) == nil,
                  text.utf8.count <= 40 else { return nil }
            return text
        }
    }

    public var data: Data? { line.map { Data(($0 + "\n").utf8) } }
}

/// BLE console bridge (Nordic UART service).
public enum ConsoleBridge {
    public static let serviceUUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    public static let rxUUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    public static let txUUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
}
