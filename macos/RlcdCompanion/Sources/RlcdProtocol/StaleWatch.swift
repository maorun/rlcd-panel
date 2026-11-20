import Foundation

/// Pure state machine that detects a sensor tile which stopped to deliver valid readings.
///
/// `update(tile:valid:)` takes the tile index (0 to 10) and the valid bit of one frame. A tile that was
/// valid once and stays invalid for `lostAfter` gives `.lost` once. The next valid frame gives `.back`.
/// A tile that was never valid and a tile outside 0 to 10 give no result.
public struct StaleWatch {
    public enum Result: Equatable {
        case lost(Int)
        case back(Int)
    }

    public static let tileCount = 11
    public static let lostAfter: TimeInterval = 35 * 60

    private let clock: () -> Date
    private var lastValid = [Date?](repeating: nil, count: tileCount)
    private var lost = [Bool](repeating: false, count: tileCount)

    public init(clock: @escaping () -> Date = { Date() }) {
        self.clock = clock
    }

    public mutating func update(tile: Int, valid: Bool) -> Result? {
        guard lost.indices.contains(tile) else { return nil }
        let now = clock()
        if valid {
            lastValid[tile] = now
            guard lost[tile] else { return nil }
            lost[tile] = false
            return .back(tile)
        }
        guard let last = lastValid[tile], !lost[tile] else { return nil }
        let age = now.timeIntervalSince(last)
        if age < 0 {
            // A clock that ran back gives no report. Anchor the last valid frame to the new time.
            lastValid[tile] = now
            return nil
        }
        guard age >= Self.lostAfter else { return nil }
        lost[tile] = true
        return .lost(tile)
    }
}
