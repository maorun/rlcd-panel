package com.rlcdpanel.watch.protocol

/**
 * Pure state machine that reports a rising weak signal bit of the `STATUS` line `LATCH`.
 *
 * [update] takes the `rxlow` mask of one `STATUS` read (bits 0 to 9 are the slots 1 to 10). It returns the slot
 * numbers (1 to 10) whose bit rose since the previous read. The first read only stores the mask. An equal mask,
 * a falling bit and a null mask (an older firmware without the field) give no slot; a null mask keeps the stored mask.
 */
class ReceptionWatch {

    private var previous: Int? = null

    fun update(mask: Int?): List<Int> {
        if (mask == null) return emptyList()
        val before = previous
        previous = mask
        if (before == null) return emptyList()
        val rose = mask and before.inv()
        return (1..SLOT_COUNT).filter { rose shr (it - 1) and 1 == 1 }
    }

    companion object {
        const val SLOT_COUNT = 10
        const val NOTIFICATION_ID_BASE = 2200

        fun message(name: String): String = "Weak signal: $name"

        fun notificationId(slot: Int): Int = NOTIFICATION_ID_BASE + slot
    }
}
