package com.rlcdpanel.watch.protocol

/** Advertisement format of a sensor, the same names as `BleFormat` of the firmware. */
enum class SensorFormat(val badge: String) {
    WOIO("SB"), METER("SB"), BTHOME("BTH"), ATC("ATC"), THERMOPRO("TP")
}

/**
 * One decoded sensor advertisement. [mac] is `AA:BB:CC:DD:EE:FF`. [batteryPct] is -1 without a battery value.
 * The decoder follows the firmware parsers (`switchbot_ble.h`, `bthome_ble.h`, `atc_ble.h`, `thermopro_ble.h`)
 * and reads the same lines of `tests/fixtures/sensor_adv_vectors.txt`.
 */
data class SensorAdvertisement(
    val format: SensorFormat,
    val mac: String,
    val temperatureC: Double,
    val humidityPct: Int,
    val batteryPct: Int
) {
    companion object {
        const val SWITCHBOT_COMPANY_ID = 0x0969
        const val SWITCHBOT_SERVICE_UUID = 0xFD3D
        const val BTHOME_SERVICE_UUID = 0xFCD2
        const val ATC_SERVICE_UUID = 0x181A
        const val THERMOPRO_HEADER = 0xC2
        private const val TEMP_MIN_C = -50.0
        private const val TEMP_MAX_C = 100.0

        /**
         * Decodes one advertisement in the dispatch order of the firmware: BTHome, ATC, ThermoPro, SwitchBot.
         * [bleMac] is the 6-byte BLE address (big-endian), [name] the advertised name or null, [mfg] the Manufacturer
         * Data with the 2-byte little-endian company ID first, [svcUuid] and [svc] the first Service Data
         * (UUID 16 bit, payload after the UUID). Returns null for a packet that the firmware rejects.
         */
        fun decode(bleMac: ByteArray, name: String?, mfg: ByteArray?, svcUuid: Int?, svc: ByteArray?): SensorAdvertisement? {
            if (bleMac.size != 6) return null
            val service = svc ?: ByteArray(0)
            val manufacturer = mfg ?: ByteArray(0)
            if (svcUuid == BTHOME_SERVICE_UUID) return bthome(bleMac, service)
            if (svcUuid == ATC_SERVICE_UUID) return atc(service)
            if (manufacturer.size >= 5 && u8(manufacturer, 0) == THERMOPRO_HEADER) return thermopro(bleMac, name, manufacturer)
            val serviceDataOfSwitchBot = if (svcUuid == SWITCHBOT_SERVICE_UUID) service else ByteArray(0)
            if (manufacturer.isEmpty() && serviceDataOfSwitchBot.isEmpty()) return null
            woio(manufacturer, serviceDataOfSwitchBot)?.let { return it }
            return meter(bleMac, serviceDataOfSwitchBot)
        }

        /** Text of a MAC with upper-case hex pairs. */
        fun macText(mac: ByteArray): String = mac.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

        private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

        private fun isZero(mac: ByteArray): Boolean = mac.all { it.toInt() == 0 }

        /** `sign(1 = positive)`, whole degrees and tenths of the SwitchBot packets. */
        private fun switchBotTemp(positive: Boolean, whole: Int, tenth: Int): Double {
            val t = whole + tenth * 0.1
            return if (!positive && (whole != 0 || tenth != 0)) -t else t
        }

        private fun woio(mfg: ByteArray, svc: ByteArray): SensorAdvertisement? {
            if (mfg.size < 13 || (u8(mfg, 0) or (u8(mfg, 1) shl 8)) != SWITCHBOT_COMPANY_ID) return null
            val p = mfg.copyOfRange(2, mfg.size)
            val frac = u8(p, 8) and 0x0F
            val humidity = u8(p, 10) and 0x7F
            if (frac > 9 || humidity > 100) return null
            val temp = switchBotTemp((u8(p, 9) and 0x80) != 0, u8(p, 9) and 0x7F, frac)
            var battery = -1
            if (svc.size >= 3 && (u8(svc, 2) and 0x7F) <= 100) battery = u8(svc, 2) and 0x7F
            return SensorAdvertisement(SensorFormat.WOIO, macText(p.copyOfRange(0, 6)), temp, humidity, battery)
        }

        private fun meter(bleMac: ByteArray, svc: ByteArray): SensorAdvertisement? {
            if (svc.size != 6 || (u8(svc, 0) != 0x54 && u8(svc, 0) != 0x69)) return null
            val battery = u8(svc, 2) and 0x7F
            val frac = u8(svc, 3) and 0x0F
            val humidity = u8(svc, 5) and 0x7F
            if (battery > 100 || frac > 9 || humidity > 100) return null
            val temp = switchBotTemp((u8(svc, 4) and 0x80) != 0, u8(svc, 4) and 0x7F, frac)
            return SensorAdvertisement(SensorFormat.METER, macText(bleMac), temp, humidity, battery)
        }

        private fun atc(p: ByteArray): SensorAdvertisement? {
            val mac = ByteArray(6)
            val temp: Double
            val humidity: Double
            val battery: Int
            when (p.size) {
                13 -> {
                    for (i in 0 until 6) mac[i] = p[i]
                    temp = ((u8(p, 6) shl 8) or u8(p, 7)).toShort() / 10.0
                    humidity = u8(p, 8).toDouble()
                    battery = u8(p, 9)
                }
                15 -> {
                    for (i in 0 until 6) mac[i] = p[5 - i]
                    temp = ((u8(p, 7) shl 8) or u8(p, 6)).toShort() / 100.0
                    humidity = ((u8(p, 9) shl 8) or u8(p, 8)) / 100.0
                    battery = u8(p, 12)
                }
                else -> return null
            }
            if (isZero(mac) || temp < TEMP_MIN_C || temp > TEMP_MAX_C || humidity > 100.0 || battery > 100) return null
            return SensorAdvertisement(SensorFormat.ATC, macText(mac), temp, Math.round(humidity).toInt(), battery)
        }

        private fun bthome(bleMac: ByteArray, p: ByteArray): SensorAdvertisement? {
            if (p.isEmpty() || (u8(p, 0) and 0x01) != 0 || (u8(p, 0) shr 5) != 2) return null
            var temp: Double? = null
            var humidity: Double? = null
            var battery = -1
            var pos = 1
            while (pos < p.size) {
                val id = u8(p, pos++)
                val len = bthomeLength(id)
                if (len == 0 || pos + len > p.size) break
                when (id) {
                    0x01 -> if (u8(p, pos) <= 100) battery = u8(p, pos)
                    0x02 -> {
                        val t = ((u8(p, pos + 1) shl 8) or u8(p, pos)).toShort() * 0.01
                        if (t >= TEMP_MIN_C && t <= TEMP_MAX_C) temp = t
                    }
                    0x03 -> {
                        val raw = (u8(p, pos + 1) shl 8) or u8(p, pos)
                        if (raw <= 10000) humidity = raw * 0.01
                    }
                    0x2E -> if (u8(p, pos) <= 100) humidity = u8(p, pos).toDouble()
                }
                pos += len
            }
            val t = temp ?: return null
            val h = humidity ?: return null
            return SensorAdvertisement(SensorFormat.BTHOME, macText(bleMac), t, Math.round(h).toInt(), battery)
        }

        private val BTHOME_LEN3 = intArrayOf(0x04, 0x05, 0x0A, 0x0B, 0x42, 0x4B)
        private val BTHOME_LEN4 = intArrayOf(0x3E, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x55, 0x5B, 0x5C)
        private val BTHOME_LEN1 = intArrayOf(0x3A, 0x46, 0x57, 0x58, 0x59, 0x60)
        private val BTHOME_LEN2 = intArrayOf(0x3C, 0x3D, 0x51, 0x52, 0x56, 0x5A, 0x5D, 0x5E, 0x5F)

        /** Value length of the BTHome v2 object IDs that the firmware can step over; 0 is unknown. */
        private fun bthomeLength(id: Int): Int = when {
            id in BTHOME_LEN3 -> 3
            id in BTHOME_LEN4 -> 4
            id <= 0x01 || id == 0x09 || id in 0x0F..0x11 || id in 0x15..0x2F || id in BTHOME_LEN1 -> 1
            id in 0x02..0x03 || id in 0x06..0x08 || id in 0x0C..0x0E || id in 0x12..0x14 ||
                id in BTHOME_LEN2 || id in 0x3F..0x41 || id in 0x43..0x45 || id in 0x47..0x4A -> 2
            else -> 0
        }

        private fun thermopro(bleMac: ByteArray, name: String?, p: ByteArray): SensorAdvertisement? {
            if (isZero(bleMac)) return null
            if (!name.isNullOrEmpty() && !(name.length >= 2 && name[0].uppercaseChar() == 'T' && name[1].uppercaseChar() == 'P')) {
                return null
            }
            val temp = ((u8(p, 2) shl 8) or u8(p, 1)).toShort() / 10.0
            if (temp < TEMP_MIN_C || temp > TEMP_MAX_C) return null
            val humidity = u8(p, 3)
            if (humidity > 100) return null
            return SensorAdvertisement(SensorFormat.THERMOPRO, macText(bleMac), temp, humidity, thermoproBattery(u8(p, 4)))
        }

        private fun thermoproBattery(raw: Int): Int {
            val bar = raw and 0x0F
            if (raw <= 2 || ((raw and 0xF0) == 0x20 && bar <= 2)) {
                return when (bar) { 2 -> 100; 1 -> 50; else -> 10 }
            }
            return if (raw <= 100) raw else -1
        }
    }
}
