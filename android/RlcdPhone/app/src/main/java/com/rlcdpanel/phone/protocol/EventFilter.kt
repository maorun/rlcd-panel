package com.rlcdpanel.phone.protocol

/** Groups the event names of the panel into categories for the event list. */
object EventFilter {

    enum class Category(val title: String) {
        ALERTS("Alerts"),
        SENSORS("Sensors"),
        CLIMATE("Climate"),
        POWER("Power"),
        CONFIG("Config"),
        OTHER("Other")
    }

    /** The names match `EventCode` in `rlcd_panel/sd_logger.h`; a new code falls into `OTHER` until it is listed here. */
    val NAMES: Map<Category, Set<String>> = mapOf(
        Category.ALERTS to setOf("ALERT_RISE", "ALERT_FALL", "ALERT_ACK", "BATTERY_LOW_SET", "BATTERY_LOW_CLEAR", "EMPTY_PROTECTION"),
        Category.SENSORS to setOf("SLOT_ASSIGN", "SLOT_RELEASE", "SLOT_STALE", "SHTC3_FAULT", "SENSOR_BATTERY", "JUMP_DROP", "SCAN_FAULT", "RECEPTION_LOW", "SENSOR_DRIFT", "SENSOR_STUCK"),
        Category.CLIMATE to setOf("WINDOW_OPEN", "WALL_DAMP", "DRY_AIR", "HEAT_TILE"),
        Category.POWER to setOf("RESET", "POWER_SOURCE", "SD_FAULT"),
        Category.CONFIG to setOf("CONFIG_LOAD", "CONFIG_SET", "NTP_SYNC", "MUTE_CHANGE")
    )

    /** The category of an event name; each name outside the lists is `OTHER`. */
    fun category(event: String): Category {
        for ((category, set) in NAMES) {
            if (event in set) return category
        }
        return Category.OTHER
    }

    /** Keeps the rows of the given categories in their order. An empty set keeps each row. */
    fun apply(rows: List<EventRow>, categories: Set<Category>): List<EventRow> {
        if (categories.isEmpty()) return rows
        return rows.filter { category(it.event) in categories }
    }
}
