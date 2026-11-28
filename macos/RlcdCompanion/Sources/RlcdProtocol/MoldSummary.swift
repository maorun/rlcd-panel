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
    /// Sum of the `heat_h` values of the rows; `nil` when no row holds a heat value (for example a `daily_v6` month).
    public let heatHours: Int?

    public init(sensor: Int, totalHours: Int?, moldDays: Int, worstDate: String?, worstHours: Int?, heatHours: Int? = nil) {
        self.sensor = sensor
        self.totalHours = totalHours
        self.moldDays = moldDays
        self.worstDate = worstDate
        self.worstHours = worstHours
        self.heatHours = heatHours
    }
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
        var heat: Int?
        for row in rows {
            if sensor < row.heatHours.count, let h = row.heatHours[sensor] { heat = (heat ?? 0) + h }
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
        return MoldRoomSummary(sensor: sensor, totalHours: total, moldDays: days, worstDate: worstDate, worstHours: worstHours, heatHours: heat)
    }

    /// One summary for each sensor 0 to 10, in sensor order.
    public static func summarize(rows: [DailyRow]) -> [MoldRoomSummary] {
        (0..<DailyLog.sensorNames.count).map { summarize(rows: rows, sensor: $0) }
    }

    /// `Heat <N> h`, or `Heat --` without a heat value.
    public static func heatText(_ room: MoldRoomSummary) -> String {
        room.heatHours.map { "Heat \($0) h" } ?? "Heat --"
    }

    /// Text of the daily chart line: the mold line with `, Heat <N> h` appended, `Heat <N> h` alone for a room without a
    /// mold value, `nil` without a mold value and without a heat value (no line). A mold value without a heat value gives `Heat --`.
    public static func chartLine(_ room: MoldRoomSummary) -> String? {
        guard let mold = lineText(room) else { return room.heatHours == nil ? nil : heatText(room) }
        return "\(mold), \(heatText(room))"
    }

    /// Text of the chart line, or `nil` without a mold value (no line).
    public static func lineText(_ room: MoldRoomSummary) -> String? {
        guard let total = room.totalHours else { return nil }
        guard let date = room.worstDate, let worst = room.worstHours else { return noMoldText }
        let days = room.moldDays == 1 ? "1 day" : "\(room.moldDays) days"
        return "Mold \(total) h, \(days), worst \(date.dropFirst(5)) \(worst) h"
    }
}
