package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuteTileModelTest {
    private fun fallback(reply: ConsoleReply): String = "Panel meldet: ${(reply as ConsoleReply.Status).line}"

    private fun status(muted: Int) = ConsoleReply.Frame(
        listOf("SENSOR 0 Onboard temp=1 rh=2 bat=-- rssi=-- age=1 state=OK", "POWER src=USB v=4.0 pct=80 alerts=00 muted=$muted")
    )

    @Test
    fun unavailableWithoutCachedReply() {
        val model = MuteTileModel.of(null)
        assertEquals(MuteTileState.UNAVAILABLE, model.state)
        assertEquals(MuteTileModel.UNAVAILABLE_TEXT, model.subtitle)
        assertNull(model.tapAction)
    }

    @Test
    fun mutedTapUnmutes() {
        val model = MuteTileModel.of(true)
        assertEquals(MuteTileState.MUTED, model.state)
        assertEquals(MuteTileModel.MUTED_TEXT, model.subtitle)
        assertEquals(MuteTileAction(false), model.tapAction)
    }

    @Test
    fun unmutedTapMutes() {
        val model = MuteTileModel.of(false)
        assertEquals(MuteTileState.UNMUTED, model.state)
        assertEquals(MuteTileModel.UNMUTED_TEXT, model.subtitle)
        assertEquals(MuteTileAction(true), model.tapAction)
    }

    @Test
    fun errorTextReplacesSubtitleAndKeepsState() {
        assertEquals("Set ble_console=2", MuteTileModel.of(false, "Set ble_console=2").subtitle)
        assertEquals(MuteTileState.UNMUTED, MuteTileModel.of(false, "x").state)
        assertEquals(MuteTileState.UNAVAILABLE, MuteTileModel.of(null, "x").state)
    }

    @Test
    fun readOnlyReplyShowsShortText() {
        assertEquals("Set ble_console=2", ChimeCommand.tileFailureText(ConsoleReply.Status("ERR READ_ONLY"), ::fallback))
        assertEquals("Set ble_console=2", ChimeCommand.tileFailureText(ConsoleReply.Status("ERR READ_ONLY extra"), ::fallback))
    }

    @Test
    fun readOnlyPrefixNeedsAWordBoundary() {
        assertEquals("Panel meldet: ERR READ_ONLYX", ChimeCommand.tileFailureText(ConsoleReply.Status("ERR READ_ONLYX"), ::fallback))
    }

    @Test
    fun otherRepliesKeepTheCommonTexts() {
        assertNull(ChimeCommand.tileFailureText(ConsoleReply.Status("OK MUTE 1"), ::fallback))
        assertEquals("Panel meldet: ERR BUSY", ChimeCommand.tileFailureText(ConsoleReply.Status("ERR BUSY"), ::fallback))
        assertEquals(ChimeCommand.READ_ONLY_TEXT, ChimeCommand.failureText(ConsoleReply.Status("ERR READ_ONLY"), ::fallback))
    }

    @Test
    fun failureTextIsNeverEmpty() {
        assertEquals("Set ble_console=2", MuteTileModel.failureText(ConsoleReply.Status("ERR READ_ONLY")) { "x" })
        assertNull(MuteTileModel.failureText(ConsoleReply.Status("OK MUTE 0")) { "x" })
        assertEquals("Panel meldet: ERR BUSY", MuteTileModel.failureText(ConsoleReply.Status("ERR BUSY"), ::fallback))
        // A Frame reply gives an empty fallback text; the tile shows a fixed text instead.
        assertEquals(MuteTileModel.UNREADABLE_TEXT, MuteTileModel.failureText(status(0)) { "" })
        assertEquals("Keine Antwort", MuteTileModel.failureText(ConsoleReply.Corrupt(ReplyError.Timeout)) { "Keine Antwort" })
    }

    @Test
    fun muteAckMatchesOnlyTheSentValue() {
        assertEquals(true, MuteTileModel.isMuteAck(ConsoleReply.Status("OK MUTE 1"), true))
        assertEquals(true, MuteTileModel.isMuteAck(ConsoleReply.Status("OK MUTE 0"), false))
        assertEquals(false, MuteTileModel.isMuteAck(ConsoleReply.Status("OK MUTE 0"), true))
        assertEquals(false, MuteTileModel.isMuteAck(ConsoleReply.Status("OK SNOOZE 5"), true))
        assertEquals(false, MuteTileModel.isMuteAck(status(1), true))
    }

    @Test
    fun labelJoinsTheSubtitleBelowSdk29() {
        val model = MuteTileModel.of(false, "Set ble_console=2")
        assertEquals("Panel stumm", MuteTileModel.labelWith(model, true))
        assertEquals("Panel stumm: Set ble_console=2", MuteTileModel.labelWith(model, false))
        assertEquals("Panel stumm", MuteTileModel.labelWith(MuteTileModel(MuteTileState.MUTED, "Panel stumm", null), false))
    }

    @Test
    fun mutedFromStatusReply() {
        assertEquals(true, MuteTileModel.mutedFrom(status(1)))
        assertEquals(false, MuteTileModel.mutedFrom(status(0)))
    }

    @Test
    fun mutedFromBadReplyIsNull() {
        assertNull(MuteTileModel.mutedFrom(ConsoleReply.Status("ERR READ_ONLY")))
        assertNull(MuteTileModel.mutedFrom(ConsoleReply.Corrupt(ReplyError.Timeout)))
        assertNull(MuteTileModel.mutedFrom(ConsoleReply.Frame(listOf("SENSOR 0 Onboard temp=1 rh=2 bat=-- rssi=-- age=1 state=OK"))))
    }
}
