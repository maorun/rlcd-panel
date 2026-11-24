import Foundation

/// Assigns a scanned sensor to a registry slot through `SET slot<N>_mac=<MAC>`.
public enum SlotAssignment {
    public static let slots = 1...10

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

    /// `DeviceConfig::NAME_MAX_CHARS`.
    public static let maxNameChars = 12

    /// The commands that bind a name to a sensor: `SET slotN_mac=<MAC>`, then `SET slotN_name=<name>` for a
    /// name that is not empty. The name is upper-cased (the firmware font has no lowercase letters). `nil` for
    /// a bad slot or MAC, a name above 12 characters, a name with a space or `=`, a name the firmware rejects,
    /// or a command line above 40 bytes.
    public static func batch(slot: Int, mac: String, name: String?) -> [ConsoleCommand]? {
        guard let macCommand = command(slot: slot, mac: mac) else { return nil }
        var commands = [macCommand]
        if let name, !name.isEmpty {
            let value = name.uppercased()
            guard value.count <= maxNameChars, !value.contains(" "), !value.contains("="),
                  ConfigSchema.validate(key: "slot\(slot)_name", value: value) == nil else { return nil }
            commands.append(.set(key: "slot\(slot)_name", value: value))
        }
        return fitsConsoleLine(commands) ? commands : nil
    }

    /// True when the console takes each command (no white space, no empty value, at most 40 bytes).
    public static func fitsConsoleLine(_ commands: [ConsoleCommand]) -> Bool {
        commands.allSatisfy { $0.line != nil }
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
    /// `SCREEN` answers `ERR NOT_OVER_BLE` on BLE: send it on the USB serial link only.
    public static func screenAllowed(onUsb: Bool) -> Bool { onUsb }
    /// The scan takes about 4 s. The 10 s reply timeout leaves room for it; each received chunk restarts the timer.
    public static let scanTimeoutSeconds = 10
    public static let defaultTimeoutSeconds = 10
    /// `SELFTEST` runs a 1 s microphone window, a 1 s BLE scan and a card mount.
    public static let selfTestTimeoutSeconds = 20
    /// `NTP SYNC` blocks the panel for about 14 s.
    public static let ntpSyncTimeoutSeconds = 30
    /// `NTP SYNC` answers `ERR NOT_OVER_BLE` on BLE: send it on the USB serial link only.
    public static func ntpSyncAllowed(onUsb: Bool) -> Bool { onUsb }

    /// Seconds to wait for the reply of a command.
    public static func timeoutSeconds(for command: ConsoleCommand) -> Int {
        switch command {
        case .scan: return scanTimeoutSeconds
        case .ntpSync: return ntpSyncTimeoutSeconds
        case .selfTest: return selfTestTimeoutSeconds
        default: return defaultTimeoutSeconds
        }
    }
}
