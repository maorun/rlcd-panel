package com.rlcdpanel.phone.protocol

/** Reply of the console command `RTC TRIM`: `OK RTC TRIM <old> <new>` or an `ERR` line. */
object RtcTrimReply {
    const val COMMAND = "RTC TRIM"
    const val NO_DRIFT_TEXT = "Kein Drift-Wert von 24 h oder mehr"
    const val READ_ONLY_TEXT = "Set ble_console=2 to apply"

    /** Trim register value before and after the command (PCF85063A offset, -64 to 63). */
    data class Result(val oldTrim: Int, val newTrim: Int)

    /** `OK RTC TRIM <old> <new>` gives both values; every other line, a bad number or a value outside -64 to 63 gives null. */
    fun parse(line: String): Result? {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        if (words.size != 5 || words[0] != "OK" || words[1] != "RTC" || words[2] != "TRIM") return null
        val old = words[3].toIntOrNull() ?: return null
        val new = words[4].toIntOrNull() ?: return null
        if (old !in -64..63 || new !in -64..63) return null
        return Result(old, new)
    }

    /** Short text of the reply line. */
    fun text(rawLine: String): String {
        val line = rawLine.trim()
        parse(line)?.let { return "RTC getrimmt: ${it.oldTrim} → ${it.newTrim}" }
        return when {
            line.startsWith("ERR NO_DRIFT") -> NO_DRIFT_TEXT
            line.startsWith("ERR READ_ONLY") -> READ_ONLY_TEXT
            else -> "Panel meldet: ${line.take(80)}"
        }
    }
}
