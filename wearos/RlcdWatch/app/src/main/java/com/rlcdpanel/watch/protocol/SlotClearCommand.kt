package com.rlcdpanel.watch.protocol

/**
 * Watch-only rules of the console command `SLOT CLEAR <N>`: it frees a dead registry slot (N 1 to 10) at once.
 * The panel answers `OK SLOT CLEAR <N>`, `ERR FIXED_MAC` for a slot with a fixed MAC or `ERR READ_ONLY` on BLE with `ble_console` 1.
 */
object SlotClearCommand {
    const val MIN_SLOT = 1
    const val MAX_SLOT = 10
    const val STALE_STATE = "STALE"
    const val READ_ONLY_TEXT = "Nur lesen. Setze ble_console=2, um Plätze freizugeben."
    const val FIXED_MAC_TEXT = "Platz hat eine feste MAC"
    const val UNKNOWN_TEXT = "Antwort nicht lesbar"

    /** `SLOT CLEAR <N>` for the slots 1 to 10; another number throws. */
    fun line(slot: Int): String {
        require(slot in MIN_SLOT..MAX_SLOT) { "slot must be 1..10" }
        return "SLOT CLEAR $slot"
    }

    /**
     * True when the sensor row gets the `Platz freigeben` button: a slot (1 to 10) with the state `STALE` and without a
     * fixed MAC. An empty [config] (no `CONFIG` read yet) gives false, because a fixed MAC is then unknown.
     */
    fun canFree(sensor: StatusSensor, config: PanelConfig): Boolean =
        sensor.index in MIN_SLOT..MAX_SLOT && sensor.state == STALE_STATE &&
            config.values.isNotEmpty() && config.slotMac(sensor.index) == null

    fun confirmText(slot: Int): String = "Platz $slot freigeben?"

    /**
     * Text of a reply to `SLOT CLEAR <slot>`; the flag is true for an error, false only for `OK SLOT CLEAR <slot>`.
     * Another `ERR` line shows as `Panel meldet: <line>`; each other reply gives [UNKNOWN_TEXT].
     */
    fun result(slot: Int, reply: ConsoleReply): Pair<String, Boolean> {
        val line = (reply as? ConsoleReply.Status)?.line
        return when {
            line == "OK SLOT CLEAR $slot" -> "Platz $slot freigegeben" to false
            line == "ERR FIXED_MAC" -> FIXED_MAC_TEXT to true
            line == "ERR READ_ONLY" -> READ_ONLY_TEXT to true
            line != null && line.startsWith("ERR ") -> "Panel meldet: ${line.take(80)}" to true
            else -> UNKNOWN_TEXT to true
        }
    }
}
