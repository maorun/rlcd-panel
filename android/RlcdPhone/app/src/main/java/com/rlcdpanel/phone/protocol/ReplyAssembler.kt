package com.rlcdpanel.phone.protocol

/** Why a console reply is unusable. */
sealed class ReplyError {
    /** A line or a reply is longer than the limits of [ReplyAssembler]. */
    object TooLarge : ReplyError()
    /** The reply ended before its `CRC32` line arrived. */
    object Timeout : ReplyError()
    data class BadCrc(val expected: Long, val actual: Long) : ReplyError()
    data class MalformedCrcLine(val line: String) : ReplyError()
}

/** A complete console reply. */
sealed class ConsoleReply {
    /** Lines before the `CRC32` line, CRC-32 verified. Each line has no LF. */
    data class Frame(val lines: List<String>) : ConsoleReply()
    /** A one-line reply: `OK ...` or `ERR ...`. */
    data class Status(val line: String) : ConsoleReply()
    data class Corrupt(val error: ReplyError) : ConsoleReply()
}

/**
 * Reassembles one console reply from BLE notification chunks. A reply ends at its `CRC32 <8 hex digits>` line,
 * which covers every byte before it. A first line that starts with `OK ` or `ERR ` is a complete reply on its own.
 * One assembler follows one connection and one command at a time.
 */
class ReplyAssembler {
    private var pending = ByteArray(0)
    private val frameBytes = java.io.ByteArrayOutputStream()
    private val lines = mutableListOf<String>()

    /** Drops a half-received reply. */
    fun reset() {
        pending = ByteArray(0)
        frameBytes.reset()
        lines.clear()
    }

    /** The reply when the time ran out before the `CRC32` line. */
    fun timeout(): ConsoleReply {
        reset()
        return ConsoleReply.Corrupt(ReplyError.Timeout)
    }

    /** Feeds one chunk and returns the reply that it completed, else null. */
    fun append(chunk: ByteArray): ConsoleReply? {
        pending += chunk
        while (true) {
            val lf = pending.indexOf('\n'.code.toByte())
            if (lf < 0) break
            val raw = pending.copyOfRange(0, lf + 1)
            pending = pending.copyOfRange(lf + 1, pending.size)
            val text = String(raw, 0, lf, Charsets.UTF_8).removeSuffix("\r")
            consume(text, raw)?.let {
                // Bytes after the end of a reply belong to no command of this assembler.
                pending = ByteArray(0)
                return it
            }
        }
        if (pending.size > MAX_LINE_BYTES || lines.size > MAX_LINES) {
            reset()
            return ConsoleReply.Corrupt(ReplyError.TooLarge)
        }
        return null
    }

    private fun consume(line: String, raw: ByteArray): ConsoleReply? {
        if (lines.isEmpty() && (line.startsWith("OK ") || line.startsWith("ERR "))) {
            return ConsoleReply.Status(line)
        }
        if (line.startsWith("CRC32 ")) {
            val hex = line.substring(6)
            val collected = lines.toList()
            val bytes = frameBytes.toByteArray()
            lines.clear()
            frameBytes.reset()
            val actual = if (hex.length == 8) hex.toLongOrNull(16) else null
            if (actual == null) return ConsoleReply.Corrupt(ReplyError.MalformedCrcLine(line))
            val expected = Checksums.crc32(bytes)
            if (expected != actual) return ConsoleReply.Corrupt(ReplyError.BadCrc(expected, actual))
            return ConsoleReply.Frame(collected)
        }
        lines.add(line)
        frameBytes.write(raw)
        return null
    }

    companion object {
        /** Longest line without LF and most lines in one reply. The firmware replies stay far below both. */
        const val MAX_LINE_BYTES = 4096
        const val MAX_LINES = 512
    }
}
