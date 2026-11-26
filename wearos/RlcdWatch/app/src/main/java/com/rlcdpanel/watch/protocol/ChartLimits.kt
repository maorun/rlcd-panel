package com.rlcdpanel.watch.protocol

/** One alert limit that a chart draws as a dashed line. */
data class ChartLimit(val key: String, val label: String, val value: Double, val axis: Axis) {
    enum class Axis { HUMIDITY, TEMPERATURE }
}

/** Alert limit lines of the history charts, read from the cached `CONFIG` values. Sends no console command. */
object ChartLimits {
    /** [zeroIsOff] is true for the keys where 0 turns the alert off. `freeze_alert_limit_c` has no off value: 0.0 is a valid limit. */
    private data class Definition(val key: String, val label: String, val axis: ChartLimit.Axis, val zeroIsOff: Boolean)

    private val definitions = listOf(
        Definition("mold_alert_limit_rh", "Mold", ChartLimit.Axis.HUMIDITY, false),
        Definition("dry_alert_limit_rh", "Dry", ChartLimit.Axis.HUMIDITY, true),
        Definition("freeze_alert_limit_c", "Freeze", ChartLimit.Axis.TEMPERATURE, false),
        Definition("heat_alert_limit_c", "Heat (feels-like)", ChartLimit.Axis.TEMPERATURE, true)
    )

    /**
     * One line for each active limit. A value of 0 for `dry_alert_limit_rh` and `heat_alert_limit_c` (off), a value outside the
     * `ConfigSchema` range, a missing key and a missing config give no line.
     */
    fun from(config: PanelConfig?): List<ChartLimit> {
        if (config == null) return emptyList()
        return definitions.mapNotNull { d ->
            val text = config.values[d.key] ?: return@mapNotNull null
            if (ConfigSchema.validate(d.key, text) != null) return@mapNotNull null
            val value = CalibrationKeys.parseNumber(text) ?: return@mapNotNull null
            if (d.zeroIsOff && value == 0.0) return@mapNotNull null
            ChartLimit(d.key, d.label, value, d.axis)
        }
    }

    fun humidity(limits: List<ChartLimit>): List<ChartLimit> = limits.filter { it.axis == ChartLimit.Axis.HUMIDITY }

    fun temperature(limits: List<ChartLimit>): List<ChartLimit> = limits.filter { it.axis == ChartLimit.Axis.TEMPERATURE }
}
