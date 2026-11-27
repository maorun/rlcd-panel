package com.rlcdpanel.watch.protocol

import java.util.Locale

/**
 * Proposal for `outdoor_slot` from the `WEEK` reply: the slot with the widest daily temperature range that is
 * colder at night than the onboard sensor. Port of `OutdoorProposal.swift`; a pure function of the reply and the
 * active config.
 */
data class OutdoorProposal(
    val slot: Int,
    val name: String,
    /** Mean daily range (maximum minus minimum) in C. */
    val rangeC: Double,
    /** Mean daily range of the next slot in C, 0 without another slot. */
    val nextRangeC: Double,
    val minC: Double,
    val onboardMinC: Double
) {
    val text: String
        get() = String.format(
            Locale.US,
            "Outdoor slot %d (%s): range %.1f C, next %.1f C, minimum %.1f C, onboard minimum %.1f C",
            slot, name, rangeC, nextRangeC, minC, onboardMinC
        )

    /** The console line of the apply button. */
    val setLine: String get() = "SET outdoor_slot=$slot"

    private class Stats(val range: Double, val minimum: Double)

    companion object {
        /** The registry slots 1 to 10. */
        private val SLOTS = 1..10

        /** A slot needs at least this many days with a temperature pair. */
        const val MIN_DAYS = 3
        /** The best range must be at least this factor above the range of the next slot. */
        const val RANGE_FACTOR = 2.0

        private fun stats(rows: List<WeekRow>, sensor: Int): Stats? {
            val pairs = rows.filter { it.sensor == sensor }.mapNotNull { r ->
                val lo = r.temperatureMinC
                val hi = r.temperatureMaxC
                if (lo != null && hi != null) lo to hi else null
            }
            if (pairs.size < MIN_DAYS) return null
            val n = pairs.size.toDouble()
            return Stats(pairs.sumOf { it.second - it.first } / n, pairs.sumOf { it.first } / n)
        }

        /** Null without a clear candidate, without a `CONFIG` reply, or when `outdoor_slot` already holds the slot. */
        fun propose(week: List<WeekRow>, config: PanelConfig?): OutdoorProposal? {
            if (config == null) return null
            val onboard = stats(week, 0) ?: return null
            val ranked = SLOTS.mapNotNull { slot -> stats(week, slot)?.let { slot to it } }
                .sortedByDescending { it.second.range }
            val best = ranked.firstOrNull() ?: return null
            if (best.second.range <= 0) return null
            val next = if (ranked.size > 1) ranked[1].second.range else 0.0
            if (best.second.range < RANGE_FACTOR * next || best.second.minimum >= onboard.minimum) return null
            if (config.values["outdoor_slot"]?.trim() == best.first.toString()) return null
            return OutdoorProposal(best.first, config.tileName(best.first), best.second.range, next, best.second.minimum, onboard.minimum)
        }
    }
}
