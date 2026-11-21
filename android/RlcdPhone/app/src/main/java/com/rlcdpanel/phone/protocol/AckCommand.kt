package com.rlcdpanel.phone.protocol

/**
 * `ACK <N>` console command of the panel: stops the chime reminder of one active alert bit.
 */
object AckCommand {
    const val MIN_BIT = 0
    const val MAX_BIT = 7

    /** Alert bits that `chime_repeat_h` can repeat (`AlertEvaluator::repeatableBits`): all bits except `CHARGE_FULL`. */
    val REPEATABLE = AlertMask(0xFF and AlertMask.CHARGE_FULL.rawValue.inv())

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

    /** Text for the result of one `ACK` command. A null [reply] means that no reply came. */
    fun resultText(reply: ConsoleReply?): String = when (reply) {
        null -> NOT_IN_RANGE
        is ConsoleReply.Status -> when {
            reply.line.startsWith("OK ACK") -> "Acknowledged"
            reply.line.startsWith("ERR NOT_ACTIVE") -> "The alert is off"
            reply.line.startsWith("ERR READ_ONLY") -> "Set ble_console=2 to apply"
            reply.line.startsWith("ERR BLUETOOTH_OFF") || reply.line.startsWith("ERR NO_CONSOLE") -> NOT_IN_RANGE
            else -> "Panel replied: ${reply.line.take(40)}"
        }
        is ConsoleReply.Corrupt -> if (reply.error == ReplyError.Timeout) NOT_IN_RANGE else "Bad reply from the panel"
        is ConsoleReply.Frame -> "Bad reply from the panel"
    }

    private const val NOT_IN_RANGE = "Panel not in range"
}
