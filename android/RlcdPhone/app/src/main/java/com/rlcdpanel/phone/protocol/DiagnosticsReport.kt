package com.rlcdpanel.phone.protocol

import java.util.Locale

/** One text report of the last `VERSION`, `STATUS` and `REJECTS` replies for a fault report. A missing reply gives `--`. */
object DiagnosticsReport {
    const val HIDDEN = "<hidden>"

    /**
     * Joins the summary lines. The report holds no Wi-Fi or `NTP` data. Sensor names and foreign MACs
     * show as `<hidden>` in the rows, unless [includeNames] is true.
     */
    fun build(version: VersionReply?, status: StatusReply?, rejects: RejectsLog?, includeNames: Boolean = false): String {
        val out = ArrayList<String>()
        out.add("RLCD Phone Diagnostics")
        out.add(
            if (version == null) "Firmware: --"
            else "Firmware: ${version.firmware} (${version.git}), state ${version.state}, built ${version.built}"
        )
        if (status == null) {
            out.add("Sensors: --")
            out.add("SD: --")
        } else {
            for (s in status.sensors) out.add(sensorLine(s, includeNames))
            out.add(
                "SD: status ${status.sdStatus ?: "--"}, free ${status.sdFreeMb?.let { "$it MB" } ?: "--"}"
            )
            status.reset?.let { out.add("Reset: ${it.reason}, abnormal ${it.abnormalCount ?: "--"}") }
            status.shtc3?.let {
                out.add("SHTC3: nack ${it.nack}, short ${it.shortReads}, crc ${it.crc}, run ${it.run}, retried ${it.retried}")
            }
            status.latch?.let { out.add(badgeSummary(it)) }
            status.noise?.let {
                out.add("Noise: ${it.db ?: "--"} dB (${it.band})")
            }
        }
        out.add("")
        out.add("## REJECTS")
        if (rejects == null) {
            out.add("--")
        } else {
            out.add(RejectsReply.HEADER)
            for (r in rejects.rows) {
                out.add("${if (includeNames) r.mac else HIDDEN},${r.format},${r.reason.wire},${r.count}")
            }
            if (rejects.dropped > 0) out.add("DROPPED,${rejects.dropped}")
        }
        return out.joinToString("\n") + "\n"
    }

    /** `Panel badges: 1 V; 4 BAT LOW` (sensor number, then its badges) or `Panel badges: none`. */
    private fun badgeSummary(latch: LatchState): String {
        val parts = (0 until LatchState.SENSOR_COUNT).mapNotNull { i ->
            latch.badges(i).takeIf { it.isNotEmpty() }?.let { "$i ${it.joinToString(", ")}" }
        }
        return "Panel badges: " + if (parts.isEmpty()) "none" else parts.joinToString("; ")
    }

    private fun sensorLine(s: StatusSensor, includeNames: Boolean): String {
        val name = if (includeNames) s.name else HIDDEN
        return "Sensor ${s.index} $name: temp=${num(s.temperature)} rh=${num(s.humidity)} " +
            "bat=${int(s.battery)} rssi=${int(s.rssi)} age=${int(s.ageSeconds)} " +
            "state=${s.state ?: "--"} mold_h=${s.moldHours ?: "--"}"
    }

    private fun num(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--"
    private fun int(v: Double?): String = v?.let { it.toInt().toString() } ?: "--"
}
