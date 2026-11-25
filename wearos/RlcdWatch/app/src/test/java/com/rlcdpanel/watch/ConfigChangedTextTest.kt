package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ConfigChangedReply
import com.rlcdpanel.watch.protocol.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigChangedTextTest {
    private fun fixtureLines(): List<String> =
        Fixtures.file("config_changed_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun fixtureGivesOneRowForEachKeyInReplyOrder() {
        val state = ConfigChangedState(entries = ConfigChangedReply.parse(fixtureLines()), loaded = true)
        assertEquals(listOf("temp_offset_c", "mold_alert_limit_rh", "slot1_name"), state.entries.map { it.key })
        assertEquals(listOf("2.25", "65", "Bad"), state.entries.map { it.value })
    }

    @Test
    fun countZeroGivesALoadedEmptyListThatShowsTheDefaultText() {
        val state = ConfigChangedState(entries = ConfigChangedReply.parse(listOf("END CHANGED 0")), loaded = true)
        assertTrue(state.loaded && state.entries.isEmpty())
        assertEquals("Alle Werte Standard", ConfigChangedText.EMPTY)
    }

    @Test
    fun readCommandIsNotASet() {
        assertEquals("CONFIG CHANGED", ConfigChangedText.COMMAND)
    }
}
