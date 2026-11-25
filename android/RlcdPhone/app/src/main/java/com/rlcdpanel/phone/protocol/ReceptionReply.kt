package com.rlcdpanel.phone.protocol

/** One `RECEPTION` row: [cells] hold the scans, oldest first, `true` for a scan with a packet. */
data class ReceptionRow(
    val slot: Int,
    val mac: String,
    val name: String,
    val scans: Int,
    val received: Int,
    val cells: List<Boolean>
) {
    /** Rate in whole percent, `null` without a counted scan. */
    val percent: Int? get() = if (scans > 0) received * 100 / scans else null

    /** True below 50 % (`received * 100 < 50 * scans`) with at least one counted scan. */
    val isLow: Boolean get() = scans > 0 && received * 100 < 50 * scans
}

/** Parser of the `RECEPTION` reply (`SerialConsoleRunner::showReception` of the firmware). */
object ReceptionReply {
    const val COMMAND = "RECEPTION"
    const val HEADER = "slot,mac,name,scans,received,mask"
    const val MAX_SCANS = 24

    private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val UINT = Regex("^(0|[1-9][0-9]{0,2})$")

    /** [lines] hold `BEGIN RECEPTION`, the header and the rows, without the `CRC32` line. A bad line throws [ParseException]. */
    fun parse(lines: List<String>): List<ReceptionRow> {
        if (lines.firstOrNull() != "BEGIN RECEPTION") throw ParseException("BEGIN RECEPTION")
        if (lines.size < 2 || lines[1] != HEADER) throw ParseException(HEADER)
        val rows = ArrayList<ReceptionRow>()
        val slots = HashSet<Int>()
        for (line in lines.drop(2)) {
            val c = line.split(",", limit = -1)
            if (c.size != 6) throw ParseException(line)
            val slot = c[0].takeIf { UINT.matches(it) }?.toInt()
            val scans = c[3].takeIf { UINT.matches(it) }?.toInt()
            val received = c[4].takeIf { UINT.matches(it) }?.toInt()
            val mask = c[5]
            if (slot == null || slot !in 1..10 || !slots.add(slot) || !MAC.matches(c[1]) ||
                scans == null || scans > MAX_SCANS || received == null ||
                mask.length != scans || mask.any { it != '0' && it != '1' } ||
                mask.count { it == '1' } != received
            ) throw ParseException(line)
            rows.add(ReceptionRow(slot, c[1], c[2], scans, received, mask.map { it == '1' }))
        }
        return rows
    }
}
