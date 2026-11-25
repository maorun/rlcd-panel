import Foundation

/// Minimum, maximum and mean of one sensor for one day. A sensor without samples gives `nil` fields.
public struct DailySensorStats: Equatable {
    public let tempMinC: Double?
    public let tempMaxC: Double?
    public let tempMeanC: Double?
    public let rhMin: Double?
    public let rhMax: Double?
    public let rhMean: Double?
}

/// One row of `daily_v1_YYYY.csv` to `daily_v6_YYYY.csv`. The noise peak fields exist only in
/// `daily_v2` to `daily_v6`; the mold hours only in `daily_v4` to `daily_v6`; the dry hours only in `daily_v6`.
public struct DailyRow: Equatable {
    public let date: String
    /// Onboard sensor first, then slots 1 to 3 (slots 1 to 5 in `daily_v3` and `daily_v4`).
    public let sensors: [DailySensorStats]
    public let noiseMinDb: Int?
    public let noiseMaxDb: Int?
    public let noisePeakDb: Int?
    public let noisePeakHour: Int?
    public let noiseEventsSum: Int?
    /// Hours above `mold_alert_limit_rh` for each sensor (`daily_v4` only; empty for `daily_v1` to `daily_v3`).
    /// `nil` for an empty or stale sensor.
    public let moldHours: [Int?]
    /// Hours at or below `dry_alert_limit_rh` for each sensor (`daily_v6` only; empty for older files).
    /// `nil` for an empty field (empty or stale sensor, or limit 0).
    public let dryHours: [Int?]

    public init(date: String, sensors: [DailySensorStats], noiseMinDb: Int?, noiseMaxDb: Int?, noisePeakDb: Int?,
                noisePeakHour: Int?, noiseEventsSum: Int?, moldHours: [Int?], dryHours: [Int?] = []) {
        self.date = date
        self.sensors = sensors
        self.noiseMinDb = noiseMinDb
        self.noiseMaxDb = noiseMaxDb
        self.noisePeakDb = noisePeakDb
        self.noisePeakHour = noisePeakHour
        self.noiseEventsSum = noiseEventsSum
        self.moldHours = moldHours
        self.dryHours = dryHours
    }
}

/// Rows of every readable daily file of a folder, oldest day first.
public struct DailyFolder: Equatable {
    public let rows: [DailyRow]
    public let failedFiles: [String]

    public init(rows: [DailyRow], failedFiles: [String]) {
        self.rows = rows
        self.failedFiles = failedFiles
    }
}

public struct DailyLog: Equatable {
    public enum Format: Equatable { case v1, v2, v3, v4, v5, v6 }

