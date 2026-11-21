import Foundation

/// Groups the event names of the panel into categories for the event list.
public enum EventFilter {
    public enum Category: String, CaseIterable, Identifiable {
        case alerts, sensors, climate, power, config, other

        public var id: String { rawValue }

        public var title: String { rawValue.capitalized }
    }

    /// The names match `EventCode` in `rlcd_panel/sd_logger.h`; a new code falls into `other` until it is listed here.
    private static let names: [Category: Set<String>] = [
        .alerts: ["ALERT_RISE", "ALERT_FALL", "ALERT_ACK", "BATTERY_LOW_SET", "BATTERY_LOW_CLEAR", "EMPTY_PROTECTION"],
        .sensors: ["SLOT_ASSIGN", "SLOT_RELEASE", "SLOT_STALE", "SHTC3_FAULT", "SENSOR_BATTERY", "JUMP_DROP"],
        .climate: ["WINDOW_OPEN"],
        .power: ["RESET", "POWER_SOURCE", "SD_FAULT"],
        .config: ["CONFIG_LOAD", "CONFIG_SET", "NTP_SYNC", "MUTE_CHANGE"],
    ]

    /// The category of an event name; each name outside the lists is `other`.
    public static func category(of event: String) -> Category {
        for (category, set) in names where set.contains(event) { return category }
        return .other
    }

    /// Keeps the rows of the given categories in their order. An empty set keeps all rows.
    public static func apply(_ rows: [EventRow], _ categories: Set<Category>) -> [EventRow] {
        guard !categories.isEmpty else { return rows }
        return rows.filter { categories.contains(category(of: $0.event)) }
    }
}
