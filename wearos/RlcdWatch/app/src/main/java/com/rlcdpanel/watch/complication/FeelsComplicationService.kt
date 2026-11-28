package com.rlcdpanel.watch.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.rlcdpanel.watch.MainActivity
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache

/**
 * Watch face complication with the feels-like temperature of the focused room (`SHORT_TEXT` and
 * `RANGED_VALUE` from 20 to 45 C). It reads [TelemetryCache] and does not scan BLE. A tap opens
 * [MainActivity].
 */
class FeelsComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, FeelsComplicationModel("31°", 31f))

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val model = FeelsComplicationModel.build(TelemetryCache(this), System.currentTimeMillis(), PanelConfig.load(this))
        listener.onComplicationData(build(request.complicationType, model))
    }

    private fun build(type: ComplicationType, model: FeelsComplicationModel): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val description = if (model.roomName.isEmpty()) "Feels like ${model.text}" else "${model.roomName} feels like ${model.text}"
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(model.text).build(),
                PlainComplicationText.Builder(description).build()
            )
                .setTitle(PlainComplicationText.Builder("FEELS").build())
                .setTapAction(tap)
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                model.value,
                FeelsComplicationModel.MIN_VALUE,
                FeelsComplicationModel.MAX_VALUE,
                PlainComplicationText.Builder(description).build()
            )
                .setText(PlainComplicationText.Builder(model.text).build())
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, FeelsComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
