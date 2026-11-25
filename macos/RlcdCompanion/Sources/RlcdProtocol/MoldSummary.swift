import Foundation

/// Mold figures of one room over the rows of a daily history. `totalHours` is `nil` when no row holds a mold value
/// for the room; then the view shows no line. `worstDate` and `worstHours` are `nil` without a day that has mold hours.
public struct MoldRoomSummary: Equatable {
    /// 0 onboard, 1 to 10 slots.
    public let sensor: Int
    public let totalHours: Int?
    /// Number of days with at least one mold hour.
    public let moldDays: Int
    /// `YYYY-MM-DD` of the day with the most mold hours; on a tie the newest day.
    public let worstDate: String?
    public let worstHours: Int?
}

/// Builds the mold summary of the daily charts from `DailyRow.moldHours` (archive folder or `DAYS` reply).
public enum MoldSummary {
    public static let noMoldText = "No mold hours"

    /// Summary of one sensor. `rows` run oldest first; a row without a value for the sensor (older file format,
    /// empty or stale sensor) does not count.
    public static func summarize(rows: [DailyRow], sensor: Int) -> MoldRoomSummary {
        var total: Int?
        var days = 0
        var worstDate: String?
        var worstHours: Int?
        for row in rows {
            guard sensor < row.moldHours.count, let hours = row.moldHours[sensor] else { continue }
            total = (total ?? 0) + hours
            if hours > 0 {
                days += 1
                // ISO dates compare as text, so a tie keeps the newest day whatever the row order.
                if let best = worstHours, let bestDate = worstDate {
                    if hours > best || (hours == best && row.date > bestDate) {
                        worstHours = hours
                        worstDate = row.date
                    }
                } else {
                    worstHours = hours
                    worstDate = row.date
                }
            }
        }
        return MoldRoomSummary(sensor: sensor, totalHours: total, moldDays: days, worstDate: worstDate, worstHours: worstHours)
    }

    /// One summary for each sensor 0 to 10, in sensor order.
    public static func summarize(rows: [DailyRow]) -> [MoldRoomSummary] {
        (0..<DailyLog.sensorNames.count).map { summarize(rows: rows, sensor: $0) }
    }

    /// Text of the chart line, or `nil` without a mold value (no line).
    public static func lineText(_ room: MoldRoomSummary) -> String? {
        guard let total = room.totalHours else { return nil }
        guard let date = room.worstDate, let worst = room.worstHours else { return noMoldText }
        let days = room.moldDays == 1 ? "1 day" : "\(room.moldDays) days"
        return "Mold \(total) h, \(days), worst \(date.dropFirst(5)) \(worst) h"
    }
}
