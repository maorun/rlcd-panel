import Foundation

/// Pure state machine that reports each rising bit of the dry air mask (`LATCH dry=`).
///
/// Bit 0 is the onboard tile, bits 1 to 10 are the slots. The first read only stores the mask, so a room that
/// was dry before gives no tile. A later read gives each bit that was clear before and is set now.
/// An equal mask and a falling bit give no tile. A `nil` mask (older firmware) gives no tile and keeps the stored mask.
/// Same rules as `WindowWatch`.
public struct DryWatch {
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

    /// Text of the notification for one rising tile, with the room name of `EventText`.
    public static func notificationBody(tile: Int, config: ConfigReply?) -> String {
        "Dry air: \(EventText.roomName(tile: tile, config: config))"
    }
}
