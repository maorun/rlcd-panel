package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.ChimeReminder
import com.rlcdpanel.phone.protocol.ChimeText
import org.junit.Assert.assertEquals
import org.junit.Test

class ChimeTextTest {
    @Test
    fun showsNextReminder() {
        assertEquals("Nächste Erinnerung in 1 h 0 min", ChimeText.reminder(ChimeReminder(2, 2, 3600, 3600)))
        assertEquals("Nächste Erinnerung in 0 h 1 min", ChimeText.reminder(ChimeReminder(2, 2, 3600, 119)))
    }

    @Test
    fun showsReminderOff() {
        assertEquals("Erinnerung aus", ChimeText.reminder(ChimeReminder(0, 2, null, null)))
    }

    @Test
    fun showsNoReminderDue() {
        assertEquals("Keine Erinnerung fällig", ChimeText.reminder(ChimeReminder(2, 0, null, null)))
        assertEquals("Keine Erinnerung fällig", ChimeText.reminder(ChimeReminder(2, 2, null, null)))
    }

    @Test
    fun showsSnoozeRemainingRoundedUp() {
        assertEquals("0 h 1 min", ChimeText.snoozeRemaining(1))
        assertEquals("0 h 1 min", ChimeText.snoozeRemaining(60))
        assertEquals("0 h 59 min", ChimeText.snoozeRemaining(3540))
        assertEquals("1 h 0 min", ChimeText.snoozeRemaining(3600))
        assertEquals("12 h 0 min", ChimeText.snoozeRemaining(43200))
    }
}
