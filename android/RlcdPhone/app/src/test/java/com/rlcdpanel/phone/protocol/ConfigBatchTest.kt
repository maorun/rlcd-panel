package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both paths of [ConfigBatch]: the firmware batch mode (`SET BEGIN` answered `OK`) and the single `SET` lines. */
class ConfigBatchTest {
    private val pairs = listOf("temp_unit" to "F", "wake_interval_s" to "90")

    private class Run(val replies: (String) -> ConsoleReply) {
        val sent = mutableListOf<String>()
        var result: ConfigBatch.Result? = null
    }

    private fun run(batch: ConfigBatch, replies: (String) -> ConsoleReply): Run {
        val r = Run(replies)
        batch.run(
            send = { command, callback -> r.sent.add(command); callback(replies(command)) },
            done = { r.result = it }
        )
        return r
    }

    private fun ok(command: String): ConsoleReply = when {
        command == "SET BEGIN" -> ConsoleReply.Status("OK SET BEGIN")
        command == "SET COMMIT" -> ConsoleReply.Status("OK SET COMMIT 2")
        command == "SET ABORT" -> ConsoleReply.Status("OK SET ABORT")
        command == "CONFIG" -> ConsoleReply.Frame(listOf("SOURCE SD"))
        command.startsWith("SET ") -> ConsoleReply.Status("OK QUEUED ${command.substring(4).substringBefore('=')}")
        else -> ConsoleReply.Status("ERR BAD_ARG")
    }

    private fun okSingle(command: String): ConsoleReply =
        if (command.startsWith("SET ")) ConsoleReply.Status("OK SET x") else ok(command)

    @Test
    fun beginOkQueuesCommitsThenReadsConfig() {
        val r = run(ConfigBatch(pairs)) { ok(it) }
        assertEquals(listOf("SET BEGIN", "SET temp_unit=F", "SET wake_interval_s=90", "SET COMMIT", "CONFIG"), r.sent)
        val result = r.result!!
        assertEquals(listOf("temp_unit", "wake_interval_s"), result.applied)
        assertNull(result.failure)
        assertNotNull(result.config)
        assertFalse(result.configFailed)
        assertTrue(result.usedBatchMode)
    }

    @Test
    fun errToBeginFallsBackToSingleSetLines() {
        for (status in listOf("ERR READ_ONLY", "ERR BAD_KEY", "ERR UNKNOWN")) {
            val r = run(ConfigBatch(pairs)) { command ->
                if (command == "SET BEGIN") ConsoleReply.Status(status)
                else if (command == "CONFIG") ConsoleReply.Frame(listOf("SOURCE SD"))
                else ConsoleReply.Status("OK SET x")
            }
            assertEquals(listOf("SET BEGIN", "SET temp_unit=F", "SET wake_interval_s=90", "CONFIG"), r.sent)
            assertEquals(listOf("temp_unit", "wake_interval_s"), r.result!!.applied)
            assertFalse(r.result!!.usedBatchMode)
            assertNull(r.result!!.failure)
        }
    }

    @Test
    fun readOnlyAtLevelOneStopsAtTheFirstSingleSet() {
        val r = run(ConfigBatch(pairs)) { ConsoleReply.Status("ERR READ_ONLY") }
        assertEquals(listOf("SET BEGIN", "SET temp_unit=F"), r.sent)
        assertEquals("temp_unit" to "ERR READ_ONLY", r.result!!.failure)
        assertTrue(r.result!!.applied.isEmpty())
        assertNull(r.result!!.config)
    }

    @Test
    fun errWhileQueuingAbortsAndAppliesNothing() {
        val r = run(ConfigBatch(pairs)) { command ->
            if (command == "SET wake_interval_s=90") ConsoleReply.Status("ERR BAD_VALUE") else ok(command)
        }
        assertEquals(listOf("SET BEGIN", "SET temp_unit=F", "SET wake_interval_s=90", "SET ABORT"), r.sent)
        val result = r.result!!
        assertTrue(result.applied.isEmpty())
        assertEquals("wake_interval_s" to "ERR BAD_VALUE", result.failure)
        assertNull(result.config)
        assertTrue(result.usedBatchMode)
    }

    @Test
    fun fullQueueAborts() {
        val r = run(ConfigBatch(pairs)) { command ->
            if (command == "SET temp_unit=F") ConsoleReply.Status("ERR BATCH_FULL") else ok(command)
        }
        assertEquals(listOf("SET BEGIN", "SET temp_unit=F", "SET ABORT"), r.sent)
        assertEquals("temp_unit" to "ERR BATCH_FULL", r.result!!.failure)
        assertTrue(r.result!!.applied.isEmpty())
    }

    @Test
    fun failedCommitAbortsAndAppliesNothing() {
        val r = run(ConfigBatch(pairs)) { command ->
            if (command == "SET COMMIT") ConsoleReply.Status("ERR WRITE_FAILED") else ok(command)
        }
        assertEquals(
            listOf("SET BEGIN", "SET temp_unit=F", "SET wake_interval_s=90", "SET COMMIT", "SET ABORT"), r.sent
        )
        assertEquals("SET COMMIT" to "ERR WRITE_FAILED", r.result!!.failure)
        assertTrue(r.result!!.applied.isEmpty())
        assertNull(r.result!!.config)
    }

