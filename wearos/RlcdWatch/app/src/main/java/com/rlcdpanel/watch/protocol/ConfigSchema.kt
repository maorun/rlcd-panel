package com.rlcdpanel.watch.protocol

/** Group of a `config.ini` key in the editor. Same groups as `ConfigCategory` of the macOS app. */
enum class ConfigCategory(val title: String) {
    CLIMATE("Climate"), ALERTS("Alerts"), POWER("Power"), SENSORS("Sensors"), BLE("BLE")
}

/** One key with its value rule ([kind], [defaultValue] from [ConfigKeys]) and the texts of the editor. */
data class SchemaEntry(
    val key: String,
    val category: ConfigCategory,
    val kind: ConfigKind,
    val defaultValue: String,
    val unit: String,
    val description: String
)

/** A checked `SET` list or the reason why the input is refused. A refused input sends no command. */
sealed class ConfigPlan {
    data class Commands(val commands: List<String>, val changes: List<ConfigChange>) : ConfigPlan()
    data class Invalid(val problems: Map<String, String>) : ConfigPlan()
}

/**
 * `SET` commands that reset changed keys to the schema default. A key whose default the console cannot send
 * (an empty name) lands in [skipped] with the reason; the other keys still reset.
 */
data class ConfigResetPlan(
    val commands: List<String>,
    val changes: List<ConfigChange>,
    val skipped: Map<String, String>
)

/** End of a batch run. */
data class ConfigRunResult(
    val applied: List<String>,
    /** Key and reply text of the first failed `SET`, null without a failure. */
    val failureKey: String?,
    val failure: String?,
    /** Number of `SET` lines that the run was asked to send; 0 is a pure `CONFIG` read. */
    val requested: Int,
    /** The `CONFIG` reply that follows the batch, null when it failed. */
    val config: ConsoleReply.Frame?
) {
    val ok: Boolean get() = failure == null && config != null
    /** The panel runs with `ble_console=1`. */
    val readOnly: Boolean get() = failure == ConfigSchema.READ_ONLY_TEXT
    val summary: String
        get() = when {
            failure != null -> "$failureKey: $failure. Übernommen: ${applied.size}"
            config == null && requested == 0 -> "CONFIG lesen fehlgeschlagen"
            config == null && "ble_console" in applied -> "Übernommen: ${applied.size}. ble_console geändert, die Konsole kann enden. Neu verbinden und CONFIG lesen"
            config == null -> "Übernommen: ${applied.size}. CONFIG lesen fehlgeschlagen"
            requested == 0 -> "CONFIG gelesen"
            else -> "Übernommen: ${applied.size}"
        }
}

/**
 * Schema of all keys of `DeviceConfigParser::KEYS` with the texts of the macOS `ConfigSchema.entries`. The value
 * rules, the order and the checks come from [ConfigKeys]: the list order is the fixed order of the batch `SET`,
 * and `ble_console` stands last.
 */
object ConfigSchema {
    const val MAX_LINE_BYTES = ConfigKeys.MAX_LINE_BYTES

    /** Text for `ERR READ_ONLY`: the panel runs with `ble_console=1`. */
    const val READ_ONLY_TEXT = "Panel ist schreibgeschützt (ble_console=1). Setze ble_console=2, um Einstellungen zu ändern"

    private fun e(key: String, cat: ConfigCategory, unit: String, text: String): SchemaEntry {
        val base = ConfigKeys.entry(key) ?: throw IllegalStateException("$key missing in ConfigKeys")
        return SchemaEntry(key, cat, base.kind, base.defaultValue, unit, text)
    }

