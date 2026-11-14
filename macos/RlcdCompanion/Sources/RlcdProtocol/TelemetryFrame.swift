import Foundation

/// One climate tile of the telemetry frame. Temperature is always in Celsius.
public struct TelemetryTile: Equatable {
    /// `nil` for an empty or stale tile (mask bit cleared).
    public let temperatureC: Double?
    public let humidityPct: Double?

    public var isValid: Bool { temperatureC != nil }
}

/// Alert bits of the firmware (`audio_alert.h`).
public struct AlertMask: OptionSet, Equatable {
    public let rawValue: UInt8
    public init(rawValue: UInt8) { self.rawValue = rawValue }

    public static let freezer = AlertMask(rawValue: 1)
    public static let humidity = AlertMask(rawValue: 2)
    public static let noiseHigh = AlertMask(rawValue: 4)
    public static let sensorLost = AlertMask(rawValue: 8)
    public static let chargeFull = AlertMask(rawValue: 16)
    public static let ventilate = AlertMask(rawValue: 32)
    public static let sensorBattery = AlertMask(rawValue: 64)
    public static let heat = AlertMask(rawValue: 128)

    private static let names: [(AlertMask, String)] = [
        (.freezer, "FREEZER"), (.humidity, "HUMIDITY"), (.noiseHigh, "NOISE HIGH"),
        (.sensorLost, "SENSOR LOST"), (.chargeFull, "CHARGE FULL"), (.ventilate, "VENTILATE"),
        (.sensorBattery, "SENSOR BATTERY"), (.heat, "HEAT"),
    ]

    /// Names of the set bits, lowest bit first.
    public var names: [String] {
        Self.names.filter { contains($0.0) }.map { $0.1 }
    }
}

public enum TelemetryError: Error, Equatable {
    case badLength(Int)
    case wrongCompanyId(UInt16)
    case unknownVersion(UInt8)
    case badCrc(expected: UInt8, actual: UInt8)
}

/// Decoded Manufacturer Data frame of the BLE telemetry broadcast (`ble_telemetry.h`).
///
/// Layout (20 bytes): company ID `FF FF`, version, sequence, cell percentage (255 unknown),
/// alert mask, valid tile mask, four tiles (int16 LE in 0.01 C, uint8 in 0.5 %RH), CRC-8 over
/// the 17 bytes before it.
public struct TelemetryFrame: Equatable {
    public static let companyId: UInt16 = 0xFFFF
    public static let formatVersion: UInt8 = 1
    public static let size = 20
    public static let tileCount = 4

    public let sequence: UInt8
    /// `nil` when the cell percentage is unknown (255) or out of range.
    public let cellPercent: Int?
    public let alerts: AlertMask
    public let tiles: [TelemetryTile]

    /// Decodes the Manufacturer Data of an advertisement (company ID first). Throws for a bad frame.
    public static func decode(_ data: Data) throws -> TelemetryFrame {
        let b = [UInt8](data)
        guard b.count == size else { throw TelemetryError.badLength(b.count) }
        let company = UInt16(b[0]) | UInt16(b[1]) << 8
        guard company == companyId else { throw TelemetryError.wrongCompanyId(company) }
        guard b[2] == formatVersion else { throw TelemetryError.unknownVersion(b[2]) }
        let expected = Checksums.crc8(b[2..<19])
        guard b[19] == expected else { throw TelemetryError.badCrc(expected: expected, actual: b[19]) }

        let validMask = b[6]
        var tiles: [TelemetryTile] = []
        for i in 0..<tileCount {
            let o = 7 + i * 3
            let raw = Int16(bitPattern: UInt16(b[o]) | UInt16(b[o + 1]) << 8)
            let t = Double(raw) / 100.0
            let h = Double(b[o + 2]) / 2.0
            // A tile outside the physical range (-50 to 100 C, 0 to 100 %RH) counts as empty.
            if validMask & (1 << UInt8(i)) != 0, (-50.0...100.0).contains(t), h <= 100.0 {
                tiles.append(TelemetryTile(temperatureC: t, humidityPct: h))
            } else {
                tiles.append(TelemetryTile(temperatureC: nil, humidityPct: nil))
            }
        }
        let cell = b[4] <= 100 ? Int(b[4]) : nil
        return TelemetryFrame(sequence: b[3], cellPercent: cell, alerts: AlertMask(rawValue: b[5]), tiles: tiles)
    }
}
