import Foundation

/// Series of `SCAN` replies for sensor placement: the last RSSI values of each MAC.
/// The caller sends `SCAN` every `interval` seconds while `isRunning`; the clock is injected for tests.
public struct ScanSeries {
    /// RSSI values kept for each MAC.
    public static let maxValues = 10
    /// Seconds between two `SCAN` commands.
    public static let intervalSeconds = 15
    /// The repeat stops after this time.
    public static let maxDurationSeconds = 600

    public enum StopReason: Equatable {
        case user, viewClosed, timeout, linkLost, scanFailed, notUsb
    }

    /// RSSI statistics of one MAC.
    public struct Stats: Equatable {
        public let mac: String
        public let min: Int
        public let mean: Double
        public let max: Int
        /// Number of kept values (at most `maxValues`).
        public let count: Int
        /// Scans of the series without this MAC.
        public let missed: Int
        /// All scans of the series; `missed` counts over this number, the RSSI values only over the last 10.
        public let scans: Int
    }

    public private(set) var isRunning = false
    public private(set) var stopReason: StopReason?
    public private(set) var scans = 0
    private var values: [String: [Int]] = [:]
    private var hits: [String: Int] = [:]
    private var order: [String] = []
    private var startedAt: Date?
    private let now: () -> Date

    public init(now: @escaping () -> Date = Date.init) { self.now = now }

    /// Starts the series. A BLE link never starts it: the firmware replies `ERR NOT_OVER_BLE`.
    @discardableResult
    public mutating func start(onUsb: Bool) -> Bool {
        guard SlotAssignment.scanAllowed(onUsb: onUsb) else {
            values = [:]; hits = [:]; order = []; scans = 0
            isRunning = false
            stopReason = .notUsb
            return false
        }
        values = [:]; hits = [:]; order = []; scans = 0
        startedAt = now()
        stopReason = nil
        isRunning = true
        return true
    }

    public mutating func stop(_ reason: StopReason) {
        guard isRunning else { return }
        isRunning = false
        stopReason = reason
    }

    /// True while the series runs and the 10 minutes are not over; stops the series at the limit.
    public mutating func shouldScan() -> Bool {
        guard isRunning else { return false }
        if let s = startedAt, now().timeIntervalSince(s) >= Double(Self.maxDurationSeconds) {
            stop(.timeout)
            return false
        }
        return true
    }

    /// Adds one reply. A MAC that is absent from it gets one more missed scan.
    public mutating func record(_ reply: ScanReply) {
        guard isRunning else { return }
        scans += 1
        for row in reply.rows {
            let mac = row.mac.uppercased()
            if values[mac] == nil { order.append(mac) }
            var v = values[mac] ?? []
            v.append(row.rssi)
            if v.count > Self.maxValues { v.removeFirst(v.count - Self.maxValues) }
            values[mac] = v
            hits[mac, default: 0] += 1
        }
        _ = shouldScan()
    }

    /// Statistics for each MAC in the order of the first sighting.
    public var stats: [Stats] {
        order.compactMap { mac in
            guard let v = values[mac], let lo = v.min(), let hi = v.max() else { return nil }
            return Stats(mac: mac, min: lo, mean: Double(v.reduce(0, +)) / Double(v.count), max: hi,
                         count: v.count, missed: scans - (hits[mac] ?? 0), scans: scans)
        }
    }

    public func stats(for mac: String) -> Stats? { stats.first { $0.mac == mac.uppercased() } }
}
