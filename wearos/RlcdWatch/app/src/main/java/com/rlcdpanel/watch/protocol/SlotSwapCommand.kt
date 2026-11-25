package com.rlcdpanel.watch.protocol

/**
 * Watch-only rules of the console command `SLOT SWAP <A> <B>`: it swaps two registry slots (1 to 10, different).
 * The panel answers `OK SLOT SWAP <A> <B>`, `ERR FIXED_MAC` for a slot with a fixed MAC, `ERR SLOT_OFF` for a disabled slot
 * or `ERR READ_ONLY` on BLE with `ble_console` 1.
 */
object SlotSwapCommand {
    const val MIN_SLOT = 1
    const val MAX_SLOT = 10
    const val READ_ONLY_TEXT = "Nur lesen. Setze ble_console=2, um Plätze zu tauschen."
    const val FIXED_MAC_TEXT = "Platz hat eine feste MAC"
    const val SLOT_OFF_TEXT = "Platz ist abgeschaltet"
    const val UNKNOWN_TEXT = "Antwort nicht lesbar"

    /** True for two different slots, both 1 to 10. */
    fun isValidPair(a: Int, b: Int): Boolean = a in MIN_SLOT..MAX_SLOT && b in MIN_SLOT..MAX_SLOT && a != b

    /** `SLOT SWAP <A> <B>`; equal slots or a number outside 1 to 10 give null. */
    fun line(a: Int, b: Int): String? = if (isValidPair(a, b)) "SLOT SWAP $a $b" else null

    /**
     * True when the sensor row takes part in a swap: a slot (1 to 10) without a fixed MAC. An empty [config]
     * (no `CONFIG` read yet) gives false, because a fixed MAC is then unknown.
     */
    fun canSwap(sensor: StatusSensor, config: PanelConfig): Boolean =
        sensor.index in MIN_SLOT..MAX_SLOT && config.values.isNotEmpty() && config.slotMac(sensor.index) == null

    fun confirmText(a: Int, b: Int): String = "Platz $a und $b tauschen?"

    /**
     * Text of a reply to `SLOT SWAP <a> <b>`; the flag is true for an error, false only for `OK SLOT SWAP <a> <b>`.
     * Another `ERR` line shows as `Panel meldet: <line>`; each other reply gives [UNKNOWN_TEXT].
     */
    fun result(a: Int, b: Int, reply: ConsoleReply): Pair<String, Boolean> {
        val line = (reply as? ConsoleReply.Status)?.line
        return when {
            line == "OK SLOT SWAP $a $b" -> "Plätze $a und $b getauscht" to false
            line == "ERR FIXED_MAC" -> FIXED_MAC_TEXT to true
            line == "ERR SLOT_OFF" -> SLOT_OFF_TEXT to true
            line == "ERR READ_ONLY" -> READ_ONLY_TEXT to true
            line != null && line.startsWith("ERR ") -> "Panel meldet: ${line.take(80)}" to true
            else -> UNKNOWN_TEXT to true
        }
    }
}
