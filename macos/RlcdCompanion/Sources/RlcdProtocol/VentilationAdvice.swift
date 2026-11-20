import Foundation

/// Action of the ventilation advice (same values as `VentilationAction` in `VentilationGuide.kt`).
public enum VentilationAction: String, CaseIterable, Equatable {
    case luftungStarten = "LUEFTUNG_STARTEN"
    case luftungBeenden = "LUEFTUNG_BEENDEN"
    case luftungAbbrechen = "LUEFTUNG_ABBRECHEN"
    case weiterLuften = "WEITER_LUEFTEN"
    case nichtLuften = "NICHT_LUEFTEN"
    case neutral = "NEUTRAL"
}

/// Ventilation advice port of `VentilationGuide.analysiereLueftung`. It reads only the telemetry frame
/// and the cached `CONFIG` reply and sends no console command.
public struct VentilationAdvice: Equatable {
    public static let targetHumidityStop = 48.0
    public static let targetHumidityStart = 53.0
    public static let hysteresisStart = 0.5
    public static let hysteresisStop = 0.1
    public static let coldLimitC = 5.0
    public static let minIndoorTempC = 17.5
    public static let minTempC = -50.0
    public static let maxTempC = 60.0
    /// A frame older than this gives no advice (same limit as `OutdoorResolver.MAX_FRAME_AGE_MS` of the Android app).
    public static let maxFrameAge: TimeInterval = 35 * 60

    public let action: VentilationAction
    public let title: String
    public let message: String
    /// Absolute humidity indoors, outdoors and the difference in g/m3.
    public let absHumidityIndoor: Double
    public let absHumidityOutdoor: Double
    public let difference: Double
    public let maxMinutes: Int?

    /// Absolute humidity in g/m3. `nil` for NaN, infinite or out-of-range input.
    public static func absoluteHumidity(tempC: Double, humidityPct: Double) -> Double? {
        guard tempC.isFinite, humidityPct.isFinite,
              tempC >= minTempC, tempC <= maxTempC,
              humidityPct >= 0.0, humidityPct <= 100.0 else { return nil }
        let saturation = 6.112 * exp((17.67 * tempC) / (tempC + 243.5))
        return (saturation * (humidityPct / 100.0) * 216.74) / (tempC + 273.15)
    }

    private static func number(_ v: Double) -> String {
        v.truncatingRemainder(dividingBy: 1.0) == 0.0 ? String(format: "%.0f", v) : String(format: "%.1f", v)
    }

    private static func diff(_ v: Double) -> String { String(format: "%.1f", v) }

    /// `nil` when a tile has no valid temperature and humidity or a value is out of range.
    public static func make(indoor: TelemetryTile, outdoor: TelemetryTile,
                            isVentilating: Bool = false, ventilatingMinutes: Int = 0) -> VentilationAdvice? {
        guard let tIn = indoor.temperatureC, let rhIn = indoor.humidityPct,
              let tOut = outdoor.temperatureC, let rhOut = outdoor.humidityPct,
              let afIn = absoluteHumidity(tempC: tIn, humidityPct: rhIn),
              let afOut = absoluteHumidity(tempC: tOut, humidityPct: rhOut) else { return nil }
        let d = afIn - afOut
        func result(_ a: VentilationAction, _ title: String, _ msg: String, max: Int? = nil) -> VentilationAdvice {
            VentilationAdvice(action: a, title: title, message: msg, absHumidityIndoor: afIn,
                              absHumidityOutdoor: afOut, difference: d, maxMinutes: max)
        }
        if isVentilating {
            let maxAllowed = tOut < coldLimitC ? 5 : 15
            if rhIn <= targetHumidityStop {
                return result(.luftungBeenden, "Target reached - close the window",
                              "The room air is at \(number(rhIn)) %. Close the window.")
            }
            if tIn <= minIndoorTempC {
                return result(.luftungAbbrechen, "Stop ventilation (cooling)",
                              "The room temperature fell to \(number(tIn)) °C. Close the window.")
            }
            if ventilatingMinutes >= maxAllowed {
                return result(.luftungAbbrechen, "Stop ventilation (time limit)",
                              "The window was open for \(ventilatingMinutes) minutes. Close it now.")
            }
            if d <= hysteresisStop {
                return result(.luftungAbbrechen, "Stop ventilation (weather change)",
                              "The outdoor humidity rose. More ventilation does not help.")
            }
            let rest = max(maxAllowed - ventilatingMinutes, 0)
            return result(.weiterLuften, "Ventilation running",
                          "\(number(rhIn - targetHumidityStop)) % to the target. About \(rest) min left.")
        }
        if rhIn < targetHumidityStart {
            return result(.nichtLuften, "Room air is fine",
                          "\(number(rhIn)) % humidity. Dehumidifying is not needed.")
        }
        if d < -hysteresisStart {
            return result(.nichtLuften, "Keep the window closed",
                          "Outdoor air is more humid (\(diff(afOut)) g/m³ vs. \(diff(afIn)) g/m³).")
        }
        if d <= hysteresisStart {
            return result(.neutral, "Almost no dehumidifying effect",
                          "Indoor and outdoor humidity are almost equal.")
        }
        let cold = tOut < coldLimitC
        return result(.luftungStarten,
                      cold ? "Short burst ventilation (cold)" : "Good time to ventilate",
                      cold ? "Outdoor \(number(tOut)) °C. Ventilate 5 minutes at most."
                           : "You remove about \(diff(d)) g of water per m³.",
                      max: cold ? 5 : 15)
    }

