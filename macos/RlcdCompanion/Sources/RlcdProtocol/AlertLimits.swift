import Foundation

/// The nine alert limit keys of `config.ini` for the alert limits view. Ranges come from `ConfigSchema`
/// (same limits as `DeviceConfigParser`); the order is fixed. Mirrors the Android `AlertLimits`.
public enum AlertLimits {
    public struct Limit: Equatable, Sendable {
        public let key: String
        public let label: String
        public let unit: String
        public let decimal: Bool

        /// Template default of the schema.
        public var defaultText: String { ConfigSchema.entry(for: key)?.defaultValue ?? "" }

        /// Range hint of the schema, with the off value when the key has one.
        public var rangeText: String {
            guard let entry = ConfigSchema.entry(for: key) else { return "" }
            switch entry.kind {
            case let .integer(lo, hi, off):
                return (off.map { "\($0) (off) or " } ?? "") + "\(lo) to \(hi) \(unit)"
            case let .decimal(lo, hi, off):
                return (off.map { "\($0) (off) or " } ?? "") + String(format: "%.1f to %.1f %@", lo, hi, unit)
            default:
                return unit
            }
        }
    }

    public static let limits: [Limit] = [
        Limit(key: "mold_alert_limit_rh", label: "Mold alert", unit: "%RH", decimal: false),
        Limit(key: "mold_alert_delay_h", label: "Mold alert delay", unit: "h", decimal: false),
        Limit(key: "dry_alert_limit_rh", label: "Dry air", unit: "%RH", decimal: false),
        Limit(key: "dry_alert_delay_h", label: "Dry air delay", unit: "h", decimal: false),
        Limit(key: "freeze_alert_limit_c", label: "Freeze alert", unit: "°C", decimal: true),
        Limit(key: "heat_alert_limit_c", label: "Heat alert", unit: "°C", decimal: false),
        Limit(key: "heat_alert_delay_h", label: "Heat alert delay", unit: "h", decimal: false),
        Limit(key: "ventilate_limit_rh", label: "Ventilation limit", unit: "%RH", decimal: false),
        Limit(key: "sensor_battery_limit_pct", label: "Sensor battery", unit: "%", decimal: false),
    ]

    /// Text of a number as the `SET` command sends it. A comma counts as a decimal point. Only the number grammar of
    /// the firmware parser passes (sign, digits, one point), so no exponent or hex float reaches the conversion.
    private static func normalized(_ limit: Limit, _ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let v = ConfigSchema.parseNumber(t), v.isFinite, abs(v) < 1e9 else { return nil }
        if limit.decimal {
            // One decimal at most.
            guard abs(v * 10 - (v * 10).rounded()) < 1e-9 else { return nil }
            return String(format: "%.1f", v)
        }
        guard v == v.rounded() else { return nil }
        return String(Int(v))
    }

    /// Error text for `text` against the firmware range, `nil` when valid.
    public static func error(_ limit: Limit, _ text: String) -> String? {
        guard let wire = normalized(limit, text) else {
            return "\(limit.label): \(limit.decimal ? "number with one decimal at most" : "whole number") needed"
        }
        if ConfigSchema.validate(key: limit.key, value: wire) != nil { return "\(limit.label): allowed \(limit.rangeText)" }
        return nil
    }

    /// Cached value of the last `CONFIG` reply, else the firmware default.
    public static func shown(_ limit: Limit, config: ConfigReply?) -> String {
        config?.values[limit.key]?.trimmingCharacters(in: .whitespaces).nonEmpty ?? limit.defaultText
    }

    /// `edits` after the user typed `text` into `limit`. A text equal to the shown value drops the entry, so an
    /// untouched form holds no edit (like the config editor draft).
    public static func updated(edits: [String: String], limit: Limit, text: String, config: ConfigReply?) -> [String: String] {
        var out = edits
        if text == shown(limit, config: config) { out[limit.key] = nil } else { out[limit.key] = text }
        return out
    }

    /// The edit controls need a console that takes `SET`: USB, or BLE with `ble_console` 2.
    public static func canEdit(transportIsUsb: Bool, consoleLevel: Int) -> Bool { transportIsUsb || consoleLevel == 2 }

    public enum Plan: Equatable {
        case commands([ConsoleCommand])
        case invalid(String)
    }

    /// One `SET` for each key of `edits` (key to input text) whose valid value differs from `config`, in the fixed
    /// order. A key without a cached value always sends. One invalid value refuses the whole list.
    public static func plan(edits: [String: String], config: ConfigReply?) -> Plan {
        var commands: [ConsoleCommand] = []
        for limit in limits {
            guard let text = edits[limit.key] else { continue }
            if let e = error(limit, text) { return .invalid(e) }
            let wire = normalized(limit, text)!
            if let cached = config?.values[limit.key], let c = Double(cached.trimmingCharacters(in: .whitespaces)),
               c == Double(wire) { continue }
            commands.append(.set(key: limit.key, value: wire))
        }
        return .commands(commands)
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
