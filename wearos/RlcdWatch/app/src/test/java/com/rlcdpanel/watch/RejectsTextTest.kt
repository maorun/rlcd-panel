package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.RejectReason
import com.rlcdpanel.watch.protocol.RejectRow
import org.junit.Assert.assertEquals
import org.junit.Test

class RejectsTextTest {
    @Test
    fun rowShowsLastFiveMacCharactersFormatReasonAndCount() {
        val row = RejectRow("AA:00:00:00:00:13", "METER", RejectReason.FULL, 2)
        assertEquals("00:13", RejectsText.shortMac(row.mac))
        assertEquals("METER FULL x2", RejectsText.detail(row))
    }

    @Test
    fun droppedTextOnlyForPositiveCount() {
        assertEquals(null, RejectsText.dropped(0))
        assertEquals("DROPPED 3", RejectsText.dropped(3))
    }
}
