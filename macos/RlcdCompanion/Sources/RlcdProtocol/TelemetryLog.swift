import Foundation

/// One stored telemetry frame. Temperatures are Celsius; a missing value is `nil`.
public struct TelemetryLogRow: Equatable, Codable {
    public struct Tile: Equatable, Codable {
        public let temperatureC: Double?
        public let humidityPct: Double?
    }

    public let time: Date
    public let sequence: UInt8
    public let cellPercent: Int?
    public let alerts: UInt8
    public let tiles: [Tile]
}

/// Local telemetry history: 7 days or 20,000 rows, oldest out first.
public struct TelemetryLog: Equatable, Codable {
    public static let maxRows = 20_000
    public static let maxAge: TimeInterval = 7 * 86_400

    public private(set) var rows: [TelemetryLogRow] = []

    public init() {}

    /// Adds a frame. A frame with the sequence of the last row is a repeated advertisement and gives no row.
    @discardableResult
    public mutating func add(_ frame: TelemetryFrame, at time: Date) -> Bool {
        guard rows.last?.sequence != frame.sequence else { return false }
        rows.append(TelemetryLogRow(
            time: time, sequence: frame.sequence, cellPercent: frame.cellPercent, alerts: frame.alerts.rawValue,
            tiles: frame.tiles.map { .init(temperatureC: $0.temperatureC, humidityPct: $0.humidityPct) }))
        prune(now: time)
        return true
    }

    public mutating func prune(now: Date) {
        let limit = now.addingTimeInterval(-Self.maxAge)
        rows.removeAll { $0.time < limit }
        rows.removeFirst(max(0, rows.count - Self.maxRows))
    }

    /// Every `step`-th row, newest row kept, for charts.
    public func downsampled(maxRows limit: Int) -> [TelemetryLogRow] {
        guard rows.count > limit, limit > 0 else { return rows }
        let step = (rows.count + limit - 1) / limit
        return rows.indices.filter { ($0 - (rows.count - 1)) % step == 0 }.map { rows[$0] }
    }

    /// A missing or corrupt file gives an empty log. Rows with a wrong tile count and rows beyond the limits drop out.
    public static func load(from url: URL, now: Date = Date()) -> TelemetryLog {
        guard let data = try? Data(contentsOf: url),
              var log = try? JSONDecoder().decode(TelemetryLog.self, from: data) else { return TelemetryLog() }
        log.rows.removeAll { $0.tiles.count != TelemetryFrame.tileCount }
        log.prune(now: now)
        return log
    }

    /// Writes through a temporary file and a rename.
    public func save(to url: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = URL(fileURLWithPath: url.path + ".tmp")
        do {
            try JSONEncoder().encode(self).write(to: tmp)
            if fm.fileExists(atPath: url.path) {
                _ = try fm.replaceItemAt(url, withItemAt: tmp)
            } else {
                try fm.moveItem(at: tmp, to: url)
            }
        } catch {
            try? fm.removeItem(at: tmp)
            throw error
        }
    }

    public static func defaultURL() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("RlcdCompanion", isDirectory: true)
            .appendingPathComponent("telemetry.json")
    }
}

/// Holds the log and writes the file at most once a minute.
public final class TelemetryLogStore {
    public static let writeInterval: TimeInterval = 60

    public private(set) var log: TelemetryLog
    private let url: URL
    private let now: () -> Date
    private var lastWrite: Date?

    public init(url: URL = TelemetryLog.defaultURL(), now: @escaping () -> Date = Date.init) {
        self.url = url
        self.now = now
        log = TelemetryLog.load(from: url, now: now())
    }

    /// Returns `true` when the frame added a row.
    @discardableResult
    public func record(_ frame: TelemetryFrame) -> Bool {
        let t = now()
        guard log.add(frame, at: t) else { return false }
        if let last = lastWrite, t.timeIntervalSince(last) < Self.writeInterval { return true }
        // A failed write also starts the interval, so a broken disk costs one encode a minute.
        lastWrite = t
        try? log.save(to: url)
        return true
    }

    /// Writes pending rows, for example at quit.
    public func flush() {
        if (try? log.save(to: url)) != nil { lastWrite = now() }
    }
}
