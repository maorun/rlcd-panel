package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TailReplyTest {
    /** A row with the 42 columns: onboard 21.5 C and 45.0 %RH, slot 1 with -3.5 C, 80 %RH and 90 % battery. */
    private fun row(time: String = "2026-03-01 04:00:00") =
        "$time,87,4.00,1,21.5,45.0,-3.5,80.0,90" + ",".repeat(27) + ",,,,,,"

    private fun reply(vararg rows: String) = listOf("BEGIN TAIL ${rows.size}", TailReply.HEADER) + rows

    @Test
    fun headerHas42Columns() {
        assertEquals(TailReply.COLUMNS, TailReply.HEADER.split(",").size)
        assertEquals(TailReply.COLUMNS, row().split(",").size)
    }

    @Test
    fun parsesRowsOldestFirst() {
        val rows = TailReply.parse(reply(row("2026-03-01 03:00:00"), row("2026-03-01 04:00:00")))
        assertEquals(listOf("2026-03-01 03:00:00", "2026-03-01 04:00:00"), rows.map { it.timestamp })
        val r = rows[1]
        assertEquals(87, r.panelBatteryPct)
        assertEquals(4.0, r.panelBatteryV!!, 0.0)
        assertEquals(true, r.usb)
        assertEquals(TailSensor(21.5, 45.0, null), r.onboard)
        assertEquals(TailSensor(-3.5, 80.0, 90), r.slots[0])
        assertEquals(TailReply.SLOT_COUNT, r.slots.size)
        assertEquals(TailSensor(null, null, null), r.slots[9])
    }

    @Test
    fun emptyReplyGivesNoRows() {
        assertTrue(TailReply.parse(reply()).isEmpty())
    }

    @Test
    fun emptyFieldsAreMissingValues() {
        val empty = "2026-03-01 04:00:00" + ",".repeat(41)
        val r = TailReply.parse(reply(empty)).single()
        assertNull(r.panelBatteryPct)
        assertNull(r.panelBatteryV)
        assertNull(r.usb)
        assertEquals(TailSensor(null, null, null), r.onboard)
    }

    @Test
    fun wrongColumnCountThrows() {
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row() + ",1")) }
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row().substringBeforeLast(","))) }
        assertThrows(ParseException::class.java) { TailReply.parse(reply("2026-03-01 04:00:00,87,4.00")) }
    }

    @Test
    fun badValuesThrow() {
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row("2026-03-01T04:00:00"))) }
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row("2026-02-30 04:00:00"))) }
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row("2026-03-01 24:00:00"))) }
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row().replace(",1,21.5,", ",2,21.5,"))) }
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row().replace("45.0", "101.0"))) }
        assertThrows(ParseException::class.java) { TailReply.parse(reply(row().replace("21.5", "21.55"))) }
    }

    @Test
    fun badBatteryValuesGiveNullLikeTheFirmware() {
        val r = TailReply.parse(reply(row().replace(",87,4.00,", ",101,x,").replace(",90,", ",abc,"))).single()
        assertNull(r.panelBatteryPct)
        assertNull(r.panelBatteryV)
        assertNull(r.slots[0].batteryPct)
        assertEquals(-3.5, r.slots[0].temperatureC!!, 0.0)
    }

    @Test
    fun badFramingThrows() {
        assertThrows(ParseException::class.java) { TailReply.parse(emptyList()) }
        assertThrows(ParseException::class.java) { TailReply.parse(listOf("BEGIN WEEK", TailReply.HEADER)) }
        assertThrows(ParseException::class.java) { TailReply.parse(listOf("BEGIN TAIL 0", "timestamp")) }
        assertThrows(ParseException::class.java) { TailReply.parse(listOf("BEGIN TAIL 2", TailReply.HEADER, row())) }
        assertThrows(ParseException::class.java) { TailReply.parse(listOf("BEGIN TAIL 0", TailReply.HEADER, row())) }
        assertThrows(ParseException::class.java) { TailReply.parse(listOf("BEGIN TAIL 05", TailReply.HEADER)) }
    }

    private fun fixtureLines(): List<String> =
        Fixtures.file("tail_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun sharedFixtureParses() {
        val rows = TailReply.parse(fixtureLines())
        assertEquals(
            listOf("2026-03-28 23:00:00", "2026-03-01 03:00:00", "2026-03-01 04:00:00"),
            rows.map { it.timestamp }
        )
        assertEquals(TailSensor(-3.5, 80.0, 90), rows[0].slots[0])
        assertEquals(true, rows[2].usb)
    }

    @Test
    fun sharedFixtureCrcMatches() {
        val text = Fixtures.file("tail_reply_sample.txt").readText()
        assertEquals(ConsoleReply.Frame(fixtureLines()), ReplyAssembler().append(text.toByteArray()))
    }
}
