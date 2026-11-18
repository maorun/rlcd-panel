package com.rlcdpanel.phone.protocol

/** Wire format of one console command. No Android types, so a unit test runs it on the JVM. */
object ConsoleWire {
    /** ATT header bytes that the MTU includes. */
    const val ATT_OVERHEAD = 3
    /** MTU before the negotiation. */
    const val DEFAULT_MTU = 23

    /** The command bytes plus the closing LF. A command with a line break is invalid. */
    fun frame(command: String): ByteArray {
        require(command.isNotEmpty() && '\n' !in command && '\r' !in command) { "Bad console command" }
        return (command + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Slices [frame] into writes of at most `max(1, mtu - 3)` bytes. */
    fun chunks(frame: ByteArray, mtu: Int): List<ByteArray> {
        val size = maxOf(1, mtu - ATT_OVERHEAD)
        return frame.toList().chunked(size).map { it.toByteArray() }
    }
}
