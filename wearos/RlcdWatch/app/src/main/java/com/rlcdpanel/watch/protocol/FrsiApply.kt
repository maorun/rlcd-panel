package com.rlcdpanel.watch.protocol

/** Watch-only rules of the `Übernehmen` button: one `SET wall_frsi_pct=<N>`, then `CONFIG`. */
object FrsiApply {
    const val READ_ONLY_TEXT = "Set ble_console=2 to apply"
    const val DIFFERENT_TEXT = "Raum und Wand müssen verschieden sein"

    /**
     * Parses the raw bytes of one `FRSI` reply. A bad `CRC32` line, a missing one or a status line such as
     * `ERR LOW_DELTA` throws [ParseException]; the screen shows the status text through [FrsiText.status].
     */
    fun parseBytes(bytes: ByteArray): FrsiReply = when (val reply = ReplyAssembler().append(bytes)) {
        is ConsoleReply.Frame -> FrsiReply.parse(reply.lines)
        else -> throw ParseException("FRSI reply: $reply")
    }

    /** The batch of the proposal, or null without a valid proposal line. */
    fun batch(reply: FrsiReply): CalibrationBatch? = reply.proposal?.let { CalibrationBatch(listOf(it)) }

    /** True when the failed reply of a batch says that the console level is read-only. */
    fun isReadOnly(result: CalibrationBatch.Result): Boolean =
        result.failure?.second?.startsWith("ERR READ_ONLY") == true

    /** Text of a finished batch: the read-only hint, the failure summary, or the applied key. */
    fun resultText(result: CalibrationBatch.Result): String = when {
        isReadOnly(result) -> READ_ONLY_TEXT
        result.failure != null || result.configFailed -> result.summary
        else -> "Übernommen: ${FrsiCommand.KEY}"
    }
}
