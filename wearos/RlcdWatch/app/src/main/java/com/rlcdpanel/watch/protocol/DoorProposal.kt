package com.rlcdpanel.watch.protocol

import java.util.Locale

/**
 * Proposal for `door_ref_slot` from the `WEEK` reply: the slot with the lowest mean daily humidity maximum, at
 * least 3 %RH drier than the next slot and drier than slot 1 (the door room). Port of `DoorProposal.swift`; a pure
 * function of the reply and the active config. The outdoor slot is no candidate.
 */
data class DoorProposal(
    val slot: Int,
    val name: String,
    /** Mean daily humidity maximum in %RH. */
    val meanMaxPct: Double,
    /** Mean daily humidity maximum of the next slot in %RH. */
    val nextMaxPct: Double
) {
    val text: String
        get() = String.format(Locale.US, "Door reference slot %d (%s): %.1f %%RH, next %.1f %%RH", slot, name, meanMaxPct, nextMaxPct)

    /** The console line of the apply button. */
    val setLine: String get() = "SET door_ref_slot=$slot"

    companion object {
        /** The registry slots 1 to 10. */
        private val SLOTS = 1..10

        /** Slot 1 and each candidate need at least this many days with a humidity maximum. */
        const val MIN_DAYS = 3
        /** The best mean maximum must be at least this far (%RH) below the mean of the next slot. */
        const val MIN_GAP_PCT = 3.0

        private fun meanMax(rows: List<WeekRow>, sensor: Int): Double? {
            val values = rows.filter { it.sensor == sensor }.mapNotNull { it.humidityMaxPct }
            if (values.size < MIN_DAYS) return null
            return values.sum() / values.size
        }

        /** Null without a clear candidate, without a `CONFIG` reply, or when `door_ref_slot` already holds the slot. */
        fun propose(week: List<WeekRow>, config: PanelConfig?): DoorProposal? {
            if (config == null) return null
            val door = meanMax(week, 1) ?: return null
            val outdoor = config.values["outdoor_slot"]?.trim()?.toIntOrNull() ?: 0
            val ranked = SLOTS.filter { it != 1 && it != outdoor }
                .mapNotNull { slot -> meanMax(week, slot)?.let { slot to it } }
                .sortedBy { it.second }
            if (ranked.size < 2) return null
            val best = ranked[0]
            val next = ranked[1].second
            if (next - best.second < MIN_GAP_PCT || best.second >= door) return null
            if (config.values["door_ref_slot"]?.trim() == best.first.toString()) return null
            return DoorProposal(best.first, config.tileName(best.first), best.second, next)
        }
    }
}
