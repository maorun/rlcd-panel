package com.rlcdpanel.phone.protocol

/** Console command `SPREAD`: no argument for the default window of 6 hours, else `SPREAD <H>` with H 7 to 24. */
object SpreadCommand {
    const val LINE = "SPREAD"
    const val DEFAULT_HOURS = 6
    const val MAX_HOURS = 24

    /** Windows of the picker. */
    val PICKER_HOURS: List<Int> = listOf(DEFAULT_HOURS, 12, MAX_HOURS)

    const val OLD_FIRMWARE_TEXT = "Update the panel firmware for longer windows"

    /** Throws [IllegalArgumentException] for hours outside 6 to 24 (the firmware answers `ERR BAD_ARG`). */
    fun line(hours: Int): String {
        require(hours in DEFAULT_HOURS..MAX_HOURS) { "hours $hours outside $DEFAULT_HOURS to $MAX_HOURS" }
        return if (hours == DEFAULT_HOURS) LINE else "$LINE $hours"
    }

    fun windowText(hours: Int): String = "Window $hours h"

    /** True when a window above 6 h got `ERR BAD_ARG`: a panel firmware without `SPREAD <H>`. */
    fun isOldFirmware(hours: Int, statusLine: String): Boolean =
        hours > DEFAULT_HOURS && statusLine.trim().let { it == "ERR BAD_ARG" || it.startsWith("ERR BAD_ARG ") }
}
