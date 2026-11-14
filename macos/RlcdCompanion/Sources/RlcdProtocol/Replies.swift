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
    /// Lines in the reply that this parser does not know (SHTC3, BLE, RUNTIME, ...), by first word.
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
            case "RESET": reset = f["reason"]
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
                  (0...3).contains(s), (0...23).contains(a) else { throw ParseError.badLine(line) }
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
}

/// The console commands that the app may send. The app sends one only after a user action.
public enum ConsoleCommand: Equatable {
    case status, history, events, config
    case set(key: String, value: String)

    /// Keys whose empty value the firmware accepts (no fixed MAC).
    static let emptyAllowedKeys: Set<String> = ["slot1_mac", "slot2_mac", "slot3_mac"]

    /// Text without the line end. Returns `nil` for a key or value that the firmware cannot take
    /// (empty value except for `slotN_mac`, white space, `=` in the key, or a line above 40 bytes).
    public var line: String? {
        switch self {
        case .status: return "STATUS"
        case .history: return "HISTORY"
        case .events: return "EVENTS"
        case .config: return "CONFIG"
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
