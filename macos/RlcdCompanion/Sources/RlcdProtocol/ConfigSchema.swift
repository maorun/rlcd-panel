import Foundation

public enum ConfigCategory: String, CaseIterable, Sendable {
    case climate = "Climate", alerts = "Alerts", power = "Power", sensors = "Sensors", ble = "BLE"
}

/// Value rule of one `config.ini` key. The rules follow `DeviceConfigParser`.
public enum ConfigValueKind: Equatable, Sendable {
    /// Whole number in `min...max`. `off` is one more accepted value outside the range (for example 0).
    case integer(min: Int, max: Int, off: Int? = nil)
    /// Decimal number in `min...max`.
    case decimal(min: Double, max: Double)
    /// One of the listed texts.
    case enumeration([String])
    /// Whole number of `bits` bits.
    case bitMask(bits: Int)
    /// 1 to 12 characters of A-Z, 0-9, space and "-".
    case name
    /// `AA:BB:CC:DD:EE:FF`, not all zero, or empty (no fixed MAC).
    case mac
}

public struct ConfigEntry: Equatable, Sendable {
    public let key: String
    public let category: ConfigCategory
    public let kind: ConfigValueKind
    /// Text of the template value. Empty for a key without a default (names and MACs).
    public let defaultValue: String
    public let unit: String
    public let description: String
}

public enum ConfigValidationError: Error, Equatable, Sendable {
    case unknownKey
    case notANumber
    case notWhole
    case outOfRange(String)
    case notInList([String])
    case badName
    case badMac
    case zeroMac
    case duplicateMac

    public var text: String {
        switch self {
        case .unknownKey: return "Unknown key"
        case .notANumber: return "Not a number"
        case .notWhole: return "Whole number needed"
        case let .outOfRange(range): return "Allowed: \(range)"
        case let .notInList(list): return "Allowed: " + list.joined(separator: ", ")
        case .badName: return "1 to 12 characters of A-Z, 0-9, space and -"
        case .badMac: return "Format AA:BB:CC:DD:EE:FF"
        case .zeroMac: return "MAC must not be all zero"
        case .duplicateMac: return "Another slot has this MAC"
        }
    }
}

/// One difference between two configurations.
public struct ConfigChange: Equatable, Sendable {
    public let key: String
    public let old: String
    public let new: String
    /// `key: old -> new`.
    public var text: String { "\(key): \(old.isEmpty ? "(empty)" : old) -> \(new.isEmpty ? "(empty)" : new)" }
}

/// Schema of all keys of `DeviceConfigParser::KEYS`.
/// The array order is the fixed order of the batch `SET`. `ble_console` stands last: a lower level
/// makes the console read-only, so no `SET` may follow it.
public enum ConfigSchema {
    public static let maxLineBytes = 40

    private static func e(_ key: String, _ cat: ConfigCategory, _ kind: ConfigValueKind, _ def: String,
                          _ unit: String, _ text: String) -> ConfigEntry {
        ConfigEntry(key: key, category: cat, kind: kind, defaultValue: def, unit: unit, description: text)
    }

