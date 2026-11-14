import Foundation

/// Comfort zone classification matching `ComfortZone` in `rlcd_panel/climate_math.h`.
public enum ComfortZone: String, CaseIterable, Equatable {
    case invalid = "INVALID"
    case cold = "COLD"
    case warm = "WARM"
    case dry = "DRY"
    case humid = "HUMID"
    case ok = "OK"

    public var label: String {
        switch self {
        case .cold: return "COLD"
        case .warm: return "WARM"
        case .dry: return "DRY"
        case .humid: return "HUMID"
        case .ok: return "OK"
        case .invalid: return "--"
        }
    }
}

/// Climate derived calculations ported from `rlcd_panel/climate_math.h`.
public enum ClimateMath {
    public static let magnusA = 17.62
    public static let magnusBC = 243.12
    public static let saturationPressureKpaAt0C = 0.61078
    public static let minTempC = -45.0
    public static let maxTempC = 60.0

    public static let comfortMinTempC = 18.0
    public static let comfortMaxTempC = 26.0
    public static let comfortMinRh = 30.0
    public static let comfortMaxRh = 60.0

    public static let heatIndexMinTempC = 26.7
    public static let heatIndexMinRh = 40.0

    /// Saturation vapor pressure in kPa.
    public static func saturationPressureKpa(_ tempC: Double) -> Double {
        saturationPressureKpaAt0C * exp((magnusA * tempC) / (tempC + magnusBC))
    }

    /// Absolute humidity in g/m3. Returns `nil` for invalid or out-of-range input.
    public static func absoluteHumidityGm3(tempC: Double, humidityPct: Double) -> Double? {
        guard tempC.isFinite, humidityPct.isFinite,
              tempC >= minTempC, tempC <= maxTempC,
              humidityPct >= 0.0, humidityPct <= 100.0 else {
            return nil
        }
        let vaporHpa = saturationPressureKpa(tempC) * 10.0 * (humidityPct / 100.0)
        return 216.7 * vaporHpa / (tempC + 273.15)
    }

    /// Dew point in Celsius. Returns `nil` for invalid, out-of-range or non-positive relative humidity.
    public static func dewPointC(tempC: Double, humidityPct: Double) -> Double? {
        guard tempC.isFinite, humidityPct.isFinite,
              tempC >= minTempC, tempC <= maxTempC,
              humidityPct > 0.0, humidityPct <= 100.0 else {
            return nil
        }
        let gamma = log(humidityPct / 100.0) + (magnusA * tempC) / (magnusBC + tempC)
        let dp = (magnusBC * gamma) / (magnusA - gamma)
        return dp.isFinite ? dp : nil
    }

    /// NOAA Rothfusz regression in Fahrenheit with its two corrections.
    public static func rothfuszF(tempF: Double, rhPct: Double) -> Double {
        var hi = -42.379 + 2.04901523 * tempF + 10.14333127 * rhPct - 0.22475541 * tempF * rhPct
            - 0.00683783 * tempF * tempF - 0.05481717 * rhPct * rhPct + 0.00122874 * tempF * tempF * rhPct
            + 0.00085282 * tempF * rhPct * rhPct - 0.00000199 * tempF * tempF * rhPct * rhPct
        if rhPct < 13.0, tempF >= 80.0, tempF <= 112.0 {
            hi -= ((13.0 - rhPct) / 4.0) * sqrt((17.0 - abs(tempF - 95.0)) / 17.0)
        } else if rhPct > 85.0, tempF >= 80.0, tempF <= 87.0 {
            hi += ((rhPct - 85.0) / 10.0) * ((87.0 - tempF) / 5.0)
        }
        return hi
    }

    /// Heat index (feels-like temperature) in Celsius. Returns `nil` for invalid or out-of-range input.
    public static func heatIndexC(tempC: Double, humidityPct: Double) -> Double? {
        guard tempC.isFinite, humidityPct.isFinite,
              tempC >= minTempC, tempC <= maxTempC,
              humidityPct >= 0.0, humidityPct <= 100.0 else {
            return nil
        }
        if tempC < heatIndexMinTempC || humidityPct < heatIndexMinRh {
            return tempC
        }
        let tempF = tempC * 9.0 / 5.0 + 32.0
        return (rothfuszF(tempF: tempF, rhPct: humidityPct) - 32.0) * 5.0 / 9.0
    }

    /// Comfort zone classification.
    public static func comfort(tempC: Double, humidityPct: Double) -> ComfortZone {
        guard tempC.isFinite, humidityPct.isFinite,
              tempC >= minTempC, tempC <= maxTempC,
              humidityPct >= 0.0, humidityPct <= 100.0 else {
            return .invalid
        }
        if tempC < comfortMinTempC { return .cold }
        if tempC > comfortMaxTempC { return .warm }
        if humidityPct < comfortMinRh { return .dry }
        if humidityPct > comfortMaxRh { return .humid }
        return .ok
    }
}
