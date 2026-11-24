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
 * Watch face complication with the temperature and humidity of the focused room. It reads
 * [TelemetryCache] and does not scan BLE. A tap opens [MainActivity].
 */
class ClimateComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, ClimateComplicationModel("21°", "45%", 45f, false))

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val model = ClimateComplicationModel.build(TelemetryCache(this), System.currentTimeMillis(), PanelConfig.load(this))
        listener.onComplicationData(build(request.complicationType, model))
    }

    private fun build(type: ComplicationType, model: ClimateComplicationModel): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(model.temperatureText).build(),
                PlainComplicationText.Builder(ClimateComplicationModel.describe("Temperature", model.temperatureText, model.roomName, model.alertNames)).build()
            )
                .setTitle(PlainComplicationText.Builder(model.humidityText).build())
                .setTapAction(tap)
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                model.humidityValue,
                ClimateComplicationModel.MIN_VALUE,
                ClimateComplicationModel.MAX_VALUE,
                PlainComplicationText.Builder(ClimateComplicationModel.describe("Humidity", model.humidityText, model.roomName, model.alertNames)).build()
            )
                .setText(PlainComplicationText.Builder(model.humidityText).build())
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    companion object {
        /**
         * Asks the system to redraw each complication of this service and of
         * [VentilationComplicationService] after the cache, the focus or the outdoor source changed.
         */
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, ClimateComplicationService::class.java))
                .requestUpdateAll()
            VentilationComplicationService.requestUpdate(context)
        }
    }
}

/**
 * Watch face complication with the ventilation advice of the focused room (`SHORT_TEXT`). It reads
 * [TelemetryCache] and does not scan BLE. A tap opens [MainActivity].
 */
class VentilationComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, VentilationComplicationModel("Lüften", "+2.3"))

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val model = VentilationComplicationModel.build(TelemetryCache(this), System.currentTimeMillis(), PanelConfig.load(this))
        listener.onComplicationData(build(request.complicationType, model))
    }

    private fun build(type: ComplicationType, model: VentilationComplicationModel): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val description = if (model.roomName.isEmpty()) "Ventilation ${model.word}" else "${model.roomName} Ventilation ${model.word}"
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(model.word).build(),
            PlainComplicationText.Builder(description).build()
        )
            .setTitle(PlainComplicationText.Builder(model.title).build())
            .setTapAction(tap)
            .build()
    }

    companion object {
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, VentilationComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
