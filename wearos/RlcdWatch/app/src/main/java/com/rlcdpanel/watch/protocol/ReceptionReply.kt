package com.rlcdpanel.watch.protocol

/** One `RECEPTION` row. [mask] holds [scans] characters, oldest scan first, `1` for a scan with a packet. */
data class ReceptionRow(
    val slot: Int,
    val mac: String,
    val name: String,
    val scans: Int,
    val received: Int,
    val mask: String
) {
    /** The scans as flags, oldest first, `true` for a scan with a packet. */
    val cells: List<Boolean> get() = mask.map { it == '1' }

    /** Rate in whole percent, `null` without a counted scan. */
    val percent: Int? get() = if (scans > 0) received * 100 / scans else null

    /** The newest [count] scans, oldest first. A shorter mask gives all its cells. */
    fun newestCells(count: Int = STRIP_CELLS): String = mask.takeLast(count)

    /** True below 50 % of the counted scans; a slot without a counted scan is not low. */
    val isLow: Boolean get() = scans > 0 && received * 100 < 50 * scans

    companion object {
        /** Cells of the strip on the round screen. */
        const val STRIP_CELLS = 12
    }
}

/** Parser of the `RECEPTION` reply (`SerialConsoleRunner::showReception` of the firmware). */
object ReceptionReply {
    const val COMMAND = "RECEPTION"
    const val HEADER = "slot,mac,name,scans,received,mask"
    const val MAX_SCANS = 24

    private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val UINT = Regex("^(0|[1-9][0-9]{0,2})$")
    private val MASK = Regex("^[01]*$")

    /** [lines] hold `BEGIN RECEPTION`, the header and the rows, without the `CRC32` line. A bad line or a repeated slot throws [ParseException]. */
    fun parse(lines: List<String>): List<ReceptionRow> {
        if (lines.firstOrNull() != "BEGIN RECEPTION") throw ParseException("BEGIN RECEPTION")
        if (lines.size < 2 || lines[1] != HEADER) throw ParseException(HEADER)
        val rows = ArrayList<ReceptionRow>()
        val slots = HashSet<Int>()
        for (line in lines.drop(2)) {
            val c = line.split(",")
            if (c.size != 6) throw ParseException(line)
            val slot = c[0].takeIf { UINT.matches(it) }?.toIntOrNull()
            val scans = c[3].takeIf { UINT.matches(it) }?.toIntOrNull()
            val received = c[4].takeIf { UINT.matches(it) }?.toIntOrNull()
            val mask = c[5]
            if (slot == null || slot !in 1..10 || !MAC.matches(c[1]) ||
                scans == null || scans > MAX_SCANS || received == null || received > scans ||
                mask.length != scans || !MASK.matches(mask) || mask.count { it == '1' } != received ||
                !slots.add(slot)
            ) throw ParseException(line)
            rows.add(ReceptionRow(slot, c[1], c[2], scans, received, mask))
        }
        return rows
    }
}
