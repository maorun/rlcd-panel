package com.rlcdpanel.phone.protocol

/** CSV text of the event rows for the share intent. The rows stay oldest first. */
object EventCsv {
    const val HEADER = "time,event,detail,text"

    /** One header line, then one line for each row. Each line ends with a newline. */
    fun format(rows: List<EventRow>): String {
        val out = StringBuilder(HEADER).append('\n')
        for (row in rows) {
            out.append(
                listOf(row.time, row.event, row.detail.toString(), EventText.describe(row))
                    .joinToString(",") { quote(it) }
            ).append('\n')
        }
        return out.toString()
    }

    /** Quotes a field that holds a comma, a quote or a line break, and doubles each inner quote. */
    internal fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else field
}
