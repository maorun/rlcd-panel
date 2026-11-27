import Foundation

/// Pure state machine that reports each rising bit of the weak signal mask (`LATCH rxlow=`).
///
/// Bits 0 to 9 are the slots 1 to 10. The first read only stores the mask, so a weak signal that already
/// existed gives no slot. A later read gives each slot whose bit was clear before and is set now.
/// An equal mask, a falling bit and a `nil` mask (older firmware) give no slot; a `nil` mask keeps the stored mask.
public struct ReceptionWatch {
    public static let slotCount = 10

    private var previous: Int?

    public init() {}

    /// The slots (1 to 10, ascending) whose bit rose since the last read.
    public mutating func update(mask: Int?) -> [Int] {
        guard let mask else { return [] }
        let current = mask & ((1 << Self.slotCount) - 1)
        defer { previous = current }
        guard let previous else { return [] }
        let rising = current & ~previous
        return (1...Self.slotCount).filter { rising >> ($0 - 1) & 1 == 1 }
    }

    /// Text of the notification for one rising slot, with the sensor name of `config` or `Slot <N>`.
    public static func notificationBody(slot: Int, config: ConfigReply?) -> String {
        "Weak signal: \(EventText.roomName(tile: slot, config: config))"
    }

    /// A rate below this share (in percent) is weak; same limit as `ReceptionMetrics.Rate.warnBelowPercent`.
    public static let adviceBelowPercent = ReceptionMetrics.Rate.warnBelowPercent
    /// An RSSI below this value (in dBm) points to a distance problem.
    public static let farRssiBelowDbm = -85

    /// What to do for a weak slot. `nil` for a rate of 50 % or more. Below 50 %, an RSSI below -85 dBm gives
    /// the move advice, a better or unknown RSSI gives the battery advice.
    public static func advice(rate: Int, rssi: Int?) -> String? {
        guard rate < adviceBelowPercent else { return nil }
        if let rssi, rssi < farRssiBelowDbm { return "Move the sensor nearer to the panel" }
        return "Check the sensor battery"
    }

    /// Advice for one `RECEPTION` row. The weak rule is `ReceptionRow.isWeak` (exact compare), so the strip
    /// warning symbol and the text always agree. An unknown RSSI (no `STATUS` read) gives the battery text.
    public static func advice(for row: ReceptionRow, rssi: Int?) -> String? {
        guard row.isWeak else { return nil }
        return advice(rate: row.percent, rssi: rssi)
    }
}
