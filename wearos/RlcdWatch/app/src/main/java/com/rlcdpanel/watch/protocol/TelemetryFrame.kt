package com.rlcdpanel.watch.protocol

/**
 * One climate tile of the telemetry frame. Temperature is always in Celsius.
 */
data class TelemetryTile(
    val temperatureC: Double?,
    val humidityPct: Double?
) {
    val isValid: Boolean
        get() = temperatureC != null && humidityPct != null
}

/**
 * Alert bits of the firmware (`audio_alert.h`).
 */
class AlertMask(rawBits: Int) {
    val rawValue: Int = rawBits and 0xFF

    val isEmpty: Boolean
        get() = rawValue == 0

    fun contains(flag: AlertMask): Boolean =
        flag.rawValue != 0 && (rawValue and flag.rawValue) == flag.rawValue

    override fun equals(other: Any?): Boolean =
        other is AlertMask && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue

    override fun toString(): String = "AlertMask(rawValue=$rawValue)"

    /**
     * Names of the set bits, lowest bit first.
     */
    val names: List<String>
        get() = ORDERED_NAMES.filter { (mask, _) -> contains(mask) }.map { (_, name) -> name }

    companion object {
        val NONE = AlertMask(0)
        val FREEZER = AlertMask(1)
        val HUMIDITY = AlertMask(2)
        val NOISE_HIGH = AlertMask(4)
        val SENSOR_LOST = AlertMask(8)
        val CHARGE_FULL = AlertMask(16)
        val VENTILATE = AlertMask(32)
        val SENSOR_BATTERY = AlertMask(64)
        val HEAT = AlertMask(128)

        private val ORDERED_NAMES = listOf(
            FREEZER to "FREEZER",
            HUMIDITY to "HUMIDITY",
            NOISE_HIGH to "NOISE HIGH",
            SENSOR_LOST to "SENSOR LOST",
            CHARGE_FULL to "CHARGE FULL",
            VENTILATE to "VENTILATE",
            SENSOR_BATTERY to "SENSOR BATTERY",
            HEAT to "HEAT"
        )
    }
}

/**
 * Error cases when decoding a BLE telemetry frame.
 */
sealed class TelemetryException(message: String) : Exception(message) {
    data class BadLength(val actual: Int) : TelemetryException("Invalid telemetry frame length: $actual")
    data class WrongCompanyId(val companyId: Int) : TelemetryException(
        String.format("Unexpected company ID: 0x%04X", companyId)
    )
    data class UnknownVersion(val version: Int) : TelemetryException("Unsupported telemetry version: $version")
    data class BadCrc(val expected: Int, val actual: Int) : TelemetryException(
        String.format("CRC-8 mismatch: expected 0x%02X, got 0x%02X", expected, actual)
    )
}

/**
 * Decoded Manufacturer Data frame of the BLE telemetry broadcast (`rlcd_panel/ble_telemetry.h`).
 *
 * Full Manufacturer Data layout (26 bytes):
 * - `0..1`: company ID `0xFFFF`
 * - `2..25`: 24-byte payload (`version`, `sequence`, `cellPercent`, `alertMask`, `validMask`,
 *   6 tiles of `int16` LE in 0.01 C + `uint8` in 0.5 %RH, `crc8` over the 23 payload bytes before it).
 */
data class TelemetryFrame(
    val sequence: Int,
    val cellPercent: Int?,
    val alerts: AlertMask,
    val tiles: List<TelemetryTile>
) {
    companion object {
        const val COMPANY_ID: Int = 0xFFFF
        const val FORMAT_VERSION: Int = 2
        const val PAYLOAD_SIZE: Int = 24
        const val SIZE: Int = 26
        const val TILE_COUNT: Int = 6

        val DEFAULT_TILE_NAMES = listOf(
            "Onboard",
            "Slot 1",
            "Slot 2",
            "Slot 3",
            "Slot 4",
            "Slot 5"
        )

        /**
         * Decodes the 26-byte Manufacturer Data field including the 2-byte company ID (`0xFFFF`).
         */
        @Throws(TelemetryException::class)
        fun decode(data: ByteArray): TelemetryFrame {
            if (data.size != SIZE) {
                throw TelemetryException.BadLength(data.size)
            }
            val company = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            if (company != COMPANY_ID) {
                throw TelemetryException.WrongCompanyId(company)
            }
            return decodePayload(data.copyOfRange(2, SIZE))
        }

        /**
         * Decodes the 24-byte payload returned by `ScanRecord.getManufacturerSpecificData(0xFFFF)`.
         */
        @Throws(TelemetryException::class)
        fun decodePayload(payload: ByteArray): TelemetryFrame {
            if (payload.size != PAYLOAD_SIZE) {
                throw TelemetryException.BadLength(payload.size)
            }
            val version = payload[0].toInt() and 0xFF
            if (version != FORMAT_VERSION) {
                throw TelemetryException.UnknownVersion(version)
            }
            val expectedCrc = Checksums.crc8(payload, offset = 0, length = PAYLOAD_SIZE - 1)
            val actualCrc = payload[PAYLOAD_SIZE - 1].toInt() and 0xFF
            if (actualCrc != expectedCrc) {
                throw TelemetryException.BadCrc(expected = expectedCrc, actual = actualCrc)
            }

            val sequence = payload[1].toInt() and 0xFF
            val rawCell = payload[2].toInt() and 0xFF
            val cellPercent = if (rawCell <= 100) rawCell else null
            val alerts = AlertMask(payload[3].toInt() and 0xFF)
            val validMask = payload[4].toInt() and 0xFF

            val tiles = ArrayList<TelemetryTile>(TILE_COUNT)
            for (i in 0 until TILE_COUNT) {
                val offset = 5 + i * 3
                val lo = payload[offset].toInt() and 0xFF
                val hi = payload[offset + 1].toInt() and 0xFF
                val rawTemp = ((hi shl 8) or lo).toShort().toInt()
                val tempC = rawTemp / 100.0
                val rhPct = (payload[offset + 2].toInt() and 0xFF) / 2.0

                val bitSet = (validMask and (1 shl i)) != 0
                if (bitSet && tempC in -50.0..100.0 && rhPct in 0.0..100.0) {
                    tiles.add(TelemetryTile(temperatureC = tempC, humidityPct = rhPct))
                } else {
                    tiles.add(TelemetryTile(temperatureC = null, humidityPct = null))
                }
            }

            return TelemetryFrame(
                sequence = sequence,
                cellPercent = cellPercent,
                alerts = alerts,
                tiles = tiles
            )
        }
    }
}
