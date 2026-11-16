package com.rlcdpanel.watch.protocol

/**
 * CRC-8 and CRC-32 checksums matching `Shtc3Codec::computeCrc8` and `SerialCrc32` in the firmware.
 */
object Checksums {
    /**
     * CRC-8 with polynomial 0x31 and initial value 0xFF (`Shtc3Codec::computeCrc8`).
     */
    fun crc8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "Slice out of bounds: offset=$offset length=$length size=${bytes.size}"
        }
        var crc = 0xFF
        for (i in offset until (offset + length)) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor 0x31) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc
    }

    /**
     * CRC-32 (IEEE 802.3, polynomial 0xEDB88320), matching `SerialCrc32`.
     */
    fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "Slice out of bounds: offset=$offset length=$length size=${bytes.size}"
        }
        var crc = 0xFFFFFFFFL
        for (i in offset until (offset + length)) {
            crc = crc xor (bytes[i].toLong() and 0xFFL)
            repeat(8) {
                val mask = -(crc and 1L)
                crc = (crc ushr 1) xor (0xEDB88320L and mask)
            }
        }
        return crc.inv() and 0xFFFFFFFFL
    }
}
