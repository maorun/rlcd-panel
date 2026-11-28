package com.rlcdpanel.watch.protocol

/**
 * Sends the selected proposals as `SET` commands, then `CONFIG`. The first `ERR` reply stops the `SET` commands and
 * suppresses `CONFIG`, like the `ConfigBatch` of the macOS app. [send] takes one command and gives its reply. With two or more
 * proposals it uses the firmware batch mode (`SET BEGIN` ... `SET COMMIT`) when the panel answers `SET BEGIN` with `OK`
 * and the old single `SET` lines otherwise (see [ConfigBatch]); [batchMode] false always sends single lines.
 */
class CalibrationBatch(private val proposals: List<CalibrationProposal>, private val batchMode: Boolean = true) {
    data class Result(
        val applied: List<String>,
        /** Key and reply text of the first failed `SET`, or null. */
        val failure: Pair<String, String>?,
        /** The `CONFIG` reply; null when the batch stopped or [configFailed] is set. */
        val config: ConsoleReply.Frame?,
        val configFailed: Boolean
    ) {
        val summary: String
            get() = when {
                failure != null -> failureText(failure.second) + " (${failure.first}). Übernommen: ${applied.size}"
                configFailed -> "Übernommen: ${applied.size}. CONFIG lesen fehlgeschlagen"
                else -> "Übernommen: ${applied.size}"
            }
    }

    fun run(send: (String, (ConsoleReply) -> Unit) -> Unit, done: (Result) -> Unit) {
        ConfigBatch(proposals.map { it.key to it.value }, batchMode).run(send) { r ->
            done(Result(r.applied, r.failure, r.config, r.configFailed))
        }
    }

    companion object {
        /** A timeout or a corrupt reply: the panel may have applied the `SET`. */
        const val UNKNOWN_STATE = ConfigBatch.UNKNOWN_STATE

        /** `ERR READ_ONLY` needs the console level 2. */
        fun failureText(status: String): String =
            if (status.startsWith("ERR READ_ONLY")) "Set ble_console=2 to apply" else status.take(80)
    }
}
