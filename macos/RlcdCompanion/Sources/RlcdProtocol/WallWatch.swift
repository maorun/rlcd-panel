import Foundation

/// Pure state machine that reports a rising wall humidity (mold risk) once for each tile.
///
/// A tile rises at `riseRh` or more and releases below `releaseRh`. A rise gives `.notify` unless the
/// same tile notified less than `repeatInterval` ago. A `nil` value releases the tile without a result.
public struct WallWatch {
    public enum Result: Equatable { case notify(tile: Int, rh: Double) }

    public static let riseRh = 80.0
    public static let releaseRh = 77.0
    public static let repeatInterval: TimeInterval = 6 * 3600
    public static let tileCount = 6

    private let clock: () -> Date
    private var risen = [Bool](repeating: false, count: tileCount)
    private var lastNotified = [Date?](repeating: nil, count: tileCount)

    public init(clock: @escaping () -> Date = { Date() }) {
        self.clock = clock
    }

    /// Feeds the wall humidity of one tile. A tile outside 0 to 5 gives no result.
    public mutating func update(tile: Int, rh: Double?) -> Result? {
        guard risen.indices.contains(tile) else { return nil }
        guard let rh, rh.isFinite else {
            risen[tile] = false
            return nil
        }
        if risen[tile] {
            if rh < Self.releaseRh { risen[tile] = false }
            return nil
        }
        guard rh >= Self.riseRh else { return nil }
        risen[tile] = true
        let now = clock()
        if let last = lastNotified[tile], now.timeIntervalSince(last) >= 0, now.timeIntervalSince(last) < Self.repeatInterval {
            return nil
        }
        lastNotified[tile] = now
        return .notify(tile: tile, rh: rh)
    }
}
