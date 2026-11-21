import Foundation

/// Plans the `Acknowledge` action of the alert notifications.
public enum AlertNotificationPlan {
    /// Category of a notification that carries the `Acknowledge` action.
    public static let categoryIdentifier = "rlcd_alert"
    /// Identifier of the `Acknowledge` action.
    public static let acknowledgeActionIdentifier = "rlcd_ack"

    /// Bit numbers 0 to 7 of the rising bits without `CHARGE_FULL`, lowest first. The firmware has no `ACK` for `CHARGE_FULL`.
    public static func actionBits(rising: AlertMask) -> [Int] {
        (0..<8).filter { bit in
            let mask = AlertMask(rawValue: UInt8(1 << bit))
            return rising.contains(mask) && mask != .chargeFull
        }
    }

    /// Notification identifier of one alert bit.
    public static func identifier(bit: Int) -> String { "rlcd_panel_alert_\(bit)" }

    /// Bit number of a notification identifier from `identifier(bit:)`; `nil` for another identifier or a bit above 7.
    public static func bit(identifier: String) -> Int? {
        let prefix = "rlcd_panel_alert_"
        guard identifier.hasPrefix(prefix), let bit = Int(identifier.dropFirst(prefix.count)), (0..<8).contains(bit) else { return nil }
        return bit
    }
}
