package com.rlcdpanel.phone.protocol

/**
 * Pure state machine that reports a rising wall humidity (mold risk) once for each tile.
 *
 * A tile rises at [RISE_RH] or more and releases below [RELEASE_RH]. A rise gives [Result.Notify] unless the
 * same tile notified less than [REPEAT_INTERVAL_MS] ago. A null or non-finite value releases the tile without a result.
 */
class WallWatch(private val clock: () -> Long = { System.currentTimeMillis() }) {

    sealed interface Result {
        data class Notify(val tile: Int, val rh: Double) : Result
    }

    private val risen = BooleanArray(TILE_COUNT) { false }
    private val lastNotified = Array<Long?>(TILE_COUNT) { null }

    /** Feeds the wall humidity of one tile. A tile outside 0 to 5 gives no result. */
    fun update(tile: Int, rh: Double?): Result? {
        if (tile !in risen.indices) return null
        if (rh == null || !rh.isFinite()) {
            risen[tile] = false
            return null
        }
        if (risen[tile]) {
            if (rh < RELEASE_RH) {
                risen[tile] = false
            }
            return null
        }
        if (rh < RISE_RH) return null
        risen[tile] = true
        val now = clock()
        val last = lastNotified[tile]
        if (last != null && now - last in 0 until REPEAT_INTERVAL_MS) {
            return null
        }
        lastNotified[tile] = now
        return Result.Notify(tile, rh)
    }

    companion object {
        const val RISE_RH = 80.0
        const val RELEASE_RH = 77.0
        const val REPEAT_INTERVAL_MS = 6L * 3600L * 1000L
        const val TILE_COUNT = 6
    }
}
