package com.rlcdpanel.watch.protocol

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlin.math.max
import kotlin.math.roundToInt

/** Finds missing hours in the rows of one climate log, for example after an empty battery or a card fault. Port of `LogGaps.swift`. */
object LogGaps {
    /** The panel writes one row each hour, so a step above 90 minutes lost at least one row. */
    const val DEFAULT_MIN_GAP_MINUTES = 90

    /** One step between two neighbour rows that is longer than the limit. */
    data class Gap(
        /** Time stamp of the last row before the gap (panel local time). */
        val start: String,
        /** Time stamp of the first row after the gap. */
        val end: String,
        /** Hourly rows that the gap lost (at least 1). */
        val missingHours: Int
    )

    data class Report(
        val gaps: List<Gap>,
        /** Rows with a readable time stamp. */
        val rowCount: Int,
        val missingHours: Int,
        /** Rows divided by rows plus missing hours, 0 to 100. 0 without rows. */
        val coveragePct: Double
    )

    private val PANEL_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    private class Stamp(val text: String, val real: Instant, val wall: Instant)

    private fun stamp(timestamp: String): Stamp? = try {
        val local = LocalDateTime.parse(timestamp, FORMAT)
        Stamp(timestamp, local.atZone(PANEL_ZONE).toInstant(), local.toInstant(ZoneOffset.UTC))
    } catch (e: DateTimeParseException) {
        null
    }

    fun find(rows: List<TailRow>, minGapMinutes: Int = DEFAULT_MIN_GAP_MINUTES): List<Gap> =
        report(rows, minGapMinutes).gaps

    /**
     * Sorts the rows by time first. A row with an unreadable time stamp is skipped. A step above [minGapMinutes]
     * gives one gap; a step of exactly [minGapMinutes] gives none. A limit below 1 minute counts as 1 minute.
     * The step is the smaller of the real and the wall-clock difference: the repeated hour of the October change
     * (two rows can share one time stamp) gives no false gap. A real gap across the October change counts one hour less.
     */
    fun report(rows: List<TailRow>, minGapMinutes: Int = DEFAULT_MIN_GAP_MINUTES): Report {
        val stamps = rows.mapNotNull { stamp(it.timestamp) }.sortedWith(compareBy<Stamp>({ it.real }, { it.wall }))
        val limitSeconds = max(minGapMinutes, 1) * 60L
        val gaps = mutableListOf<Gap>()
        for (i in 1 until stamps.size) {
            val a = stamps[i - 1]
            val b = stamps[i]
            val step = minOf(Duration.between(a.real, b.real).seconds, Duration.between(a.wall, b.wall).seconds)
            if (step <= limitSeconds) continue
            gaps += Gap(a.text, b.text, max(1, (step / 3600.0).roundToInt() - 1))
        }
        val missing = gaps.sumOf { it.missingHours }
        val coverage = if (stamps.isEmpty()) 0.0 else stamps.size.toDouble() / (stamps.size + missing) * 100
        return Report(gaps, stamps.size, missing, coverage)
    }

    /** `Keine Lücken` or `<N> h fehlen in <M> Lücken`. */
    fun summaryText(report: Report): String = when {
        report.gaps.isEmpty() -> "Keine Lücken"
        report.gaps.size == 1 -> "${report.missingHours} h fehlen in 1 Lücke"
        else -> "${report.missingHours} h fehlen in ${report.gaps.size} Lücken"
    }

    fun gapLine(gap: Gap): String = "${gap.start} bis ${gap.end}: ${gap.missingHours} h"
}
