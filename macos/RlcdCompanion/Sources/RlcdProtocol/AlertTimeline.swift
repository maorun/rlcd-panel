import Foundation

/// One alert interval from the event ring: a rise paired with the next fall of the same alert bit.
public struct AlertInterval: Equatable {
    public init(alert: Int, start: Date, end: Date, isOpen: Bool, startUnknown: Bool) {
        self.alert = alert; self.start = start; self.end = end; self.isOpen = isOpen; self.startUnknown = startUnknown
    }
    /// Alert bit mask as `EventText` reads the detail (1, 2, 4, ... 128).
    public let alert: Int
    public let start: Date
    /// The fall time, or the injected `now` for an open interval.
    public let end: Date
    public let isOpen: Bool
    /// True when the start is a guess: a fall without a rise, or an interval that spans an `EVENTS_DROPPED` row.
    public let startUnknown: Bool

    public var name: String { EventText.alertNames[alert] ?? "ALERT \(alert)" }
}

public enum AlertTimeline {
    /// Panel local time of an event row (`YYYY-MM-DD hh:mm:ss`); nil for an empty or bad time. The panel RTC holds
    /// local time without a zone, so the Mac time zone reads it. A panel in another zone shifts the bars.
    static func parse(time: String) -> Date? { parse(time: time, with: makeFormatter()) }

    private static func makeFormatter() -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter
    }

    private static func parse(time: String, with formatter: DateFormatter) -> Date? { formatter.date(from: time) }

    /// Pairs each `ALERT_RISE` with the next `ALERT_FALL` of the same alert bit. An alert without a fall stays open
    /// until `now`. A fall without a rise starts at the first row with a valid time and has `startUnknown`; an
    /// `EVENTS_DROPPED` row sets `startUnknown` on each interval that is open at that row. A fall with a bad time
    /// closes its alert without an interval. Other codes and rows
    /// without a valid time are ignored. A second rise of an open alert keeps the first start.
    public static func build(rows: [EventRow], now: Date) -> [AlertInterval] {
        let formatter = makeFormatter()
        let times = rows.map { parse(time: $0.time, with: formatter) }
        // The `EVENTS_DROPPED` row carries the current RTC time, so it cannot start an interval.
        guard let firstIndex = rows.indices.first(where: { times[$0] != nil && rows[$0].event != "EVENTS_DROPPED" }),
              let firstTime = times[firstIndex] else { return [] }
        var open: [Int: (start: Date, unknown: Bool)] = [:]
        var done: [AlertInterval] = []
        for (index, row) in rows.enumerated() {
            switch row.event {
            case "EVENTS_DROPPED":
                for alert in open.keys { open[alert]?.unknown = true }
            case "ALERT_RISE":
                guard let time = times[index], open[row.detail] == nil else { continue }
                open[row.detail] = (time, false)
            case "ALERT_FALL":
                let start = open.removeValue(forKey: row.detail)
                // A fall without a valid time ends the alert, but the interval has no drawable end.
                guard let time = times[index] else { continue }
                done.append(AlertInterval(
                    alert: row.detail, start: start?.start ?? firstTime, end: max(time, start?.start ?? firstTime),
                    isOpen: false, startUnknown: start?.unknown ?? true))
            default:
                continue
            }
        }
        for (alert, entry) in open {
            done.append(AlertInterval(alert: alert, start: entry.start, end: max(now, entry.start), isOpen: true, startUnknown: entry.unknown))
        }
        return done.sorted { ($0.start, $0.alert) < ($1.start, $1.alert) }
    }
}