    public static let entries: [ConfigEntry] = {
        var out: [ConfigEntry] = [
            // Climate
            e("temp_offset_c", .climate, .decimal(min: -10, max: 10), "-1.9", "°C", "Add this value to the onboard temperature."),
            e("rh_offset_pct", .climate, .decimal(min: -10, max: 10), "0.0", "%RH", "Add this value to the onboard humidity."),
            e("temp_unit", .climate, .enumeration(["C", "F"]), "C", "", "Show the temperature in Celsius or Fahrenheit. The card files and alerts stay in Celsius."),
            e("display_invert", .climate, .integer(min: 0, max: 1), "0", "", "0 is dark on light. 1 is light on dark."),
            e("freeze_alert_limit_c", .climate, .decimal(min: -20, max: 15), "0.0", "°C", "Start the freezer alert below this temperature."),
            e("mold_alert_limit_rh", .climate, .integer(min: 40, max: 95), "70", "%RH", "Count the hours above this humidity for the mold alert."),
            e("heat_alert_limit_c", .climate, .integer(min: 27, max: 45, off: 0), "0", "°C", "Start the heat alert at this feels-like temperature. 0 is off."),
            e("ventilate_limit_rh", .climate, .integer(min: 50, max: 80), "60", "%RH", "Show the ventilation badge above this humidity."),
            // Alerts
            e("noise_alert_limit_db", .alerts, .integer(min: 40, max: 100, off: 0), "0", "dB", "Start the noise alert at this level. 0 is off."),
            e("chime_alert_mask", .alerts, .bitMask(bits: 8), "255", "", "Bit mask of the alerts that sound the chime. Bit 0 is freezer. Bit 7 is heat."),
            e("chime_volume_pct", .alerts, .integer(min: 0, max: 100), "30", "%", "Set the chime volume."),
            e("chime_repeat_h", .alerts, .integer(min: 0, max: 24), "0", "h", "Sound an active alert again after this time. 0 is off."),
            e("quiet_hours_start_hour", .alerts, .integer(min: 0, max: 23), "22", "h", "Set the first hour of the quiet hours."),
            e("quiet_hours_end_hour", .alerts, .integer(min: 0, max: 23), "7", "h", "Set the last hour of the quiet hours."),
            e("climate_alert_tiles", .alerts, .bitMask(bits: 4), "15", "", "Bit mask of the tiles that feed the freezer and mold alerts. Bit 0 is onboard."),
            e("sensor_battery_limit_pct", .alerts, .integer(min: 0, max: 50), "10", "%", "Show BAT LOW at or below this sensor battery level. 0 is off."),
            // Power
            e("wake_interval_s", .power, .integer(min: 30, max: 900), "60", "s", "Set the time between two scans on battery power."),
            e("night_wake_interval_s", .power, .integer(min: 60, max: 1800, off: 0), "0", "s", "Set the time between two scans in the quiet hours. 0 uses wake_interval_s."),
            e("battery_offset_mv", .power, .integer(min: -300, max: 300), "0", "mV", "Add this value to the cell voltage."),
            e("sensor_stale_min", .power, .integer(min: 5, max: 240), "30", "min", "Mark a sensor as stale after this time without a packet."),
            e("slot_release_h", .power, .integer(min: 0, max: 168), "0", "h", "Release a slot without a fixed MAC after this time without a packet. 0 is off."),
            e("ntp_sync_interval_h", .power, .integer(min: 6, max: 168), "24", "h", "Set the time between two NTP syncs."),
            e("log_retention_months", .power, .integer(min: 0, max: 120), "0", "months", "Keep the log files for this time. 0 keeps all files."),
            // Sensors
            e("mic_spl_offset_db", .sensors, .decimal(min: -20, max: 20), "0.0", "dB", "Add this value to the microphone level."),
            e("outdoor_slot", .sensors, .integer(min: 0, max: 3), "0", "", "Select the outdoor slot for the ventilation advice. 0 is off."),
        ]
        for n in 1...3 {
            out.append(e("slot\(n)_temp_offset_c", .sensors, .decimal(min: -5, max: 5), "0.0", "°C", "Add this value to the slot \(n) temperature."))
        }
        for n in 1...3 {
            out.append(e("slot\(n)_rh_offset_pct", .sensors, .decimal(min: -10, max: 10), "0.0", "%RH", "Add this value to the slot \(n) humidity."))
        }
        out.append(e("onboard_name", .sensors, .name, "", "", "Name of the onboard tile."))
        for n in 1...3 { out.append(e("slot\(n)_name", .sensors, .name, "", "", "Name of the slot \(n) tile.")) }
        for n in 1...3 {
            out.append(e("slot\(n)_mac", .sensors, .mac, "", "", "Fixed MAC of slot \(n). Empty means no fixed MAC."))
        }
        // BLE
        out.append(e("ble_broadcast_enabled", .ble, .integer(min: 0, max: 1), "0", "", "Send the telemetry frame after each scan. 0 is off."))
        out.append(e("ble_console", .ble, .integer(min: 0, max: 2), "0", "", "BLE console on USB power. 1 allows read commands. 2 also allows SET."))
        return out
    }()

    public static let keys: [String] = entries.map(\.key)
    private static let index: [String: Int] = Dictionary(uniqueKeysWithValues: keys.enumerated().map { ($1, $0) })

    public static func entry(for key: String) -> ConfigEntry? { index[key].map { entries[$0] } }
    public static func entries(in category: ConfigCategory) -> [ConfigEntry] { entries.filter { $0.category == category } }

