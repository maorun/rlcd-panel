package com.rlcdpanel.phone.protocol

/** Readable texts of a `SALT` result. */
object SaltText {
    fun outcome(reply: SaltReply): String = when (val o = reply.outcome) {
        is SaltReply.Outcome.Measured ->
            "${o.measurement.name}: ${o.measurement.hours} h, Mittel ${o.measurement.rhMean} %RH, Soll ${o.measurement.reference} %RH"
        is SaltReply.Outcome.Skipped -> "SKIP ${o.name}: nur ${o.hours} h, mindestens 6 h nötig"
        is SaltReply.Outcome.Unstable -> "UNSTABLE ${o.name}: Schwankung ${o.span} %RH, Messung zu unruhig"
    }

    /** Text of a status line such as `ERR BAD_TEMP`; other lines keep their first 80 characters. */
    fun status(line: String): String = when {
        line.startsWith("ERR BAD_TEMP") -> "ERR BAD_TEMP: Temperatur außerhalb 10 bis 35 °C"
        line.startsWith("ERR READ_ONLY") -> CalibrationBatch.failureText(line)
        else -> line.take(80)
    }
}
