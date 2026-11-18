package com.rlcdpanel.watch.protocol

/** Console commands `MUTE` and `BEEP` and the texts of their replies. */
object ChimeCommand {
    const val STATUS = "STATUS"
    const val READ_ONLY_TEXT = "Set ble_console=2 to apply"

    /** Alert names of the bits 0 to 7 (`AlertMask`). */
    val ALERT_NAMES = listOf(
        "Freezer", "Humidity", "Noise", "Charge full",
        "Battery low", "Ventilate", "Sensor battery", "Heat"
    )

    fun mute(on: Boolean): String = "MUTE ${if (on) 1 else 0}"

    /** `BEEP <bit>` for the alert bits 0 to 7; another bit throws. */
    fun beep(bit: Int): String {
        require(bit in 0..7) { "bit out of range: $bit" }
        return "BEEP $bit"
    }

    fun alertName(bit: Int): String = ALERT_NAMES[bit]

    /** Error text of a reply; null for `OK ...`. [fallback] words each other reply (`BleConsoleClient.errorText`). */
    fun failureText(reply: ConsoleReply, fallback: (ConsoleReply) -> String): String? {
        val line = (reply as? ConsoleReply.Status)?.line ?: return fallback(reply)
        return when {
            line == "OK MUTE 0" || line == "OK MUTE 1" || line.startsWith("OK BEEP ") -> null
            line.startsWith("ERR READ_ONLY") -> READ_ONLY_TEXT
            line.startsWith("ERR MUTED") -> "Panel ist stumm, in den Ruhezeiten oder Lautstärke 0"
            line.startsWith("ERR PLAY_FAILED") -> "Wiedergabe fehlgeschlagen"
            else -> fallback(reply)
        }
    }

    /** Text for `OK BEEP <bit> <ms>`. */
    fun okText(line: String): String {
        val words = line.split(' ')
        val bit = words.getOrNull(2)?.toIntOrNull()
        val ms = words.getOrNull(3)
        return if (words.getOrNull(1) == "BEEP" && bit != null && bit in 0..7 && ms != null &&
            ms.isNotEmpty() && ms.length <= 6 && ms.all { it in '0'..'9' })
            "${alertName(bit)}: gespielt ($ms ms)"
        else line.take(80)
    }
}