    val entries: List<SchemaEntry> = buildList {
        val c = ConfigCategory.CLIMATE
        add(e("temp_offset_c", c, "°C", "Add this value to the onboard temperature."))
        add(e("usb_temp_offset_c", c, "°C", "Add this value to the onboard temperature on USB power only."))
        add(e("rh_offset_pct", c, "%RH", "Add this value to the onboard humidity."))
        add(e("rh_gain", c, "", "Multiply the raw onboard humidity by this value before the offset. Steps of 0.002."))
        add(e("temp_unit", c, "", "Show the temperature in Celsius or Fahrenheit. The card files and alerts stay in Celsius."))
        add(e("display_invert", c, "", "0 is dark on light. 1 is light on dark."))
        add(e("freeze_alert_limit_c", c, "°C", "Start the freezer alert below this temperature."))
        add(e("mold_alert_limit_rh", c, "%RH", "Count the hours above this humidity for the mold alert."))
        add(e("mold_alert_delay_h", c, "h", "Raise the humidity alert only when the newest hourly samples of the tile are also above the mold limit. 0 is off. A short shower gives no chime."))
        add(e("reception_low_pct", c, "%", "Show the R badge when the BLE reception rate of a slot falls below this value. The badge releases 20 % above it. 0 is off. Lower it for a far sensor."))
        add(e("dry_alert_delay_h", c, "h", "Set the dry air badge only when the newest hourly samples of the tile are also at or below the dry limit. 0 is off. A short dry spell gives no badge."))
        add(e("jump_limit_c", c, "°C", "Hold a packet in the jump guard when it differs by more than this temperature from a stored reading younger than 10 minutes. 0 turns this check off. Raise it for a sensor in the sun or near a heater."))
        add(e("jump_limit_rh", c, "%RH", "Hold a packet in the jump guard when it differs by more than this humidity from a stored reading younger than 10 minutes. 0 turns this check off. With both jump limits 0 the guard is off."))
        add(e("drift_limit_c", c, "°C", "Set a sensor as drifting (OUTLIER in SPREAD and the S badge) when it deviates by more than this temperature from the median of all sensors. The badge releases 0.2 °C below it. Raise it for sensors of a lower class."))
        add(e("drift_limit_rh", c, "%RH", "Set a sensor as drifting (OUTLIER in SPREAD and the S badge) when it deviates by more than this humidity from the median of all sensors. The badge releases 1.0 %RH below it. Raise it for sensors of a lower class."))
        add(e("heat_alert_limit_c", c, "°C", "Start the heat alert at this feels-like temperature. 0 is off."))
        add(e("dry_alert_limit_rh", c, "%RH", "Show the dry air badge at or below this humidity. 0 is off."))
        add(e("window_drop_c", c, "°C", "Show the W badge on a tile that falls by this temperature within 15 minutes. 0 is off. The badge ends when the tile rises by half the limit or after 2 hours."))
        add(e("wall_frsi_pct", c, "%", "Temperature factor of the outer wall for the WALL text of the stats view. Needs the outdoor slot. 0 is off."))
        add(e("ventilate_limit_rh", c, "%RH", "Show the ventilation badge above this humidity."))
        val a = ConfigCategory.ALERTS
        add(e("noise_alert_limit_db", a, "dB", "Start the noise alert at this level. 0 is off."))
        add(e("chime_alert_mask", a, "", "Bit mask of the alerts that sound the chime. Bit 0 is freezer. Bit 7 is heat."))
        add(e("chime_volume_pct", a, "%", "Set the chime volume."))
        add(e("chime_repeat_h", a, "h", "Sound an active alert again after this time. 0 is off."))
        add(e("quiet_hours_start_hour", a, "h", "Set the first hour of the quiet hours."))
        add(e("quiet_hours_end_hour", a, "h", "Set the last hour of the quiet hours."))
        add(e("climate_alert_tiles", a, "", "Bit mask of the tiles that feed the freezer and mold alerts. Bit 0 is onboard. Bits 1 to 10 are the slots."))
        add(e("sensor_battery_limit_pct", a, "%", "Show BAT LOW at or below this sensor battery level. 0 is off."))
        val p = ConfigCategory.POWER
        add(e("wake_interval_s", p, "s", "Set the time between two scans on battery power."))
        add(e("night_wake_interval_s", p, "s", "Set the time between two scans in the quiet hours. 0 uses wake_interval_s."))
        add(e("battery_offset_mv", p, "mV", "Add this value to the cell voltage."))
        add(e("sensor_stale_min", p, "min", "Mark a sensor as stale after this time without a packet."))
        add(e("slot_release_h", p, "h", "Release a slot without a fixed MAC after this time without a packet. 0 is off."))
        add(e("ntp_sync_interval_h", p, "h", "Set the time between two NTP syncs."))
        add(e("log_retention_months", p, "months", "Keep the log files for this time. 0 keeps all files."))
        val s = ConfigCategory.SENSORS
        add(e("mic_spl_offset_db", s, "dB", "Add this value to the microphone level."))
        add(e("outdoor_slot", s, "", "Select the outdoor slot for the ventilation advice. 0 is off."))
        add(e("door_ref_slot", s, "", "Reference slot for door ventilation of slot 1. 0 is off."))
        for (n in 1..10) add(e("slot${n}_temp_offset_c", s, "°C", "Add this value to the slot $n temperature."))
        for (n in 1..10) add(e("slot${n}_rh_offset_pct", s, "%RH", "Add this value to the slot $n humidity."))
        for (n in 1..10) add(e("slot${n}_rh_gain", s, "", "Multiply the raw slot $n humidity by this value before the offset. Steps of 0.002."))
        add(e("onboard_name", s, "", "Name of the onboard tile."))
        for (n in 1..10) add(e("slot${n}_name", s, "", "Name of the slot $n tile."))
        for (n in 1..10) add(e("slot${n}_mac", s, "", "Fixed MAC of slot $n. Empty means no fixed MAC."))
        add(e("slot_auto_assign", s, "", "1 gives the first free slot to each new sensor MAC. 0 lets only a slot with a fixed MAC take a reading."))
        add(e("auto_assign_min_rssi", s, "dBm", "A new sensor MAC with a weaker RSSI gets no free slot. 0 is off. Slots that hold the MAC and fixed MAC slots ignore it."))
        for (n in 1..10) add(e("slot${n}_enabled", s, "", "1 enables sensor slot $n, 0 disables and hides it."))
        val b = ConfigCategory.BLE
        add(e("ble_broadcast_enabled", b, "", "Send the telemetry frame after each scan. 0 is off."))
        add(e("ble_console", b, "", "BLE console on USB power. 1 allows read commands. 2 also allows SET."))
    }

