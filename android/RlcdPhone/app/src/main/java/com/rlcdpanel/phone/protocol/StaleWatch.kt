package com.rlcdpanel.phone.protocol

/**
 * Pure state machine that detects a sensor tile which stopped to deliver valid readings.
 *
 * [update] takes the tile index (0 to 10) and the valid bit of one frame. A tile that was valid once
 * and stays invalid for [LOST_AFTER_MS] gives [Result.Lost] once. The next valid frame gives [Result.Back].
 * A tile that was never valid and a tile outside 0 to 10 give no result.
 */
class StaleWatch(private val clock: () -> Long = { System.currentTimeMillis() }) {

    sealed class Result {
        abstract val tile: Int
        data class Lost(override val tile: Int) : Result()
        data class Back(override val tile: Int) : Result()
    }

    private val lastValid = arrayOfNulls<Long>(TILE_COUNT)
    private val lost = BooleanArray(TILE_COUNT)

    fun update(tile: Int, valid: Boolean): Result? {
        if (tile !in 0 until TILE_COUNT) return null
        val now = clock()
        if (valid) {
            lastValid[tile] = now
            if (!lost[tile]) return null
            lost[tile] = false
            return Result.Back(tile)
        }
        val last = lastValid[tile] ?: return null
        if (lost[tile]) return null
        val age = now - last
        if (age < 0) {
            // A clock that ran back gives no report. Anchor the last valid frame to the new time.
            lastValid[tile] = now
            return null
        }
        if (age < LOST_AFTER_MS) return null
        lost[tile] = true
        return Result.Lost(tile)
    }

    /** Feeds the tiles of one [frame] (page 0: tiles 0 to 5, page 1: tiles 6 to 10). Returns each result. */
    fun updateFrame(frame: TelemetryFrame): List<Result> {
        val offset = frame.page * TelemetryFrame.TILES_PER_PAGE
        return frame.tiles.withIndex().mapNotNull { (i, tile) -> update(offset + i, tile.temperatureC != null) }
    }

    companion object {
        const val TILE_COUNT = 11
        const val LOST_AFTER_MS = 35L * 60L * 1000L
    }
}
