import Foundation

/// Temperature, humidity and battery of one sensor in one hourly row. An empty or stale sensor gives `nil` fields.
public struct ClimateSensorReading: Equatable {
    public let tempC: Double?
    public let rh: Double?
    /// The onboard sensor has no battery column, so its value is always `nil`.
    public let batteryPct: Int?
}

/// One row of `climate_v2_YYYYMM.csv` to `climate_v5_YYYYMM.csv`. Fields that a format lacks are `nil`.
public struct ClimateRow: Equatable {
    /// Local time as `YYYY-MM-DD HH:MM:SS`.
    public let timestamp: String
    public let batteryPct: Int?
    public let batteryV: Double?
    public let usb: Bool?
    /// Onboard sensor first, then slots 1 to 3 (slots 1 to 5 in `climate_v5`).
    public let sensors: [ClimateSensorReading]
    public let noiseRms: Int?
    public let noiseDb: Int?
    public let noiseBand: String?
    public let noiseEvents: Int?
    public let noiseL10: Int?
    public let noiseL90: Int?
}

/// Rows of every readable climate file of one month, oldest hour first.
public struct ClimateFolder: Equatable {
    public let rows: [ClimateRow]
    public let failedFiles: [String]

    public init(rows: [ClimateRow], failedFiles: [String]) {
        self.rows = rows
        self.failedFiles = failedFiles
    }
}

public struct ClimateLog: Equatable {
    public enum Format: Equatable { case v2, v3, v4, v5, v6 }

    public static let sensorNames = ["onboard", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"]

    public let format: Format
    public let rows: [ClimateRow]

    /// Sensors in the rows of a format: `climate_v6` has 11 sensors, `climate_v5` has 6 sensors.
    private static func sensorCount(_ format: Format) -> Int {
        switch format {
        case .v6: return sensorNames.count
        case .v5: return 6
        default: return 4
        }
    }

    /// Noise columns of a format, after the sensor columns.
    private static func noiseColumns(_ format: Format) -> [String] {
        var names = ["noise_rms", "noise_db", "noise_band"]
        if format != .v2 { names.append("noise_events") }
        if format == .v4 || format == .v5 || format == .v6 { names += ["noise_l10", "noise_l90"] }
        return names
    }

    private static func header(_ format: Format) -> String {
        var names = ["timestamp", "battery_pct", "battery_v", "usb", "onboard_temp_c", "onboard_rh"]
        for s in sensorNames.dropFirst().prefix(sensorCount(format) - 1) {
            names += ["\(s)_temp_c", "\(s)_rh", "\(s)_battery_pct"]
        }
        return (names + noiseColumns(format)).joined(separator: ",")
    }

    /// The header row selects the format (18, 19, 21, 27 or 42 columns). An unknown header throws `missingLine`; a row
    /// with a wrong column count or a non-numeric field throws `badLine`. An empty field gives `nil`.
    public static func parse(text: String) throws -> ClimateLog {
        let lines = text.split(whereSeparator: \.isNewline).map(String.init).filter { !$0.isEmpty }
        guard let first = lines.first else { throw ParseError.missingLine("header") }
        let head = (first.hasPrefix("\u{FEFF}") ? String(first.dropFirst()) : first)
            .trimmingCharacters(in: .whitespaces)
        let format: Format
        switch head {
        case header(.v2): format = .v2
        case header(.v3): format = .v3
        case header(.v4): format = .v4
        case header(.v5): format = .v5
        case header(.v6): format = .v6
        default: throw ParseError.missingLine("header")
        }
        let sensorTotal = sensorCount(format)
        let noiseStart = 6 + (sensorTotal - 1) * 3
        let columns = noiseStart + noiseColumns(format).count
        let rows = try lines.dropFirst().map { line -> ClimateRow in
            let f = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard f.count == columns, isTimestamp(f[0]) else { throw ParseError.badLine(line) }
            func decimal(_ i: Int) throws -> Double? {
                if f[i].isEmpty { return nil }
                guard let v = Double(f[i]), v.isFinite else { throw ParseError.badLine(line) }
                return v
            }
            func integer(_ i: Int) throws -> Int? {
                if f[i].isEmpty { return nil }
                guard let v = Int(f[i]) else { throw ParseError.badLine(line) }
                return v
            }
            func flag(_ i: Int) throws -> Bool? {
                switch f[i] {
                case "": return nil
                case "0": return false
                case "1": return true
                default: throw ParseError.badLine(line)
                }
            }
            var sensors = [ClimateSensorReading(tempC: try decimal(4), rh: try decimal(5), batteryPct: nil)]
            for s in 1..<sensorTotal {
                let b = 6 + (s - 1) * 3
                sensors.append(ClimateSensorReading(tempC: try decimal(b), rh: try decimal(b + 1), batteryPct: try integer(b + 2)))
            }
            let n = noiseStart
            let hasEvents = format != .v2
            let hasLevels = format == .v4 || format == .v5 || format == .v6
            return ClimateRow(
                timestamp: f[0], batteryPct: try integer(1), batteryV: try decimal(2), usb: try flag(3), sensors: sensors,
                noiseRms: try integer(n), noiseDb: try integer(n + 1), noiseBand: f[n + 2].isEmpty ? nil : f[n + 2],
                noiseEvents: hasEvents ? try integer(n + 3) : nil,
                noiseL10: hasLevels ? try integer(n + 4) : nil, noiseL90: hasLevels ? try integer(n + 5) : nil)
        }
        return ClimateLog(format: format, rows: rows)
    }

    /// True for `climate_v2_YYYYMM.csv` to `climate_v5_YYYYMM.csv`; the check of `LogArchive` decides the name.
    public static func isClimateFileName(_ name: String) -> Bool {
        LogArchive.isValidName(name) && name.hasPrefix("climate_")
    }

    /// The month `YYYYMM` of a climate file name, or `nil` for another name.
    public static func month(of name: String) -> String? {
        guard isClimateFileName(name) else { return nil }
        return String(name.dropLast(4).suffix(6))
    }

    /// The months with a climate file in the folder, oldest first (local files only).
    public static func availableMonths(in folder: URL) -> [String] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return Array(Set(names.compactMap(month(of:)))).sorted()
    }

