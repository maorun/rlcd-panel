package com.rlcdpanel.watch.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rlcdpanel.watch.MainActivity
import com.rlcdpanel.watch.protocol.CachedOutdoor
import com.rlcdpanel.watch.protocol.CachedTelemetry
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import com.rlcdpanel.watch.ventilation.VentilationGuide
import java.util.Locale

/**
 * Text of the tile for one moment. [isEmpty] is true for an empty cache, a failed decode, a stale
 * frame or a focused room without a valid reading; the tile then shows `--` and [hint].
 * [advice] is the title of the ventilation advice, or null when the tile shows none.
 */
data class ClimateTileModel(
    val roomName: String,
    val temperature: String,
    val humidity: String,
    val cell: String,
    val alerts: String,
    val age: String,
    val hint: String?,
    val isEmpty: Boolean,
    val advice: String? = null
) {
    companion object {
        const val OPEN_HINT = "Open the app"
        const val PLACEHOLDER = "--"

        fun empty(roomName: String): ClimateTileModel = ClimateTileModel(
            roomName = roomName,
            temperature = PLACEHOLDER,
            humidity = PLACEHOLDER,
            cell = PLACEHOLDER,
            alerts = "",
            age = "",
            hint = OPEN_HINT,
            isEmpty = true
        )

        fun build(
            cached: CachedTelemetry?,
            focusedRoom: Int,
            config: PanelConfig,
            nowMillis: Long,
            outdoor: CachedOutdoor? = null,
            outdoorCached: CachedTelemetry? = cached
        ): ClimateTileModel {
            val name = config.tileName(focusedRoom)
            if (cached == null || cached.isStale(nowMillis)) {
                return empty(name)
            }
            // A page 1 frame holds the rooms 6 to 10 at the indices 0 to 4.
            val tile = cached.frame.tiles.getOrNull(focusedRoom % TelemetryFrame.TILES_PER_PAGE)
            if (tile == null || !tile.isValid) {
                return empty(name)
            }
            val alertNames = cached.frame.alerts.names
            return ClimateTileModel(
                roomName = name,
                temperature = String.format(Locale.US, "%.1f °C", tile.temperatureC),
                humidity = String.format(Locale.US, "%.0f %%", tile.humidityPct),
                cell = cached.frame.cellPercent?.let { "Panel $it %" } ?: "Panel --",
                alerts = if (alertNames.isEmpty()) "No alerts" else alertNames.joinToString(", "),
                age = formatAge(cached.ageMillis(nowMillis)),
                hint = null,
                isEmpty = false,
                advice = adviceTitle(tile.temperatureC, tile.humidityPct, focusedRoom, outdoor, outdoorCached, nowMillis)
            )
        }

        /**
         * Title of the ventilation advice for the focused room, or null. No advice without an outdoor
         * source, for the outdoor tile as focused room, for a stale outdoor frame and for an
         * Open-Meteo reading older than 1 hour. The tile does not know whether a window is open.
         */
        private fun adviceTitle(
            indoorTempC: Double?,
            indoorRhPct: Double?,
            focusedRoom: Int,
            outdoor: CachedOutdoor?,
            outdoorCached: CachedTelemetry?,
            nowMillis: Long
        ): String? {
            if (outdoor == null || indoorTempC == null || indoorRhPct == null) {
                return null
            }
            val outTemp: Double?
            val outRh: Double?
            val slot = outdoor.slotIndex
            if (slot != null) {
                if (slot == focusedRoom || outdoorCached == null || outdoorCached.isStale(nowMillis)) {
                    return null
                }
                val outTile = outdoorCached.frame.tiles.getOrNull(slot % TelemetryFrame.TILES_PER_PAGE)
                if (outdoorCached.frame.page != slot / TelemetryFrame.TILES_PER_PAGE ||
                    outTile == null || !outTile.isValid
                ) {
                    return null
                }
                outTemp = outTile.temperatureC
                outRh = outTile.humidityPct
            } else {
                val meteo = outdoor.meteo
                if (meteo == null || meteo.isStale(nowMillis)) {
                    return null
                }
                outTemp = meteo.tempC
                outRh = meteo.rhPct
            }
            if (outTemp == null || outRh == null) {
                return null
            }
            return VentilationGuide.analysiereLueftung(indoorTempC, indoorRhPct, outTemp, outRh)?.titel
        }

        fun formatAge(ageMillis: Long): String {
            val minutes = ageMillis / 60_000L
            return if (minutes < 1L) "now" else "$minutes min ago"
        }
    }
}

/**
 * Wear OS tile with the focused room and the active alerts. It reads [TelemetryCache] and does
 * not scan BLE. A tap opens [MainActivity].
 */
class ClimateTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val cache = TelemetryCache(this)
        val focused = cache.focusedRoom()
        val outdoor = cache.readOutdoor()
        val focusedPage = focused / TelemetryFrame.TILES_PER_PAGE
        val cached = cache.read(page = focusedPage)
        val outdoorPage = outdoor.slotIndex?.let { it / TelemetryFrame.TILES_PER_PAGE } ?: focusedPage
        val model = ClimateTileModel.build(
            cached = cached,
            focusedRoom = focused,
            config = PanelConfig.load(this),
            nowMillis = System.currentTimeMillis(),
            outdoor = outdoor,
            outdoorCached = if (outdoorPage == focusedPage) cached else cache.read(page = outdoorPage)
        )
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MILLIS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(model)))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        )

    private fun layout(model: ClimateTileModel): LayoutElementBuilders.LayoutElement {
        val openApp = ModifiersBuilders.Clickable.Builder()
            .setId("open_app")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(model.roomName, 14f, WHITE))
            .addContent(text("${model.temperature}  ${model.humidity}", 22f, WHITE))
        if (model.advice != null) {
            column.addContent(text(model.advice, 12f, ADVICE, maxLines = 1))
        }
        column.addContent(text(model.cell, 12f, GREY))
        if (model.hint != null) {
            column.addContent(text(model.hint, 14f, WHITE))
        } else {
            column.addContent(text(model.alerts, 12f, ALERT))
            column.addContent(text(model.age, 11f, GREY))
        }

        return LayoutElementBuilders.Box.Builder()
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column.build())
            .build()
    }

    private fun text(value: String, size: Float, color: Int, maxLines: Int = 2): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .build()
            )
            .build()

    companion object {
        /** The tile asks for a refresh every 15 minutes. */
        const val REFRESH_INTERVAL_MILLIS: Long = 15L * 60L * 1000L
        private const val RESOURCES_VERSION = "1"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFFB0B0B0.toInt()
        private const val ALERT = 0xFFFF8A80.toInt()
        private const val ADVICE = 0xFF80CBC4.toInt()

        /** Asks the system to redraw the tile after the cache changed. */
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(ClimateTileService::class.java)
        }
    }
}
