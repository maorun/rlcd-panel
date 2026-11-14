import Foundation

/// Reason of a link failure. The texts are fixed.
public enum LinkFailure: Equatable {
    case connectTimeout, writeTimeout, commandTimeout

    public var reason: String {
        switch self {
        case .connectTimeout: return "Console connect timed out (10 s)"
        case .writeTimeout: return "Console write timed out (5 s)"
        case .commandTimeout: return "Console reply timed out (10 s)"
        }
    }
}

/// Pure state machine for the timeouts of the BLE console link. It owns no timer.
/// The caller starts and ends each phase and calls `check()` on a tick.
/// A failure clears all timers. The caller then closes the link and drops the command queue.
public struct LinkWatchdog {
    public static let connectTimeout: TimeInterval = 10
    public static let writeTimeout: TimeInterval = 5
    public static let commandTimeout: TimeInterval = 10

    public enum Phase: Equatable { case idle, connecting, writing, awaitingChunk }

    private let clock: () -> TimeInterval
    private var connectStart: TimeInterval?
    private var writeStart: TimeInterval?
    private var commandStart: TimeInterval?

    public init(clock: @escaping () -> TimeInterval) { self.clock = clock }

    public var phase: Phase {
        if connectStart != nil { return .connecting }
        if writeStart != nil { return .writing }
        if commandStart != nil { return .awaitingChunk }
        return .idle
    }

    /// A connection attempt starts.
    public mutating func beginConnect() { connectStart = clock() }
    /// "Console connected" arrived.
    public mutating func connected() { connectStart = nil }
    /// One write with response goes out.
    public mutating func beginWrite() { writeStart = clock() }
    /// `didWriteValueFor` arrived.
    public mutating func writeCompleted() { writeStart = nil }
    /// A command waits for its reply.
    public mutating func beginCommand() { commandStart = clock() }
    /// A reply chunk arrived. It restarts the command timer, but only while a command waits.
    public mutating func chunkReceived() { if commandStart != nil { commandStart = clock() } }
    /// The reply is complete.
    public mutating func commandFinished() { commandStart = nil }

    /// Ends all timers without a failure.
    public mutating func cancel() {
        connectStart = nil
        writeStart = nil
        commandStart = nil
    }

    /// Returns a failure when a timer ran out, and then clears all timers.
    public mutating func check() -> LinkFailure? {
        let now = clock()
        let failure: LinkFailure?
        if let s = connectStart, now - s >= Self.connectTimeout {
            failure = .connectTimeout
        } else if let s = writeStart, now - s >= Self.writeTimeout {
            failure = .writeTimeout
        } else if let s = commandStart, now - s >= Self.commandTimeout {
            failure = .commandTimeout
        } else {
            failure = nil
        }
        if failure != nil { cancel() }
        return failure
    }
}
