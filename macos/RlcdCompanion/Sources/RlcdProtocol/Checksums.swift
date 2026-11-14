import Foundation

/// CRC-8 and CRC-32 as used by the firmware.
public enum Checksums {
    /// CRC-8, polynomial 0x31, initial value 0xFF (`Shtc3Codec::computeCrc8`).
    public static func crc8<S: Sequence>(_ bytes: S) -> UInt8 where S.Element == UInt8 {
        var crc: UInt8 = 0xFF
        for byte in bytes {
            crc ^= byte
            for _ in 0..<8 {
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x31 : crc << 1
            }
        }
        return crc
    }

    /// CRC-32 (IEEE 802.3, polynomial 0xEDB88320), the same value as zlib (`SerialCrc32`).
    public static func crc32<S: Sequence>(_ bytes: S) -> UInt32 where S.Element == UInt8 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                crc = (crc >> 1) ^ (0xEDB8_8320 & (0 &- (crc & 1)))
            }
        }
        return ~crc
    }
}
