import Foundation

/// Detects rising alert bits between consecutive telemetry frames.
public struct AlertTransitions {
    /// Compares two telemetry frames and returns the rising alert bits.
    /// Returns empty if either frame is nil, if both frames have the same sequence number,
    /// or if no new alert bits were raised.
    public static func risingAlerts(previous: TelemetryFrame?, current: TelemetryFrame?) -> AlertMask {
        guard let prev = previous, let curr = current else { return AlertMask(rawValue: 0) }
        guard prev.sequence != curr.sequence else { return AlertMask(rawValue: 0) }
        let rising = curr.alerts.rawValue & ~prev.alerts.rawValue
        return AlertMask(rawValue: rising)
    }
}