    /// Number grammar of `DeviceConfigParser::parseNumber`: optional sign, digits, optional `.` and digits,
    /// at most 15 characters. No exponent, no trailing text.
    static func parseNumber(_ s: String) -> Double? {
        let b = Array(s.utf8)
        guard !b.isEmpty, b.count <= 15 else { return nil }
        var i = 0
        if b[i] == UInt8(ascii: "+") || b[i] == UInt8(ascii: "-") { i += 1 }
        let intStart = i
        while i < b.count, (48...57).contains(b[i]) { i += 1 }
        guard i > intStart else { return nil }
        if i < b.count, b[i] == UInt8(ascii: ".") {
            i += 1
            let fracStart = i
            while i < b.count, (48...57).contains(b[i]) { i += 1 }
            guard i > fracStart else { return nil }
        }
        guard i == b.count else { return nil }
        return Double(s)
    }

    private static func isMac(_ s: String) -> Bool {
        let b = Array(s.utf8)
        guard b.count == 17 else { return false }
        for i in 0..<17 {
            if i % 3 == 2 {
                if b[i] != UInt8(ascii: ":") { return false }
            } else if !Character(UnicodeScalar(b[i])).isHexDigit { return false }
        }
        return true
    }

    private static func isName(_ s: String) -> Bool {
        let n = s.utf8.count
        guard (1...12).contains(n) else { return false }
        // The parser trims the value, so a leading or trailing space would not stay.
        guard s.first != " ", s.last != " " else { return false }
        return s.utf8.allSatisfy { ($0 >= 65 && $0 <= 90) || ($0 >= 48 && $0 <= 57) || $0 == 32 || $0 == 45 }
    }

    /// Returns `nil` for a value that `DeviceConfigParser` accepts. Checks one key; a MAC that two slots
    /// hold is a check of the whole set (`problems(values:)`).
    public static func validate(key: String, value: String) -> ConfigValidationError? {
        guard let entry = entry(for: key) else { return .unknownKey }
        switch entry.kind {
        case let .integer(lo, hi, off):
            return validateInteger(value, lo: lo, hi: hi, off: off)
        case let .bitMask(bits):
            return validateInteger(value, lo: 0, hi: (1 << bits) - 1, off: nil)
        case let .decimal(lo, hi):
            guard let v = parseNumber(value) else { return .notANumber }
            return v >= lo && v <= hi ? nil : .outOfRange("\(fmt(lo)) to \(fmt(hi))")
        case let .enumeration(list):
            return list.contains(value) ? nil : .notInList(list)
        case .name:
            return isName(value) ? nil : .badName
        case .mac:
            if value.isEmpty { return nil }
            guard isMac(value) else { return .badMac }
            return value.uppercased() == "00:00:00:00:00:00" ? .zeroMac : nil
        }
    }

    private static func validateInteger(_ value: String, lo: Int, hi: Int, off: Int?) -> ConfigValidationError? {
        guard let v = parseNumber(value) else { return .notANumber }
        guard v == v.rounded() else { return .notWhole }
        let i = Int(v)
        if i >= lo && i <= hi { return nil }
        if let off, i == off { return nil }
        let range = off.map { "\($0) or \(lo) to \(hi)" } ?? "\(lo) to \(hi)"
        return .outOfRange(range)
    }

    private static func fmt(_ d: Double) -> String { d == d.rounded() ? String(Int(d)) : String(d) }

    /// Problem texts by key for the values that the user changed (`values` holds the new values).
    /// A problem is a failed validation, a duplicate fixed MAC, or a `SET` line that the console cannot
    /// take (white space, empty value or more than 40 bytes).
    public static func problems(changes: [ConfigChange], allValues: [String: String] = [:]) -> [String: String] {
        var out: [String: String] = [:]
        for c in changes {
            if let err = validate(key: c.key, value: c.new) {
                out[c.key] = err.text
            } else if ConsoleCommand.set(key: c.key, value: c.new).line == nil {
                if c.new.contains(" ") {
                    out[c.key] = "A space in the value is not supported over the console"
                } else {
                    out[c.key] = c.new.isEmpty ? "Empty value not supported" : "Command line not valid or above \(maxLineBytes) bytes"
                }
            }
        }
        // The firmware rejects a MAC that another slot holds at that moment. Replay the batch order.
        var macs: [String: String] = [:]
        for n in 1...3 { macs["slot\(n)_mac"] = (allValues["slot\(n)_mac"] ?? "").uppercased() }
        for n in 1...3 {
            let key = "slot\(n)_mac"
            guard let c = changes.first(where: { $0.key == key }) else { continue }
            let value = c.new.uppercased()
            if !value.isEmpty, validate(key: key, value: value) == nil,
               macs.contains(where: { $0.key != key && $0.value == value }) {
                out[key] = ConfigValidationError.duplicateMac.text + ". Clear the other slot first."
            }
            macs[key] = value
        }
        return out
    }

