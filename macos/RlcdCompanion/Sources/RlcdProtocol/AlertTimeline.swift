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

/// One badge interval from the event ring: a set row paired with the next clear row of the same tile.
public struct BadgeInterval: Equatable {
    public init(event: String, tile: Int, start: Date, end: Date, isOpen: Bool, startUnknown: Bool) {
        self.event = event; self.tile = tile; self.start = start; self.end = end
        self.isOpen = isOpen; self.startUnknown = startUnknown
    }
    /// Event code: `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` or `SENSOR_DRIFT`.
    public let event: String
    /// Tile 0 (onboard) to 10 (slot 10), the low byte of the detail.
    public let tile: Int
    public let start: Date
    /// The clear time, or the injected `now` for an open interval.
    public let end: Date
    public let isOpen: Bool
    /// True when the start is a guess: a clear row without a set row, or an interval that spans an `EVENTS_DROPPED` row.
    public let startUnknown: Bool

    /// Badge letter of the panel tile.
    public var badge: String { AlertTimeline.badgeLetters[event] ?? "?" }
    /// Room name of the tile: the `DeviceConfig` name, else `Onboard` or `Slot <N>`.
    public func room(config: ConfigReply?) -> String { EventText.roomName(tile: tile, config: config) }
}

public enum AlertTimeline {
    /// Events that set and clear a tile badge, with the panel badge letter.
    static let badgeLetters = ["WINDOW_OPEN": "W", "WALL_DAMP": "C", "DRY_AIR": "D", "SENSOR_DRIFT": "S"]

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

    /// One interval of the shared pairing: a set row paired with the next clear row of the same key.
    private struct Pair<Key: Hashable> {
        let key: Key, start: Date, end: Date, isOpen: Bool, startUnknown: Bool
    }

    /// Pairs set and clear rows. `classify` returns the key and whether the row sets; `nil` ignores the row. An open
    /// key ends at `now`. A clear row without a set row starts at the first row with a valid time and is unknown; an
    /// `EVENTS_DROPPED` row marks each open interval unknown. A clear row with a bad time closes its key without an
    /// interval. A second set row of an open key keeps the first start. The `EVENTS_DROPPED` row carries the current
    /// RTC time, so it cannot start an interval.
    private static func pair<Key: Hashable>(rows: [EventRow], now: Date, classify: (EventRow) -> (Key, Bool)?) -> [Pair<Key>] {
        let formatter = makeFormatter()
        let times = rows.map { parse(time: $0.time, with: formatter) }
        guard let firstIndex = rows.indices.first(where: { times[$0] != nil && rows[$0].event != "EVENTS_DROPPED" }),
              let firstTime = times[firstIndex] else { return [] }
        var open: [Key: (start: Date, unknown: Bool)] = [:]
        var done: [Pair<Key>] = []
        for (index, row) in rows.enumerated() {
            if row.event == "EVENTS_DROPPED" {
                for key in open.keys { open[key]?.unknown = true }
                continue
            }
            guard let (key, isSet) = classify(row) else { continue }
            if isSet {
                guard let time = times[index], open[key] == nil else { continue }
                open[key] = (time, false)
            } else {
                let start = open.removeValue(forKey: key)
                // A clear row without a valid time ends the interval, but it has no drawable end.
                guard let time = times[index] else { continue }
                done.append(Pair(key: key, start: start?.start ?? firstTime, end: max(time, start?.start ?? firstTime),
                                 isOpen: false, startUnknown: start?.unknown ?? true))
            }
        }
        for (key, entry) in open {
            done.append(Pair(key: key, start: entry.start, end: max(now, entry.start), isOpen: true, startUnknown: entry.unknown))
        }
        return done
    }

    /// Pairs each `ALERT_RISE` with the next `ALERT_FALL` of the same alert bit (see `pair`). Other codes and rows
    /// without a valid time are ignored.
    public static func build(rows: [EventRow], now: Date) -> [AlertInterval] {
        pair(rows: rows, now: now) { row -> (Int, Bool)? in
            switch row.event {
            case "ALERT_RISE": return (row.detail, true)
            case "ALERT_FALL": return (row.detail, false)
            default: return nil
            }
        }
        .map { AlertInterval(alert: $0.key, start: $0.start, end: $0.end, isOpen: $0.isOpen, startUnknown: $0.startUnknown) }
        .sorted { ($0.start, $0.alert) < ($1.start, $1.alert) }
    }

    /// Pairs each set row of `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` and `SENSOR_DRIFT` with the next clear row of the
    /// same badge and tile. The detail low byte is the tile (0 to 10), the high byte the state (1 set, 0 clear). A
    /// badge without a clear row stays open until `now`. A clear row without a set row starts at the first row with
    /// a valid time and has `startUnknown`; an `EVENTS_DROPPED` row sets `startUnknown` on each interval that is open
    /// at that row. A clear row with a bad time closes its badge without an interval. Rows with an invalid detail,
    /// other codes and set rows without a valid time are ignored. A second set row of an open badge keeps the first start.
    public static func badges(rows: [EventRow], now: Date) -> [BadgeInterval] {
        struct Key: Hashable { let event: String; let tile: Int }
        return pair(rows: rows, now: now) { row -> (Key, Bool)? in
            guard badgeLetters[row.event] != nil, let (tile, state) = EventText.tileState(row.detail) else { return nil }
            return (Key(event: row.event, tile: tile), state == 1)
        }
        .map { BadgeInterval(event: $0.key.event, tile: $0.key.tile, start: $0.start, end: $0.end,
                             isOpen: $0.isOpen, startUnknown: $0.startUnknown) }
        .sorted { ($0.start, $0.event, $0.tile) < ($1.start, $1.event, $1.tile) }
    }
}

public enum BadgeIntervalText {
    private static let shortFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter
    }()

    /// `1 h 05 min`, `42 min` or `< 1 min`; a negative span counts as zero.
    public static func duration(_ seconds: TimeInterval) -> String {
        let minutes = max(0, Int(seconds / 60))
        if minutes < 1 { return "< 1 min" }
        if minutes < 60 { return "\(minutes) min" }
        let rest = minutes % 60
        return "\(minutes / 60) h \(rest < 10 ? "0" : "")\(rest) min"
    }

    /// One line: badge letter, room, start (`~` for an unknown start), end (`now` for an open interval) and duration.
    public static func line(_ interval: BadgeInterval, config: ConfigReply?) -> String {
        shortFormatter.timeZone = .current
        let start = (interval.startUnknown ? "~" : "") + shortFormatter.string(from: interval.start)
        let end = interval.isOpen ? "now" : shortFormatter.string(from: interval.end)
        return "\(interval.badge)  \(interval.room(config: config))  \(start) - \(end)  \(duration(interval.end.timeIntervalSince(interval.start)))"
    }
}
