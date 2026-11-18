import Foundation

/// Why the firmware rejected a packet (`RejectReason` text of the `REJECTS` reply).
public enum RejectReason: String, Equatable, CaseIterable {
    case full = "FULL"
    case unassigned = "UNASSIGNED"
    case disabled = "DISABLED"
    case jump = "JUMP"

    /// Short hint for the user.
    public var fixHint: String {
        switch self {
        case .full: "Free a slot"
        case .unassigned: "Assign to slot"
        case .disabled: "Set slot<N>_enabled=1"
        case .jump: "Check the sensor position"
        }
    }
}

public struct RejectRow: Equatable, Identifiable {
    public var id: String { mac }
    public let mac: String
    public let format: String
    public let reason: RejectReason
    /// Rejected packets of the MAC in the last scan, 1 to 255.
    public let count: Int
}

/// Parsed `REJECTS` reply.
public struct RejectsReply: Equatable {
    public static let header = "mac,format,reason,count"

    public let rows: [RejectRow]
    /// MACs that the firmware dropped (`DROPPED,<N>` row), 0 without that row.
    public let dropped: Int

    /// `lines` hold `BEGIN REJECTS`, the header and the rows.
    public static func parse(lines: [String]) throws -> RejectsReply {
        guard lines.first == "BEGIN REJECTS" else { throw ParseError.missingLine("BEGIN REJECTS") }
        guard lines.count >= 2, lines[1] == header else { throw ParseError.missingLine(header) }
        var rows: [RejectRow] = []
        var dropped = 0
        var seenDropped = false
        var seen = Set<String>()
        for line in lines.dropFirst(2) {
            let c = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            if c.first == "DROPPED" {
                guard line == lines.last else { throw ParseError.badLine(line) } // the firmware sends it last
                guard c.count == 2, let n = plainInt(c[1]), n >= 0, !seenDropped else { throw ParseError.badLine(line) }
                seenDropped = true
                dropped = n
                continue
            }
            guard c.count == 4, SlotAssignment.isValidMac(c[0]), ScanReply.formats.contains(c[1]),
                  let reason = RejectReason(rawValue: c[2]), let count = plainInt(c[3]), (1...255).contains(count) else {
                throw ParseError.badLine(line)
            }
            guard seen.insert(c[0].uppercased()).inserted else { throw ParseError.badLine(line) } // one row per MAC
            rows.append(RejectRow(mac: c[0].uppercased(), format: c[1], reason: reason, count: count))
        }
        return RejectsReply(rows: rows, dropped: dropped)
    }
}
