package com.rlcdpanel.phone.protocol

/** Verdict of one `SELFTEST` check. [wire] is the text of the reply. */
enum class SelfTestState(val wire: String) {
    OK("OK"), FAIL("FAIL"), SKIP("SKIP");

    companion object {
        fun fromWire(text: String): SelfTestState? = values().firstOrNull { it.wire == text }
    }
}

/** One `CHECK <name> <OK|FAIL|SKIP> <detail>` line. */
data class SelfTestCheck(val name: String, val state: SelfTestState, val detail: String) {
    /** Pass or fail mark of the check; a skipped check shows a dash. */
    val mark: String get() = when (state) {
        SelfTestState.OK -> "✔"
        SelfTestState.FAIL -> "✘"
        SelfTestState.SKIP -> "–"
    }
}

/** Parsed `SELFTEST` reply (`SerialConsoleRunner::selfTest` of the firmware). */
data class SelfTestReply(val checks: List<SelfTestCheck>, val pass: Int, val fail: Int) {
    /** True when no check failed and at least one check passed. */
    val passed: Boolean get() = fail == 0 && pass > 0

    companion object {
        private val UINT = Regex("^(0|[1-9][0-9]{0,4})$")

        /**
         * [lines] hold `BEGIN SELFTEST`, the `CHECK` lines and `END SELFTEST pass=<N> fail=<N>`, without the
         * `CRC32` line. An unknown state, a repeated check, a bad line or counts that differ from the checks
         * throw [ParseException].
         */
        fun parse(lines: List<String>): SelfTestReply {
            if (lines.firstOrNull() != "BEGIN SELFTEST") throw ParseException("BEGIN SELFTEST")
            val checks = ArrayList<SelfTestCheck>()
            var end: String? = null
            for (line in lines.drop(1)) {
                if (line.startsWith("CRC32")) continue
                if (end != null) throw ParseException(line)
                if (line.startsWith("END SELFTEST")) { end = line; continue }
                val p = line.split(" ", limit = 4)
                val state = p.getOrNull(2)?.let { SelfTestState.fromWire(it) }
                if (p.size < 3 || p[0] != "CHECK" || p[1].isEmpty() || state == null || checks.any { it.name == p[1] }) {
                    throw ParseException(line)
                }
                checks.add(SelfTestCheck(p[1], state, p.getOrElse(3) { "" }))
            }
            val last = end ?: throw ParseException("END SELFTEST")
            val f = last.split(" ")
            val pass = f.getOrNull(2)?.removePrefix("pass=")?.takeIf { f[2].startsWith("pass=") && UINT.matches(it) }?.toIntOrNull()
            val fail = f.getOrNull(3)?.removePrefix("fail=")?.takeIf { f[3].startsWith("fail=") && UINT.matches(it) }?.toIntOrNull()
            if (f.size != 4 || f[1] != "SELFTEST" || pass == null || fail == null ||
                pass != checks.count { it.state == SelfTestState.OK } ||
                fail != checks.count { it.state == SelfTestState.FAIL }
            ) throw ParseException(last)
            return SelfTestReply(checks, pass, fail)
        }
    }
}