    /// Outdoor tile for the `outdoor_slot` of the last `CONFIG` reply (1 to 5, slot N is tile N).
    /// `nil` for a missing frame, a stale frame, a missing config, `outdoor_slot` 0 or another value,
    /// and an outdoor tile without a valid reading.
    public static func outdoorTile(frame: TelemetryFrame?, frameDate: Date?, config: ConfigReply?, now: Date) -> TelemetryTile? {
        guard let frame, let frameDate, let config,
              now.timeIntervalSince(frameDate) <= maxFrameAge,
              let raw = config.values["outdoor_slot"], let slot = Int(raw), (1...5).contains(slot),
              slot < frame.tiles.count else { return nil }
        let tile = frame.tiles[slot]
        guard let t = tile.temperatureC, let h = tile.humidityPct,
              absoluteHumidity(tempC: t, humidityPct: h) != nil else { return nil }
        return tile
    }

    /// Advice for the indoor tile `index`. `nil` for the outdoor tile itself, an index outside the frame,
    /// an invalid indoor tile and each case of `outdoorTile` that gives `nil`.
    public static func advice(forTile index: Int, frame: TelemetryFrame?, frameDate: Date?, config: ConfigReply?, now: Date) -> VentilationAdvice? {
        guard let frame, frame.tiles.indices.contains(index),
              let outdoor = outdoorTile(frame: frame, frameDate: frameDate, config: config, now: now),
              Int(config?.values["outdoor_slot"] ?? "") != index else { return nil }
        return make(indoor: frame.tiles[index], outdoor: outdoor)
    }

    /// Humidity of the room air at the outer wall (%RH) for the indoor tile `index`.
    /// `nil` for `wall_frsi_pct` 0 or missing, a missing config, the outdoor tile, an invalid indoor tile,
    /// an invalid outdoor tile and a bad result. The caller passes only fresh tiles.
    public static func wallRh(tile: TelemetryTile, index: Int, outdoor: TelemetryTile?, config: ConfigReply?) -> Double? {
        guard let config, let outdoor,
              let pct = Int(config.values["wall_frsi_pct"] ?? ""), pct != 0,
              Int(config.values["outdoor_slot"] ?? "") != index,
              let tIn = tile.temperatureC, let rhIn = tile.humidityPct,
              let tOut = outdoor.temperatureC else { return nil }
        return ClimateMath.wallSurfaceRh(tInC: tIn, rhInPct: rhIn, tOutC: tOut, frsi: Double(pct) / 100.0)
    }

    /// Text `Wall <N>%` for the indoor tile `index` (see `wallRh`).
    /// The panel shows `WALL --` for a bad result; the app hides the line.
    public static func wallText(tile: TelemetryTile, index: Int, outdoor: TelemetryTile?, config: ConfigReply?) -> String? {
        guard let rh = wallRh(tile: tile, index: index, outdoor: outdoor, config: config) else { return nil }
        return "Wall \(Int(rh.rounded()))%"
    }
}
