import Foundation

/// Pure state machine that reports each rising bit of the frozen reading mask (`LATCH stuck=`).
///
/// Bit 0 is the onboard tile, bits 1 to 10 are the slots. The first read only sets the baseline, so a frozen
/// reading that already existed gives no tile. A later read gives each bit that was clear before and is set now.
/// A falling bit and a `nil` mask (older firmware) give no tile; a `nil` mask keeps the last known state.
public struct StuckWatch {
    public static let tileCount = 11

    private var previous: Int?

    public init() {}

    /// The tiles (0 to 10, ascending) whose bit rose since the last read.
    public mutating func update(mask: Int?) -> [Int] {
        guard let mask else { return [] }
        let current = mask & ((1 << Self.tileCount) - 1)
        defer { previous = current }
        guard let previous else { return [] }
        let rising = current & ~previous
        return (0..<Self.tileCount).filter { rising >> $0 & 1 == 1 }
    }

    /// Text of the notification for one rising tile, with the sensor name of `config` or `Onboard` / `Slot <N>`.
    public static func notificationBody(tile: Int, config: ConfigReply?) -> String {
        "Sensor frozen: \(EventText.roomName(tile: tile, config: config)). It sends the same values."
    }
}
