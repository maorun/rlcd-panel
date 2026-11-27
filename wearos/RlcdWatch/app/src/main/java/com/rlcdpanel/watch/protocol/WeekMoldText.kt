package com.rlcdpanel.watch.protocol

/** Text of the week mold hours and dry hours of one sensor. */
object WeekMoldText {
    /** `Schimmel <N> h` with the sum of `moldHours` over the days with a value; `null` when no day has one. */
    fun summary(rows: List<WeekRow>): String? = sum("Schimmel", rows.mapNotNull { it.moldHours })

    /** `Trocken <N> h` with the sum of `dryHours` over the days with a value; `null` when no day has one. */
    fun dry(rows: List<WeekRow>): String? = sum("Trocken", rows.mapNotNull { it.dryHours })

    /** `HEAT <N> h` with the sum of `heatHours` over the days with a value; `null` when no day has one. */
    fun heat(rows: List<WeekRow>): String? = sum("HEAT", rows.mapNotNull { it.heatHours })

    private fun sum(label: String, values: List<Int>): String? =
        if (values.isEmpty()) null else "$label ${values.sum()} h"
}
