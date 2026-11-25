package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.compose.runtime.remember
import com.rlcdpanel.watch.protocol.CalibrationBatch
import com.rlcdpanel.watch.protocol.SaltReply
import com.rlcdpanel.watch.protocol.SaltText
import com.rlcdpanel.watch.protocol.TwoPointCalibration

/**
 * State of the salt screen: the last read, the last NaCl and MgCl2 result of the room and the apply result.
 * A new read keeps the result of the other salt; a failed read drops the result of its own salt.
 */
data class SaltState(
    val isReading: Boolean = false,
    val reply: SaltReply? = null,
    val message: String? = null,
    val naCl: SaltReply? = null,
    val mgCl2: SaltReply? = null,
    val isApplying: Boolean = false,
    val applyMessage: String? = null,
    val applyIsError: Boolean = false
) {
    /** Start of a read of one salt: the results stay, the old messages go. */
    fun reading(): SaltState = SaltState(isReading = true, naCl = naCl, mgCl2 = mgCl2)

    /** A read of [magnesium] ended with [reply]. */
    fun withReply(magnesium: Boolean, reply: SaltReply): SaltState =
        SaltState(
            reply = reply,
            naCl = if (magnesium) naCl else reply,
            mgCl2 = if (magnesium) reply else mgCl2
        )

    /** No read ran ([text] tells why), so both results stay valid. */
    fun withoutRead(text: String): SaltState = copy(isReading = false, reply = null, message = text)

    /**
     * State after an apply batch. A `SET` may have run (also after an unknown state), so both raw means are stale:
     * both results go. Without a `SET` the results stay.
     */
    fun afterApply(batch: CalibrationBatch.Result): SaltState {
        val failed = batch.failure != null || batch.configFailed
        val text = SaltScreenText.applyText(batch)
        return if (SaltScreenText.changedConfig(batch)) {
            SaltState(applyMessage = text, applyIsError = failed)
        } else {
            copy(isApplying = false, applyMessage = text, applyIsError = failed)
        }
    }

    /** A read of [magnesium] ended without a result: that salt has no result, the other one stays. */
    fun withFailure(magnesium: Boolean, text: String): SaltState =
        SaltState(
            message = text,
            naCl = if (magnesium) naCl else null,
            mgCl2 = if (magnesium) null else mgCl2
        )
}

/** Texts of the salt screen. */
object SaltScreenText {
    /** `Vorschlag: rh_offset_pct=1.2`; an empty text for a result without a proposal. */
    fun proposal(reply: SaltReply): String? = reply.proposal?.let { "Vorschlag: ${it.key}=${it.value}" }

    /** `Gain 1.066, Offset 0.8`. */
    fun twoPoint(result: TwoPointCalibration.Result): String = "Gain ${result.gain}, Offset ${result.offset}"

    /** True when a `SET` of the batch may have run, so the cached config is stale. */
    fun changedConfig(result: CalibrationBatch.Result): Boolean =
        result.applied.isNotEmpty() || result.failure?.second == CalibrationBatch.UNKNOWN_STATE

    /** Text of a finished apply batch: the failure summary or the applied keys. */
    fun applyText(result: CalibrationBatch.Result): String =
        if (result.failure != null || result.configFailed) result.summary
        else "Übernommen: ${result.applied.joinToString(", ")}"
}

@Composable
fun SaltScreen(
    state: SaltState,
    roomName: String,
    /** Active `config.ini` values of the cached `CONFIG` read; empty until a read. */
    currentConfig: Map<String, String>,
    onRead: (Boolean) -> Unit,
    onApply: (TwoPointCalibration.Result) -> Unit
) {
    val evaluation = remember(state.naCl, state.mgCl2, currentConfig) {
        TwoPointCalibration.evaluate(state.naCl, state.mgCl2, currentConfig.takeIf { it.isNotEmpty() })
    }
    val busy = state.isReading || state.isApplying
    val listState = rememberScalingLazyListState()
    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { Text("Salztest: $roomName", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                item {
                    Chip(
                        onClick = { onRead(false) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "NaCl", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = { onRead(true) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "MgCl2", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            text = message,
                            fontSize = 10.sp,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                state.reply?.let { reply ->
                    item {
                        Text(
                            text = SaltText.outcome(reply),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    SaltScreenText.proposal(reply)?.let { proposal ->
                        item {
                            Text(
                                text = proposal,
                                fontSize = 11.sp,
                                color = Color(0xFF80CBC4),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Einzelner Vorschlag. Beide Salze messen und Übernehmen tippen.",
                                fontSize = 9.sp,
                                color = Color(0xFFB0BEC5),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
                evaluation.result?.let { result ->
                    item {
                        Text(
                            text = SaltScreenText.twoPoint(result),
                            fontSize = 12.sp,
                            color = Color(0xFF80CBC4),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    item {
                        Chip(
                            onClick = { onApply(result) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.primaryChipColors(),
                            label = { Text(if (state.isApplying) "Sende ..." else "Übernehmen", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }
                evaluation.error?.let { error ->
                    item {
                        Text(
                            text = error,
                            fontSize = 10.sp,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                state.applyMessage?.let { text ->
                    item {
                        Text(
                            text = text,
                            fontSize = 10.sp,
                            color = if (state.applyIsError) Color(0xFFFF8A80) else Color(0xFF80CBC4),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.reply == null && state.message == null && !state.isReading && state.applyMessage == null) {
                    item {
                        Text(
                            "Tippe auf NaCl und MgCl2, je mit dem Sensor in der Salzbox. Panel an USB, ble_console 1 oder 2; Übernehmen braucht ble_console 2.",
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
