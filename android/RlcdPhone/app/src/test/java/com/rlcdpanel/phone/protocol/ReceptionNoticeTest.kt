package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionNoticeTest {

    private fun fixtureStatus(): StatusReply {
        val reply = ReplyAssembler().append(Fixtures.file("status_reply_sample.txt").readBytes())
            ?: throw AssertionError("fixture gave no reply")
        return StatusReply.parse((reply as ConsoleReply.Frame).lines)
    }

    private fun withMask(status: StatusReply, mask: Int?): StatusReply =
        status.copy(latch = status.latch!!.copy(receptionLow = mask))

    private fun names(status: StatusReply): List<String?> = status.sensors.map { it.name }

    @Test
    fun fixtureMaskGivesNameAndIdOfEachSlot() {
        val status = fixtureStatus()
        val notice = ReceptionNotice()
        assertTrue(notice.plan(withMask(status, 0), names(status)).isEmpty())
        val plan = notice.plan(status, names(status))
        assertEquals(listOf(2, 10), plan.map { it.slot })
        assertEquals("Weak signal: Keller", plan[0].message)
        assertEquals(2202, plan[0].notificationId)
        assertEquals("Weak signal: 10", plan[1].message)
        assertEquals(2210, plan[1].notificationId)
    }

    @Test
    fun firstReplyGivesNoNotification() {
        val status = fixtureStatus()
        assertEquals(0x202, status.latch!!.receptionLow)
        assertTrue(ReceptionNotice().plan(status, names(status)).isEmpty())
    }

    @Test
    fun replyWithoutRxlowGivesNoNotification() {
        val status = fixtureStatus()
        val notice = ReceptionNotice()
        notice.plan(withMask(status, 0), names(status))
        assertNull(withMask(status, null).latch!!.receptionLow)
        assertTrue(notice.plan(withMask(status, null), names(status)).isEmpty())
    }

    @Test
    fun unknownSensorGivesSlotNumber() {
        val status = fixtureStatus()
        val notice = ReceptionNotice()
        notice.plan(withMask(status, 0), emptyList())
        val plan = notice.plan(withMask(status, 0b100), listOf("Onboard", "A", " "))
        assertEquals(1, plan.size)
        assertEquals("Weak signal: 3", plan[0].message)
        assertEquals(2203, plan[0].notificationId)
    }

    @Test
    fun panelConfigWithoutNameGivesDefaultSlotTitle() {
        val status = fixtureStatus()
        val notice = ReceptionNotice()
        val config = PanelConfig(mapOf("slot2_name" to "Keller"))
        notice.plan(withMask(status, 0), config.tileNames)
        val plan = notice.plan(status, config.tileNames)
        assertEquals(listOf("Weak signal: Keller", "Weak signal: Slot 10"), plan.map { it.message })
    }

    @Test
    fun nullMaskKeepsStoredMaskAndRiseAfterwardsCounts() {
        val status = fixtureStatus()
        val notice = ReceptionNotice()
        notice.plan(withMask(status, 0), names(status))
        assertTrue(notice.plan(withMask(status, null), names(status)).isEmpty())
        assertEquals(listOf(3), notice.plan(withMask(status, 0b100), names(status)).map { it.slot })
    }

    @Test
    fun equalMaskAndFallingBitGiveNoNotification() {
        val status = fixtureStatus()
        val notice = ReceptionNotice()
        notice.plan(withMask(status, 0b110), names(status))
        assertTrue(notice.plan(withMask(status, 0b110), names(status)).isEmpty())
        assertTrue(notice.plan(withMask(status, 0b010), names(status)).isEmpty())
    }
}
