package com.rlcdpanel.phone.protocol

/**
 * Sends the selected proposals as `SET` commands, then `CONFIG`. The first `ERR` reply stops the `SET` commands and
 * suppresses `CONFIG`, like the `ConfigBatch` of the macOS app. [send] takes one command and gives its reply.
 */
class CalibrationBatch(private val proposals: List<CalibrationProposal>) {
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
        val applied = mutableListOf<String>()
        fun config() {
            send("CONFIG") { reply ->
                done(Result(applied, null, reply as? ConsoleReply.Frame, reply !is ConsoleReply.Frame))
            }
        }
        fun step(i: Int) {
            if (i >= proposals.size) return config()
            val p = proposals[i]
            send("SET ${p.key}=${p.value}") { reply ->
                val status = (reply as? ConsoleReply.Status)?.line
                when {
                    status != null && status.startsWith("OK") -> { applied.add(p.key); step(i + 1) }
                    status != null -> done(Result(applied, p.key to status, null, false))
                    else -> done(Result(applied, p.key to UNKNOWN_STATE, null, false))
                }
            }
        }
        step(0)
    }

    companion object {
        /** A timeout or a corrupt reply: the panel may have applied the `SET`. */
        const val UNKNOWN_STATE = "Keine gültige Antwort, Zustand unbekannt. CONFIG lesen"

        /** `ERR READ_ONLY` needs the console level 2. */
        fun failureText(status: String): String =
            if (status.startsWith("ERR READ_ONLY")) "Set ble_console=2 to apply" else status.take(80)
    }
}
