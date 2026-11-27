package com.rlcdpanel.watch.protocol

/** State of the alert causes card: the last `ALERTS` reply, or the reason it has none. */
data class AlertCausesState(
    val isReading: Boolean = false,
    val lines: List<String>? = null,
    /** Active alert mask of the reply; the card shows [lines] only while it equals the alert mask of the frame. */
    val mask: Int? = null,
    val message: String? = null
) {
    /** True when the causes belong to the alerts that the frame shows now. */
    fun matches(frameMask: Int): Boolean = lines != null && mask == frameMask

    /** Lines of the alarm card for the alert mask [frameMask] of the newest frame. */
    fun cardLines(frameMask: Int): List<String> = when {
        isReading -> listOf(READING_TEXT)
        lines != null && matches(frameMask) -> lines
        message != null -> listOf(message)
        else -> listOf(TAP_TEXT)
    }

    companion object {
        const val READING_TEXT = "Lese Ursachen ..."
        const val TAP_TEXT = "Tippen zeigt die Ursachen"

        /** Builds the state from a console reply; [tileNames] are the names of tiles 0 to 10. */
        fun of(reply: ConsoleReply, tileNames: List<String>, corruptText: String = "Bad reply from the panel"): AlertCausesState = when (reply) {
            is ConsoleReply.Frame -> try {
                val log = AlertsReply.parse(reply.lines)
                AlertCausesState(lines = log.causeLines(tileNames), mask = log.activeMask)
            } catch (e: ParseException) {
                AlertCausesState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
            }
            is ConsoleReply.Status -> AlertCausesState(message = AlertsReply.errorText(reply.line))
            is ConsoleReply.Corrupt -> AlertCausesState(message = corruptText)
        }
    }
}
