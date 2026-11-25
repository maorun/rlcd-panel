package com.rlcdpanel.phone.protocol

/** One line `<key>=<value>` of the `CONFIG CHANGED` reply: a key whose value differs from the default. */
data class ConfigChangedEntry(val key: String, val value: String)

/**
 * Parser of the `CONFIG CHANGED` reply (`SerialConsoleRunner::showConfigChanged` of the firmware): one line
 * `<key>=<value>` for each key that differs from the default, then `END CHANGED <N>`.
 */
object ConfigChangedReply {
    private val END = Regex("^END CHANGED (0|[1-9][0-9]*)$")

    /**
     * [lines] hold the entries and the `END CHANGED <N>` line, without the `CRC32` line. A line without `=`, an unknown
     * or repeated key, a missing `END` line and a count that differs from the line count throw [ParseException].
     */
    fun parse(lines: List<String>): List<ConfigChangedEntry> {
        val end = lines.lastOrNull() ?: throw ParseException("END CHANGED")
        val count = END.matchEntire(end)?.groupValues?.get(1)?.toIntOrNull() ?: throw ParseException(end)
        val entries = ArrayList<ConfigChangedEntry>()
        val seen = HashSet<String>()
        for (line in lines.dropLast(1)) {
            val eq = line.indexOf('=')
            if (eq <= 0) throw ParseException(line)
            val key = line.substring(0, eq)
            if (ConfigSchema.entry(key) == null || !seen.add(key)) throw ParseException(line)
            entries.add(ConfigChangedEntry(key, line.substring(eq + 1)))
        }
        if (entries.size != count) throw ParseException(end)
        return entries
    }
}
