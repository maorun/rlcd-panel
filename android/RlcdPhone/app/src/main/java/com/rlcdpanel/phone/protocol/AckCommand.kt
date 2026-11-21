package com.rlcdpanel.phone.protocol

/**
 * `ACK <N>` console command of the panel: stops the chime reminder of one active alert bit.
 */
object AckCommand {
    const val MIN_BIT = 0
    const val MAX_BIT = 7

    /** Alert bits that `chime_repeat_h` can repeat (`AlertEvaluator::repeatableBits`): all bits except `CHARGE_FULL`. */
    val REPEATABLE = AlertMask(0xFF and AlertMask.CHARGE_FULL.rawValue.inv())

    /** `ACK ALL`: acknowledges each repeatable active alert bit at once. */
    const val ALL = "ACK ALL"

    fun line(bit: Int): String {
        require(bit in MIN_BIT..MAX_BIT) { "bit must be 0..7" }
        return "ACK $bit"
    }

    /** Bit numbers (0 to 7, lowest first) of the [rising] alert bits that get an Acknowledge action. */
    fun actionBits(rising: AlertMask): List<Int> =
        (MIN_BIT..MAX_BIT).filter { bit ->
            val flag = AlertMask(1 shl bit)
            rising.contains(flag) && REPEATABLE.contains(flag)
        }

    /**
     * True when two or more repeatable active bits are not acknowledged (`CHARGE_FULL` does not count).
     * A single open bit keeps its own `ACK <N>` button.
     */
    fun canAcknowledgeAll(alertMask: Int, ackedMask: Int): Boolean {
        val open = alertMask and ackedMask.inv() and REPEATABLE.rawValue
        return Integer.bitCount(open) >= 2
    }

    /** Text for `OK ACK ALL <2 hex digits>`: the names of the new bits, lowest first; null for another line. */
    private fun allText(line: String): String? {
        val hex = line.removePrefix("OK ACK ALL ").takeIf { it != line } ?: return null
        if (hex.length != 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val mask = hex.toInt(16)
        if (mask == 0) return null
        val names = (MIN_BIT..MAX_BIT).filter { mask and (1 shl it) != 0 }.map { ALERT_NAMES[it] }
        return "Acknowledged: " + names.joinToString(", ")
    }

    /** Text for the result of one `ACK` command. A null [reply] means that no reply came. */
    fun resultText(reply: ConsoleReply?): String = when (reply) {
        null -> NOT_IN_RANGE
        is ConsoleReply.Status -> allText(reply.line) ?: when {
            reply.line.startsWith("OK ACK") -> "Acknowledged"
            reply.line.startsWith("ERR NOT_ACTIVE") -> "The alert is off"
            reply.line.startsWith("ERR READ_ONLY") -> "Set ble_console=2 to apply"
            reply.line.startsWith("ERR BLUETOOTH_OFF") || reply.line.startsWith("ERR NO_CONSOLE") -> NOT_IN_RANGE
            else -> "Panel replied: ${reply.line.take(40)}"
        }
        is ConsoleReply.Corrupt -> if (reply.error == ReplyError.Timeout) NOT_IN_RANGE else "Bad reply from the panel"
        is ConsoleReply.Frame -> "Bad reply from the panel"
    }

    /** Names of the firmware alert bits 0 to 7 (`AlertMask`), as in the macOS `ackAlertName`. */
    private val ALERT_NAMES = listOf(
        "Freezer", "Humidity", "Noise high", "Sensor lost",
        "Charge full", "Ventilate", "Sensor battery", "Heat"
    )

    private const val NOT_IN_RANGE = "Panel not in range"
}
