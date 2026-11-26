import Foundation

/// One row of the `ALERTS` reply: an alert bit with the tiles that cause it.
public struct AlertRow: Equatable {
    public let name: String
    public let active: Bool
    public let acked: Bool
    /// Tile mask (bit 0 onboard, bits 1 to 10 slots); `nil` for an alert without tiles.
    public let tiles: Int?
    /// Trigger limit as the firmware prints it; `nil` for an alert without a limit.
    public let limit: String?

    /// Tile numbers in the mask, lowest first.
    public var tileIndices: [Int] {
        guard let tiles else { return [] }
        return (0...10).filter { tiles & (1 << $0) != 0 }
    }
}

/// Parsed `ALERTS` reply (`SerialConsoleRunner::showAlerts` of the firmware).
public struct AlertsReply: Equatable {
    public static let header = "alert,active,acked,tiles,limit"
    public static let alertNames = ["FREEZER", "HUMIDITY", "NOISE", "SENSOR_LOST", "CHARGE_FULL", "VENTILATE", "SENSOR_BATTERY", "HEAT"]

    public let rows: [AlertRow]

    /// `lines` hold `BEGIN ALERTS`, the header and the rows, without the `CRC32` line.
    public static func parse(lines: [String]) throws -> AlertsReply {
        guard lines.first == "BEGIN ALERTS" else { throw ParseError.missingLine("BEGIN ALERTS") }
        guard lines.count >= 2, lines[1] == header else { throw ParseError.missingLine(header) }
        var rows: [AlertRow] = []
        var seen = Set<String>()
        for line in lines.dropFirst(2) {
            let c = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard c.count == 5, alertNames.contains(c[0]), let active = flag(c[1]), let acked = flag(c[2]) else {
                throw ParseError.badLine(line)
            }
            var tiles: Int?
            if !c[3].isEmpty {
                guard c[3].count == 3, c[3].allSatisfy(\.isHexDigit), let m = Int(c[3], radix: 16), m <= 0x7FF else {
                    throw ParseError.badLine(line)
                }
                tiles = m
            }
            var limit: String?
            if !c[4].isEmpty {
                guard c[4].range(of: #"^-?[0-9]{1,4}(\.[0-9])?$"#, options: .regularExpression) != nil else {
                    throw ParseError.badLine(line)
                }
                limit = c[4]
            }
            guard seen.insert(c[0]).inserted else { throw ParseError.badLine(line) } // one row per alert
            rows.append(AlertRow(name: c[0], active: active, acked: acked, tiles: tiles, limit: limit))
        }
        return AlertsReply(rows: rows)
    }

    private static func flag(_ s: String) -> Bool? {
        s == "1" ? true : s == "0" ? false : nil
    }

    /// Mask of the active alerts in the bit order of `AlertMask`, to compare with the alert mask of a telemetry frame.
    public var activeMask: UInt8 {
        rows.reduce(0) { mask, row in
            guard row.active, let bit = Self.alertNames.firstIndex(of: row.name) else { return mask }
            return mask | UInt8(1 << bit)
        }
    }

    /// `HUMIDITY: Bad, Keller` for an active alert; `nil` when the alert is not active or unknown.
    /// An alert without tiles shows only its name; a held alert without a tile above the limit says so.
    public func causeText(for alert: String, tileNames: [String]) -> String? {
        guard let row = rows.first(where: { $0.name == alert }), row.active else { return nil }
        guard row.tiles != nil else { return row.name }
        let indices = row.tileIndices
        if indices.isEmpty { return "\(row.name): no tile above the limit" }
        let names = indices.map { $0 < tileNames.count && !tileNames[$0].isEmpty ? tileNames[$0] : "Tile \($0)" }
        return "\(row.name): " + names.joined(separator: ", ")
    }

    /// One cause text for each active alert, in reply order.
    public func causeLines(tileNames: [String]) -> [String] {
        rows.compactMap { causeText(for: $0.name, tileNames: tileNames) }
    }

    /// The firmware answers `ERR UNKNOWN` to a command that it does not know.
    public static func errorText(_ reply: String) -> String {
        switch reply {
        case "ERR UNKNOWN": return "Update the panel firmware"
        case "ERR BAD_ARG": return "The panel rejected the command"
        default: return reply
        }
    }
}
