package com.rlcdpanel.watch.protocol

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Rises and active hours of one alert in one calendar month. */
data class AlertMonthStat(
    val alert: Int,
    /** `YYYY-MM` in the phone time zone (the panel time reads as local time, see `AlertTimeline.parse`). */
    val month: String,
    /** Intervals with a known start that begin in this month. */
    val rises: Int,
    /** Hours of the intervals with a known start that fall in this month. */
    val hours: Double,
    /** Intervals with `startUnknown` that begin in this month; they add to neither [rises] nor [hours]. */
    val unknownStarts: Int
) {
    val name: String get() = AlertTimeline.ALERT_NAMES[alert] ?: "ALERT $alert"
}

/** Sets and active hours of one tile badge in one calendar month. */
data class BadgeMonthStat(
    /** Event code: `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR`, `SENSOR_DRIFT` or `HEAT_TILE`. */
    val event: String,
    /** Tile 0 (onboard) to 10 (slot 10). */
    val tile: Int,
    val month: String,
    val rises: Int,
    val hours: Double,
    val unknownStarts: Int
) {
    val badge: String get() = AlertTimeline.BADGE_LETTERS[event] ?: "?"
    val room: String get() = AlertTimeline.roomName(tile)
}

/** Monthly statistics of the alert and badge intervals; port of `AlertStats.build` of `AlertStats.swift`. */
object EventStats {
    private class Acc(var rises: Int = 0, var hours: Double = 0.0, var unknown: Int = 0)

    /** Alert statistics sorted by month, then alert bit. */
    fun alerts(intervals: List<AlertInterval>, zone: ZoneId = ZoneId.systemDefault()): List<AlertMonthStat> =
        accumulate(intervals.map { Triple(it.alert, it.startMs..it.endMs, it.startUnknown) }, zone)
            .map { (key, acc) -> AlertMonthStat(key.first, key.second, acc.rises, acc.hours, acc.unknown) }
            .sortedWith(compareBy({ it.month }, { it.alert }))

    /** Badge statistics sorted by month, then event code, then tile. */
    fun badges(intervals: List<BadgeInterval>, zone: ZoneId = ZoneId.systemDefault()): List<BadgeMonthStat> =
        accumulate(intervals.map { Triple(it.event to it.tile, it.startMs..it.endMs, it.startUnknown) }, zone)
            .map { (key, acc) -> BadgeMonthStat(key.first.first, key.first.second, key.second, acc.rises, acc.hours, acc.unknown) }
            .sortedWith(compareBy({ it.month }, { it.event }, { it.tile }))

    /**
     * Splits each interval at the month boundaries. The rise counts in the month of the start; an interval with an
     * unknown start counts only in `unknown`, so a guessed start adds no hours.
     */
    private fun <K> accumulate(
        items: List<Triple<K, LongRange, Boolean>>,
        zone: ZoneId
    ): Map<Pair<K, String>, Acc> {
        val result = LinkedHashMap<Pair<K, String>, Acc>()
        for ((key, span, startUnknown) in items) {
            val start = Instant.ofEpochMilli(span.first).atZone(zone)
            val acc = result.getOrPut(key to monthKey(start)) { Acc() }
            if (startUnknown) {
                acc.unknown += 1
                continue
            }
            acc.rises += 1
            var cursor = start
            val end = Instant.ofEpochMilli(span.last).atZone(zone)
            while (cursor.isBefore(end)) {
                val nextMonth = cursor.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone)
                val segmentEnd = if (nextMonth.isBefore(end)) nextMonth else end
                result.getOrPut(key to monthKey(cursor)) { Acc() }.hours +=
                    (segmentEnd.toInstant().toEpochMilli() - cursor.toInstant().toEpochMilli()) / 3_600_000.0
                cursor = segmentEnd
            }
        }
        return result
    }

    private fun monthKey(time: ZonedDateTime): String =
        String.format(Locale.ROOT, "%04d-%02d", time.year, time.monthValue)

    private fun counts(rises: Int, hours: Double, unknown: Int): String =
        String.format(Locale.ROOT, "%d x, %.1f h", rises, hours) + if (unknown > 0) ", $unknown ohne Start" else ""

    /** One line: `<month> <name>: <rises> x, <hours> h`, with `, <N> ohne Start` for guessed starts. */
    fun lineText(stat: AlertMonthStat): String = "${stat.month} ${stat.name}: ${counts(stat.rises, stat.hours, stat.unknownStarts)}"

    /** One line: `<month> <letter> <room>: <rises> x, <hours> h`. */
    fun badgeLineText(stat: BadgeMonthStat): String =
        "${stat.month} ${stat.badge} ${stat.room}: ${counts(stat.rises, stat.hours, stat.unknownStarts)}"
}
