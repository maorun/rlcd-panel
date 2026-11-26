package com.rlcdpanel.phone.protocol

/** Why the panel rejected a packet. [wire] is the text of the `REJECTS` reply. */
enum class RejectReason(val wire: String) {
    FULL("FULL"),
    UNASSIGNED("UNASSIGNED"),
    DISABLED("DISABLED"),
    JUMP("JUMP"),
    WEAK("WEAK");

    companion object {
        fun fromWire(text: String): RejectReason? = values().firstOrNull { it.wire == text }
    }
}

/** What the user can do about a [RejectRow] in a companion app. */
enum class RejectAction { ASSIGN, FREE }

/** One `REJECTS` row. [count] is 1 to 255. */
data class RejectRow(val mac: String, val format: String, val reason: RejectReason, val count: Int) {
    val advice: String get() = RejectsReply.advice(reason)

    /** `UNASSIGNED` gives [RejectAction.ASSIGN], `FULL` gives [RejectAction.FREE]; the other reasons give no action. */
    val action: RejectAction?
        get() = when (reason) {
            RejectReason.UNASSIGNED -> RejectAction.ASSIGN
            RejectReason.FULL -> RejectAction.FREE
            else -> null
        }
}

/** The parsed `REJECTS` reply: the rows and the count of MACs that did not fit in the 8 entries. */
data class RejectsLog(val rows: List<RejectRow>, val dropped: Int)

/** Parser of the `REJECTS` reply (`SerialConsoleRunner::showRejects` of the firmware). */
object RejectsReply {
    const val HEADER = "mac,format,reason,count"

    /** What the user does to get a sensor accepted for the given reject reason. */
    fun advice(reason: RejectReason): String = when (reason) {
        RejectReason.FULL -> "Free a slot"
        RejectReason.UNASSIGNED -> "Set a fixed MAC or slot_auto_assign=1"
        RejectReason.DISABLED -> "Set slot<N>_enabled=1"
        RejectReason.JUMP -> "Check jump_limit_c and jump_limit_rh"
        RejectReason.WEAK -> "Move the sensor or lower auto_assign_min_rssi"
    }

    /** Text of the `DROPPED,<N>` row, null without lost MACs. */
    fun droppedText(count: Int): String? = if (count > 0) "$count more ${if (count == 1) "sensor" else "sensors"} not listed" else null

    private val FORMATS = setOf("WOIO", "METER", "BTHOME", "ATC", "THERMOPRO")
    private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val UINT = Regex("^[0-9]{1,5}$")

    /** [lines] hold `BEGIN REJECTS`, the header and the rows, without the `CRC32` line. A bad line or a repeated MAC throws [ParseException]. */
    fun parse(lines: List<String>): RejectsLog {
        if (lines.firstOrNull() != "BEGIN REJECTS") throw ParseException("BEGIN REJECTS")
        if (lines.size < 2 || lines[1] != HEADER) throw ParseException(HEADER)
        val rows = ArrayList<RejectRow>()
        var dropped = 0
        var droppedSeen = false
        val macs = HashSet<String>()
        for (line in lines.drop(2)) {
            val c = line.split(",")
            if (c[0] == "DROPPED") {
                val n = c.getOrNull(1)?.takeIf { UINT.matches(it) }?.toIntOrNull()
                if (c.size != 2 || n == null || n !in 1..255 || droppedSeen) throw ParseException(line)
                droppedSeen = true
                dropped = n
                continue
            }
            if (droppedSeen) throw ParseException(line)
            val count = c.getOrNull(3)?.takeIf { UINT.matches(it) }?.toIntOrNull()
            val reason = c.getOrNull(2)?.let { RejectReason.fromWire(it) }
            if (c.size != 4 || !MAC.matches(c[0]) || c[1] !in FORMATS || reason == null ||
                count == null || count !in 1..255 || !macs.add(c[0].uppercase())
            ) throw ParseException(line)
            rows.add(RejectRow(c[0], c[1], reason, count))
        }
        return RejectsLog(rows, dropped)
    }
}
