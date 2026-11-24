package com.rlcdpanel.phone.protocol

/** Console command `SPREAD`: it has no argument. */
object SpreadCommand {
    const val LINE = "SPREAD"
}

/** Reply of `SPREAD`: the co-location check of all sensors. */
data class SpreadReply(val outcome: Outcome) {
    /** One `SENSOR` line: deviations from the median and the outlier flag. */
    data class SensorSpread(
        val name: String,
        val tempDev: Double,
        val rhDev: Double,
        val isOutlier: Boolean
    )

    sealed class Outcome {
        data class Measured(
            val sensors: Long,
            val tempMedian: Double,
            val rhMedian: Double,
            val rows: List<SensorSpread>
        ) : Outcome()

        /** `SKIP sensors=<n>`: fewer than 3 sensors have 6 hours of samples. */
        data class Skipped(val sensors: Long) : Outcome()
    }

    companion object {
        /** Sensor name rule of `device_config.h`: 1 to 12 characters of A-Z, 0-9, space and minus. */
        private val NAME = Regex("[A-Za-z0-9 -]{1,12}")

        /** Parses the lines of a `SPREAD` frame (a `BEGIN` line is allowed). The first line decides the outcome. */
        fun parse(lines: List<String>): SpreadReply {
            val body = lines.filter { !it.startsWith("BEGIN ") }
            val first = body.firstOrNull() ?: throw ParseException("SPREAD")
            val words = first.split(' ').filter { it.isNotEmpty() }
            val f = CalibrationReply.fields(first)
            if (first.startsWith("SKIP ")) {
                val n = if (words.size == 2 && body.size == 1) CalibrationReply.hours(f["sensors"]) else null
                return SpreadReply(Outcome.Skipped(n ?: throw ParseException(first)))
            }
            if (!first.startsWith("SPREAD ")) throw ParseException("SPREAD")
            val n = CalibrationReply.hours(f["sensors"])
            val tempMedian = CalibrationReply.decimal(f["temp_median"])
            val rhMedian = CalibrationReply.decimal(f["rh_median"])
            if (words.size != 4 || n == null || tempMedian == null || rhMedian == null) throw ParseException(first)
            val rows = body.drop(1).map { if (it.startsWith("SENSOR ")) row(it) else throw ParseException(it) }
            if (rows.size.toLong() != n) throw ParseException(first)
            return SpreadReply(Outcome.Measured(n, tempMedian, rhMedian, rows))
        }

        /** The name may hold spaces: it is the text between `SENSOR ` and ` temp_dev=`. */
        private fun row(line: String): SensorSpread {
            val marker = " temp_dev="
            val at = line.indexOf(marker)
            if (at < "SENSOR ".length + 1) throw ParseException(line)
            val name = line.substring("SENSOR ".length, at)
            if (!NAME.matches(name)) throw ParseException(line)
            val tail = line.substring(at + 1).split(' ').filter { it.isNotEmpty() }
            val f = CalibrationReply.fields("SENSOR ${tail.joinToString(" ")}")
            val temp = CalibrationReply.decimal(f["temp_dev"])
            val rh = CalibrationReply.decimal(f["rh_dev"])
            val flag = tail.getOrNull(2)
            if (tail.size != 3 || temp == null || rh == null || (flag != "OK" && flag != "OUTLIER")) {
                throw ParseException(line)
            }
            return SensorSpread(name, temp, rh, flag == "OUTLIER")
        }
    }
}

/** Readable texts of a `SPREAD` result. */
object SpreadText {
    const val SKIP_TEXT = "Put 3 or more sensors at one place for 6 hours"

    fun summary(m: SpreadReply.Outcome.Measured): String =
        "${m.sensors} sensors, median ${m.tempMedian} °C / ${m.rhMedian} %RH"

    fun row(r: SpreadReply.SensorSpread): String =
        "${if (r.isOutlier) "⚠ " else ""}${r.name}: ${r.tempDev} °C, ${r.rhDev} %RH" +
            if (r.isOutlier) " OUTLIER" else ""

    fun outcome(reply: SpreadReply): String = when (val o = reply.outcome) {
        is SpreadReply.Outcome.Measured -> summary(o)
        is SpreadReply.Outcome.Skipped -> SKIP_TEXT
    }
}
