package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.SaltReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaltScreenTextTest {
    @Test
    fun proposalLineShowsKeyAndValue() {
        val reply = SaltReply.parse(
            listOf("SALT slot2 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3", "slot2_rh_offset_pct=2.3")
        )
        assertEquals("Vorschlag: slot2_rh_offset_pct=2.3", SaltScreenText.proposal(reply))
    }

    @Test
    fun skippedResultHasNoProposal() {
        assertNull(SaltScreenText.proposal(SaltReply.parse(listOf("SKIP slot1 hours=3"))))
    }

    @Test
    fun unstableResultHasNoProposal() {
        assertNull(SaltScreenText.proposal(SaltReply.parse(listOf("UNSTABLE slot1 span=1.5"))))
    }
}
