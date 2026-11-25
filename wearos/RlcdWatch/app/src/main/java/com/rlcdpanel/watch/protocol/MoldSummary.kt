package com.rlcdpanel.watch.protocol

/**
 * Mold figures of one room over the rows of a `DAYS` reply. [totalHours] is `null` when no row holds a mold value
 * for the room (the panel logged none); then the screen shows `--`. [worstDate] and [worstHours] are `null`
 * without a day that has mold hours.
 */
data class MoldRoomSummary(
    /** 0 onboard, 1 to 10 slots. */
    val sensor: Int,
    val name: String,
    val totalHours: Int?,
    /** Number of days with at least one mold hour. */
    val moldDays: Int,
    /** `YYYY-MM-DD` of the day with the most mold hours; on a tie the newest day. */
    val worstDate: String?,
    val worstHours: Int?
)

/** Builds the mold summary of the watch from the rows of one `DAYS 31` reply. */
object MoldSummary {
    /** Command of the screen: the newest 31 daily rows. */
    const val COMMAND = "DAYS 31"
    const val EMPTY = "--"

    /**
     * One entry for each room that holds a value in [rows] (`DaysReply.sensors`), sorted by [MoldRoomSummary.totalHours],
     * highest first. A room without any mold value comes last; equal sums keep the sensor order.
     */
    fun build(rows: List<DaysRow>, config: PanelConfig): List<MoldRoomSummary> =
        DaysReply.sensors(rows)
            .map { s -> room(s, config.tileName(s), rows) }
            .sortedWith(compareByDescending<MoldRoomSummary> { it.totalHours ?: -1 }.thenBy { it.sensor })

    private fun room(sensor: Int, name: String, rows: List<DaysRow>): MoldRoomSummary {
        var total: Int? = null
        var days = 0
        var worstDate: String? = null
        var worstHours: Int? = null
        for (row in rows) {
            val hours = row.sensors[sensor].moldHours ?: continue
            total = (total ?: 0) + hours
            if (hours > 0) {
                days++
                // Rows run oldest first, so >= keeps the newest day on a tie.
                if (worstHours == null || hours >= worstHours) {
                    worstHours = hours
                    worstDate = row.date
                }
            }
        }
        return MoldRoomSummary(sensor, name, total, days, worstDate, worstHours)
    }

    /** `<sum> h`, or `--` without a mold value. */
    fun totalText(room: MoldRoomSummary): String = room.totalHours?.let { "$it h" } ?: EMPTY

    /** `<n> Tage`, or `--` without a mold value. */
    fun daysText(room: MoldRoomSummary): String =
        if (room.totalHours == null) EMPTY else if (room.moldDays == 1) "1 Tag" else "${room.moldDays} Tage"

    /** `MM-DD <h> h` of the worst day, or `--` without a day that has mold hours. */
    fun worstText(room: MoldRoomSummary): String {
        val date = room.worstDate ?: return EMPTY
        return "${DaysReply.dayLabel(date)} ${room.worstHours} h"
    }
}
