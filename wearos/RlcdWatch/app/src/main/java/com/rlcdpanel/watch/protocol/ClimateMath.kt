package com.rlcdpanel.watch.protocol

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Comfort zone classification matching `ComfortZone` in `rlcd_panel/climate_math.h`.
 */
enum class ComfortZone(val label: String) {
    INVALID("--"),
    COLD("COLD"),
    WARM("WARM"),
    DRY("DRY"),
    HUMID("HUMID"),
    OK("OK")
}

/**
 * Climate derived calculations ported from `rlcd_panel/climate_math.h` and `ClimateMath.swift`.
 */
object ClimateMath {
    const val MAGNUS_A = 17.62
    const val MAGNUS_B_C = 243.12
    const val SATURATION_PRESSURE_KPA_AT_0C = 0.61078
    const val MIN_TEMP_C = -45.0
    const val MAX_TEMP_C = 60.0

    const val COMFORT_MIN_TEMP_C = 18.0
    const val COMFORT_MAX_TEMP_C = 26.0
    const val COMFORT_MIN_RH = 30.0
    const val COMFORT_MAX_RH = 60.0

    const val HEAT_INDEX_MIN_TEMP_C = 26.7
    const val HEAT_INDEX_MIN_RH = 40.0

    fun saturationPressureKpa(tempC: Double): Double =
        SATURATION_PRESSURE_KPA_AT_0C * exp((MAGNUS_A * tempC) / (tempC + MAGNUS_B_C))

    /**
     * Absolute humidity in g/m3. Returns `null` for invalid or out-of-range input.
     */
    fun absoluteHumidityGm3(tempC: Double, humidityPct: Double): Double? {
        if (!tempC.isFinite() || !humidityPct.isFinite() ||
            tempC < MIN_TEMP_C || tempC > MAX_TEMP_C ||
            humidityPct < 0.0 || humidityPct > 100.0
        ) {
            return null
        }
        val vaporHpa = saturationPressureKpa(tempC) * 10.0 * (humidityPct / 100.0)
        return 216.7 * vaporHpa / (tempC + 273.15)
    }

    /**
     * Dew point in Celsius. Returns `null` for invalid, out-of-range or non-positive relative humidity.
     */
    fun dewPointC(tempC: Double, humidityPct: Double): Double? {
        if (!tempC.isFinite() || !humidityPct.isFinite() ||
            tempC < MIN_TEMP_C || tempC > MAX_TEMP_C ||
            humidityPct <= 0.0 || humidityPct > 100.0
        ) {
            return null
        }
        val gamma = ln(humidityPct / 100.0) + (MAGNUS_A * tempC) / (MAGNUS_B_C + tempC)
        val dp = (MAGNUS_B_C * gamma) / (MAGNUS_A - gamma)
        return if (dp.isFinite()) dp else null
    }

    /**
     * NOAA Rothfusz regression in Fahrenheit with its two corrections.
     */
    fun rothfuszF(tempF: Double, rhPct: Double): Double {
        var hi = -42.379 +
            2.04901523 * tempF +
            10.14333127 * rhPct -
            0.22475541 * tempF * rhPct -
            0.00683783 * tempF * tempF -
            0.05481717 * rhPct * rhPct +
            0.00122874 * tempF * tempF * rhPct +
            0.00085282 * tempF * rhPct * rhPct -
            0.00000199 * tempF * tempF * rhPct * rhPct
        if (rhPct < 13.0 && tempF in 80.0..112.0) {
            hi -= ((13.0 - rhPct) / 4.0) * sqrt((17.0 - abs(tempF - 95.0)) / 17.0)
        } else if (rhPct > 85.0 && tempF in 80.0..87.0) {
            hi += ((rhPct - 85.0) / 10.0) * ((87.0 - tempF) / 5.0)
        }
        return hi
    }

    /**
     * Heat index (feels-like temperature) in Celsius. Returns `null` for invalid or out-of-range input.
     */
    fun heatIndexC(tempC: Double, humidityPct: Double): Double? {
        if (!tempC.isFinite() || !humidityPct.isFinite() ||
            tempC < MIN_TEMP_C || tempC > MAX_TEMP_C ||
            humidityPct < 0.0 || humidityPct > 100.0
        ) {
            return null
        }
        if (tempC < HEAT_INDEX_MIN_TEMP_C || humidityPct < HEAT_INDEX_MIN_RH) {
            return tempC
        }
        val tempF = tempC * 9.0 / 5.0 + 32.0
        return (rothfuszF(tempF = tempF, rhPct = humidityPct) - 32.0) * 5.0 / 9.0
    }

    const val WALL_FRSI_MIN = 0.50
    const val WALL_FRSI_MAX = 0.95

    /**
     * Humidity of the room air at the wall temperature `tOutC + frsi * (tInC - tOutC)`, same vapor pressure,
     * clamped to 100 %RH. Matches `ClimateMath::wallSurfaceRh`. Returns `null` for invalid input,
     * `frsi` outside 0.50 to 0.95 and `tOutC` above `tInC`.
     */
    fun wallSurfaceRh(tInC: Double, rhInPct: Double, tOutC: Double, frsi: Double): Double? {
        if (!tInC.isFinite() || !rhInPct.isFinite() || !tOutC.isFinite() || !frsi.isFinite() ||
            tInC < MIN_TEMP_C || tInC > MAX_TEMP_C || tOutC < MIN_TEMP_C || tOutC > MAX_TEMP_C ||
            rhInPct < 0.0 || rhInPct > 100.0 ||
            frsi < WALL_FRSI_MIN || frsi > WALL_FRSI_MAX ||
            tOutC > tInC
        ) {
            return null
        }
        val wallC = tOutC + frsi * (tInC - tOutC)
        val rh = rhInPct * saturationPressureKpa(tInC) / saturationPressureKpa(wallC)
        return if (rh.isFinite()) rh.coerceIn(0.0, 100.0) else null
    }

    /**
     * Comfort zone classification.
     */
    fun comfort(tempC: Double, humidityPct: Double): ComfortZone {
        if (!tempC.isFinite() || !humidityPct.isFinite() ||
            tempC < MIN_TEMP_C || tempC > MAX_TEMP_C ||
            humidityPct < 0.0 || humidityPct > 100.0
        ) {
            return ComfortZone.INVALID
        }
        if (tempC < COMFORT_MIN_TEMP_C) return ComfortZone.COLD
        if (tempC > COMFORT_MAX_TEMP_C) return ComfortZone.WARM
        if (humidityPct < COMFORT_MIN_RH) return ComfortZone.DRY
        if (humidityPct > COMFORT_MAX_RH) return ComfortZone.HUMID
        return ComfortZone.OK
    }
}