    /// The format number of a climate file name (`climate_v5_202611.csv` gives 5).
    private static func version(of name: String) -> Int {
        Int(name.split(separator: "_").dropFirst().first?.dropFirst() ?? "") ?? 0
    }

    private static func isTimestamp(_ text: String) -> Bool {
        let parts = text.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        let d = parts[0].split(separator: "-", omittingEmptySubsequences: false)
        let t = parts[1].split(separator: ":", omittingEmptySubsequences: false)
        guard d.count == 3, d[0].count == 4, d[1].count == 2, d[2].count == 2,
              t.count == 3, t.allSatisfy({ $0.count == 2 }),
              let y = Int(d[0]), let mo = Int(d[1]), let day = Int(d[2]),
              let h = Int(t[0]), let mi = Int(t[1]), let s = Int(t[2]) else { return false }
        return (2000...2099).contains(y) && (1...12).contains(mo) && (1...31).contains(day)
            && (0...23).contains(h) && (0...59).contains(mi) && (0...60).contains(s)
    }

    /// The month `YYYYMM` that lies `by` months before `month` (`by` 1 gives the previous month), or `nil` for a bad month.
    public static func earlierMonth(_ month: String, by: Int) -> String? {
        guard month.count == 6, let y = Int(month.prefix(4)), let m = Int(month.suffix(2)), (1...12).contains(m) else { return nil }
        let index = y * 12 + (m - 1) - by
        guard index >= 0 else { return nil }
        return String(format: "%04d%02d", index / 12, index % 12 + 1)
    }

