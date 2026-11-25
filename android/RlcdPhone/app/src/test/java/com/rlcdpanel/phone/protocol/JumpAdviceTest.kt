package com.rlcdpanel.phone.protocol

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpAdviceTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 25, 12, 0, 0).toInstant(zone).toEpochMilli()
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun drop(slot: Int, hoursAgo: Long, event: String = "JUMP_DROP"): EventRow =
        EventRow(LocalDateTime.of(2026, 11, 25, 12, 0, 0).minusHours(hoursAgo).format(format), event, slot)

    private fun config(limit: String? = "8.0") =
        PanelConfig(if (limit == null) emptyMap() else mapOf("jump_limit_c" to limit))

    @Test
    fun threeDropsOfSlot4GiveOneAdvice() {
        val rows = listOf(drop(4, 1), drop(4, 5), drop(4, 20))
        val advice = JumpAdvice.evaluate(rows, config(), now, zone)
        assertEquals(1, advice.size)
        assertEquals(4, advice[0].slot)
        assertEquals(3, advice[0].drops)
        assertEquals(CalibrationProposal("jump_limit_c", "10.0"), advice[0].proposal)
    }

    @Test
    fun twoDropsGiveNoAdvice() {
        assertTrue(JumpAdvice.evaluate(listOf(drop(4, 1), drop(4, 2)), config(), now, zone).isEmpty())
    }

    @Test
    fun dropsOlderThan24HoursGiveNoAdvice() {
        val rows = listOf(drop(4, 25), drop(4, 30), drop(4, 40), drop(4, 1))
        assertTrue(JumpAdvice.evaluate(rows, config(), now, zone).isEmpty())
    }

    @Test
    fun dropsOfDifferentSlotsDoNotAdd() {
        val rows = listOf(drop(1, 1), drop(2, 1), drop(3, 1))
        assertTrue(JumpAdvice.evaluate(rows, config(), now, zone).isEmpty())
    }

    @Test
    fun otherEventsAndBadTimesAreIgnored() {
        val rows = listOf(drop(4, 1, "SLOT_STALE"), drop(4, 2, "SLOT_STALE"), drop(4, 3, "SLOT_STALE"), EventRow("", "JUMP_DROP", 4))
        assertTrue(JumpAdvice.evaluate(rows, config(), now, zone).isEmpty())
    }

    @Test
    fun proposalAddsTwoToTheActiveValue() {
        val rows = listOf(drop(2, 1), drop(2, 2), drop(2, 3))
        assertEquals("13.5", JumpAdvice.evaluate(rows, config("11.5"), now, zone)[0].proposal.value)
    }

    @Test
    fun proposalClampsAt20() {
        val rows = listOf(drop(2, 1), drop(2, 2), drop(2, 3))
        assertEquals("20.0", JumpAdvice.evaluate(rows, config("19.0"), now, zone)[0].proposal.value)
    }

    @Test
    fun limitAlreadyAt20GivesNoAdvice() {
        val rows = listOf(drop(2, 1), drop(2, 2), drop(2, 3))
        assertTrue(JumpAdvice.evaluate(rows, config("20.0"), now, zone).isEmpty())
    }

    @Test
    fun limitZeroGivesNoAdvice() {
        val rows = listOf(drop(2, 1), drop(2, 2), drop(2, 3))
        assertTrue(JumpAdvice.evaluate(rows, config("0"), now, zone).isEmpty())
        assertTrue(JumpAdvice.evaluate(rows, config("0.0"), now, zone).isEmpty())
    }

    @Test
    fun missingLimitGivesNoAdvice() {
        val rows = listOf(drop(2, 1), drop(2, 2), drop(2, 3))
        assertTrue(JumpAdvice.evaluate(rows, config(null), now, zone).isEmpty())
    }

    @Test
    fun windowBoundaryIsExactly24Hours() {
        assertTrue(JumpAdvice.evaluate(listOf(drop(2, 24), drop(2, 1), drop(2, 2)), config(), now, zone).isEmpty())
        assertEquals(1, JumpAdvice.evaluate(listOf(drop(2, 23), drop(2, 1), drop(2, 2)), config(), now, zone).size)
    }

    @Test
    fun detailOutsideSlot1To10DoesNotCount() {
        assertTrue(JumpAdvice.evaluate(listOf(drop(0, 1), drop(0, 2), drop(0, 3)), config(), now, zone).isEmpty())
        assertTrue(JumpAdvice.evaluate(listOf(drop(11, 1), drop(11, 2), drop(11, 3)), config(), now, zone).isEmpty())
    }

    @Test
    fun badLimitTextGivesNoAdvice() {
        val rows = listOf(drop(2, 1), drop(2, 2), drop(2, 3))
        assertTrue(JumpAdvice.evaluate(rows, config("abc"), now, zone).isEmpty())
    }

    @Test
    fun severalSlotsGiveAdviceInSlotOrder() {
        val rows = listOf(drop(5, 1), drop(2, 1), drop(5, 2), drop(2, 2), drop(5, 3), drop(2, 3))
        assertEquals(listOf(2, 5), JumpAdvice.evaluate(rows, config(), now, zone).map { it.slot })
    }

    @Test
    fun sampleEventsReplyGivesNoAdvice() {
        val lines = Fixtures.file("events_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val log = EventsReply.parse(lines)
        // The sample holds one JUMP_DROP row; the time of the sample is the newest row.
        val sampleNow = AlertTimeline.parse("2026-11-18 09:30:00", zone)!!
        assertTrue(JumpAdvice.evaluate(log.rows, config(), sampleNow, zone).isEmpty())
        // Two more drops of slot 3 turn the same sample into 3 drops, so the rule counts the fixture row.
        val more = log.rows + listOf(EventRow("2026-11-18 09:25:00", "JUMP_DROP", 3), EventRow("2026-11-18 09:26:00", "JUMP_DROP", 3))
        assertEquals(listOf(3), JumpAdvice.evaluate(more, config(), sampleNow, zone).map { it.slot })
    }

    @Test
    fun textNamesTheRoom() {
        val advice = JumpAdvice.evaluate(listOf(drop(4, 1), drop(4, 2), drop(4, 3)), config(), now, zone)[0]
        assertEquals("Sprung-Filter verwirft Messwerte von Küche: 3 in 24 h. Grenze 8.0 → 10.0 °C", advice.text("Küche"))
    }
}
