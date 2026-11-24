import Foundation

/// Parsed `TAIL <N>` reply: the newest hourly rows of the `climate_v6` log, oldest first.
public struct TailReply: Equatable {
    /// Row counts that the picker of the diagnostics view offers.
    public static let rowChoices = [6, 24, 72]

    public let rows: [ClimateRow]

    /// `lines` hold `BEGIN TAIL <n>`, the `climate_v6` header and `n` rows (without the `CRC32` line).
    /// Throws `badLine` for a bad count, a row count that differs from `n`, a wrong column count or a bad value,
    /// and `missingLine` for a header that is not the `climate_v6` header.
    public static func parse(lines: [String]) throws -> TailReply {
        guard let first = lines.first, first.hasPrefix("BEGIN TAIL ") else { throw ParseError.missingLine("BEGIN TAIL") }
        guard let count = plainInt(String(first.dropFirst("BEGIN TAIL ".count))), (0...72).contains(count) else {
            throw ParseError.badLine(first)
        }
        guard lines.count >= 2 else { throw ParseError.missingLine("header") }
        let log = try ClimateLog.parse(text: lines.dropFirst().joined(separator: "\n"))
        guard log.format == .v6 else { throw ParseError.missingLine("header") }
        guard log.rows.count == count else { throw ParseError.badLine(first) }
        return TailReply(rows: log.rows)
    }

    /// Clear text for the error replies of `TAIL`; other text stays as it is.
    public static func message(_ reply: String) -> String {
        switch reply {
        case "ERR NO_CARD": return "No MicroSD card in the panel"
        case "ERR NO_FILE": return "No log file yet (the panel clock may be invalid)"
        case "ERR READ_FAILED": return "The panel could not read the log file"
        case "ERR BAD_ARG": return "The panel rejected the row count"
        default: return reply
        }
    }
}
