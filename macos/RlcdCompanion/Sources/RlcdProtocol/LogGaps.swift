import Foundation

/// Finds missing hours in the rows of one climate log month, for example after an empty battery or a card fault.
public enum LogGaps {
    /// Default step limit: the panel writes one row each hour, so a step above 90 minutes lost at least one row.
    public static let defaultMinGapMinutes = 90

    /// One step between two neighbour rows that is longer than the limit.
    public struct Gap: Equatable {
        /// Time of the last row before the gap.
        public let start: Date
        /// Time of the first row after the gap.
        public let end: Date
        /// Hourly rows that the gap lost (at least 1).
        public let missingHours: Int
    }

    public struct Report: Equatable {
        public let gaps: [Gap]
        /// Rows with a readable timestamp.
        public let rowCount: Int
        public let missingHours: Int
        /// Rows divided by rows plus missing hours, 0 to 100. 0 for a month without rows.
        public let coveragePct: Double
    }

    /// The same timestamp as wall-clock time without a time zone, so a daylight saving change cannot move it.
    private static let wallParser: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    private struct Stamp {
        let real: Date
        let wall: Date
    }

    private static func stamp(_ row: ClimateRow) -> Stamp? {
        guard let real = BatteryForecast.parse(row.timestamp), let wall = wallParser.date(from: row.timestamp) else { return nil }
        return Stamp(real: real, wall: wall)
    }

    /// Sorts the rows by time first. A row with an unreadable timestamp is skipped. A step above `minGapMinutes`
    /// gives one gap; a step of exactly `minGapMinutes` gives none. A limit below 1 minute counts as 1 minute.
    /// The step is the smaller of the real and the wall-clock difference: the repeated hour of the October change
    /// (the log holds local time, so two rows can share one timestamp) gives no false gap, and the lost hour of the
    /// March change counts as 1 hour. A real gap across the October change counts one hour less. The days before the first row and after the last row are not counted.
    public static func find(rows: [ClimateRow], minGapMinutes: Int = defaultMinGapMinutes) -> [Gap] {
        report(rows: rows, minGapMinutes: minGapMinutes).gaps
    }

    public static func report(rows: [ClimateRow], minGapMinutes: Int = defaultMinGapMinutes) -> Report {
        let stamps = rows.compactMap(stamp).sorted { ($0.real, $0.wall) < ($1.real, $1.wall) }
        var gaps: [Gap] = []
        let limit = Double(max(minGapMinutes, 1)) * 60
        for (a, b) in zip(stamps, stamps.dropFirst()) {
            let step = min(b.real.timeIntervalSince(a.real), b.wall.timeIntervalSince(a.wall))
            guard step > limit else { continue }
            gaps.append(Gap(start: a.real, end: b.real, missingHours: max(1, Int((step / 3600).rounded()) - 1)))
        }
        let missing = gaps.reduce(0) { $0 + $1.missingHours }
        let coverage = stamps.isEmpty ? 0 : Double(stamps.count) / Double(stamps.count + missing) * 100
        return Report(gaps: gaps, rowCount: stamps.count, missingHours: missing, coveragePct: coverage)
    }
}
