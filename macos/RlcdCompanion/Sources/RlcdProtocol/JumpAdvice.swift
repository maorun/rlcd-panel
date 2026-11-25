import Foundation

/// Advice to raise `jump_limit_c` for a slot whose good readings the jump guard drops often.
/// A pure function of the `EVENTS` rows, the active config and the clock.
public struct JumpAdvice: Equatable, Sendable {
    /// A slot needs at least this many `JUMP_DROP` rows in the window.
    public static let minDrops = 3
    /// Length of the window in seconds.
    public static let windowSeconds: TimeInterval = 24 * 3600
    public static let step = 2.0
    public static let maxLimit = 20.0
    /// Firmware default of `jump_limit_c`, used when the config has no value.
    public static let defaultLimit = 8.0
    public static let key = "jump_limit_c"

    public let slot: Int
    public let name: String
    public let drops: Int
    public let change: ConfigChange

    public var text: String {
        "\(name): the jump guard dropped \(drops) readings in 24 hours. Raise \(change.text)."
    }

    /// One advice for each slot (ascending) with `minDrops` or more `JUMP_DROP` rows in the 24 hours before `now`.
    /// No advice without a config, when `jump_limit_c` is 0 (check off), already 20.0 or not a number. Drops at or before
    /// the newest `CONFIG_SET` row of `jump_limit_c` do not count. The panel RTC holds local time without a zone,
    /// so the Mac time zone reads it (see `AlertTimeline.parse`). Rows with a bad time or slot do not count.
    public static func evaluate(events: [EventRow], config: ConfigReply?, now: Date) -> [JumpAdvice] {
        guard let config else { return [] } // the active limit is unknown
        let oldText = config.values[key] ?? String(format: "%.1f", defaultLimit)
        guard let old = Double(oldText), old > 0 else { return [] }
        guard old < maxLimit else { return [] } // nothing left to raise
        let proposed = String(format: "%.1f", min(old + step, maxLimit))
        // Drops before the newest change of the limit belong to the old limit: a repeated apply must not stack.
        let keyIndex = ConfigSchema.firmwareKeys.firstIndex(of: key)
        let lastChange = events.compactMap { row -> Date? in
            guard row.event == "CONFIG_SET", row.detail & 0xFF == keyIndex else { return nil }
            return AlertTimeline.parse(time: row.time)
        }.max()
        var counts: [Int: Int] = [:]
        for row in events where row.event == "JUMP_DROP" && (1...10).contains(row.detail) {
            guard let time = AlertTimeline.parse(time: row.time) else { continue }
            let age = now.timeIntervalSince(time)
            guard age >= 0, age <= windowSeconds else { continue }
            if let lastChange, time <= lastChange { continue }
            counts[row.detail, default: 0] += 1
        }
        return counts.keys.sorted().compactMap { slot in
            let n = counts[slot] ?? 0
            guard n >= minDrops else { return nil }
            return JumpAdvice(slot: slot, name: EventText.roomName(tile: slot, config: config), drops: n,
                              change: ConfigChange(key: key, old: oldText, new: proposed))
        }
    }
}