    val keys: List<String> = entries.map { it.key }
    private val byKey: Map<String, SchemaEntry> = entries.associateBy { it.key }

    fun entry(key: String): SchemaEntry? = byKey[key]

    fun entries(category: ConfigCategory): List<SchemaEntry> = entries.filter { it.category == category }

    /** Error text for a value that `DeviceConfigParser` rejects, null for a valid value. */
    fun validate(key: String, value: String): String? = ConfigKeys.validate(key, value)

    /** Differences from [old] to [new] in schema order. A key that a map lacks has the schema default. */
    fun diff(old: Map<String, String>, new: Map<String, String>): List<ConfigChange> = ConfigKeys.diff(old, new)

    /** `SET key=value` lines in the fixed schema order; `ble_console` comes last. Keys outside the schema drop out. */
    fun batch(changes: List<ConfigChange>): List<String> =
        ConfigKeys.batch(changes).map { "SET ${it.key}=${it.value}" }

    /** Problem text by key: a failed validation, a duplicate fixed MAC or a `SET` line above [MAX_LINE_BYTES] bytes. */
    fun problems(changes: List<ConfigChange>, allValues: Map<String, String> = emptyMap()): Map<String, String> =
        ConfigKeys.problems(changes, allValues)

    /**
     * Plan for the [edits] (key to input text) against the cached [current] values. One problem refuses the whole
     * list, so no `SET` reaches the panel. An edit equal to the current value gives no command.
     */
    fun plan(edits: Map<String, String>, current: Map<String, String>): ConfigPlan {
        val changes = diff(current, current + edits.filterKeys { it in byKey })
        val problems = problems(changes, current)
        if (problems.isNotEmpty()) return ConfigPlan.Invalid(problems)
        return ConfigPlan.Commands(batch(changes), changes)
    }

    /**
     * Reset plan for the [changed] keys (reply of `CONFIG CHANGED`): one `SET key=default` for each key whose value
     * differs from the schema default, in the fixed schema order with `ble_console` last. Keys outside the schema drop out.
     */
    fun resetPlan(changed: List<ConfigChangedEntry>): ConfigResetPlan {
        val current = changed.filter { it.key in byKey }.associate { it.key to it.value }
        val defaults = current.mapValues { byKey.getValue(it.key).defaultValue }
        val all = diff(current, defaults)
        val problems = problems(all)
        val ok = all.filter { it.key !in problems }
        return ConfigResetPlan(batch(ok), ok, problems)
    }

    /** Text of a failed reply. `ERR READ_ONLY` gets the clear text for `ble_console=1`. */
    fun failureText(reply: ConsoleReply): String = when {
        reply is ConsoleReply.Status && reply.line.startsWith("ERR READ_ONLY") -> READ_ONLY_TEXT
        reply is ConsoleReply.Status -> reply.line.take(80)
        else -> "Keine gültige Antwort vom Panel. Zustand des Panels unbekannt, CONFIG lesen"
    }

    /**
     * Sends [commands] one by one through [send] (`BleConsoleClient::send`). The first `ERR` or unusable reply drops
     * the remaining `SET` commands. `CONFIG` follows in each case, so the form shows the real state of the panel.
     * Each of [commands] must be a `SET <key>=<value>` line. A line above [MAX_LINE_BYTES] bytes is refused before the send and counts as a failure. The chain differs from
     * `CalibrationBatch.run` on purpose: the editor reads `CONFIG` after a failure, so the form shows the panel state.
     */
    fun run(commands: List<String>, send: (String, (ConsoleReply) -> Unit) -> Unit, done: (ConfigRunResult) -> Unit) {
        val applied = mutableListOf<String>()
        var failureKey: String? = null
        var failure: String? = null
        fun config() {
            send("CONFIG") { reply -> done(ConfigRunResult(applied, failureKey, failure, commands.size, reply as? ConsoleReply.Frame)) }
        }
        fun step(i: Int) {
            if (i >= commands.size) return config()
            val line = commands[i]
            val key = line.removePrefix("SET ").substringBefore('=')
            if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) {
                failureKey = key
                failure = "Befehl länger als $MAX_LINE_BYTES Bytes"
                return config()
            }
            send(line) { reply ->
                if (reply is ConsoleReply.Status && reply.line.startsWith("OK")) {
                    applied.add(key)
                    step(i + 1)
                } else {
                    failureKey = key
                    failure = failureText(reply)
                    config()
                }
            }
        }
        step(0)
    }
}
