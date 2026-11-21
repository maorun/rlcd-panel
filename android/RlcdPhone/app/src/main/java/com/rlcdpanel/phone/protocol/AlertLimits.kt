package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.finder.SlotAssignment
import java.util.Locale

/** The seven alert limit keys of `config.ini` with the ranges of `DeviceConfigParser` (`device_config.h`). */
object AlertLimits {
    /** One key. [offValue] is the value that switches the limit off outside [min]..[max], null without one. */
    data class Limit(
        val key: String,
        val label: String,
        val unit: String,
        val min: Double,
        val max: Double,
        val default: Double,
        val decimal: Boolean = false,
        val offValue: Int? = null
    ) {
        /** Range text for the hint under the field. */
        val rangeText: String
            get() {
                val range = if (decimal) "${format(min)} bis ${format(max)}" else "${min.toInt()} bis ${max.toInt()}"
                return if (offValue != null && min.toInt() != offValue) "$offValue (aus) oder $range $unit" else "$range $unit"
            }
    }

    /** Fixed order of the keys and of the `SET` commands. */
    val LIMITS: List<Limit> = listOf(
        Limit("mold_alert_limit_rh", "Schimmel-Alarm", "%RH", 40.0, 95.0, 70.0),
        Limit("mold_alert_delay_h", "Verzögerung Schimmelalarm (h)", "h", 0.0, 12.0, 0.0),
        Limit("dry_alert_limit_rh", "Trockene Luft", "%RH", 20.0, 45.0, 0.0, offValue = 0),
        Limit("freeze_alert_limit_c", "Frost-Alarm", "°C", -20.0, 15.0, 0.0, decimal = true),
        Limit("heat_alert_limit_c", "Hitze-Alarm", "°C", 27.0, 45.0, 0.0, offValue = 0),
        Limit("ventilate_limit_rh", "Lüften-Grenze", "%RH", 50.0, 80.0, 60.0),
        Limit("sensor_battery_limit_pct", "Sensor-Batterie", "%", 0.0, 50.0, 10.0, offValue = 0)
    )

    private val INT_TEXT = Regex("^-?[0-9]{1,4}$")
    private val DECIMAL_TEXT = Regex("^-?[0-9]{1,3}(\\.[0-9])?$")

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    /** Parsed number of [text] (a comma counts as a decimal point), null when [limit] does not accept the form. */
    private fun number(limit: Limit, text: String): Double? {
        val t = text.trim().replace(',', '.')
        return if (limit.decimal) {
            if (DECIMAL_TEXT.matches(t)) t.toDoubleOrNull() else null
        } else {
            if (INT_TEXT.matches(t)) t.toIntOrNull()?.toDouble() else null
        }
    }

    /** Error text for [text] against the firmware range of [limit], null when the value is valid. */
    fun error(limit: Limit, text: String): String? {
        val value = number(limit, text) ?: return "${limit.label}: ${if (limit.decimal) "Zahl mit höchstens einer Nachkommastelle" else "ganze Zahl"} nötig"
        val off = limit.offValue
        if (off != null && value == off.toDouble()) return null
        return if (value < limit.min || value > limit.max) "${limit.label}: erlaubt ${limit.rangeText}" else null
    }

    /** Value text of [limit] as the `SET` command sends it. Call only after [error] gave null. */
    fun wire(limit: Limit, text: String): String {
        val value = number(limit, text)!!
        return if (limit.decimal) format(value) else value.toInt().toString()
    }

    /** Cached value of [limit] in [config]; null when the key is missing or not a number (value unknown). */
    fun cached(limit: Limit, config: PanelConfig): Double? =
        config.values[limit.key]?.trim()?.toDoubleOrNull()

    /** Value that the screen shows for [limit]: the cached value, else the firmware default. */
    fun shown(limit: Limit, config: PanelConfig): Double = cached(limit, config) ?: limit.default

    /** The edit controls show only on console level 2; lower levels answer `ERR READ_ONLY` to `SET`. */
    fun canEdit(config: PanelConfig): Boolean = config.canWrite

    /** A checked command list or the first reason why the input is refused. */
    sealed class Plan {
        data class Commands(val commands: List<String>) : Plan()
        data class Invalid(val reason: String) : Plan()
    }

    /**
     * `SET <key>=<value>` for each key of [edits] (key to input text) whose valid value differs from [config], in the
     * order of [LIMITS]. A value equal to the cached value and a key without an edit give no command. A key without a
     * cached value is unknown, so its edit always sends. One invalid value refuses the whole list, so no `SET`
     * reaches the panel.
     */
    fun plan(edits: Map<String, String>, config: PanelConfig): Plan {
        val commands = mutableListOf<String>()
        for (limit in LIMITS) {
            val text = edits[limit.key] ?: continue
            error(limit, text)?.let { return Plan.Invalid(it) }
            if (number(limit, text) == cached(limit, config)) continue
            commands.add("SET ${limit.key}=${wire(limit, text)}")
        }
        return Plan.Commands(commands)
    }

    /** Sends [commands] one by one, then `CONFIG`. The first `ERR` stops the batch before `CONFIG`. */
    fun run(commands: List<String>, send: (String, (ConsoleReply) -> Unit) -> Unit, done: (SlotAssignment.Result) -> Unit) =
        SlotAssignment.run(commands, send, done)
}
