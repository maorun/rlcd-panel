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

/// `SENSOR <index> <name> temp=<C> rh=<%RH> bat=<%> rssi=<dBm> age=<s> state=<...>`.
public struct StatusSensor: Equatable {
    public let index: Int
    public let name: String
    public let temperature: Double?
    public let humidity: Double?
    public let battery: Double?
    public let rssi: Double?
    public let ageSeconds: Double?
    public let state: String
}

/// Parsed `STATUS` reply. Unknown lines are kept in `otherLines`, so a newer firmware does not break the parser.
public struct StatusReply: Equatable {
    public let sensors: [StatusSensor]
    public let sdStatus: String?
    public let resetReason: String?
    public let powerSource: String?
    public let cellVolts: Double?
    public let cellPercent: Double?
    public let alertMask: UInt8
    public let muted: Bool
    /// Lines in the reply that this parser does not read in full (SHTC3, BLE, RUNTIME, ...), by first word. `RESET` is also here: `resetReason` reads only the reason, `DiagnosticsReply` reads the abnormal count.
    public let otherLines: [String: String]

    public var isUsbPower: Bool { powerSource == "USB" }

    public static func parse(lines: [String]) throws -> StatusReply {
        var sensors: [StatusSensor] = []
        var sd: String?
        var reset: String?
        var src: String?
        var volts: Double?
        var pct: Double?
        var mask: UInt8?
        var muted = false
        var other: [String: String] = [:]
        for line in lines {
            let words = line.split(separator: " ", omittingEmptySubsequences: true)
            guard let head = words.first else { continue }
            let f = fields(line)
            switch head {
            case "SENSOR":
                guard words.count >= 3, let idx = Int(words[1]) else { throw ParseError.badLine(line) }
                sensors.append(StatusSensor(
                    index: idx, name: String(words[2]), temperature: number(f["temp"]),
                    humidity: number(f["rh"]), battery: number(f["bat"]), rssi: number(f["rssi"]),
                    ageSeconds: number(f["age"]), state: f["state"] ?? ""))
            case "SD": sd = f["status"]
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
            default:
                other[String(head)] = line
            }
        }
        guard let mask else { throw ParseError.missingLine("POWER") }
        guard !sensors.isEmpty else { throw ParseError.missingLine("SENSOR") }
        return StatusReply(
            sensors: sensors, sdStatus: sd, resetReason: reset, powerSource: src, cellVolts: volts,
            cellPercent: pct, alertMask: mask, muted: muted, otherLines: other)
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
                  (0...5).contains(s), (0...23).contains(a) else { throw ParseError.badLine(line) }
            return HistoryRow(sensor: s, ageHours: a, temperatureC: number(c[2]), humidityPct: number(c[3]), noiseDb: number(c[4]))
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
private func plainInt(_ s: String) -> Int? {
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
    /// Registry slot 1 to 3 that holds the sensor, `nil` for `-`.
    public let slot: Int?
    public let temperatureC: Double?
    public let humidityPct: Double?
    public let batteryPct: Double?
}

/// Parsed `SCAN` reply.
public struct ScanReply: Equatable {
    public static let header = "mac,format,rssi,slot,temp_c,rh_pct,battery_pct"
    public static let formats: Set<String> = ["WOIO", "METER", "BTHOME", "ATC"]

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

    /// Sensor name for tile index (0 = onboard_name, 1..5 = slot1_name..slot5_name).
    public func name(forSlot slot: Int) -> String? {
        if slot == 0 {
            if let n = values["onboard_name"], !n.isEmpty { return n }
        } else if (1...5).contains(slot) {
            if let n = values["slot\(slot)_name"], !n.isEmpty { return n }
        }
        return nil
    }

    /// Fixed MAC for slot index (1..5 = slot1_mac..slot5_mac).
    public func mac(forSlot slot: Int) -> String? {
        guard (1...5).contains(slot) else { return nil }
        if let m = values["slot\(slot)_mac"], !m.isEmpty { return m }
        return nil
    }
}

/// The console commands that the app may send. The app sends one only after a user action.
public enum ConsoleCommand: Equatable {
    case status, history, events, config, version, scan
    case time
    case timeSet(Date)
    case beep(Int)
    case pattern(Int)
    case set(key: String, value: String)

    public static func beep(bit: Int) -> ConsoleCommand { .beep(bit) }
    public static func pattern(n: Int) -> ConsoleCommand { .pattern(n) }

    /// Keys whose empty value the firmware accepts (no fixed MAC).
    static let emptyAllowedKeys: Set<String> = ["slot1_mac", "slot2_mac", "slot3_mac", "slot4_mac", "slot5_mac"]

    /// Text without the line end. Returns `nil` for a key or value that the firmware cannot take
    /// (empty value except for `slotN_mac`, white space, `=` in the key, or a line above 40 bytes).
    public var line: String? {
        switch self {
        case .status: return "STATUS"
        case .history: return "HISTORY"
        case .events: return "EVENTS"
        case .config: return "CONFIG"
        case .version: return "VERSION"
        case .scan: return "SCAN"
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
