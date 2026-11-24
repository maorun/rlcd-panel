import Foundation

/// Date forecast for the battery change of one SwitchBot slot, from the hourly `ClimateLog` rows.
public enum BatteryForecast {
    /// Only rows of the last 60 days before the newest row count.
    public static let windowDays = 60
    /// A rise of this many percent between two rows is a new battery.
    public static let riseResetPct = 10
    /// The remaining rows must span at least this many days.
    public static let minSpanDays = 7
    /// The battery must fall at least this many percent over the remaining rows.
    public static let minDropPct = 3
    /// Used when no `CONFIG` reply gives `sensor_battery_limit_pct`.
    public static let defaultLimitPct = 10.0

    /// `sensor_battery_limit_pct` of the last `CONFIG` reply, else 10 %. The value 0 means `BAT LOW` is off, so it
    /// also gives 10 %.
    public static func limitPct(config: ConfigReply?) -> Double {
        guard let v = config?.values["sensor_battery_limit_pct"].flatMap({ Double($0) }), v > 0 else { return defaultLimitPct }
        return v
    }

    /// The date when the least-squares line over the `batteryPct` of slot `sensor` (1 to 5) reaches `limitPct`.
    /// Uses the rows of the last 60 days after the last rise of 10 % or more. `nil` for the onboard sensor, for
    /// less than 7 days of data, for a drop below 3 %, or for a line that does not fall. A line that already
    /// reached the limit gives the time of the newest row. The window counts back from the newest row, not from today.
    public static func estimate(rows: [ClimateRow], sensor: Int, limitPct: Double) -> Date? {
        guard (1..<ClimateLog.sensorNames.count).contains(sensor), limitPct.isFinite else { return nil }
        var samples: [(time: Double, pct: Double)] = []
        for row in rows {
            guard sensor < row.sensors.count, let pct = row.sensors[sensor].batteryPct,
                  let date = parse(row.timestamp) else { continue }
            samples.append((date.timeIntervalSince1970, Double(pct)))
        }
        samples.sort { $0.time < $1.time }
        guard let newest = samples.last?.time else { return nil }
        samples = samples.filter { $0.time >= newest - Double(windowDays) * 86_400 }
        if let reset = samples.indices.dropFirst().last(where: { samples[$0].pct - samples[$0 - 1].pct >= Double(riseResetPct) }) {
            samples = Array(samples[reset...])
        }
        guard let first = samples.first, let last = samples.last, samples.count >= 2,
              last.time - first.time >= Double(minSpanDays) * 86_400,
              first.pct - last.pct >= Double(minDropPct) else { return nil }
        // Least squares on days since the first row.
        let xs = samples.map { ($0.time - first.time) / 86_400 }
        let ys = samples.map(\.pct)
        let n = Double(samples.count)
        let meanX = xs.reduce(0, +) / n
        let meanY = ys.reduce(0, +) / n
        var sxx = 0.0, sxy = 0.0
        for i in xs.indices {
            sxx += (xs[i] - meanX) * (xs[i] - meanX)
            sxy += (xs[i] - meanX) * (ys[i] - meanY)
        }
        guard sxx > 0 else { return nil }
        let slope = sxy / sxx
        guard slope < 0 else { return nil }
        let day = meanX + (limitPct - meanY) / slope
        let target = first.time + day * 86_400
        guard target.isFinite else { return nil }
        return Date(timeIntervalSince1970: max(target, newest))
    }

    /// The panel writes local time in Europe/Berlin.
    private static let parser: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = PanelClock.timeZone
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    /// Local panel time (Europe/Berlin) of a log timestamp `YYYY-MM-DD HH:MM:SS`; shared with `PanelBatterySeries`.
    static func parse(_ timestamp: String) -> Date? { parser.date(from: timestamp) }
}
