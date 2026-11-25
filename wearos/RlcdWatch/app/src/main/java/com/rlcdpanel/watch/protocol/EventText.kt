package com.rlcdpanel.watch.protocol

/**
 * Port of `EventText` of `EventText.swift`. A row that the app cannot decode (unknown event name, detail outside
 * the range) gives the raw text `<event>,<detail>`.
 */
object EventText {
    /** Alert names by bit mask (`ALERT_*` of `audio_alert.h`). */
    private val ALERT_NAMES = mapOf(
        1 to "FREEZER", 2 to "HUMIDITY", 4 to "NOISE", 8 to "SENSOR_LOST",
        16 to "CHARGE_FULL", 32 to "VENTILATE", 64 to "SENSOR_BATTERY", 128 to "HEAT"
    )
    private val NTP_RESULTS = listOf("OK", "WIFI_FAILED", "NTP_TIMEOUT", "RTC_WRITE_FAILED")
    private val RESET_REASONS = listOf(
        "UNKNOWN", "POWERON", "EXT", "SW", "PANIC", "INT_WDT", "TASK_WDT", "WDT", "DEEPSLEEP", "BROWNOUT", "SDIO"
    )
    private val ABNORMAL_RESETS = setOf("PANIC", "INT_WDT", "TASK_WDT", "WDT", "BROWNOUT")
    private val BLE_FORMATS = listOf("WOIO", "METER", "BTHOME", "ATC", "THERMOPRO")
    private val SD_STATUSES = mapOf(1 to "OK", 2 to "NO_CARD", 3 to "MOUNT_FAILED", 4 to "WRITE_FAILED", 5 to "FULL")

    /** Keys in the order of `DeviceConfigParser::KEYS`; the event `CONFIG_SET` stores the index. */
    private val CONFIG_KEYS: List<String> = listOf(
        "temp_offset_c", "rh_offset_pct", "freeze_alert_limit_c",
        "mold_alert_limit_rh", "chime_volume_pct", "quiet_hours_start_hour",
        "quiet_hours_end_hour", "mic_spl_offset_db", "log_retention_months",
        "noise_alert_limit_db", "chime_alert_mask", "ntp_sync_interval_h",
        "wake_interval_s", "sensor_stale_min", "display_invert",
        "climate_alert_tiles", "battery_offset_mv", "outdoor_slot",
        "ventilate_limit_rh", "sensor_battery_limit_pct", "slot1_temp_offset_c",
        "slot2_temp_offset_c", "slot3_temp_offset_c", "slot1_rh_offset_pct",
        "slot2_rh_offset_pct", "slot3_rh_offset_pct", "onboard_name",
        "slot1_name", "slot2_name", "slot3_name",
        "slot1_mac", "slot2_mac", "slot3_mac",
        "slot_release_h", "night_wake_interval_s", "temp_unit",
        "chime_repeat_h", "heat_alert_limit_c", "ble_broadcast_enabled",
        "ble_console", "slot4_name", "slot5_name",
        "slot4_mac", "slot5_mac", "slot4_temp_offset_c",
        "slot5_temp_offset_c", "slot4_rh_offset_pct", "slot5_rh_offset_pct",
        "usb_temp_offset_c", "dry_alert_limit_rh", "rh_gain",
        "slot1_rh_gain", "slot2_rh_gain", "slot3_rh_gain",
        "slot4_rh_gain", "slot5_rh_gain", "door_ref_slot",
        "slot6_name", "slot7_name", "slot8_name", "slot9_name", "slot10_name",
        "slot6_mac", "slot7_mac", "slot8_mac", "slot9_mac", "slot10_mac",
        "slot6_temp_offset_c", "slot7_temp_offset_c", "slot8_temp_offset_c",
        "slot9_temp_offset_c", "slot10_temp_offset_c",
        "slot6_rh_offset_pct", "slot7_rh_offset_pct", "slot8_rh_offset_pct",
        "slot9_rh_offset_pct", "slot10_rh_offset_pct",
        "slot6_rh_gain", "slot7_rh_gain", "slot8_rh_gain", "slot9_rh_gain", "slot10_rh_gain",
        "wall_frsi_pct", "slot_auto_assign",
        "slot1_enabled", "slot2_enabled", "slot3_enabled", "slot4_enabled", "slot5_enabled",
        "slot6_enabled", "slot7_enabled", "slot8_enabled", "slot9_enabled", "slot10_enabled",
        "auto_assign_min_rssi", "window_drop_c", "mold_alert_delay_h", "reception_low_pct",
        "dry_alert_delay_h", "jump_limit_c", "jump_limit_rh", "drift_limit_c", "drift_limit_rh"
    )

