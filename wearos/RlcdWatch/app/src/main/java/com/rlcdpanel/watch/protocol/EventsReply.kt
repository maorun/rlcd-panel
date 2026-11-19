package com.rlcdpanel.watch.protocol

/** One `EVENTS` row. [time] is empty when the RTC time was invalid. */
data class EventRow(val time: String, val event: String, val detail: Int)

/** The parsed `EVENTS` reply: the rows oldest first and the count of events that the panel lost. */
data class EventsLog(val rows: List<EventRow>, val dropped: Int)

/** Port of `EventsReply.parse` of `Replies.swift`. The `EVENTS_DROPPED` row becomes [EventsLog.dropped], not a row. */
object EventsReply {
    const val HEADER = "time,event,detail"
    const val DROPPED_EVENT = "EVENTS_DROPPED"

    private val INT = Regex("^-?[0-9]+$")

    /** [lines] hold `BEGIN EVENTS`, the header and the rows. A bad line throws [ParseException]. */
    fun parse(lines: List<String>): EventsLog {
        if (lines.firstOrNull() != "BEGIN EVENTS") throw ParseException("BEGIN EVENTS")
        if (lines.size < 2 || lines[1] != HEADER) throw ParseException(HEADER)
        val rows = ArrayList<EventRow>()
        var dropped = 0
        for (line in lines.drop(2)) {
            val c = line.split(",")
            val detail = c.getOrNull(2)?.takeIf { INT.matches(it) }?.toIntOrNull()
            if (c.size != 3 || detail == null) throw ParseException(line)
            if (c[1] == DROPPED_EVENT) {
                // The firmware counts lost events as uint16; a negative count is a bad row.
                if (detail < 0) throw ParseException(line)
                dropped += detail
            } else rows.add(EventRow(c[0], c[1], detail))
        }
        return EventsLog(rows, dropped)
    }
}
