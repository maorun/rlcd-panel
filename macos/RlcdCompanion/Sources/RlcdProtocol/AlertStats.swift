import Foundation

/// Rises and active hours of one alert in one calendar month.
public struct AlertMonthStat: Equatable {
    public init(alert: Int, month: String, rises: Int, hours: Double, unknownStarts: Int) {
        self.alert = alert; self.month = month; self.rises = rises; self.hours = hours; self.unknownStarts = unknownStarts
    }
    public let alert: Int
    /// `YYYY-MM` in the Mac time zone (the panel time reads as local time, see `AlertTimeline.parse`).
    public let month: String
    /// Intervals with a known start that begin in this month.
    public let rises: Int
    /// Hours of the intervals with a known start that fall in this month.
    public let hours: Double
    /// Intervals with `startUnknown` that begin in this month; they add to neither `rises` nor `hours`.
    public let unknownStarts: Int

    public var name: String { EventText.alertNames[alert] ?? "ALERT \(alert)" }
}

public struct AlertStatsResult: Equatable {
    public let stats: [AlertMonthStat]
    /// Files with an events name that failed the name check, the size limit or the parse.
    public let skippedFiles: [String]
}

public enum AlertStats {
    /// Reads each `events_v1_YYYY.csv` of the folder (local files only) and builds the monthly statistics. A file
    /// with an `events_` prefix that has a bad name, is too large or has a bad line goes to `skippedFiles`; the
    /// other files still count. The good files join in name order, so an alert open at a year change stays one interval.
    public static func load(from folder: URL, now: Date = Date()) -> AlertStatsResult {
        let names = ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [])
            .filter { $0.hasPrefix("events_") }.sorted()
        var rows: [EventRow] = []
        var skipped: [String] = []
        for name in names {
            let url = folder.appendingPathComponent(name)
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? Int.max
            guard LogArchive.isValidName(name), size <= ReplyAssembler.maxFileBytes,
                  let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8),
                  let parsed = try? EventsReply.parse(file: text) else { skipped.append(name); continue }
            rows += parsed
        }
        return AlertStatsResult(stats: build(intervals: AlertTimeline.build(rows: rows, now: now)), skippedFiles: skipped)
    }

    /// Splits each interval at the month boundaries. The rise counts in the month of the start. Sorted by month,
    /// then alert.
    public static func build(intervals: [AlertInterval]) -> [AlertMonthStat] {
        let calendar = Calendar.current
        struct Key: Hashable { let alert: Int; let month: String }
        var acc: [Key: (rises: Int, hours: Double, unknown: Int)] = [:]
        for interval in intervals {
            let startKey = Key(alert: interval.alert, month: monthKey(interval.start, calendar))
            if interval.startUnknown {
                acc[startKey, default: (0, 0, 0)].unknown += 1
                continue
            }
            acc[startKey, default: (0, 0, 0)].rises += 1
            var cursor = interval.start
            while cursor < interval.end {
                guard let monthStart = calendar.dateInterval(of: .month, for: cursor),
                      monthStart.end > cursor else { break }
                let segmentEnd = min(interval.end, monthStart.end)
                acc[Key(alert: interval.alert, month: monthKey(cursor, calendar)), default: (0, 0, 0)].hours +=
                    segmentEnd.timeIntervalSince(cursor) / 3600
                cursor = segmentEnd
            }
        }
        return acc.map { AlertMonthStat(alert: $0.key.alert, month: $0.key.month, rises: $0.value.rises,
                                         hours: $0.value.hours, unknownStarts: $0.value.unknown) }
            .sorted { ($0.month, $0.alert) < ($1.month, $1.alert) }
    }

    private static func monthKey(_ date: Date, _ calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month], from: date)
        return String(format: "%04d-%02d", c.year ?? 0, c.month ?? 0)
    }
}
