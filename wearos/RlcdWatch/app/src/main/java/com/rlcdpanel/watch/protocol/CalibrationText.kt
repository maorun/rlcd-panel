package com.rlcdpanel.watch.protocol

/** Texts and picker of the watch `CALIB` screen. Watch only, so it stays out of `scripts/check-protocol-sync.sh`. */
object CalibrationText {
    /** One row of the comparison; every field is final display text. */
    data class Row(val name: String, val hours: String, val tempDiff: String, val rhDiff: String)

    fun command(reference: Int): String {
        require(reference in 0..MAX_REFERENCE) { "reference $reference outside 0 to $MAX_REFERENCE" }
        return "CALIB $reference"
    }

    /** The firmware serves `CALIB` for the onboard sensor and slots 1 to 5 only (`CALIB_SOURCE_COUNT`). */
    const val MAX_REFERENCE = 5

    /** Reference sensor and name for each occupied tile: onboard and each enabled slot 1 to 5 with a fixed MAC or a name. */
    fun picker(config: PanelConfig): List<Pair<Int, String>> =
        (0..MAX_REFERENCE).filter { n ->
            n == 0 || (config.slotEnabled(n) && (config.slotMac(n) != null || config.slotName(n) != null))
        }.map { it to config.tileName(it) }

    fun reference(reply: CalibrationReply): String = "Referenz: ${reply.reference}"

    /** One row for each `SENSOR` line, then one for each `SKIP` line with `--` for the missing values. */
    fun rows(reply: CalibrationReply): List<Row> =
        reply.sensors.map { Row(it.name, "${it.hours} h", it.tempDiff.toString(), it.rhDiff.toString()) } +
            reply.skipped.map { Row(it.name, "-- h", "--", "--") }

    fun line(row: Row): String =
        if (row.tempDiff == "--") "${row.name}: ${row.hours}, T --, RH --"
        else "${row.name}: ${row.hours}, T ${row.tempDiff} C, RH ${row.rhDiff} %"

    fun usb(u: CalibrationReply.UsbSplit): String =
        "USB ${u.usbHours} h, Batterie ${u.batHours} h, usb_temp_offset ${u.usbTempOffset}, Diff ${u.usbTempDiff ?: "--"}"

    /** What a finished apply batch means for the screen. */
    data class ApplyOutcome(
        /** True when a `SET` may have run, so the shown differences and the cached config are old. */
        val changed: Boolean,
        val isError: Boolean,
        val message: String
    )

    fun applyOutcome(result: CalibrationBatch.Result): ApplyOutcome {
        val failed = result.failure != null || result.configFailed
        return ApplyOutcome(
            changed = result.applied.isNotEmpty() || result.failure?.second == CalibrationBatch.UNKNOWN_STATE,
            isError = failed,
            message = if (failed) result.summary else "Übernommen: ${result.applied.joinToString(", ")}"
        )
    }
}
