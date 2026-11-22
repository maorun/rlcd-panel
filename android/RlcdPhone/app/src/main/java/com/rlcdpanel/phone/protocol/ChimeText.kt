package com.rlcdpanel.phone.protocol

/** Readable text of the chime reminder of a `CHIME` line. */
object ChimeText {
    /** `<H> h <M> min` of the remaining snooze seconds (rounded up to whole minutes). */
    fun snoozeRemaining(seconds: Long): String {
        val minutes = (seconds + 59) / 60
        return "${minutes / 60} h ${minutes % 60} min"
    }

    fun reminder(reminder: ChimeReminder): String {
        if (reminder.repeatHours == 0) return "Erinnerung aus"
        val next = reminder.nextS
        if (reminder.bits == 0 || next == null) return "Keine Erinnerung fällig"
        return "Nächste Erinnerung in ${next / 3600} h ${next % 3600 / 60} min"
    }
}
