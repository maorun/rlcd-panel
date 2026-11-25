import Foundation

/// One row of the `RECEPTION` reply: the scan pattern of one registry slot.
public struct ReceptionRow: Equatable, Identifiable {
    public var id: Int { slot }
    /// Registry slot, 1 to 10.
    public let slot: Int
    public let mac: String
    /// Name bound to the MAC, empty for an unknown MAC.
    public let name: String
    /// Counted scans, 0 to 24.
    public let scans: Int
    /// Scans with a packet.
    public let received: Int
    /// One cell for each counted scan, oldest scan first, `true` for a received packet.
    public let cells: [Bool]

    public var percent: Int { scans > 0 ? received * 100 / scans : 0 }
    /// Below 50 % of the counted scans (exact compare, like `ReceptionMetrics.Rate.isWeak`).
    public var isWeak: Bool { scans > 0 && received * 100 < ReceptionMetrics.Rate.warnBelowPercent * scans }
}

/// Parsed `RECEPTION` reply.
public struct ReceptionReply: Equatable {
    public static let header = "slot,mac,name,scans,received,mask"
    public static let maxScans = 24

    public let rows: [ReceptionRow]

    /// `lines` hold `BEGIN RECEPTION`, the header and the rows (without the `CRC32` line).
    /// Throws `badLine` for a wrong column count, a bad slot, MAC or count, a mask character other than `0` or `1`,
    /// a mask length other than `scans`, a `received` value that differs from the `1` count and a repeated slot.
    public static func parse(lines: [String]) throws -> ReceptionReply {
        guard lines.first == "BEGIN RECEPTION" else { throw ParseError.missingLine("BEGIN RECEPTION") }
        guard lines.count >= 2, lines[1] == header else { throw ParseError.missingLine(header) }
        var rows: [ReceptionRow] = []
        var seen = Set<Int>()
        for line in lines.dropFirst(2) {
            let c = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard c.count == 6, let slot = plainInt(c[0]), (1...ReceptionMetrics.slotCount).contains(slot),
                  SlotAssignment.isValidMac(c[1]),
                  let scans = plainInt(c[3]), (0...maxScans).contains(scans),
                  let received = plainInt(c[4]), (0...scans).contains(received),
                  c[5].utf8.count == scans, c[5].utf8.allSatisfy({ $0 == 0x30 || $0 == 0x31 }) else {
                throw ParseError.badLine(line)
            }
            let cells = c[5].utf8.map { $0 == 0x31 }
            guard cells.filter({ $0 }).count == received else { throw ParseError.badLine(line) }
            guard seen.insert(slot).inserted else { throw ParseError.badLine(line) }
            rows.append(ReceptionRow(slot: slot, mac: c[1].uppercased(), name: c[2], scans: scans, received: received, cells: cells))
        }
        return ReceptionReply(rows: rows)
    }
}
