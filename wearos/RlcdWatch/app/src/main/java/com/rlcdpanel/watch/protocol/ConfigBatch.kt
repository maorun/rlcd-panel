package com.rlcdpanel.watch.protocol

/**
 * Sends a list of `SET` pairs, then `CONFIG`. With two to [MAX_KEYS] pairs it asks for the firmware batch mode first
 * (`SET BEGIN`). On `OK` each `SET` only queues (`OK QUEUED <key>`) and `SET COMMIT` writes all keys in one file write; the keys
 * count as applied after `OK SET COMMIT <N>`. On any other status (an older firmware, or `ERR READ_ONLY` at `ble_console` 1)
 * the old single `SET` lines follow. The first `ERR` reply stops the `SET` commands and suppresses `CONFIG`, like the macOS
 * `ConfigBatch`; in batch mode `SET ABORT` drops the queue then. [send] takes one command and gives its reply.
 */
class ConfigBatch(private val pairs: List<Pair<String, String>>, private val batchMode: Boolean = true) {
    data class Result(
        val applied: List<String>,
        /** Key and reply text of the first failed `SET` (`SET COMMIT` for a failed commit, `SET BEGIN` for a lost reply), or null. */
        val failure: Pair<String, String>?,
        /** The `CONFIG` reply; null when the batch stopped or [configFailed] is set. */
        val config: ConsoleReply.Frame?,
        val configFailed: Boolean,
        /** True when the firmware batch mode (`SET BEGIN` answered `OK`) carried the keys. */
        val usedBatchMode: Boolean = false
    )

    fun run(send: (String, (ConsoleReply) -> Unit) -> Unit, done: (Result) -> Unit) {
        if (batchMode && pairs.size in 2..MAX_KEYS) {
            send("SET BEGIN") { reply ->
                val status = (reply as? ConsoleReply.Status)?.line
                when {
                    status != null && status.startsWith("OK") -> queue(send, done, 0)
                    reply is ConsoleReply.Status -> single(send, done) // older firmware or read-only: single SET lines
                    else -> abortWith(send, done, "SET BEGIN" to UNKNOWN_STATE) // the panel may hold a queue: no single lines
                }
            }
        } else {
            single(send, done)
        }
    }

    private fun config(
        send: (String, (ConsoleReply) -> Unit) -> Unit,
        done: (Result) -> Unit,
        applied: List<String>,
        usedBatchMode: Boolean
    ) {
        send("CONFIG") { reply ->
            done(Result(applied, null, reply as? ConsoleReply.Frame, reply !is ConsoleReply.Frame, usedBatchMode))
        }
    }

    private fun single(send: (String, (ConsoleReply) -> Unit) -> Unit, done: (Result) -> Unit) {
        val applied = mutableListOf<String>()
        fun step(i: Int) {
            if (i >= pairs.size) return config(send, done, applied, false)
            val (key, value) = pairs[i]
            send("SET $key=$value") { reply ->
                val status = (reply as? ConsoleReply.Status)?.line
                when {
                    status != null && status.startsWith("OK QUEUED") ->
                        // A queue of an earlier run is open: nothing was written.
                        send("SET ABORT") { done(Result(applied, key to BATCH_STATE, null, false)) }
                    status != null && status.startsWith("OK") -> { applied.add(key); step(i + 1) }
                    status != null -> done(Result(applied, key to status, null, false))
                    else -> done(Result(applied, key to UNKNOWN_STATE, null, false))
                }
            }
        }
        step(0)
    }

    /** Drops the queue on the panel (best effort), then reports [failure]; no key counts as applied. */
    private fun abortWith(
        send: (String, (ConsoleReply) -> Unit) -> Unit,
        done: (Result) -> Unit,
        failure: Pair<String, String>
    ) {
        send("SET ABORT") { done(Result(emptyList(), failure, null, false, true)) }
    }

    /** Batch mode: queue pair [i], then commit. A refused pair or a failed commit aborts the queue and applies nothing. */
    private fun queue(send: (String, (ConsoleReply) -> Unit) -> Unit, done: (Result) -> Unit, i: Int) {
        fun abort(failure: Pair<String, String>) = abortWith(send, done, failure)
        if (i >= pairs.size) {
            send("SET COMMIT") { reply ->
                val status = (reply as? ConsoleReply.Status)?.line
                when {
                    status != null && status.startsWith("OK") -> config(send, done, pairs.map { it.first }.distinct(), true)
                    status != null -> abort("SET COMMIT" to status) // a failed write keeps the queue on the panel
                    else -> abort("SET COMMIT" to UNKNOWN_STATE) // best effort: the queue may still be open
                }
            }
            return
        }
        val (key, value) = pairs[i]
        send("SET $key=$value") { reply ->
            val status = (reply as? ConsoleReply.Status)?.line
            when {
                status != null && status.startsWith("OK QUEUED") -> queue(send, done, i + 1)
                // A plain OK means the queue expired and the key was written alone.
                status != null && status.startsWith("OK") -> abort(key to BATCH_STATE)
                status != null -> abort(key to status)
                else -> abort(key to UNKNOWN_STATE) // best effort: the queue may still be open
            }
        }
    }

    companion object {
        /** The firmware queue holds 32 keys. */
        const val MAX_KEYS = 32

        /** A timeout or a corrupt reply: the panel may have applied the `SET`. */
        const val UNKNOWN_STATE = "Keine gültige Antwort, Zustand unbekannt. CONFIG lesen"

        /** The panel answered `OK` in a way that does not match its batch state: nothing counts as applied. */
        const val BATCH_STATE = "ERR BATCH_STATE"
    }
}
