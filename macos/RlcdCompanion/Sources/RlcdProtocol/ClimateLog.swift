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
    public enum Format: Equatable { case v2, v3, v4, v5 }

    public static let sensorNames = ["onboard", "s1", "s2", "s3", "s4", "s5"]

    public let format: Format
    public let rows: [ClimateRow]

    /// Sensors in the rows of a format: `climate_v5` adds the slots 4 and 5.
    private static func sensorCount(_ format: Format) -> Int { format == .v5 ? sensorNames.count : 4 }

    /// Noise columns of a format, after the sensor columns.
    private static func noiseColumns(_ format: Format) -> [String] {
        var names = ["noise_rms", "noise_db", "noise_band"]
        if format != .v2 { names.append("noise_events") }
        if format == .v4 || format == .v5 { names += ["noise_l10", "noise_l90"] }
        return names
    }

    private static func header(_ format: Format) -> String {
        var names = ["timestamp", "battery_pct", "battery_v", "usb", "onboard_temp_c", "onboard_rh"]
        for s in sensorNames.dropFirst().prefix(sensorCount(format) - 1) {
            names += ["\(s)_temp_c", "\(s)_rh", "\(s)_battery_pct"]
        }
        return (names + noiseColumns(format)).joined(separator: ",")
    }

    /// The header row selects the format (18, 19, 21 or 27 columns). An unknown header throws `missingLine`; a row
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
            let hasLevels = format == .v4 || format == .v5
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
