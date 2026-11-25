package com.rlcdpanel.watch.protocol

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** One alert interval from the event ring: a rise paired with the next fall of the same alert bit. Times are epoch milliseconds. */
data class AlertInterval(
    /** Alert bit mask as `EventText` reads the detail (1, 2, 4, ... 128). */
    val alert: Int,
    val startMs: Long,
    /** The fall time, or the injected `now` for an open interval. */
    val endMs: Long,
    val isOpen: Boolean,
    /** True when the start is a guess: a fall without a rise, or an interval that spans an `EVENTS_DROPPED` row. */
    val startUnknown: Boolean
) {
    val name: String get() = AlertTimeline.ALERT_NAMES[alert] ?: "ALERT $alert"
    val durationMs: Long get() = endMs - startMs
}

/** Port of `AlertTimeline.build` of `AlertTimeline.swift`. */
object AlertTimeline {
    /** Alert names by bit mask (`ALERT_*` of `audio_alert.h`). `AlertTimelineTest` pins them to `EventText`, which stays private for the wearos sync. */
    val ALERT_NAMES = mapOf(
        1 to "FREEZER", 2 to "HUMIDITY", 4 to "NOISE", 8 to "SENSOR_LOST",
        16 to "CHARGE_FULL", 32 to "VENTILATE", 64 to "SENSOR_BATTERY", 128 to "HEAT"
    )

    private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.ROOT)

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    /**
     * Panel local time of an event row (`YYYY-MM-DD hh:mm:ss`) as epoch milliseconds; null for an empty or bad time.
     * The panel RTC holds local time without a zone, so [zone] reads it. A panel in another zone shifts the intervals.
     */
    fun parse(time: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        LocalDateTime.parse(time, FORMAT).atZone(zone).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * Pairs each `ALERT_RISE` with the next `ALERT_FALL` of the same alert bit. An alert without a fall stays open
     * until [nowMs]. A fall without a rise starts at the first row with a valid time and has `startUnknown`; an
     * `EVENTS_DROPPED` row sets `startUnknown` on each interval that is open at that row. A fall with a bad time
     * closes its alert without an interval. Other codes and rows without a valid time are ignored. A second rise of
     * an open alert keeps the first start. The result is sorted by start, then by alert bit.
     * `EventsReply.parse` moves `EVENTS_DROPPED` rows out of the rows, so the app path never meets one; the branch keeps
     * parity with the Swift original for direct callers. A lost fall keeps the first rise start, as in Swift.
     * The ring drops its oldest events first, so a lost rise shows as a fall without a rise (`startUnknown`).
     */
    fun build(rows: List<EventRow>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<AlertInterval> {
        val times = rows.map { parse(it.time, zone) }
        // The `EVENTS_DROPPED` row carries the current RTC time, so it cannot start an interval.
        val firstIndex = rows.indices.firstOrNull { times[it] != null && rows[it].event != EventsReply.DROPPED_EVENT }
            ?: return emptyList()
        val firstTime = times[firstIndex]!!
        class Open(val start: Long, var unknown: Boolean)
        val open = LinkedHashMap<Int, Open>()
        val done = ArrayList<AlertInterval>()
        for ((index, row) in rows.withIndex()) {
            when (row.event) {
                EventsReply.DROPPED_EVENT -> open.values.forEach { it.unknown = true }
                "ALERT_RISE" -> {
                    val time = times[index] ?: continue
                    if (row.detail !in open) open[row.detail] = Open(time, false)
                }
                "ALERT_FALL" -> {
                    val start = open.remove(row.detail)
                    // A fall without a valid time ends the alert, but the interval has no drawable end.
                    val time = times[index] ?: continue
                    val from = start?.start ?: firstTime
                    done.add(AlertInterval(row.detail, from, maxOf(time, from), false, start?.unknown ?: true))
                }
            }
        }
        for ((alert, entry) in open) {
            done.add(AlertInterval(alert, entry.start, maxOf(nowMs, entry.start), true, entry.unknown))
        }
        return done.sortedWith(compareBy({ it.startMs }, { it.alert }))
    }

    /** One timeline line: `<name>: <start> – <end or läuft> (<duration>)`. A guessed start has a leading `~`. */
    fun lineText(interval: AlertInterval, zone: ZoneId = ZoneId.systemDefault()): String {
        fun at(ms: Long) = SHORT.format(Instant.ofEpochMilli(ms).atZone(zone))
        val start = (if (interval.startUnknown) "~" else "") + at(interval.startMs)
        val end = if (interval.isOpen) "läuft" else at(interval.endMs)
        return "${interval.name}: $start – $end (${durationText(interval.durationMs)})"
    }

    /** Duration text such as `45 min`, `2 h 05 min` or `1 d 3 h`; below one minute `< 1 min`. */
    fun durationText(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) / 60_000)
        return when {
            minutes < 1 -> "< 1 min"
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> "${minutes / 60} h ${"%02d".format(Locale.ROOT, minutes % 60)} min"
            else -> "${minutes / (24 * 60)} d ${(minutes / 60) % 24} h"
        }
    }
}
