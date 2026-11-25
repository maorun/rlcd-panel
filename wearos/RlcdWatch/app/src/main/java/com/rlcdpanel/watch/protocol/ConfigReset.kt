package com.rlcdpanel.watch.protocol

/**
 * Watch-only rules to reset one changed setting to the default. The plan comes from the shared
 * [ConfigSchema.resetPlan]; this object adds the texts and a run that sends the `SET` commands and then stops.
 */
object ConfigReset {
    const val READ_ONLY_TEXT = "Nur lesen"
    const val RESET_LABEL = "Zurücksetzen"

    /** What one row can do. */
    sealed class RowPlan {
        /** [command] is the `SET key=default` line, [defaultValue] its value (empty for a MAC). */
        data class Sendable(val key: String, val command: String, val defaultValue: String) : RowPlan()
        /** The console cannot send the default. [reason] tells why. */
        data class Skipped(val key: String, val reason: String) : RowPlan()
        /** The value equals the default, or the key is unknown. */
        object None : RowPlan()
    }

    fun planFor(entry: ConfigChangedEntry): RowPlan {
        val plan = ConfigSchema.resetPlan(listOf(entry))
        plan.commands.firstOrNull()?.let { return RowPlan.Sendable(entry.key, it, it.substringAfter('=')) }
        plan.skipped[entry.key]?.let { return RowPlan.Skipped(entry.key, it) }
        return RowPlan.None
    }

    /** Confirmation text with the key and the default value. */
    fun confirmText(plan: RowPlan.Sendable): String =
        "${plan.key} auf ${if (plan.defaultValue.isEmpty()) "leer" else plan.defaultValue} setzen?"

    /** End of a run: [failure] is the text of the first failed command, null when all succeeded. */
    data class Result(
        val sent: Int,
        val failure: String?,
        val readOnly: Boolean,
        /** True when the reply was no `ERR` status (timeout, bad frame): the `SET` may have applied. */
        val unknownState: Boolean = false
    )

    /**
     * Sends [commands] one by one through [send]. The first reply that is not `OK` stops the run and the
     * remaining commands stay unsent. `ERR READ_ONLY` sets [Result.readOnly].
     */
    fun run(commands: List<String>, send: (String, (ConsoleReply) -> Unit) -> Unit, done: (Result) -> Unit) {
        fun step(i: Int) {
            if (i >= commands.size) return done(Result(i, null, false))
            if (commands[i].toByteArray(Charsets.UTF_8).size > ConfigSchema.MAX_LINE_BYTES) {
                return done(Result(i, "Befehl länger als ${ConfigSchema.MAX_LINE_BYTES} Bytes", false))
            }
            send(commands[i]) { reply ->
                if (reply is ConsoleReply.Status && reply.line.startsWith("OK")) {
                    step(i + 1)
                } else {
                    val readOnly = reply is ConsoleReply.Status && reply.line.startsWith("ERR READ_ONLY")
                    val unknown = reply !is ConsoleReply.Status
                    done(Result(i, if (readOnly) READ_ONLY_TEXT else ConfigSchema.failureText(reply), readOnly, unknown))
                }
            }
        }
        step(0)
    }
}
