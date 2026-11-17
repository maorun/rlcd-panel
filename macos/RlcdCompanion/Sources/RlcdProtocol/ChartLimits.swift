import Foundation

/// One alert limit that a chart draws as a dashed line.
public struct ChartLimit: Equatable {
    public enum Axis: Equatable { case humidity, temperature }

    public let key: String
    public let label: String
    public let value: Double
    public let axis: Axis
}

/// Alert limit lines of the climate charts, read from the last `CONFIG` reply. Sends no console command.
public enum ChartLimits {
    /// `zeroIsOff` is true for the keys where 0 turns the alert off. `freeze_alert_limit_c` has no off value:
    /// 0.0 is its default and a valid limit.
    private static let definitions: [(key: String, label: String, axis: ChartLimit.Axis, zeroIsOff: Bool)] = [
        ("mold_alert_limit_rh", "Mold", .humidity, false),
        ("dry_alert_limit_rh", "Dry", .humidity, true),
        ("freeze_alert_limit_c", "Freeze", .temperature, false),
        ("heat_alert_limit_c", "Heat (feels-like)", .temperature, true),
    ]

    /// One line for each active limit. A value of 0 for `dry_alert_limit_rh` and `heat_alert_limit_c` (off), a value outside the `ConfigSchema` range, a missing
    /// key and a missing reply give no line.
    public static func from(config: ConfigReply?) -> [ChartLimit] {
        guard let config else { return [] }
        return definitions.compactMap { d in
            guard let text = config.values[d.key],
                  ConfigSchema.validate(key: d.key, value: text) == nil,
                  let value = ConfigSchema.parseNumber(text), !(d.zeroIsOff && value == 0) else { return nil }
            return ChartLimit(key: d.key, label: d.label, value: value, axis: d.axis)
        }
    }

    public static func humidity(_ limits: [ChartLimit]) -> [ChartLimit] { limits.filter { $0.axis == .humidity } }
    public static func temperature(_ limits: [ChartLimit]) -> [ChartLimit] { limits.filter { $0.axis == .temperature } }
}
