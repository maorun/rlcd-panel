package com.rlcdpanel.watch.protocol

/** Text of the week mold hours of one sensor. */
object WeekMoldText {
    /** `Schimmel <N> h` with the sum of `moldHours` over the days with a value; `null` when no day has one. */
    fun summary(rows: List<WeekRow>): String? {
        val values = rows.mapNotNull { it.moldHours }
        return if (values.isEmpty()) null else "Schimmel ${values.sum()} h"
    }
}
