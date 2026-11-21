package com.rlcdpanel.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimeStateTest {

    @Test
    fun noAckAllBeforeAStatusRead() {
        assertFalse(ChimeState().canAcknowledgeAll)
    }

    @Test
    fun missingAckedMaskCountsAsNothingAcknowledged() {
        assertTrue(ChimeState(alertMask = 0x32, ackedMask = null).canAcknowledgeAll)
    }

    @Test
    fun oneOpenBitGivesNoAckAll() {
        assertFalse(ChimeState(alertMask = 0x32, ackedMask = 0x02).canAcknowledgeAll)
    }

    @Test
    fun chargeFullDoesNotCount() {
        assertFalse(ChimeState(alertMask = 0x12, ackedMask = 0x00).canAcknowledgeAll)
    }
}
