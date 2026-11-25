package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SensorHealthTest {

    private fun status(sensors: List<String>, reception: String? = null): StatusReply {
        val lines = mutableListOf("SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK")
        lines += sensors
        lines += "POWER src=USB v=4.05 pct=88 alerts=00 muted=0"
        if (reception != null) lines += reception
        return StatusReply.parse(lines)
    }

    private fun verdict(sensor: String, reception: String? = null, limit: Double? = null): SensorHealth.Verdict =
        SensorHealth.rows(status(listOf(sensor), reception), limit)[0].verdict

    @Test
    fun eachVerdictFromFixture() {
        val frame = ReplyAssembler().append(Fixtures.file("status_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        val rows = SensorHealth.rows(StatusReply.parse(frame.lines), null)
        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { it.slot })
        // Slot 5 has rssi -90, so WEAK_SIGNAL wins over its rate.
        assertEquals(
            listOf(
                SensorHealth.Verdict.GOOD, SensorHealth.Verdict.EMPTY, SensorHealth.Verdict.STALE,
                SensorHealth.Verdict.BATTERY, SensorHealth.Verdict.WEAK_SIGNAL
            ),
            rows.map { it.verdict }
        )
        assertEquals("Wohnzimmer", rows[0].name)
        assertEquals("Move the sensor nearer to the panel.", rows[4].verdict.advice)
    }

    @Test
    fun verdictOrder() {
        assertEquals(SensorHealth.Verdict.STALE, verdict("SENSOR 1 A temp=1 rh=1 bat=1 rssi=-95 age=9 state=STALE"))
        assertEquals(SensorHealth.Verdict.EMPTY, verdict("SENSOR 1 A temp=-- rh=-- bat=-- rssi=-95 age=-- state=EMPTY"))
        assertEquals(SensorHealth.Verdict.BATTERY, verdict("SENSOR 1 A temp=1 rh=1 bat=3 rssi=-95 age=9 state=BATLOW"))
    }

    @Test
    fun batteryLimitPlusFive() {
        val line = "SENSOR 1 A temp=1 rh=1 bat=15 rssi=-60 age=9 state=OK"
        assertEquals(SensorHealth.Verdict.GOOD, verdict(line))
        assertEquals(SensorHealth.Verdict.BATTERY, verdict(line, limit = 10.0))
        assertEquals(SensorHealth.Verdict.GOOD, verdict("SENSOR 1 A temp=1 rh=1 bat=16 rssi=-60 age=9 state=OK", limit = 10.0))
        assertEquals(SensorHealth.Verdict.GOOD, verdict(line, limit = 0.0))
    }

    @Test
    fun receptionRuleNeedsSixScans() {
        val line = "SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK"
        val rest = " s2=-- s3=-- s4=-- s5=--"
        assertEquals(SensorHealth.Verdict.GOOD, verdict(line, "RECEPTION s1=2/5$rest"))
        assertEquals(SensorHealth.Verdict.WEAK_SIGNAL, verdict(line, "RECEPTION s1=2/6$rest"))
        assertEquals(SensorHealth.Verdict.GOOD, verdict(line, "RECEPTION s1=3/6$rest"))
    }

    @Test
    fun rssiOnlyWithoutReceptionLine() {
        assertEquals(SensorHealth.Verdict.WEAK_SIGNAL, verdict("SENSOR 1 A temp=1 rh=1 bat=80 rssi=-86 age=9 state=OK"))
        assertEquals(SensorHealth.Verdict.GOOD, verdict("SENSOR 1 A temp=1 rh=1 bat=80 rssi=-85 age=9 state=OK"))
    }

    @Test
    fun nonNumericFieldThrows() {
        try {
            SensorHealth.rows(status(listOf("SENSOR 1 A temp=1 rh=1 bat=80 rssi=x age=9 state=OK")), null)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun missingStateThrows() {
        try {
            SensorHealth.rows(status(listOf("SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9")), null)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun duplicateSlotThrows() {
        try {
            SensorHealth.rows(
                status(
                    listOf(
                        "SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK",
                        "SENSOR 1 B temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK"
                    )
                ),
                null
            )
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun skipsSlotsOutsideOneToTen() {
        val rows = SensorHealth.rows(
            status(
                listOf(
                    "SENSOR 11 X temp=1 rh=1 bat=80 rssi=x age=9 state=OK",
                    "SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK"
                )
            ),
            null
        )
        assertEquals(listOf(1), rows.map { it.slot })
    }

    @Test
    fun emptySlotHasNoReception() {
        val rows = SensorHealth.rows(
            status(
                listOf("SENSOR 1 A temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY"),
                "RECEPTION s1=-- s2=-- s3=-- s4=-- s5=--"
            ),
            null
        )
        assertEquals(null, rows[0].reception)
        assertEquals(SensorHealth.Verdict.EMPTY, rows[0].verdict)
    }

    @Test
    fun formatGivesBadge() {
        val formats = listOf(
            "SHTC3" to "[I2C]", "WOIO" to "[SB]", "METER" to "[SB]", "ATC" to "[ATC]",
            "BTHOME" to "[BTH]", "THERMOPRO" to "[TP]"
        )
        formats.forEachIndexed { i, (format, badge) ->
            val line = "SENSOR ${i + 1} A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK format=$format"
            val row = SensorHealth.rows(status(listOf(line)), null)[0]
            assertEquals(format, row.format)
            assertEquals(badge, row.badge)
            assertEquals(badge, SensorHealth.badgeFor(format))
        }
    }

    @Test
    fun missingFormatGivesNull() {
        val row = SensorHealth.rows(status(listOf("SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK")), null)[0]
        assertEquals(null, row.format)
        assertEquals(null, row.badge)
        assertEquals(null, SensorHealth.badgeFor(null))
    }

    @Test
    fun emptyAndUnknownFormatGiveNoBadge() {
        val rows = SensorHealth.rows(
            status(
                listOf(
                    "SENSOR 1 A temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY format=--",
                    "SENSOR 2 B temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK format=NEW"
                )
            ),
            null
        )
        assertEquals(null, rows[0].format)
        assertEquals(null, rows[0].badge)
        assertEquals("NEW", rows[1].format)
        assertEquals(null, rows[1].badge)
    }

    @Test
    fun statusSensorCarriesFormat() {
        val reply = StatusReply.parse(
            listOf(
                "SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK format=SHTC3",
                "SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK",
                "POWER src=USB v=4.05 pct=88 alerts=00 muted=0"
            )
        )
        assertEquals("SHTC3", reply.sensors[0].format)
        assertEquals("[I2C]", reply.sensors[0].badge)
        assertEquals(null, reply.sensors[1].format)
        assertEquals(null, reply.sensors[1].badge)
    }

    @Test
    fun moldHoursFollowTheSlot() {
        val frame = ReplyAssembler().append(Fixtures.file("status_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        val rows = SensorHealth.rows(StatusReply.parse(frame.lines), null)
        assertEquals(listOf<Int?>(3, null, null, 5, null), rows.map { it.moldHours })
        val none = SensorHealth.rows(status(listOf("SENSOR 1 A temp=1 rh=1 bat=1 rssi=-60 age=9 state=OK")), null)
        assertEquals(null, none[0].moldHours)
    }

    @Test
    fun dryHoursFollowTheSlot() {
        val lines = listOf("SENSOR 1 A temp=1 rh=1 bat=1 rssi=-60 age=9 state=OK dry_h=4", "SENSOR 2 B temp=1 rh=1 bat=1 rssi=-60 age=9 state=OK dry_h=--", "SENSOR 3 C temp=1 rh=1 bat=1 rssi=-60 age=9 state=OK")
        val rows = SensorHealth.rows(status(lines), null)
        assertEquals(listOf<Int?>(4, null, null), rows.map { it.dryHours })
    }
}
