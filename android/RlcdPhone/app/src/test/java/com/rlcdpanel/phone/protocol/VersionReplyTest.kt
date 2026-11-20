package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class VersionReplyTest {
    private fun bad(vararg lines: String) {
        try {
            VersionReply.parse(lines.toList())
            fail("expected ParseException")
        } catch (_: ParseException) {
        }
    }

    @Test
    fun parsesAllFields() {
        val r = VersionReply.parse(listOf("VERSION fw=1.0.0 git=abc1234-dirty core=3.3.12 state=68 built=2026-11-03T04:05:06"))
        assertEquals(VersionReply("1.0.0", "abc1234-dirty", "3.3.12", "68", "2026-11-03T04:05:06"), r)
    }

    @Test
    fun acceptsUnknownBuildTime() {
        val r = VersionReply.parse(listOf("VERSION fw=1.0.0 git=unknown core=3.3.12 state=68 built=unknown"))
        assertEquals("unknown", r.built)
    }

    @Test
    fun rejectsMissingLineAndMissingFields() {
        bad()
        bad("STATUS ok")
        bad("VERSION fw=1.0.0 core=3.3.12 state=68 built=unknown")
        bad("VERSION fw=1.0.0 git= core=3.3.12 state=68 built=unknown")
    }
}
