package com.rlcdpanel.watch.protocol

/** Parsed `VERSION` reply: `VERSION fw=<version> git=<hash> core=<core> state=<version> built=<timestamp>`. */
data class VersionReply(
    val firmware: String,
    val git: String,
    val core: String,
    val state: String,
    /** Build time as the panel reports it (`unknown` when the build has none). */
    val built: String
) {
    companion object {
        /** [lines] hold the `VERSION` line. A missing line or a missing or empty field throws [ParseException]. */
        fun parse(lines: List<String>): VersionReply {
            val line = lines.firstOrNull { it.startsWith("VERSION ") } ?: throw ParseException("VERSION")
            val fields = HashMap<String, String>()
            for (token in line.split(" ").drop(1)) {
                val i = token.indexOf('=')
                if (i > 0) fields.putIfAbsent(token.substring(0, i), token.substring(i + 1))
            }
            fun field(key: String): String = fields[key]?.takeIf { it.isNotEmpty() } ?: throw ParseException(line)
            return VersionReply(field("fw"), field("git"), field("core"), field("state"), field("built"))
        }
    }
}
