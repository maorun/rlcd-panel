import Foundation

/// Local RTC clock conversions and validation for the ESP32-S3-RLCD panel.
/// The panel RTC runs on local time in Europe/Berlin (CET or CEST).
public enum PanelClock {
    public static let timeZone = TimeZone(identifier: "Europe/Berlin")!

    public static let validYearRange = 2025...2099

    public static let calendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        cal.locale = Locale(identifier: "en_US_POSIX")
        return cal
    }()

    /// Formats a Date in Europe/Berlin local time as `YYYY-MM-DDTHH:MM:SS`.
    /// Returns `nil` if the year is outside 2025...2099.
    public static func format(date: Date) -> String? {
        let cal = calendar
        let year = cal.component(.year, from: date)
        guard validYearRange.contains(year) else { return nil }
        let comps = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        guard let y = comps.year, let m = comps.month, let d = comps.day,
              let h = comps.hour, let min = comps.minute, let s = comps.second else {
            return nil
        }
        return String(format: "%04d-%02d-%02dT%02d:%02d:%02d", y, m, d, h, min, s)
    }

    /// Parses a timestamp `YYYY-MM-DDTHH:MM:SS` in Europe/Berlin local time.
    public static func parse(text: String) -> Date? {
        let parts = text.split(separator: "T")
        guard parts.count == 2 else { return nil }
        let dateParts = parts[0].split(separator: "-")
        let timeParts = parts[1].split(separator: ":")
        guard dateParts.count == 3, timeParts.count == 3,
              let y = Int(dateParts[0]), let m = Int(dateParts[1]), let d = Int(dateParts[2]),
              let h = Int(timeParts[0]), let min = Int(timeParts[1]), let s = Int(timeParts[2]) else {
            return nil
        }
        var comps = DateComponents()
        comps.timeZone = timeZone
        comps.year = y
        comps.month = m
        comps.day = d
        comps.hour = h
        comps.minute = min
        comps.second = s
        return calendar.date(from: comps)
    }

    /// Offset in seconds between panel local time and Mac time: (panelDate - macDate).
    /// Positive when panel time is ahead of Mac time.
    public static func offsetSeconds(panelDate: Date, macDate: Date) -> Int {
        Int(panelDate.timeIntervalSince(macDate).rounded())
    }

    /// Computes the offset in seconds from a parsed TimeReply.
    public static func offsetSeconds(panelTime: TimeReply, macDate: Date) -> Int? {
        guard let d = panelTime.date else { return nil }
        return offsetSeconds(panelDate: d, macDate: macDate)
    }

    /// Returns true if TIME SET command is allowed.
    /// Allowed on USB serial link, or over BLE when ble_console is at least 2.
    public static func isTimeSetAllowed(onUsb: Bool, bleConsoleLevel: Int) -> Bool {
        onUsb || bleConsoleLevel >= 2
    }
}
