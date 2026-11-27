package com.rlcdpanel.watch.protocol

/** One row of the `ALERTS` reply. [tiles] is the tile mask (bit 0 onboard, bits 1 to 10 slots), null for an alert without tiles. */
data class AlertRow(val name: String, val active: Boolean, val acked: Boolean, val tiles: Int?, val limit: String?) {
    /** Tile numbers in the mask, lowest first. */
    val tileIndices: List<Int>
        get() = tiles?.let { mask -> (0..10).filter { mask and (1 shl it) != 0 } } ?: emptyList()
}

/** The parsed `ALERTS` reply (`SerialConsoleRunner::showAlerts` of the firmware). */
data class AlertsLog(val rows: List<AlertRow>) {
    /** Mask of the active alerts in the bit order of `AlertMask`, to compare with the alert mask of a telemetry frame. */
    val activeMask: Int
        get() = rows.fold(0) { mask, row -> if (row.active) mask or (1 shl AlertsReply.NAME_LIST.indexOf(row.name)) else mask }

    /** `HUMIDITY: Bad, Keller` for an active alert, null when it is not active or unknown. */
    fun causeText(alert: String, tileNames: List<String>): String? {
        val row = rows.firstOrNull { it.name == alert }?.takeIf { it.active } ?: return null
        if (row.tiles == null) return row.name
        val indices = row.tileIndices
        if (indices.isEmpty()) return "${row.name}: no tile above the limit"
        return "${row.name}: " + indices.joinToString(", ") { i -> tileNames.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "Tile $i" }
    }

    /** One cause text for each active alert, in reply order. */
    fun causeLines(tileNames: List<String>): List<String> = rows.mapNotNull { causeText(it.name, tileNames) }
}

/** Parser of the `ALERTS` reply. */
object AlertsReply {
    const val HEADER = "alert,active,acked,tiles,limit"
    /** Alert names in the bit order of `AlertMask`. */
    val NAME_LIST = listOf("FREEZER", "HUMIDITY", "NOISE", "SENSOR_LOST", "CHARGE_FULL", "VENTILATE", "SENSOR_BATTERY", "HEAT")
    private val NAMES = NAME_LIST.toSet()
    private val HEX3 = Regex("^[0-9A-Fa-f]{3}$")
    private val LIMIT = Regex("^-?[0-9]{1,4}(\\.[0-9])?$")

    /** [lines] hold `BEGIN ALERTS`, the header and the rows, without the `CRC32` line. A bad line or a repeated alert throws [ParseException]. */
    fun parse(lines: List<String>): AlertsLog {
        if (lines.firstOrNull() != "BEGIN ALERTS") throw ParseException("BEGIN ALERTS")
        if (lines.size < 2 || lines[1] != HEADER) throw ParseException(HEADER)
        val rows = ArrayList<AlertRow>()
        val seen = HashSet<String>()
        for (line in lines.drop(2)) {
            val c = line.split(",")
            val active = flag(c.getOrNull(1))
            val acked = flag(c.getOrNull(2))
            if (c.size != 5 || c[0] !in NAMES || active == null || acked == null) throw ParseException(line)
            val tiles = if (c[3].isEmpty()) null else {
                if (!HEX3.matches(c[3])) throw ParseException(line)
                c[3].toInt(16).also { if (it > 0x7FF) throw ParseException(line) }
            }
            if (c[4].isNotEmpty() && !LIMIT.matches(c[4])) throw ParseException(line)
            if (!seen.add(c[0])) throw ParseException(line)
            rows.add(AlertRow(c[0], active, acked, tiles, c[4].ifEmpty { null }))
        }
        return AlertsLog(rows)
    }

    private fun flag(s: String?): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }

    /** Clear text of an error line. An older firmware answers `ERR UNKNOWN`. */
    fun errorText(line: String): String = when (line.trim()) {
        "ERR UNKNOWN" -> "Update the panel firmware"
        "ERR BAD_ARG" -> "The panel rejected the command"
        else -> line
    }
}
