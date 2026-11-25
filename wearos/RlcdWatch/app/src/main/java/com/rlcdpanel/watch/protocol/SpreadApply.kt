package com.rlcdpanel.watch.protocol

/**
 * Watch-only rules to apply the offset proposals of a `SPREAD` reply. The panel prints one proposal line after each
 * `OUTLIER` line, so the n-th outlier row owns the n-th proposal. An `OK` row has no proposal and no command.
 */
object SpreadApply {
    const val READ_ONLY_TEXT = "Nur lesen. Setze ble_console=2, um Offsets zu übernehmen."

    /** The proposal of the row at [index], or null for an `OK` row, a bad index or a missing proposal line. */
    fun proposalFor(measured: SpreadReply.Outcome.Measured, index: Int): SpreadReply.Proposal? {
        val row = measured.rows.getOrNull(index) ?: return null
        if (!row.isOutlier) return null
        val outliersBefore = measured.rows.take(index).count { it.isOutlier }
        return measured.proposals.getOrNull(outliersBefore)?.takeIf { isOffsetProposal(it) }
    }

    /** True when the keys are offset keys of the right kind and both values lie in the firmware range. */
    fun isOffsetProposal(p: SpreadReply.Proposal): Boolean =
        p.tempKey.endsWith("temp_offset_c") && p.rhKey.endsWith("rh_offset_pct") &&
            CalibrationKeys.validate(p.tempKey, p.tempOffset.toString()) &&
            CalibrationKeys.validate(p.rhKey, p.rhOffset.toString())

    /** The batch that sends the commands of [proposal], temperature first. */
    fun batch(proposal: SpreadReply.Proposal): CalibrationBatch = CalibrationBatch(
        listOf(
            CalibrationProposal(proposal.tempKey, proposal.tempOffset.toString()),
            CalibrationProposal(proposal.rhKey, proposal.rhOffset.toString())
        )
    )

    /** Confirmation text with both offsets, for example `Temp -1.0 °C, Feuchte -5.0 %RH`. */
    fun confirmText(proposal: SpreadReply.Proposal): String =
        "Temp ${proposal.tempOffset} °C, Feuchte ${proposal.rhOffset} %RH"

    /** True when the failed reply of a batch says that the console level is read-only. */
    fun isReadOnly(result: CalibrationBatch.Result): Boolean =
        result.failure?.second?.startsWith("ERR READ_ONLY") == true
}
