package com.rlcdpanel.watch.protocol

import java.util.Locale

/** Step grid of the watch stepper for one alert limit: 1 %RH, 1 h, 1 % or 0.5 C, with the off value first. */
object AlertLimitStepper {
    private const val DECIMAL_STEP = 0.5

    /** All values of [limit] in ascending order as the `SET` command sends them. The off value comes first. */
    fun steps(limit: AlertLimits.Limit): List<String> {
        val values = mutableListOf<Double>()
        val off = limit.offValue
        if (off != null && off.toDouble() < limit.min) values.add(off.toDouble())
        if (limit.decimal) {
            var v = limit.min
            while (v <= limit.max + 1e-9) { values.add(v); v += DECIMAL_STEP }
        } else {
            var v = limit.min.toInt()
            while (v <= limit.max.toInt()) { values.add(v.toDouble()); v += 1 }
        }
        return values.map { text(limit, it) }
    }

    private fun text(limit: AlertLimits.Limit, value: Double): String =
        if (limit.decimal) String.format(Locale.ROOT, "%.1f", value) else value.toInt().toString()

    /** Text of the stepper at the start: the cached value (on the grid format), else the firmware default. */
    fun startText(limit: AlertLimits.Limit, config: PanelConfig): String =
        text(limit, AlertLimits.shown(limit, config))

    /** Value one step in [direction] (+1 or -1) from [current]; stops at the ends. An off-grid value snaps first. */
    fun move(limit: AlertLimits.Limit, current: String, direction: Int): String {
        val steps = steps(limit)
        val value = current.trim().replace(',', '.').toDoubleOrNull() ?: return steps.first()
        val numbers = steps.map { it.toDouble() }
        val index = when {
            direction > 0 -> numbers.indexOfFirst { it > value + 1e-9 }.let { if (it < 0) numbers.lastIndex else it }
            else -> numbers.indexOfLast { it < value - 1e-9 }.let { if (it < 0) 0 else it }
        }
        return steps[index]
    }
}
