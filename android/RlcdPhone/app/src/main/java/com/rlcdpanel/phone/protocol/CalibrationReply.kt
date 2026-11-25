package com.rlcdpanel.phone.protocol

/** One `key=value` line for `config.ini`. */
data class CalibrationProposal(val key: String, val value: String)

/** Reply of `CALIB N`. Same parsing rules as `CalibrationReply` of the macOS app. */
data class CalibrationReply(
    val reference: String,
    val sensors: List<Sensor>,
    val skipped: List<Skipped>,
    /** Null without a `USB` line. */
    val usb: UsbSplit?,
    /** Only keys with a value that the key range accepts. */
    val proposals: List<CalibrationProposal>
) {
    /** Value of the line `usb_temp_offset_c=<x>`, null without the line. */
    val usbProposal: Double?
        get() = proposals.firstOrNull { it.key == USB_KEY }?.value?.toDoubleOrNull()

    data class Sensor(
        val name: String,
        val hours: Long,
        val tempOffset: Double,
        val rhOffset: Double,
        val tempDiff: Double,
        val rhDiff: Double
    )

    data class Skipped(val name: String, val hours: Long)

    data class UsbSplit(val usbHours: Long, val batHours: Long, val usbTempOffset: Double, val usbTempDiff: Double?)

    companion object {
        const val USB_KEY = "usb_temp_offset_c"

        /** Parses the lines of a `CALIB` frame (a `BEGIN` line is allowed). Needs the `REFERENCE` line. */
        fun parse(lines: List<String>): CalibrationReply {
            var reference: String? = null
            val sensors = mutableListOf<Sensor>()
            val skipped = mutableListOf<Skipped>()
            val proposals = mutableListOf<CalibrationProposal>()
            var usb: UsbSplit? = null
            for (line in lines) {
                when {
                    line.startsWith("USB ") -> usb = usbSplit(line)
                    line.startsWith("REFERENCE ") -> {
                        val name = line.substring(10)
                        if (name.isEmpty() || name.contains(' ')) throw ParseException(line)
                        reference = name
                    }
                    line.startsWith("SENSOR ") -> sensors.add(sensor(line))
                    line.startsWith("SKIP ") -> {
                        val words = line.split(' ').filter { it.isNotEmpty() }
                        val h = if (words.size == 3) hours(fields(line)["hours"]) else null
                        if (h == null) throw ParseException(line)
                        skipped.add(Skipped(words[1], h))
                    }
                    line.contains('=') && !line.contains(' ') -> {
                        val key = line.substringBefore('=')
                        val value = line.substringAfter('=')
                        if (key == USB_KEY && !CalibrationKeys.validate(key, value)) throw ParseException(line)
                        if (isOffsetKey(key) && CalibrationKeys.validate(key, value)) {
                            proposals.removeAll { it.key == key } // the last line of a key wins
                            proposals.add(CalibrationProposal(key, value))
                        }
                    }
                }
            }
            return CalibrationReply(reference ?: throw ParseException("REFERENCE"), sensors, skipped, usb, proposals)
        }

        private fun isOffsetKey(key: String) = key.endsWith("temp_offset_c") || key.endsWith("rh_offset_pct")

        internal fun fields(line: String): Map<String, String> =
            line.split(' ').filter { it.isNotEmpty() }.drop(1).mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq >= 0) part.substring(0, eq) to part.substring(eq + 1) else null
            }.toMap()

        /** Digits only; an empty text, a sign and white space give null. A `Long` matches the 64-bit `Int` of Swift. */
        internal fun hours(s: String?): Long? =
            if (s.isNullOrEmpty() || !s.all { it in '0'..'9' }) null else s.toLongOrNull()

        /** Plain decimal text with an optional minus sign; `nan`, `inf`, a plus sign and hex floats give null. */
        internal fun decimal(s: String?): Double? {
            if (s.isNullOrEmpty()) return null
            val body = if (s.startsWith("-")) s.substring(1) else s
            val parts = body.split('.')
            if (parts.size !in 1..2 || !parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } }) return null
            return s.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        private fun sensor(line: String): Sensor {
            val words = line.split(' ').filter { it.isNotEmpty() }
            val f = fields(line)
            val h = hours(f["hours"])
            val to = decimal(f["temp_offset"])
            val ro = decimal(f["rh_offset"])
            val td = decimal(f["temp_diff"])
            val rd = decimal(f["rh_diff"])
            if (words.size != 7 || h == null || to == null || ro == null || td == null || rd == null) {
                throw ParseException(line)
            }
            return Sensor(words[1], h, to, ro, td, rd)
        }

        private fun usbSplit(line: String): UsbSplit {
            val f = fields(line)
            val uh = hours(f["hours"])
            val bh = hours(f["bat_hours"])
            val offset = decimal(f["usb_temp_offset"])
            val diffText = f["usb_temp_diff"]
            if (line.split(' ').filter { it.isNotEmpty() }.size != 5 || uh == null || bh == null ||
                offset == null || diffText == null
            ) throw ParseException(line)
            if (diffText == "--") return UsbSplit(uh, bh, offset, null)
            return UsbSplit(uh, bh, offset, decimal(diffText) ?: throw ParseException(line))
        }
    }
}

