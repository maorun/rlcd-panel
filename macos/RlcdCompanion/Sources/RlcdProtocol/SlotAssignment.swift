import Foundation

/// Assigns a scanned sensor to a registry slot through `SET slot<N>_mac=<MAC>`.
public enum SlotAssignment {
    public static let slots = 1...3

    public static func key(slot: Int) -> String? { slots.contains(slot) ? "slot\(slot)_mac" : nil }

    public static func isMacKey(_ key: String) -> Bool { slots.contains { key == "slot\($0)_mac" } }

    /// `AA:BB:CC:DD:EE:FF`, six hex pairs, either case.
    public static func isValidMac(_ mac: String) -> Bool {
        let parts = mac.split(separator: ":", omittingEmptySubsequences: false)
        return parts.count == 6 && parts.allSatisfy { $0.utf8.count == 2 && $0.allSatisfy(\.isHexDigit) }
    }

    /// The `SET` command for one slot. `mac == nil` clears the slot (empty value). `nil` for a bad slot or MAC.
    public static func command(slot: Int, mac: String?) -> ConsoleCommand? {
        guard let key = key(slot: slot) else { return nil }
        guard let mac else { return .set(key: key, value: "") }
        let value = mac.uppercased()
        guard ConfigSchema.validate(key: key, value: value) == nil, !value.isEmpty else { return nil } // also refuses 00:00:00:00:00:00
        return .set(key: key, value: value)
    }

    /// Slots other than `slot` that already hold `mac` as a fixed MAC (`config` values). The firmware rejects this `SET`.
    public static func conflicts(slot: Int, mac: String, config: ConfigReply?) -> [Int] {
        let target = mac.uppercased()
        return slots.filter { other in
            other != slot && config?.values["slot\(other)_mac"]?.uppercased() == target
        }
    }

    /// Warning text for a MAC that another slot holds, `nil` without a conflict.
    public static func warning(slot: Int, mac: String, config: ConfigReply?) -> String? {
        let other = conflicts(slot: slot, mac: mac, config: config)
        guard !other.isEmpty else { return nil }
        return "Slot \(other.map(String.init).joined(separator: ", ")) already holds \(mac.uppercased()). The firmware rejects a duplicate MAC."
    }

    /// `SCAN` runs only on the USB serial link; the firmware replies `ERR NOT_OVER_BLE` on BLE.
    public static func scanAllowed(onUsb: Bool) -> Bool { onUsb }
    /// The scan takes about 4 s. The 10 s reply timeout leaves room for it; each received chunk restarts the timer.
    public static let scanTimeoutSeconds = 10
    public static let defaultTimeoutSeconds = 10

    /// Seconds to wait for the reply of a command.
    public static func timeoutSeconds(for command: ConsoleCommand) -> Int {
        command == .scan ? scanTimeoutSeconds : defaultTimeoutSeconds
    }
}
