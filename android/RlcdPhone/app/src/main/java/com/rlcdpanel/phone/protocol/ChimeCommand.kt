package com.rlcdpanel.phone.protocol

/** Console commands `MUTE`, `SNOOZE` and `BEEP` and the texts of their replies. */
object ChimeCommand {
    const val STATUS = "STATUS"
    const val IDENTIFY = "IDENTIFY"
    const val READ_ONLY_TEXT = "Set ble_console=2 to apply"
    /** Short text of the Quick Settings tile for `ERR READ_ONLY`. */
    const val TILE_READ_ONLY_TEXT = "Set ble_console=2"

    /** Alert names of the bits 0 to 7 (`AlertMask`). */
    val ALERT_NAMES = listOf(
        "Freezer", "Humidity", "Noise", "Charge full",
        "Battery low", "Ventilate", "Sensor battery", "Heat"
    )

    const val MAX_SNOOZE_MINUTES = 720

    fun mute(on: Boolean): String = "MUTE ${if (on) 1 else 0}"

    /** `SNOOZE <M>` for 0 to 720 minutes; 0 ends the snooze. Another value throws. */
    fun snooze(minutes: Int): String {
        require(minutes in 0..MAX_SNOOZE_MINUTES) { "minutes out of range: $minutes" }
        return "SNOOZE $minutes"
    }

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
            line == "OK MUTE 0" || line == "OK MUTE 1" || line.startsWith("OK BEEP ") || snoozeText(line) != null || identifyText(line) != null -> null
            line.startsWith("ERR READ_ONLY") -> READ_ONLY_TEXT
            line.startsWith("ERR MUTED") -> "Panel ist stumm, in den Ruhezeiten oder Lautstärke 0"
            line.startsWith("ERR PLAY_FAILED") -> "Wiedergabe fehlgeschlagen"
            else -> fallback(reply)
        }
    }

    /** Like [failureText], but words `ERR READ_ONLY` as [TILE_READ_ONLY_TEXT] for the tile. */
    fun tileFailureText(reply: ConsoleReply, fallback: (ConsoleReply) -> String): String? =
        if ((reply as? ConsoleReply.Status)?.line.let { it == "ERR READ_ONLY" || it?.startsWith("ERR READ_ONLY ") == true }) TILE_READ_ONLY_TEXT
        else failureText(reply, fallback)

    /** Text for `OK BEEP <bit> <ms>`. */
    fun okText(line: String): String {
        snoozeText(line)?.let { return it }
        val words = line.split(' ')
        val bit = words.getOrNull(2)?.toIntOrNull()
        val ms = words.getOrNull(3)
        return if (words.getOrNull(1) == "BEEP" && bit != null && bit in 0..7 && ms != null &&
            ms.isNotEmpty() && ms.length <= 6 && ms.all { it in '0'..'9' })
            "${alertName(bit)}: gespielt ($ms ms)"
        else line.take(80)
    }

    /** Text for `OK SNOOZE <M>`; null for any other line. */
    fun snoozeText(line: String): String? {
        val words = line.split(' ')
        val minutes = words.getOrNull(2)
        if (words.size != 3 || words[0] != "OK" || words[1] != "SNOOZE" || minutes == null ||
            minutes.isEmpty() || minutes.length > 3 || !minutes.all { it in '0'..'9' } ||
            minutes.toInt() > MAX_SNOOZE_MINUTES) return null
        return if (minutes.toInt() == 0) "Stumm beendet" else "Stumm für ${minutes.toInt()} min"
    }

    /** `Panel zeigt RLCD <XXXX>` for `OK IDENTIFY <4 hex digits>`; null for any other line. */
    fun identifyText(line: String): String? {
        val words = line.split(' ')
        val code = words.getOrNull(2)
        return if (words.size == 3 && words[0] == "OK" && words[1] == "IDENTIFY" && code != null &&
            code.length == 4 && code.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' })
            "Panel zeigt RLCD ${code.uppercase()}"
        else null
    }
}
