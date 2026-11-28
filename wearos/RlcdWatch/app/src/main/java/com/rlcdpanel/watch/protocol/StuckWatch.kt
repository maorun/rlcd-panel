package com.rlcdpanel.watch.protocol

/**
 * Pure state machine that reports a rising frozen reading bit of the `STATUS` line `LATCH`.
 *
 * [update] takes the `stuck` mask of one `STATUS` read (bit 0 onboard tile, bits 1 to 10 slots). It returns the tiles
 * whose bit rose since the previous read. The first read only stores the mask. An equal mask, a falling bit and
 * a null mask (an older firmware without the field) give no tile; a null mask keeps the stored mask.
 */
class StuckWatch {

    private var previous: Int? = null

    fun update(mask: Int?): List<Int> {
        if (mask == null) return emptyList()
        val before = previous
        previous = mask
        if (before == null) return emptyList()
        val rose = mask and before.inv()
        return (0 until TILE_COUNT).filter { rose shr it and 1 == 1 }
    }

    companion object {
        const val TILE_COUNT = 11
        const val NOTIFICATION_ID_BASE = 2500
        const val TEXT = "Sensor frozen"

        fun message(name: String): String = "Sensor frozen: $name"

        fun notificationId(tile: Int): Int = NOTIFICATION_ID_BASE + tile

        /** True when the tile (0 onboard, 1 to 10 slots) has its bit set; a null mask and a tile outside 0 to 10 give false. */
        fun isStuck(mask: Int?, tile: Int): Boolean =
            mask != null && tile in 0 until TILE_COUNT && mask shr tile and 1 == 1
    }
}