    fun describe(row: EventRow): String {
        val d = row.detail
        val raw = "${row.event},$d"
        return when (row.event) {
            "ALERT_RISE", "ALERT_FALL" -> {
                val name = ALERT_NAMES[d] ?: return raw
                "$name alert ${if (row.event == "ALERT_RISE") "on" else "off"}"
            }
            "ALERT_ACK" -> ALERT_NAMES[d]?.let { "Alert $it acknowledged" } ?: raw
            "CONFIG_LOAD" -> if (d < 0) raw else "config loaded, $d rejected line${if (d == 1) "" else "s"}"
            "NTP_SYNC" -> NTP_RESULTS.getOrNull(d)?.let { "NTP sync $it" } ?: raw
            "BATTERY_LOW_SET", "BATTERY_LOW_CLEAR" ->
                if (d !in 0..100) raw else "battery low ${if (row.event == "BATTERY_LOW_SET") "set" else "clear"} at $d %"
            "EMPTY_PROTECTION" -> if (d == 0 || d == 1) "empty battery protection ${if (d == 1) "set" else "release"}" else raw
            "RESET" -> {
                val reason = RESET_REASONS.getOrNull(d) ?: return raw
                "reset $reason" + if (reason in ABNORMAL_RESETS) " (abnormal)" else ""
            }
            "SLOT_ASSIGN" -> {
                val slot = d shr 8
                val format = BLE_FORMATS.getOrNull(d and 0xFF)
                if (d !in 0..0xFFFF || slot !in 1..10 || format == null) raw else "slot $slot $format"
            }
            "SLOT_RELEASE" -> {
                // Low byte is the slot; the high byte 1 marks a manual release, 0 the timer.
                val slot = d and 0xFF
                val manual = d shr 8
                if (d !in 0..0xFFFF || slot !in 1..10 || manual > 1) raw
                else if (manual == 1) "Slot $slot released by the user" else "slot $slot released"
            }
            "SLOT_STALE" -> {
                // Low byte is the slot; the high byte 0 marks a lost slot, 1 a slot that is back.
                val slot = d and 0xFF
                val back = d shr 8
                if (d !in 0..0xFFFF || slot !in 1..10 || back > 1) raw else if (back == 1) "Slot $slot back" else "Slot $slot lost"
            }
            "RECEPTION_LOW" -> {
                // Low byte is the slot; the high byte 1 marks a weak signal, 0 a good signal again.
                val slot = d and 0xFF
                val low = d shr 8
                if (d !in 0..0xFFFF || slot !in 1..10 || low > 1) raw else if (low == 1) "Slot $slot weak signal" else "Slot $slot signal good"
            }
            "JUMP_DROP" -> if (d in 1..10) "Slot $d reading jump dropped" else raw
            "CONFIG_SET" -> {
                val index = d and 0xFF
                val transport = d shr 8
                val key = CONFIG_KEYS.getOrNull(index)
                if (d !in 0..0xFFFF || transport > 1) raw
                else if (key == null) "Config set: key #$index"
                else "Config set: $key (${if (transport == 0) "USB" else "BLE"})"
            }
            "POWER_SOURCE" -> when (d) {
                1 -> "USB power connected"
                0 -> "Battery power"
                else -> raw
            }
            "MUTE_CHANGE" -> {
                val state = d and 0xFF
                val source = d shr 8
                val name = listOf("BOOT", "USB", "BLE").getOrNull(source)
                if (d !in 0..0xFFFF || state > 2 || name == null) raw
                else if (state == 2) "Chime snoozed ($name)"
                else if (state == 1) "Chime muted ($name)" else "Chime on ($name)"
            }
            "SHTC3_FAULT" -> {
                val state = d and 0xFF
                val cause = d shr 8
                if (d !in 0..0xFFFF) raw
                else if (state == 0 && cause == 0) "Onboard sensor back"
                else if (state == 1 && cause in 1..3) "Onboard sensor fault (${listOf("NACK", "SHORT", "CRC")[cause - 1]})"
                else raw
            }
            "SENSOR_BATTERY" -> {
                val slot = d and 0xFF
                val low = d shr 8
                if (d !in 0..0xFFFF || slot !in 1..10 || low > 1) raw
                else if (low == 1) "Slot $slot battery low" else "Slot $slot battery OK"
            }
            "CLOCK_SET" -> {
                val action = d and 0xFF
                val transport = d shr 8
                if (d !in 0..0xFFFF || action > 1 || transport > 1) raw
                else {
                    val name = if (transport == 0) "USB" else "BLE"
                    if (action == 0) "Clock set ($name)" else "RTC trimmed ($name)"
                }
            }
            "SCAN_FAULT" -> when (d) {
                1 -> "BLE scan start failed"
                0 -> "BLE scan works again"
                else -> raw
            }
            "WINDOW_OPEN" -> {
                // Low byte is the tile (0 onboard, 1 to 10 slots); the high byte 1 marks an open window, 0 a closed one.
                val tile = d and 0xFF
                val open = d shr 8
                if (d !in 0..0xFFFF || tile > 10 || open > 1) raw
                else "Window ${if (open == 1) "open" else "closed"} (${if (tile == 0) "Onboard" else "tile $tile"})"
            }
            "WALL_DAMP", "DRY_AIR" -> {
                // Low byte is the tile (0 onboard, 1 to 10 slots); the high byte 1 marks the badge set, 0 cleared.
                val tile = d and 0xFF
                val set = d shr 8
                val room = if (tile == 0) "Onboard" else "tile $tile"
                if (d !in 0..0xFFFF || tile > 10 || set > 1) raw
                else if (row.event == "WALL_DAMP") (if (set == 1) "Wall damp ($room)" else "Wall dry again ($room)")
                else (if (set == 1) "Dry air ($room)" else "Air humidity good ($room)")
            }
            "SENSOR_DRIFT" -> {
                // Low byte is the tile (0 onboard, 1 to 10 slots); the high byte 1 marks a drifting sensor, 0 a good one.
                val tile = d and 0xFF
                val drift = d shr 8
                if (d !in 0..0xFFFF || tile > 10 || drift > 1) raw
                else "Sensor drift ${if (drift == 1) "" else "gone "}(${if (tile == 0) "Onboard" else "tile $tile"})"
            }
            "SD_FAULT" -> SD_STATUSES[d]?.let { if (d == 1) "SD card OK" else "SD card fault: $it" } ?: raw
            else -> raw
        }
    }

    /** True for a `RESET` row with an abnormal reason. */
    fun isAbnormal(row: EventRow): Boolean =
        row.event == "RESET" && RESET_REASONS.getOrNull(row.detail) in ABNORMAL_RESETS
}
