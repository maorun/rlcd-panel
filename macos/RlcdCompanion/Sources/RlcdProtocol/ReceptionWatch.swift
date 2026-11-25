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
}
