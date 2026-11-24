package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.BluetoothAddressCheck
import com.rlcdpanel.watch.SensorHealthText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSensorMacTest {
    private fun sensor(line: String) = StatusReply.parse(listOf(line)).sensors.single()

    @Test
    fun macFieldIsParsedAndUpperCased() {
        val s = sensor("SENSOR 1 Wohnzimmer temp=22.1 rh=48 bat=87 rssi=-61 age=14 state=OK format=WOIO mac=aa:bb:cc:dd:ee:01 mold_h=3")
        assertEquals("AA:BB:CC:DD:EE:01", s.mac)
        assertTrue(SensorHealthText.canMeter(s))
    }

    @Test
    fun missingDashAndBadMacGiveNull() {
        assertNull(sensor("SENSOR 1 A temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY").mac)
        assertNull(sensor("SENSOR 1 A temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY mac=--").mac)
        assertNull(sensor("SENSOR 1 A temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY mac=xyz").mac)
    }

    @Test
    fun onboardSensorAndSensorWithoutMacHaveNoMeter() {
        assertFalse(SensorHealthText.canMeter(sensor("SENSOR 0 Onboard temp=21 rh=45 bat=-- rssi=-- age=1 state=OK mac=AA:BB:CC:DD:EE:01")))
        assertFalse(SensorHealthText.canMeter(sensor("SENSOR 2 B temp=21 rh=45 bat=-- rssi=-- age=1 state=OK")))
    }

    @Test
    fun scanAddressCheck() {
        assertTrue(BluetoothAddressCheck.isValid("AA:BB:CC:DD:EE:01"))
        assertFalse(BluetoothAddressCheck.isValid("aa:bb:cc:dd:ee:01"))
        assertFalse(BluetoothAddressCheck.isValid("AA:BB:CC:DD:EE"))
    }
}
