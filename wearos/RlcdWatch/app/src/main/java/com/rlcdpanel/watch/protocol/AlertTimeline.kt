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

/** One badge interval from the event ring: a set row paired with the next clear row of the same tile. Times are epoch milliseconds. */
data class BadgeInterval(
    /** Event code: `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` or `SENSOR_DRIFT`. */
    val event: String,
    /** Tile 0 (onboard) to 10 (slot 10), the low byte of the detail. */
    val tile: Int,
    val startMs: Long,
    /** The clear time, or the injected `now` for an open interval. */
    val endMs: Long,
    val isOpen: Boolean,
    /** True when the start is a guess: a clear row without a set row, or an interval that spans an `EVENTS_DROPPED` row. */
    val startUnknown: Boolean
) {
    /** Badge letter of the panel tile. */
    val badge: String get() = AlertTimeline.BADGE_LETTERS[event] ?: "?"
    /** Room of the tile: `Onboard` or `Slot <N>`. */
    val room: String get() = AlertTimeline.roomName(tile)
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
    fun build(rows: List<EventRow>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<AlertInterval> =
        pair(rows, nowMs, zone) { row ->
            when (row.event) {
                "ALERT_RISE" -> row.detail to true
                "ALERT_FALL" -> row.detail to false
                else -> null
            }
        }
            .map { AlertInterval(it.key, it.startMs, it.endMs, it.isOpen, it.startUnknown) }
            .sortedWith(compareBy({ it.startMs }, { it.alert }))

    /** Room of a tile: `Onboard` for tile 0, else `Slot <N>`. */
    fun roomName(tile: Int): String = if (tile == 0) "Onboard" else "Slot $tile"

    /** Events that set and clear a tile badge, with the panel badge letter. */
    val BADGE_LETTERS = mapOf("WINDOW_OPEN" to "W", "WALL_DAMP" to "C", "DRY_AIR" to "D", "SENSOR_DRIFT" to "S")

    /**
     * Pairs each set row of `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` and `SENSOR_DRIFT` with the next clear row of the
     * same badge and tile. The detail low byte is the tile (0 to 10), the high byte the state (1 set, 0 clear). The
     * pairing rules are those of [build]. Rows with an invalid detail and other codes are ignored. The result is
     * sorted by start, then by event code, then by tile. Port of `AlertTimeline.badges` of `AlertTimeline.swift`.
     */
    fun badges(rows: List<EventRow>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<BadgeInterval> =
        pair(rows, nowMs, zone) { row ->
            val detail = row.detail
            if (row.event !in BADGE_LETTERS || detail !in 0..0xFFFF || detail and 0xFF > 10 || detail shr 8 > 1) null
            else (row.event to (detail and 0xFF)) to (detail shr 8 == 1)
        }
            .map { BadgeInterval(it.key.first, it.key.second, it.startMs, it.endMs, it.isOpen, it.startUnknown) }
            .sortedWith(compareBy({ it.startMs }, { it.event }, { it.tile }))

    private class Span<K>(val key: K, val startMs: Long, val endMs: Long, val isOpen: Boolean, val startUnknown: Boolean)

    /** Shared pairing: [classify] gives the key and whether the row sets; null ignores the row. */
    private fun <K> pair(rows: List<EventRow>, nowMs: Long, zone: ZoneId, classify: (EventRow) -> Pair<K, Boolean>?): List<Span<K>> {
        val times = rows.map { parse(it.time, zone) }
        // The `EVENTS_DROPPED` row carries the current RTC time, so it cannot start an interval.
        val firstIndex = rows.indices.firstOrNull { times[it] != null && rows[it].event != EventsReply.DROPPED_EVENT }
            ?: return emptyList()
        val firstTime = times[firstIndex]!!
        class Open(val start: Long, var unknown: Boolean)
        val open = LinkedHashMap<K, Open>()
        val done = ArrayList<Span<K>>()
        for ((index, row) in rows.withIndex()) {
            if (row.event == EventsReply.DROPPED_EVENT) {
                open.values.forEach { it.unknown = true }
                continue
            }
            val (key, isSet) = classify(row) ?: continue
            if (isSet) {
                val time = times[index] ?: continue
                if (key !in open) open[key] = Open(time, false)
            } else {
                val start = open.remove(key)
                // A clear row without a valid time ends the interval, but it has no drawable end.
                val time = times[index] ?: continue
                val from = start?.start ?: firstTime
                done.add(Span(key, from, maxOf(time, from), false, start?.unknown ?: true))
            }
        }
        for ((key, entry) in open) {
            done.add(Span(key, entry.start, maxOf(nowMs, entry.start), true, entry.unknown))
        }
        return done
    }

    /** One timeline line: `<name>: <start> – <end or läuft> (<duration>)`. A guessed start has a leading `~`. */
    fun lineText(interval: AlertInterval, zone: ZoneId = ZoneId.systemDefault()): String {
        fun at(ms: Long) = SHORT.format(Instant.ofEpochMilli(ms).atZone(zone))
        val start = (if (interval.startUnknown) "~" else "") + at(interval.startMs)
        val end = if (interval.isOpen) "läuft" else at(interval.endMs)
        return "${interval.name}: $start – $end (${durationText(interval.durationMs)})"
    }

    /** One badge line: `<letter> <room>: <start> – <end or läuft> (<duration>)`. A guessed start has a leading `~`. */
    fun badgeLineText(interval: BadgeInterval, zone: ZoneId = ZoneId.systemDefault()): String {
        fun at(ms: Long) = SHORT.format(Instant.ofEpochMilli(ms).atZone(zone))
        val start = (if (interval.startUnknown) "~" else "") + at(interval.startMs)
        val end = if (interval.isOpen) "läuft" else at(interval.endMs)
        return "${interval.badge} ${interval.room}: $start – $end (${durationText(interval.durationMs)})"
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