    /// Reads the climate files of one month (`YYYYMM`) from the folder (local files only). A file that fails to
    /// parse is listed in `failedFiles`. When two files hold the same timestamp, the newer format wins.
    public static func load(from folder: URL, month: String) -> ClimateFolder {
        let names = ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [])
            .filter { Self.month(of: $0) == month }
            .sorted { version(of: $0) < version(of: $1) } // an older format first, so the newer one wins
        var byTime: [String: ClimateRow] = [:]
        var failed: [String] = []
        for name in names {
            let url = folder.appendingPathComponent(name)
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? Int.max
            guard size <= ReplyAssembler.maxFileBytes, let data = try? Data(contentsOf: url),
                  let text = String(data: data, encoding: .utf8),
                  let log = try? parse(text: text) else { failed.append(name); continue }
            for row in log.rows { byTime[row.timestamp] = row }
        }
        return ClimateFolder(rows: byTime.values.sorted { $0.timestamp < $1.timestamp }, failedFiles: failed)
    }
}

/// Panel cell voltage of the rows of one month and the hours on USB power. Built from the `battery_v` and `usb`
/// columns of `climate_v2` to `climate_v6` rows; sends no console command.
public struct PanelBatterySeries: Equatable {
    public struct Point: Equatable {
        public let timestamp: String
        public let volts: Double
    }

    /// Consecutive rows with `usb` 1. `start` and `end` are the first and last row timestamp of the run, so a run
    /// of one row has equal values. A row with `usb` 0 or an empty `usb` field ends a run, and so does a time
    /// step above `maxStepSeconds` between two rows.
    public struct UsbRange: Equatable {
        public let start: String
        public let end: String

        /// Start of the first row hour (local time of the panel).
        public var startDate: Date? { PanelBatterySeries.date(start) }
        /// End of the last row hour: the log has one row each hour, so the last USB row covers the hour after it.
        public var endDate: Date? { PanelBatterySeries.date(end)?.addingTimeInterval(PanelBatterySeries.hourSeconds) }
    }

    public static let hourSeconds: TimeInterval = 3600
    /// The largest step between two rows of one run: 90 minutes, like the log gap rule of `ClimateHistory::restore`.
    public static let maxStepSeconds: TimeInterval = 5400

    /// The same timestamp read as wall-clock time in UTC, to measure a step without a DST jump.
    private static let wallParser: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    /// Local panel time (Europe/Berlin) of a row timestamp. The chart view uses it for the voltage line too, so the
    /// line and the USB band share one time zone.
    public static func date(_ timestamp: String) -> Date? { BatteryForecast.parse(timestamp) }

    /// Seconds between two row timestamps: the smaller of the real step and the wall-clock step, so the hour that
    /// the DST change skips or repeats does not count as a gap. `nil` when a timestamp does not parse.
    static func step(from a: String, to b: String) -> TimeInterval? {
        guard let ra = date(a), let rb = date(b),
              let wa = wallParser.date(from: a), let wb = wallParser.date(from: b) else { return nil }
        return min(rb.timeIntervalSince(ra), wb.timeIntervalSince(wa))
    }

    /// Rows with a voltage, in row order. An empty field gives no point.
    public let points: [Point]
    public let usbRanges: [UsbRange]

    /// `true` when no row has a voltage; the view then shows `No battery data`.
    public var isEmpty: Bool { points.isEmpty }

    public init(rows: [ClimateRow]) {
        points = rows.compactMap { row in row.batteryV.map { Point(timestamp: row.timestamp, volts: $0) } }
        var ranges: [UsbRange] = []
        var open: (start: String, end: String)?
        var previous: String?
        for row in rows {
            if let run = open, row.usb == true, let a = previous {
                // A log gap, or a timestamp that does not parse, closes the run.
                if let step = Self.step(from: a, to: row.timestamp), step <= Self.maxStepSeconds {} else {
                    ranges.append(UsbRange(start: run.start, end: run.end))
                    open = nil
                }
            }
            if row.usb == true {
                open = (open?.start ?? row.timestamp, row.timestamp)
            } else if let run = open {
                ranges.append(UsbRange(start: run.start, end: run.end))
                open = nil
            }
            previous = row.timestamp
        }
        if let run = open { ranges.append(UsbRange(start: run.start, end: run.end)) }
        usbRanges = ranges
    }
}
