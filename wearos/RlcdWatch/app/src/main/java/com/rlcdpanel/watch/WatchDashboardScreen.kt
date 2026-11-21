package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rlcdpanel.watch.protocol.ClimateMath
import com.rlcdpanel.watch.protocol.ComfortZone
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import com.rlcdpanel.watch.protocol.TelemetryTile
import com.rlcdpanel.watch.protocol.WallText
import com.rlcdpanel.watch.ventilation.OutdoorClimate
import com.rlcdpanel.watch.ventilation.OutdoorSource
import com.rlcdpanel.watch.ventilation.VentilationAction
import com.rlcdpanel.watch.ventilation.VentilationEmpfehlung
import java.util.Locale

@Composable
fun WatchDashboardScreen(
    snapshot: TelemetrySnapshot?,
    panelConfig: PanelConfig = PanelConfig(),
    isScanning: Boolean,
    statusMessage: String,
    nowMillis: Long,
    outdoorClimate: OutdoorClimate?,
    outdoorSource: OutdoorSource,
    focusedRoomIndex: Int,
    isVentilating: Boolean,
    ventilationDurationMinutes: Int,
    ventilationAdvice: VentilationEmpfehlung?,
    isSelfComparison: Boolean,
    configStatus: String? = null,
    isReadingConfig: Boolean = false,
    onReadConfig: () -> Unit = {},
    onOpenSensorHealth: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onToggleScan: () -> Unit,
    onCycleOutdoorSource: () -> Unit,
    onSelectRoomIndex: (Int) -> Unit,
    onToggleVentilation: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    HeaderStatusCard(
                        snapshot = snapshot,
                        isScanning = isScanning,
                        statusMessage = statusMessage,
                        nowMillis = nowMillis,
                        onToggleScan = onToggleScan
                    )
                }

                item {
                    ReadConfigChip(
                        status = configStatus,
                        isReading = isReadingConfig,
                        onReadConfig = onReadConfig
                    )
                }

                item {
                    Chip(
                        onClick = onOpenSensorHealth,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Sensor health", fontSize = 12.sp, maxLines = 1) }
                    )
                }

                item {
                    Chip(
                        onClick = onOpenHistory,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Room history", fontSize = 12.sp, maxLines = 1) }
                    )
                }

                item {
                    OutdoorSourceCard(
                        outdoorClimate = outdoorClimate,
                        outdoorSource = outdoorSource,
                        onCycleOutdoorSource = onCycleOutdoorSource
                    )
                }

                if (isSelfComparison) {
                    item {
                        SelfComparisonCard()
                    }
                } else if (ventilationAdvice != null) {
                    val roomName = panelConfig.tileName(focusedRoomIndex)
                    item {
                        VentilationAdviceCard(
                            roomName = roomName,
                            advice = ventilationAdvice,
                            isVentilating = isVentilating,
                            durationMinutes = ventilationDurationMinutes,
                            onToggleVentilation = onToggleVentilation
                        )
                    }
                }

                if (snapshot != null) {
                    val alerts = snapshot.frame.alerts.names
                    if (alerts.isNotEmpty()) {
                        item {
                            AlertBannerCard(alerts = alerts)
                        }
                    }

                    val tiles = snapshot.frame.tiles
                    val visibleIndices = tiles.indices.filter { i ->
                        i == 0 || tiles[i].isValid
                    }
                    val stale = nowMillis - snapshot.receivedAtMillis > TelemetryCache.MAX_AGE_MILLIS
                    val outdoorTile = WallText.outdoorTile(tiles, panelConfig)
                    itemsIndexed(visibleIndices) { _, tileIndex ->
                        val name = panelConfig.tileName(tileIndex)
                        val isFocused = tileIndex == focusedRoomIndex
                        ClimateTileCard(
                            name = name,
                            tile = tiles[tileIndex],
                            isFocused = isFocused,
                            wall = if (stale) null else WallText.format(tiles[tileIndex], tileIndex, outdoorTile, panelConfig),
                            onClick = { onSelectRoomIndex(tileIndex) }
                        )
                    }
                } else {
                    item {
                        EmptyTelemetryCard()
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderStatusCard(
    snapshot: TelemetrySnapshot?,
    isScanning: Boolean,
    statusMessage: String,
    nowMillis: Long,
    onToggleScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RLCD PANEL",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF90CAF9)
        )
        Text(
            text = statusMessage,
            fontSize = 11.sp,
            color = Color.LightGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (snapshot != null) {
            val cellText = snapshot.frame.cellPercent?.let { "$it %" } ?: "--"
            val ageSec = ((nowMillis - snapshot.receivedAtMillis).coerceAtLeast(0L)) / 1000L
            Text(
                text = "Akku $cellText • ${snapshot.rssi} dBm • ${ageSec}s",
                fontSize = 11.sp,
                color = Color(0xFFB0BEC5)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Chip(
            onClick = onToggleScan,
            modifier = Modifier.fillMaxWidth(0.85f),
            colors = ChipDefaults.primaryChipColors(
                backgroundColor = if (isScanning) Color(0xFF1B5E20) else Color(0xFF37474F)
            ),
            label = {
                Text(
                    text = if (isScanning) "BLE Scan aktiv" else "BLE Scan starten",
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        )
    }
}

@Composable
private fun ReadConfigChip(
    status: String?,
    isReading: Boolean,
    onReadConfig: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Chip(
            onClick = onReadConfig,
            enabled = !isReading,
            modifier = Modifier.fillMaxWidth(0.85f),
            colors = ChipDefaults.secondaryChipColors(),
            label = {
                Text(
                    text = if (isReading) "Lese Panel ..." else "Read panel config",
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        )
        if (status != null) {
            Text(
                text = status,
                fontSize = 10.sp,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OutdoorSourceCard(
    outdoorClimate: OutdoorClimate?,
    outdoorSource: OutdoorSource,
    onCycleOutdoorSource: () -> Unit
) {
    Card(
        onClick = onCycleOutdoorSource,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AUSSENKLIMA",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF81D4FA)
                )
                Text(
                    text = outdoorSource.displayName,
                    fontSize = 10.sp,
                    color = Color(0xFFFFCC80)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            if (outdoorClimate != null) {
                Text(
                    text = String.format(Locale.US, "%.1f °C • %.0f %%RH", outdoorClimate.tempC, outdoorClimate.rhPct),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            } else {
                Text(
                    text = "Wetterdaten werden geladen...",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun SelfComparisonCard() {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = Color(0xFF37474F),
            endBackgroundColor = Color(0xFF263238)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "SELBSTVERGLEICH",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFB74D)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Sensor ist als Außen- und Raumklima gewählt. Wähle eine andere Quelle.",
                fontSize = 11.sp,
                color = Color.LightGray
            )
        }
    }
}

@Composable
private fun VentilationAdviceCard(
    roomName: String,
    advice: VentilationEmpfehlung,
    isVentilating: Boolean,
    durationMinutes: Int,
    onToggleVentilation: () -> Unit
) {
    val cardColor = Color(advice.statusFarbe.hexColor)

    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = cardColor,
            endBackgroundColor = cardColor
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LÜFTUNG: $roomName",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (isVentilating) {
                    Text(
                        text = "$durationMinutes Min",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFF59D)
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = advice.titel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = advice.nachricht,
                fontSize = 11.sp,
                color = Color(0xFFECEFF1)
            )

            Spacer(modifier = Modifier.height(6.dp))

            val buttonText = when {
                isVentilating -> "Fenster schließen (Beenden)"
                advice.aktion == VentilationAction.LUEFTUNG_STARTEN -> "Lüften starten"
                else -> "Lüften manuell starten"
            }

            Chip(
                onClick = onToggleVentilation,
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.primaryChipColors(
                    backgroundColor = if (isVentilating) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                ),
                label = {
                    Text(
                        text = buttonText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1
                    )
                }
            )
        }
    }
}

@Composable
private fun AlertBannerCard(alerts: List<String>) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = Color(0xFFB71C1C),
            endBackgroundColor = Color(0xFF7F0000)
        )
    ) {
        Column {
            Text(
                text = "ALARM",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = alerts.joinToString(", "),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFCDD2)
            )
        }
    }
}

@Composable
private fun ClimateTileCard(
    name: String,
    tile: TelemetryTile,
    isFocused: Boolean,
    wall: WallText.Result? = null,
    onClick: () -> Unit
) {
    val cardModifier = if (isFocused) {
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color(0xFF64B5F6), RoundedCornerShape(18.dp))
    } else {
        Modifier.fillMaxWidth()
    }

    Card(
        onClick = onClick,
        enabled = true,
        modifier = cardModifier
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val tempC = tile.temperatureC
            val rhPct = tile.humidityPct
            val comfort = if (tempC != null && rhPct != null) {
                ClimateMath.comfort(tempC, rhPct)
            } else {
                ComfortZone.INVALID
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isFocused) "▶ $name" else name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFocused) Color(0xFF90CAF9) else Color(0xFFCFD8DC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ComfortBadge(zone = comfort)
            }

            Spacer(modifier = Modifier.height(2.dp))

            if (tempC != null && rhPct != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f °C", tempC),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f %%RH", rhPct),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF80DEEA)
                    )
                }

                val dp = ClimateMath.dewPointC(tempC, rhPct)
                val hi = ClimateMath.heatIndexC(tempC, rhPct)
                val ah = ClimateMath.absoluteHumidityGm3(tempC, rhPct)

                val details = buildList {
                    if (dp != null) add(String.format(Locale.US, "DP %.1f°C", dp))
                    if (hi != null && hi > tempC + 0.5) add(String.format(Locale.US, "HI %.1f°C", hi))
                    if (ah != null) add(String.format(Locale.US, "AH %.1fg", ah))
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" • "),
                        fontSize = 10.sp,
                        color = Color(0xFF90A4AE)
                    )
                }
                if (wall != null) {
                    Text(
                        text = wall.text,
                        fontSize = 10.sp,
                        fontWeight = if (wall.warning) FontWeight.Bold else FontWeight.Normal,
                        color = if (wall.warning) Color(0xFFFF8A65) else Color(0xFF90A4AE)
                    )
                }
            } else {
                Text(
                    text = "-- °C   -- %RH",
                    fontSize = 15.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun ComfortBadge(zone: ComfortZone) {
    val bgColor = when (zone) {
        ComfortZone.OK -> Color(0xFF2E7D32)
        ComfortZone.COLD, ComfortZone.DRY -> Color(0xFF0277BD)
        ComfortZone.WARM, ComfortZone.HUMID -> Color(0xFFEF6C00)
        ComfortZone.INVALID -> Color(0xFF455A64)
    }
    Box(
        modifier = Modifier
            .background(color = bgColor, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = zone.label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun EmptyTelemetryCard() {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Warte auf BLE-Frame (0xFFFF)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Setze ble_broadcast_enabled=1 auf dem RLCD-Panel.",
                fontSize = 10.sp,
                color = Color.LightGray
            )
        }
    }
}
