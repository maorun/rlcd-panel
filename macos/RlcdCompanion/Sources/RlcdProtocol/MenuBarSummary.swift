import Foundation

/// Menu bar content built from the last telemetry frame only. It sends no console command.
public struct MenuBarSummary: Equatable {
    /// A frame older than this counts as stale.
    public static let staleSeconds: TimeInterval = 15 * 60
    /// A frame older than this (boundary included) shows `--` in the label. It exceeds the longest firmware wake interval (night, 1800 s), so a silent panel never looks current.
    public static let expiredSeconds: TimeInterval = 1900
    /// Suffix of the label for a stale frame.
    public static let staleMark = " ⏱"
    public static let normalSymbol = "thermometer.medium"
    public static let alertSymbol = "exclamationmark.triangle.fill"

    /// One line for each valid tile: `<name>: <temperature> °<unit>, <humidity> %`.
    public let lines: [String]
    /// Onboard temperature for the menu bar label in the `temp_unit` of the last `CONFIG`; `-- °C` or `-- °F` without a valid onboard tile.
    public let labelText: String
    public let alerts: AlertMask
    public let isStale: Bool

    public var hasAlert: Bool { alerts.rawValue != 0 }
    public var alertNames: [String] { alerts.names }
    /// Label text with the stale mark.
    public var labelWithMark: String { labelText + (isStale ? Self.staleMark : "") }
    public var symbolName: String { hasAlert ? Self.alertSymbol : Self.normalSymbol }

    /// `config` supplies the tile names and `temp_unit` of the last `CONFIG`; `nil` gives `Tile <N>` and Celsius.
    public static func make(frame: TelemetryFrame?, frameDate: Date?, config: ConfigReply?, now: Date) -> MenuBarSummary {
        let fahrenheit = config?.temperatureUnit == "F"
        let unit = fahrenheit ? "°F" : "°C"
        func tempText(_ c: Double) -> String {
            String(format: "%.1f %@", fahrenheit ? c * 9.0 / 5.0 + 32.0 : c, unit)
        }
        guard let frame else { return MenuBarSummary(lines: [], labelText: "-- \(unit)", alerts: [], isStale: true) }
        let stale = frameDate.map { now.timeIntervalSince($0) > staleSeconds } ?? true
        var lines: [String] = []
        for (i, tile) in frame.tiles.enumerated() {
            guard let t = tile.temperatureC else { continue }
            let name = config?.name(forSlot: i) ?? "Tile \(i + 1)"
            let temp = tempText(t)
            let rh = tile.humidityPct.map { String(format: ", %.1f %%", $0) } ?? ""
            lines.append("\(name): \(temp)\(rh)")
        }
        let expired = frameDate.map { now.timeIntervalSince($0) >= expiredSeconds } ?? true
        let label = expired ? "-- \(unit)" : (frame.tiles.first?.temperatureC.map(tempText) ?? "-- \(unit)")
        return MenuBarSummary(lines: lines, labelText: label, alerts: frame.alerts, isStale: stale)
    }
}
