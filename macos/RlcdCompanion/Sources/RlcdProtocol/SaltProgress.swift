import Foundation

/// Progress of a salt test: the count of stable hours of one sensor from `HISTORY N` replies.
/// The caller sends `HISTORY N 6` (`HISTORY N` for an older firmware) every `intervalSeconds` while `isRunning`; the clock is injected for tests.
public struct SaltProgress {
    /// Stable hours that the salt check needs (same number as `SALT N`).
    public static let requiredHours = 6
    /// Largest humidity span of stable hours in %RH (same limit as `SALT N`).
    public static let maxSpanPct = 1.0
    /// Seconds between two history commands.
    public static let intervalSeconds = 600
    /// The watch stops after this time.
    public static let maxDurationSeconds = 8 * 3600

    public enum StopReason: Equatable {
        case user, viewClosed, timeout, linkLost, replyFailed
    }

    public private(set) var isRunning = false
    public private(set) var stopReason: StopReason?
    /// Stable hours of the last reply, 0 before the first reply.
    public private(set) var stableHours = 0
    public private(set) var replies = 0
    /// True while the watch sends `HISTORY N <requiredHours>`; false after an older firmware answered `ERR BAD_ARG`.
    public private(set) var usesHourWindow = true
    private var startedAt: Date?
    private let now: () -> Date

    public init(now: @escaping () -> Date = Date.init) { self.now = now }

    /// Hours until `requiredHours` stable hours, 0 when reached.
    public var hoursRemaining: Int { max(0, Self.requiredHours - stableHours) }
    public var isReady: Bool { stableHours >= Self.requiredHours }
    /// Text of the view, for example `Stable 3/6 h`. The count stops at 6.
    public var text: String { "Stable \(min(stableHours, Self.requiredHours))/\(Self.requiredHours) h" }

    /// Count of the newest hours of `sensor` whose humidity span is at most `maxSpanPct`.
    /// The run starts at age 0 and ends at the first missing hour or when the span grows above the limit,
    /// so an unstable newest hour resets the count to 1 and a gap or an older unstable hour ends it.
    /// The count does not check that the sensor is fresh; `SALT N` has the same property and its reply is the final gate.
    /// `SALT N` counts the 6 newest samples whatever their age, so after a skipped hourly sample the count here
    /// is the stricter one: the button may wait while the firmware would already accept the check.
    public static func stableHours(rows: [HistoryRow], sensor: Int) -> Int {
        var byAge: [Int: Double] = [:]
        for r in rows where r.sensor == sensor {
            if let rh = r.humidityPct { byAge[r.ageHours] = rh }
        }
        var lo = Double.infinity, hi = -Double.infinity, count = 0
        while let rh = byAge[count] {
            lo = min(lo, rh); hi = max(hi, rh)
            if hi - lo > maxSpanPct + 1e-9 { break }
            count += 1
        }
        return count
    }

    /// The command of one tick: 6 rows give the full count, so the short read saves BLE bytes.
    public func historyCommand(sensor: Int) -> ConsoleCommand {
        usesHourWindow ? .historySensorHours(sensor, Self.requiredHours) : .historySensor(sensor)
    }

    /// The command to send again after an `ERR` reply to `cmd`, or nil when the watch must stop.
    /// Only `ERR BAD_ARG` for the short read gives a command, once per watch (`HISTORY N` of an older firmware).
    /// A reply for another sensor than `sensor` (a late reply of an older watch) gives nil.
    public mutating func retryAfterError(_ cmd: ConsoleCommand, text: String, sensor: Int) -> ConsoleCommand? {
        guard case let .historySensorHours(n, _) = cmd, n == sensor, text == "ERR BAD_ARG", fallBackToFullHistory() else { return nil }
        return historyCommand(sensor: n)
    }

    /// Switches to `HISTORY N` after `ERR BAD_ARG` for the short read (firmware without the hour window).
    /// Returns true once, while the watch runs and still uses the short read; the caller then repeats the tick.
    public mutating func fallBackToFullHistory() -> Bool {
        guard isRunning, usesHourWindow else { return false }
        usesHourWindow = false
        return true
    }

    /// Starts the watch and clears the count.
    public mutating func start() {
        usesHourWindow = true
        stableHours = 0
        replies = 0
        startedAt = now()
        stopReason = nil
        isRunning = true
    }

    public mutating func stop(_ reason: StopReason) {
        guard isRunning else { return }
        isRunning = false
        stopReason = reason
    }

    /// True while the watch runs and 8 hours are not over; stops the watch at the limit.
    /// The limit uses the wall clock and is checked at each tick, so it can fire up to one interval late.
    public mutating func shouldPoll() -> Bool {
        guard isRunning else { return false }
        if let s = startedAt, now().timeIntervalSince(s) >= Double(Self.maxDurationSeconds) {
            stop(.timeout)
            return false
        }
        return true
    }

    /// Adds one `HISTORY N` reply. Returns the new count.
    @discardableResult
    public mutating func record(rows: [HistoryRow], sensor: Int) -> Int {
        guard isRunning else { return stableHours }
        replies += 1
        stableHours = Self.stableHours(rows: rows, sensor: sensor)
        _ = shouldPoll()
        return stableHours
    }
}
