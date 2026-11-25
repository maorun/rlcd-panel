import Foundation

/// Parsed `DAYS <N>` reply: the newest daily summary rows of the `daily_v5` log, oldest first.
public struct DaysReply: Equatable {
    /// Row counts that the picker of the daily chart offers.
    public static let rowChoices = [7, 14, 31]

    public let rows: [DailyRow]

    /// `lines` hold `BEGIN DAYS <n>`, the `daily_v5` header and `n` rows (without the `CRC32` line).
    /// Throws `badLine` for a bad count, a row count that differs from `n`, a wrong column count or a bad value,
    /// and `missingLine` for a header that is not the `daily_v5` header.
    public static func parse(lines: [String]) throws -> DaysReply {
        guard let first = lines.first, first.hasPrefix("BEGIN DAYS ") else { throw ParseError.missingLine("BEGIN DAYS") }
        guard let count = plainInt(String(first.dropFirst("BEGIN DAYS ".count))), (0...31).contains(count) else {
            throw ParseError.badLine(first)
        }
        guard lines.count >= 2 else { throw ParseError.missingLine("header") }
        let log = try DailyLog.parse(text: lines.dropFirst().joined(separator: "\n"))
        guard log.format == .v5 else { throw ParseError.missingLine("header") }
        guard log.rows.count == count else { throw ParseError.badLine(first) }
        return DaysReply(rows: log.rows)
    }

    /// Clear text for the error replies of `DAYS`; other text stays as it is.
    public static func message(_ reply: String) -> String {
        switch reply {
        case "ERR NO_CARD": return "No MicroSD card in the panel"
        case "ERR NO_FILE": return "No daily file yet (the panel clock may be invalid)"
        case "ERR READ_FAILED": return "The panel could not read the daily file"
        case "ERR BAD_ARG": return "The panel rejected the day count"
        default: return reply
        }
    }
}