/** Reply of `SALT N`: the check of one sensor in a closed box with saturated sodium chloride. */
data class SaltReply(val outcome: Outcome) {
    data class Measurement(
        val name: String,
        val hours: Long,
        val rhMean: Double,
        val rhSpan: Double,
        val tempMean: Double,
        val reference: Double
    )

    sealed class Outcome {
        data class Measured(val measurement: Measurement, val proposal: CalibrationProposal?) : Outcome()
        data class Skipped(val name: String, val hours: Long) : Outcome()
        data class Unstable(val name: String, val span: Double) : Outcome()
    }

    val proposal: CalibrationProposal? get() = (outcome as? Outcome.Measured)?.proposal

    companion object {
        /** Parses the lines of a `SALT` frame (a `BEGIN` line is allowed). The first line decides the outcome. */
        fun parse(lines: List<String>): SaltReply {
            val body = lines.filter { !it.startsWith("BEGIN ") }
            val first = body.firstOrNull() ?: throw ParseException("SALT")
            val words = first.split(' ').filter { it.isNotEmpty() }
            val f = CalibrationReply.fields(first)
            if (first.startsWith("SKIP ")) {
                val h = if (words.size == 3) CalibrationReply.hours(f["hours"]) else null
                if (h == null) throw ParseException(first)
                return SaltReply(Outcome.Skipped(words[1], h))
            }
            if (first.startsWith("UNSTABLE ")) {
                val span = if (words.size == 3) CalibrationReply.decimal(f["span"]) else null
                if (span == null) throw ParseException(first)
                return SaltReply(Outcome.Unstable(words[1], span))
            }
            if (!first.startsWith("SALT ")) throw ParseException("SALT")
            val h = CalibrationReply.hours(f["hours"])
            val mean = CalibrationReply.decimal(f["rh_mean"])
            val span = CalibrationReply.decimal(f["rh_span"])
            val temp = CalibrationReply.decimal(f["temp_mean"])
            val ref = CalibrationReply.decimal(f["reference"])
            if (words.size != 7 || h == null || mean == null || span == null || temp == null || ref == null) {
                throw ParseException(first)
            }
            val name = words[1]
            val wanted = if (name == "onboard") "rh_offset_pct" else "${name}_rh_offset_pct"
            var proposal: CalibrationProposal? = null
            for (line in body.drop(1)) {
                if (!line.contains('=') || line.contains(' ')) continue
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                if (key == wanted && CalibrationKeys.validate(key, value)) proposal = CalibrationProposal(key, value)
            }
            return SaltReply(Outcome.Measured(Measurement(name, h, mean, span, temp, ref), proposal))
        }
    }
}

/** Key ranges of the offset keys (`ConfigSchema` of the macOS app, `DeviceConfigParser` of the firmware). */
object CalibrationKeys {
    private val SLOT_KEY = Regex("^slot([1-9]|10)_(temp_offset_c|rh_offset_pct|rh_gain)$")

    /** Range of [key] or null for a key that is not an offset key. */
    fun range(key: String): ClosedFloatingPointRange<Double>? {
        val base = SLOT_KEY.matchEntire(key)?.groupValues?.get(2)
        return when {
            key == "temp_offset_c" -> -10.0..10.0
            key == "usb_temp_offset_c" -> -5.0..0.0
            key == "rh_offset_pct" || base == "rh_offset_pct" -> -10.0..10.0
            base == "temp_offset_c" -> -5.0..5.0
            key == "rh_gain" || base == "rh_gain" -> 0.9..1.1
            else -> null
        }
    }

    /** Number grammar of `DeviceConfigParser::parseNumber`: optional sign, digits, optional `.` and digits, 15 characters at most. */
    fun parseNumber(s: String): Double? {
        if (s.isEmpty() || s.length > 15) return null
        val body = if (s[0] == '+' || s[0] == '-') s.substring(1) else s
        val parts = body.split('.')
        if (parts.size !in 1..2 || !parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } }) return null
        return s.toDoubleOrNull()
    }

    /** True when the firmware takes [value] for [key]. */
    fun validate(key: String, value: String): Boolean {
        val r = range(key) ?: return false
        val v = parseNumber(value) ?: return false
        return v in r
    }
}
