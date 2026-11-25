package com.rlcdpanel.watch.protocol

import java.time.ZoneId
import java.util.Locale

/**
 * Advice to raise `jump_limit_c` when the jump guard drops many readings of one slot.
 * [proposal] is the `SET` key and value; [text] is the one-line message for the sensor health screen.
 */
data class JumpAdvice(
    val slot: Int,
    val drops: Int,
    val currentC: Double,
    val proposedC: Double
) {
    val proposal: CalibrationProposal get() = CalibrationProposal(JumpAdvice.KEY, format(proposedC))

    fun text(room: String): String =
        "Sprung-Filter verwirft Messwerte von $room: $drops in 24 h. Grenze ${format(currentC)} → ${format(proposedC)} °C"

    companion object {
        const val KEY = "jump_limit_c"
        const val EVENT = "JUMP_DROP"
        /** Drops of one slot in the window that give an advice. */
        const val MIN_DROPS = 3
        const val WINDOW_MS = 24L * 60 * 60 * 1000
        const val STEP_C = 2.0
        const val MAX_C = 20.0
        private const val SLOT_COUNT = 10

        private fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        /**
         * One advice for each slot with [MIN_DROPS] or more `JUMP_DROP` rows in the newest 24 hours before [nowMs],
         * in slot order. `jump_limit_c=0` (guard check off), an unreadable value and a limit that already
         * is [MAX_C] give none. A row without a valid time and a detail outside slot 1 to 10 do not count.
         * A missing or unreadable `jump_limit_c` in [config] (no cached `CONFIG`) gives none.
         * [rows] come from the `EVENTS` reply. The panel ring holds 16 events and the hourly card write clears it,
         * so only drops since the last write can count; the 24-hour window is an upper bound.
         * Port of the rules of `JumpAdvice.evaluate` of the macOS app.
         */
        fun evaluate(rows: List<EventRow>, config: PanelConfig, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<JumpAdvice> {
            // A missing key means no cached `CONFIG`; a proposal on the default could lower a raised limit.
            val current = config.values[KEY]?.trim()?.toDoubleOrNull() ?: return emptyList()
            if (current <= 0.0 || current >= MAX_C) return emptyList()
            val proposed = minOf(current + STEP_C, MAX_C)
            val counts = HashMap<Int, Int>()
            for (row in rows) {
                if (row.event != EVENT || row.detail !in 1..SLOT_COUNT) continue
                val time = AlertTimeline.parse(row.time, zone) ?: continue
                if (time > nowMs - WINDOW_MS) counts[row.detail] = (counts[row.detail] ?: 0) + 1
            }
            return counts.entries.filter { it.value >= MIN_DROPS }.sortedBy { it.key }
                .map { JumpAdvice(it.key, it.value, current, proposed) }
        }
    }
}