    @Test
    fun lostCommitReplyGivesUnknownStateAndAborts() {
        val r = run(ConfigBatch(pairs)) { command ->
            if (command == "SET COMMIT") ConsoleReply.Corrupt(ReplyError.Timeout) else ok(command)
        }
        assertEquals("SET COMMIT" to ConfigBatch.UNKNOWN_STATE, r.result!!.failure)
        assertEquals(
            listOf("SET BEGIN", "SET temp_unit=F", "SET wake_interval_s=90", "SET COMMIT", "SET ABORT"), r.sent
        )
        assertTrue(r.result!!.applied.isEmpty())
    }

    @Test
    fun lostBeginReplyAbortsInsteadOfFallingBack() {
        val r = run(ConfigBatch(pairs)) { ConsoleReply.Corrupt(ReplyError.Timeout) }
        assertEquals(listOf("SET BEGIN", "SET ABORT"), r.sent)
        assertEquals("SET BEGIN" to ConfigBatch.UNKNOWN_STATE, r.result!!.failure)
        assertTrue(r.result!!.applied.isEmpty())
    }

    @Test
    fun plainOkWhileQueuingMeansTheQueueExpired() {
        val r = run(ConfigBatch(pairs)) { command ->
            if (command == "SET temp_unit=F") ConsoleReply.Status("OK SET temp_unit") else ok(command)
        }
        assertEquals(listOf("SET BEGIN", "SET temp_unit=F", "SET ABORT"), r.sent)
        assertEquals("temp_unit" to ConfigBatch.BATCH_STATE, r.result!!.failure)
        assertTrue(r.result!!.applied.isEmpty())
    }

    @Test
    fun queuedReplyInSingleModeMeansAStaleQueue() {
        val r = run(ConfigBatch(pairs)) { command ->
            when (command) {
                "SET BEGIN" -> ConsoleReply.Status("ERR READ_ONLY")
                "SET ABORT" -> ConsoleReply.Status("OK SET ABORT")
                else -> ConsoleReply.Status("OK QUEUED x")
            }
        }
        assertEquals(listOf("SET BEGIN", "SET temp_unit=F", "SET ABORT"), r.sent)
        assertEquals("temp_unit" to ConfigBatch.BATCH_STATE, r.result!!.failure)
        assertTrue(r.result!!.applied.isEmpty())
    }

    @Test
    fun repeatedKeyCountsOnce() {
        val r = run(ConfigBatch(listOf("temp_unit" to "C", "temp_unit" to "F"))) { ok(it) }
        assertEquals(listOf("temp_unit"), r.result!!.applied)
    }

    @Test
    fun failedConfigReadAfterCommitIsReported() {
        val r = run(ConfigBatch(pairs)) { command ->
            if (command == "CONFIG") ConsoleReply.Status("ERR NO_CARD") else ok(command)
        }
        assertEquals(listOf("temp_unit", "wake_interval_s"), r.result!!.applied)
        assertTrue(r.result!!.configFailed)
        assertNull(r.result!!.failure)
    }

    @Test
    fun oneKeyUsesNoBatchMode() {
        val r = run(ConfigBatch(listOf("temp_unit" to "F"))) { okSingle(it) }
        assertEquals(listOf("SET temp_unit=F", "CONFIG"), r.sent)
        assertFalse(r.result!!.usedBatchMode)
    }

    @Test
    fun moreKeysThanTheQueueUseSingleLines() {
        val many = (0..ConfigBatch.MAX_KEYS).map { "temp_unit" to "C" }
        val r = run(ConfigBatch(many)) { okSingle(it) }
        assertEquals("SET temp_unit=C", r.sent.first())
        assertFalse(r.sent.contains("SET BEGIN"))
        val exact = run(ConfigBatch(many.take(ConfigBatch.MAX_KEYS))) { ok(it) }
        assertEquals("SET BEGIN", exact.sent.first())
    }

    @Test
    fun batchModeCanBeSwitchedOff() {
        val r = run(ConfigBatch(pairs, batchMode = false)) { okSingle(it) }
        assertEquals(listOf("SET temp_unit=F", "SET wake_interval_s=90", "CONFIG"), r.sent)
    }

    @Test
    fun calibrationBatchUsesTheBatchModeToo() {
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(
            listOf(CalibrationProposal("slot7_rh_gain", "1.066"), CalibrationProposal("slot7_rh_offset_pct", "0.8"))
        ).run({ command, callback -> sent.add(command); callback(ok(command)) }, { result = it })
        assertEquals(
            listOf("SET BEGIN", "SET slot7_rh_gain=1.066", "SET slot7_rh_offset_pct=0.8", "SET COMMIT", "CONFIG"), sent
        )
        assertEquals(listOf("slot7_rh_gain", "slot7_rh_offset_pct"), result!!.applied)
    }
}
