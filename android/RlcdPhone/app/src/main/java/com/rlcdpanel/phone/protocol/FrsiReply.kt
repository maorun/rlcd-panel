package com.rlcdpanel.phone.protocol

/** Console command `FRSI <R> <W>`: R is the room sensor (0 to 10), W is the wall slot (1 to 10), both differ. */
object FrsiCommand {
    const val KEY = "wall_frsi_pct"

    /** Throws [IllegalArgumentException] for numbers that the firmware answers with `ERR BAD_ARG`. */
    fun line(room: Int, wall: Int): String {
        require(room in 0..SaltCommand.MAX_SENSOR) { "room $room outside 0 to ${SaltCommand.MAX_SENSOR}" }
        require(wall in 1..SaltCommand.MAX_SENSOR) { "wall $wall outside 1 to ${SaltCommand.MAX_SENSOR}" }
        require(room != wall) { "room and wall are equal" }
        return "FRSI $room $wall"
    }
}

/** Reply of `FRSI <R> <W>`. The errors `ERR LOW_DELTA`, `ERR NO_OUTDOOR` and `ERR BAD_ARG` are status lines, no frame. */
data class FrsiReply(val outcome: Outcome) {
    data class Measurement(
        val room: String,
        val wall: String,
        val hours: Long,
        val frsi: Double,
        val span: Double,
        val deltaC: Double,
        /** Value of `wall_frsi_pct` (50 to 95) or null without a valid proposal line. */
        val proposalPct: Int?
    )

    sealed class Outcome {
        data class Measured(val measurement: Measurement) : Outcome()
        data class Skipped(val hours: Long) : Outcome()
        /** [span] is null for `span=--` (an hour without a factor). */
        data class Unstable(val span: Double?) : Outcome()
    }

    /** The proposal that `Apply` sends, or null. */
    val proposal: CalibrationProposal?
        get() = (outcome as? Outcome.Measured)?.measurement?.proposalPct?.let { CalibrationProposal(FrsiCommand.KEY, it.toString()) }

    companion object {
        /** Parses the lines of an `FRSI` frame (a `BEGIN` line is allowed). The first line decides the outcome. */
        fun parse(lines: List<String>): FrsiReply {
            val body = lines.filter { !it.startsWith("BEGIN ") }
            val first = body.firstOrNull() ?: throw ParseException("FRSI")
            val words = first.split(' ').filter { it.isNotEmpty() }
            val f = CalibrationReply.fields(first)
            if (first.startsWith("SKIP ")) {
                val h = if (words.size == 2) CalibrationReply.hours(f["hours"]) else null
                return FrsiReply(Outcome.Skipped(h ?: throw ParseException(first)))
            }
            if (first.startsWith("UNSTABLE ")) {
                val text = if (words.size == 2) f["span"] else null
                if (text == "--") return FrsiReply(Outcome.Unstable(null))
                return FrsiReply(Outcome.Unstable(CalibrationReply.decimal(text) ?: throw ParseException(first)))
            }
            if (!first.startsWith("FRSI ")) throw ParseException("FRSI")
            val room = f["room"]
            val wall = f["wall"]
            val h = CalibrationReply.hours(f["hours"])
            val frsi = CalibrationReply.decimal(f["frsi"])
            val span = CalibrationReply.decimal(f["span"])
            val delta = CalibrationReply.decimal(f["delta_c"])
            if (words.size != 7 || room.isNullOrEmpty() || wall.isNullOrEmpty() || h == null ||
                frsi == null || span == null || delta == null
            ) throw ParseException(first)
            var pct: Int? = null
            for (line in body.drop(1)) {
                if (!line.startsWith("${FrsiCommand.KEY}=") || line.contains(' ')) continue
                val value = line.substringAfter('=')
                val n = if (value.length in 1..2 && value.all { it in '0'..'9' }) value.toInt() else null
                if (n != null && n in 50..95) pct = n
            }
            return FrsiReply(Outcome.Measured(Measurement(room, wall, h, frsi, span, delta, pct)))
        }
    }
}

/** Readable texts of an `FRSI` result. */
object FrsiText {
    fun outcome(reply: FrsiReply): String = when (val o = reply.outcome) {
        is FrsiReply.Outcome.Measured -> {
            val m = o.measurement
            "Wandfaktor ${m.frsi} (${m.room} / ${m.wall}), ${m.hours} h, Schwankung ${m.span}, Differenz ${m.deltaC} °C"
        }
        is FrsiReply.Outcome.Skipped -> "SKIP: nur ${o.hours} h, mindestens 6 h nötig"
        is FrsiReply.Outcome.Unstable -> "UNSTABLE: Schwankung ${o.span ?: "--"}, Messung zu unruhig"
    }

    /** Text of a status line such as `ERR LOW_DELTA`; other lines keep their first 80 characters. */
    fun status(line: String): String = when {
        line.startsWith("ERR LOW_DELTA") -> "ERR LOW_DELTA: Raum und außen unter 10 °C Unterschied"
        line.startsWith("ERR NO_OUTDOOR") -> "ERR NO_OUTDOOR: outdoor_slot fehlt oder hat keinen frischen Messwert"
        line.startsWith("ERR BAD_ARG") -> "ERR BAD_ARG: Raum, Wand und outdoor_slot müssen verschieden sein"
        line.startsWith("ERR READ_ONLY") -> CalibrationBatch.failureText(line)
        else -> line.take(80)
    }
}
