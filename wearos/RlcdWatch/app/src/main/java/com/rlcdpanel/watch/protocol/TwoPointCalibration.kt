package com.rlcdpanel.watch.protocol

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/** Gain and offset from one NaCl check and one MgCl2 check. Same rules as `TwoPointCalibration` of the macOS app. */
object TwoPointCalibration {
    data class Result(
        val gainKey: String,
        val offsetKey: String,
        /** Gain with 3 decimals (steps of 0.002) and offset with 1 decimal, as `SET` values. */
        val gain: String,
        val offset: String,
        /** Raw means (before the active gain and offset) at the NaCl and the MgCl2 point. */
        val rawNaCl: Double,
        val rawMgCl2: Double
    ) {
        /** The `SET` proposals in batch order: gain first, then offset. */
        val proposals: List<CalibrationProposal>
            get() = listOf(CalibrationProposal(gainKey, gain), CalibrationProposal(offsetKey, offset))
    }

    sealed class Failure(val text: String) : Exception(text) {
        object NotMeasured : Failure("Check both salts first")
        object DifferentSensors : Failure("The two checks belong to different sensors")
        object ConfigUnknown : Failure("Read the config first: the active gain and offset are unknown")
        object BadActiveValues : Failure("The active gain or offset is not a valid number")
        object PointsTooClose : Failure("The two raw means are too close or in the wrong order")
        object UnknownSensor : Failure("The sensor name is unknown")
        object ClampedSample : Failure("A mean humidity at 0 or 100 %RH is clamped, so no raw value follows")
        data class GainOutOfRange(val gain: Double) :
            Failure("Gain ${fmt("%.3f", gain)} is outside 0.90 to 1.10, no SET sent")
        data class OffsetOutOfRange(val offset: Double) :
            Failure("Offset ${fmt("%.1f", offset)} is outside -10.0 to 10.0, no SET sent")
    }

    /** Step of the stored gain. */
    private const val GAIN_STEP = 0.002

    private fun fmt(format: String, value: Double) = String.format(Locale.ROOT, format, value)

    /** Rounds half away from zero, like `rounded()` of Swift. */
    private fun round(x: Double): Double {
        val t = truncate(x) // x - t is exact, so a tie at 0.5 stays a tie
        return if (abs(x - t) >= 0.5) t + sign(x) else t
    }

    /** Keys of a sensor name: `onboard` has `rh_gain` and `rh_offset_pct`, `slotN` has `slotN_rh_gain` and `slotN_rh_offset_pct`. */
    fun keys(sensor: String): Pair<String, String> =
        if (sensor == "onboard") "rh_gain" to "rh_offset_pct" else "${sensor}_rh_gain" to "${sensor}_rh_offset_pct"

    /**
     * The history samples already contain the active gain and offset, so the raw means are `(mean - offset) / gain`.
     * The gain is the slope through the two references, rounded to 0.002; the offset is the mean residual at the two
     * points. A missing key in [current] uses the firmware default (gain 1.00, offset 0.0); a null [current] is an error.
     * @throws Failure for each result that must not reach a `SET`.
     */
    fun compute(naCl: SaltReply?, mgCl2: SaltReply?, current: Map<String, String>?): Result {
        val a = (naCl?.outcome as? SaltReply.Outcome.Measured)?.measurement ?: throw Failure.NotMeasured
        val b = (mgCl2?.outcome as? SaltReply.Outcome.Measured)?.measurement ?: throw Failure.NotMeasured
        if (a.name != b.name) throw Failure.DifferentSensors
        if (current == null) throw Failure.ConfigUnknown
        val (gainKey, offsetKey) = keys(a.name)
        if (CalibrationKeys.range(gainKey) == null || CalibrationKeys.range(offsetKey) == null) throw Failure.UnknownSensor
        val activeGain = CalibrationKeys.parseNumber(current[gainKey] ?: "1.00")
        val activeOffset = CalibrationKeys.parseNumber(current[offsetKey] ?: "0.0")
        if (activeGain == null || activeOffset == null || activeGain <= 0.0) throw Failure.BadActiveValues
        if (a.rhMean !in 0.5..99.5 || b.rhMean !in 0.5..99.5) throw Failure.ClampedSample
        val rawA = (a.rhMean - activeOffset) / activeGain
        val rawB = (b.rhMean - activeOffset) / activeGain
        if (rawA - rawB <= 1.0) throw Failure.PointsTooClose // NaCl is the wetter point
        val slope = (a.reference - b.reference) / (rawA - rawB)
        if (slope < 0.9 || slope > 1.1) throw Failure.GainOutOfRange(slope)
        val gain = round(slope / GAIN_STEP) * GAIN_STEP
        val offset = round(((a.reference - gain * rawA) + (b.reference - gain * rawB)) / 2 * 10) / 10
        val gainText = fmt("%.3f", gain)
        val offsetText = fmt("%.1f", offset + 0.0) // + 0.0 turns -0.0 into 0.0, so no `-0.0` reaches a SET
        if (!CalibrationKeys.validate(gainKey, gainText)) throw Failure.GainOutOfRange(gain)
        if (!CalibrationKeys.validate(offsetKey, offsetText)) throw Failure.OffsetOutOfRange(offset)
        return Result(gainKey, offsetKey, gainText, offsetText, rawA, rawB)
    }

    /** Result for the screen: [result] or an [error] text. Missing checks give neither, so the screen waits. */
    data class Evaluation(val result: Result? = null, val error: String? = null)

    /** Runs [compute] for the screen. `Check both salts first` is no error while a check is missing. */
    fun evaluate(naCl: SaltReply?, mgCl2: SaltReply?, current: Map<String, String>?): Evaluation {
        if (naCl == null || mgCl2 == null) return Evaluation()
        return try {
            Evaluation(result = compute(naCl, mgCl2, current))
        } catch (f: Failure) {
            Evaluation(error = f.text)
        }
    }
}
