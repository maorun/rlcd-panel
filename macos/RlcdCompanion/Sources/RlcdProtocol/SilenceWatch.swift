import Foundation

/// Pure state machine that detects a panel which stopped to send telemetry frames.
///
/// `check()` reports `.silent` once when the last frame is older than `silenceSeconds`.
/// `onFrame()` reports `.back` once for the first frame after a `.silent`.
/// Without any frame since the creation nothing is reported.
public struct SilenceWatch {
    public enum Event: Equatable { case none, silent, back }

    public static let silenceSeconds: TimeInterval = 35 * 60

    private let clock: () -> Date
    private var lastFrameDate: Date?
    private var silent = false

    public init(clock: @escaping () -> Date = { Date() }) {
        self.clock = clock
    }

    /// Time of the last frame, or `nil` without a frame.
    public var lastFrameAt: Date? { lastFrameDate }

    /// Records a received frame. Returns `.back` when the panel was silent.
    public mutating func onFrame() -> Event {
        lastFrameDate = clock()
        guard silent else { return .none }
        silent = false
        return .back
    }

    /// Periodic check. Returns `.silent` once for each silent period.
    public mutating func check() -> Event {
        guard let last = lastFrameDate, !silent else { return .none }
        let now = clock()
        let age = now.timeIntervalSince(last)
        if age < 0 {
            // A clock that ran back gives no report. Anchor the last frame to the new time.
            lastFrameDate = now
            return .none
        }
        if age <= Self.silenceSeconds { return .none }
        silent = true
        return .silent
    }
}