    /// Value of `key` in a configuration; a key that the reply lacks has the schema default.
    private static func current(_ key: String, _ values: [String: String]) -> String {
        values[key] ?? entry(for: key)?.defaultValue ?? ""
    }

    private static func sameValue(_ entry: ConfigEntry, _ a: String, _ b: String) -> Bool {
        switch entry.kind {
        case .integer, .decimal, .bitMask:
            if let x = parseNumber(a), let y = parseNumber(b) { return x == y }
            return a == b
        case .mac: return a.uppercased() == b.uppercased()
        default: return a == b
        }
    }

    /// Differences from `old` to `new` in schema order. Keys outside the schema are ignored.
    public static func diff(old: ConfigReply, new: ConfigReply) -> [ConfigChange] {
        diff(old: old.values, new: new.values)
    }

    public static func diff(old: [String: String], new: [String: String]) -> [ConfigChange] {
        entries.compactMap { entry in
            let o = current(entry.key, old), n = current(entry.key, new)
            return sameValue(entry, o, n) ? nil : ConfigChange(key: entry.key, old: o, new: n)
        }
    }

    /// `SET` commands for the changes, in the fixed schema order.
    public static func batch(for changes: [ConfigChange]) -> [ConsoleCommand] {
        changes.filter { index[$0.key] != nil }
            .sorted { index[$0.key]! < index[$1.key]! }
            .map { .set(key: $0.key, value: $0.new) }
    }
}

/// Runs a batch: the `SET` commands one after the other, then one `CONFIG`. The first `ERR` reply to a
/// `SET` drops the remaining `SET` commands. The final `CONFIG` still runs, so the form shows the panel state.
public struct ConfigBatch: Equatable {
    public private(set) var pending: [ConsoleCommand]
    public private(set) var applied: [String] = []
    /// Key and reply text of the first failed `SET`.
    public private(set) var failure: (key: String, reply: String)?
    private var current: ConsoleCommand?

    public static func == (l: ConfigBatch, r: ConfigBatch) -> Bool {
        l.pending == r.pending && l.applied == r.applied && l.current == r.current && l.configFailed == r.configFailed
            && l.failure?.key == r.failure?.key && l.failure?.reply == r.failure?.reply
    }

    public init(changes: [ConfigChange]) {
        pending = ConfigSchema.batch(for: changes) + [.config]
    }

    public var isFinished: Bool { pending.isEmpty && current == nil }

    /// The next command to send, or `nil` when the batch is done or a reply is open.
    public mutating func next() -> ConsoleCommand? {
        guard current == nil, !pending.isEmpty else { return nil }
        current = pending.removeFirst()
        return current
    }

    /// Takes the status reply (`OK SET <key>` or `ERR <reason>`) of the running `SET`.
    public mutating func receive(status: String) {
        if current == .config {
            current = nil
            if status.hasPrefix("ERR") { configFailed = true }
            return
        }
        guard case let .set(key, _)? = current else { return }
        current = nil
        if status.hasPrefix("ERR") {
            if failure == nil { failure = (key, status) }
            pending = pending.filter { $0 == .config }
        } else {
            applied.append(key)
        }
    }

    /// Set when the final `CONFIG` got an `ERR` reply.
    public private(set) var configFailed = false

    /// The running `CONFIG` is done.
    public mutating func receiveFrame() {
        if current == .config { current = nil }
    }

    public var summary: String {
        if configFailed { return "Applied \(applied.count). CONFIG refresh failed" }
        if let f = failure { return "Stopped at \(f.key): \(f.reply). Applied: \(applied.count)" }
        return "Applied \(applied.count) setting" + (applied.count == 1 ? "" : "s")
    }
}
