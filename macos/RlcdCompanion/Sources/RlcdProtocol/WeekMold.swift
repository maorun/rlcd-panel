import Foundation

/// One day of the mold chart of the `WEEK` reply. `hours` is `nil` for a gap day, an empty field and a reply with 7 columns.
public struct WeekMoldPoint: Hashable {
    public let day: Int
    public let label: String
    public let hours: Int?
}

public enum WeekMold {
    /// Axis label of a day: `MM-DD` from the date, `day <N>` without a stamp.
    public static func label(day: Int, date: String) -> String {
        date.isEmpty ? "day \(day)" : String(date.suffix(5))
    }

    /// Seven points for the days 6 to 0 (oldest first) of `sensor`. A day without a row gives `nil` hours.
    public static func points(rows: [WeekRow], sensor: Int) -> [WeekMoldPoint] {
        (0...6).reversed().map { day in
            let row = rows.first { $0.sensor == sensor && $0.day == day }
            return WeekMoldPoint(day: day, label: label(day: day, date: row?.date ?? ""), hours: row?.moldHours)
        }
    }
}