    public static let sensorNames = ["onboard", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"]
    private static let statColumns = ["temp_min_c", "temp_max_c", "temp_mean_c", "rh_min", "rh_max", "rh_mean"]

    public let format: Format
    public let rows: [DailyRow]

    /// Sensors in the rows of a format: `daily_v5` and `daily_v6` have 11 sensors, `daily_v3` and `daily_v4` have 6 sensors.
    private static func sensorCount(_ format: Format) -> Int {
        switch format {
        case .v5, .v6: return sensorNames.count
        case .v3, .v4: return 6
        default: return 4
        }
    }

    private static func header(_ format: Format) -> String {
        var names = ["date"] + sensorNames.prefix(sensorCount(format)).flatMap { s in statColumns.map { "\(s)_\($0)" } }
        names += ["noise_min_db", "noise_max_db"]
        if format != .v1 { names += ["noise_peak_db", "noise_peak_hour", "noise_events_sum"] }
        if format == .v4 { names += (0..<6).map { "mold_h_\($0)" } }
        if format == .v5 || format == .v6 { names += (0..<sensorNames.count).map { "mold_h_\($0)" } }
        if format == .v6 { names += (0..<sensorNames.count).map { "dry_h_\($0)" } }
        return names.joined(separator: ",")
    }

    /// The header row selects the format (27, 30, 42, 48, 83 or 94 columns). An unknown header throws `missingLine`; a row with a
    /// wrong column count or a non-numeric field throws `badLine`. An empty field gives `nil`.
    public static func parse(text: String) throws -> DailyLog {
        let lines = text.split(whereSeparator: \.isNewline).map(String.init).filter { !$0.isEmpty }
        guard let first = lines.first else { throw ParseError.missingLine("header") }
        let head = (first.hasPrefix("\u{FEFF}") ? String(first.dropFirst()) : first)
            .trimmingCharacters(in: .whitespaces)
        let format: Format
        switch head {
        case header(.v1): format = .v1
        case header(.v2): format = .v2
        case header(.v3): format = .v3
        case header(.v4): format = .v4
        case header(.v5): format = .v5
        case header(.v6): format = .v6
        default: throw ParseError.missingLine("header")
        }
        let sensorTotal = sensorCount(format)
        let moldCount = (format == .v4 ? 6 : ((format == .v5 || format == .v6) ? sensorNames.count : 0))
        let dryCount = format == .v6 ? sensorNames.count : 0
        let columns = 1 + sensorTotal * statColumns.count + (format == .v1 ? 2 : 5) + moldCount + dryCount
        let rows = try lines.dropFirst().map { line -> DailyRow in
            let f = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard f.count == columns, isDate(f[0]) else { throw ParseError.badLine(line) }
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
            let sensors = try (0..<sensorTotal).map { s -> DailySensorStats in
                let b = 1 + s * statColumns.count
                return DailySensorStats(
                    tempMinC: try decimal(b), tempMaxC: try decimal(b + 1), tempMeanC: try decimal(b + 2),
                    rhMin: try decimal(b + 3), rhMax: try decimal(b + 4), rhMean: try decimal(b + 5))
            }
            let n = 1 + sensorTotal * statColumns.count
            return DailyRow(
                date: f[0], sensors: sensors, noiseMinDb: try integer(n), noiseMaxDb: try integer(n + 1),
                noisePeakDb: format != .v1 ? try integer(n + 2) : nil,
                noisePeakHour: format != .v1 ? try integer(n + 3) : nil,
                noiseEventsSum: format != .v1 ? try integer(n + 4) : nil,
                moldHours: moldCount > 0 ? try (0..<moldCount).map { try integer(n + 5 + $0) } : [],
                dryHours: dryCount > 0 ? try (0..<dryCount).map { try integer(n + 5 + moldCount + $0) } : [])
        }
        return DailyLog(format: format, rows: rows)
    }

    /// True for `daily_v1_YYYY.csv` to `daily_v6_YYYY.csv`; the check of `LogArchive` decides the name.
    public static func isDailyFileName(_ name: String) -> Bool {
        LogArchive.isValidName(name) && name.hasPrefix("daily_")
    }

    private static func isDate(_ text: String) -> Bool {
        let p = text.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, p[0].count == 4, p[1].count == 2, p[2].count == 2,
              let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]) else { return false }
        return (2000...2099).contains(y) && (1...12).contains(m) && (1...31).contains(d)
    }

    /// Reads every daily file of the folder (local files only). A file that fails to parse is listed in
    /// `failedFiles`. When two files hold the same date, the newer format wins.
    public static func load(from folder: URL) -> DailyFolder {
        let names = ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [])
            .filter(isDailyFileName).sorted()
        var byDate: [String: DailyRow] = [:]
        var failed: [String] = []
        for name in names {
            let url = folder.appendingPathComponent(name)
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? Int.max
            guard size <= ReplyAssembler.maxFileBytes, let data = try? Data(contentsOf: url),
                  let text = String(data: data, encoding: .utf8),
                  let log = try? parse(text: text) else { failed.append(name); continue }
            for row in log.rows { byDate[row.date] = row } // a newer version sorts after an older one for the same year
        }
        return DailyFolder(rows: byDate.values.sorted { $0.date < $1.date }, failedFiles: failed)
    }
}
