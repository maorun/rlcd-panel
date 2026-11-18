@file:OptIn(ExperimentalMaterial3Api::class)

package com.rlcdpanel.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.ClimateMath
import com.rlcdpanel.phone.protocol.ComfortZone
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryTile
import com.rlcdpanel.phone.ventilation.OutdoorClimate
import com.rlcdpanel.phone.ventilation.OutdoorResolver
import com.rlcdpanel.phone.ventilation.OutdoorSource
import com.rlcdpanel.phone.ventilation.VentilationAction
import com.rlcdpanel.phone.ventilation.VentilationEmpfehlung
import java.util.Locale

/** A tile counts as stale when no packet arrived for this long. */
const val STALE_AFTER_MILLIS: Long = OutdoorResolver.MAX_FRAME_AGE_MS

internal val PhoneColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    onSurface = Color.White
)

@Composable
fun PhoneDashboardScreen(
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
    onToggleScan: () -> Unit,
    onCycleOutdoorSource: () -> Unit,
    onSelectRoomIndex: (Int) -> Unit,
    onToggleVentilation: () -> Unit,
    onOpenSensorHealth: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    /** Protocol source badge of each tile index, from the last sensor health read; tile 0 is always the onboard sensor. */
    sourceBadges: Map<Int, String> = emptyMap()
) {
    MaterialTheme(colorScheme = PhoneColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(modifier = Modifier.height(24.dp)) }

                item {
                    StatusHeader(
                        snapshot = snapshot,
                        isScanning = isScanning,
                        statusMessage = statusMessage,
                        nowMillis = nowMillis,
                        onToggleScan = onToggleScan,
                        onOpenSensorHealth = onOpenSensorHealth,
                        onOpenHistory = onOpenHistory
                    )
                }

                if (snapshot != null && !snapshot.frame.alerts.isEmpty) {
                    item { AlertBadges(alerts = snapshot.frame.alerts) }
                }

                item {
                    OutdoorCard(
                        outdoorClimate = outdoorClimate,
                        outdoorSource = outdoorSource,
                        onCycleOutdoorSource = onCycleOutdoorSource
                    )
                }

                if (isSelfComparison) {
                    item { SelfComparisonCard() }
                } else if (ventilationAdvice != null) {
                    item {
                        VentilationCard(
                            roomName = panelConfig.tileName(focusedRoomIndex),
                            advice = ventilationAdvice,
                            isVentilating = isVentilating,
                            durationMinutes = ventilationDurationMinutes,
                            onToggleVentilation = onToggleVentilation
                        )
                    }
                } else if (isVentilating) {
                    // Keep the stop control when stale data hides the advice.
                    item {
                        VentilationStopCard(
                            roomName = panelConfig.tileName(focusedRoomIndex),
                            durationMinutes = ventilationDurationMinutes,
                            onToggleVentilation = onToggleVentilation
                        )
                    }
                }

                if (snapshot != null) {
                    val tiles = snapshot.frame.tiles
                    val visible = tiles.indices.filter { i -> i == 0 || tiles[i].isValid }
                    val stale = nowMillis - snapshot.receivedAtMillis > STALE_AFTER_MILLIS
                    val batteryAlert = snapshot.frame.alerts.contains(AlertMask.SENSOR_BATTERY)
                    items(visible) { index ->
                        ClimateCard(
                            name = panelConfig.tileName(index),
                            badge = if (index == 0) SensorHealth.badgeFor("SHTC3") else sourceBadges[index],
                            tile = tiles[index],
                            isFocused = index == focusedRoomIndex,
                            isStale = stale,
                            batteryLow = batteryAlert && index != 0,
                            onClick = { onSelectRoomIndex(index) }
                        )
                    }
                } else {
                    item { EmptyCard() }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatusHeader(
    snapshot: TelemetrySnapshot?,
    isScanning: Boolean,
    statusMessage: String,
    nowMillis: Long,
    onToggleScan: () -> Unit,
    onOpenSensorHealth: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "RLCD PANEL",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = statusMessage, fontSize = 13.sp, color = Color.LightGray)
            if (snapshot != null) {
                val cell = snapshot.frame.cellPercent?.let { "$it %" } ?: "--"
                val ageSec = ((nowMillis - snapshot.receivedAtMillis).coerceAtLeast(0L)) / 1000L
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Akku $cell • Seq #${snapshot.frame.sequence}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Letztes Paket vor ${ageSec}s • ${snapshot.rssi} dBm",
                    fontSize = 13.sp,
                    color = Color(0xFFB0BEC5)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onToggleScan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) Color(0xFF1B5E20) else Color(0xFF37474F),
                    contentColor = Color.White
                )
            ) {
                Text(if (isScanning) "BLE Scan aktiv" else "BLE Scan starten")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onOpenSensorHealth,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F), contentColor = Color.White)
            ) {
                Text("Sensor health")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F), contentColor = Color.White)
            ) {
                Text("Verlauf 24 h")
            }
        }
    }
}

@Composable
private fun AlertBadges(alerts: AlertMask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "ALARM", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                text = alerts.names.joinToString(" • "),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFCDD2)
            )
        }
    }
}

@Composable
private fun OutdoorCard(
    outdoorClimate: OutdoorClimate?,
    outdoorSource: OutdoorSource,
    onCycleOutdoorSource: () -> Unit
) {
    Card(onClick = onCycleOutdoorSource, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "AUSSENKLIMA",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF81D4FA)
                )
                Text(text = outdoorSource.displayName, fontSize = 12.sp, color = Color(0xFFFFCC80))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (outdoorClimate != null) {
                    String.format(Locale.US, "%.1f °C • %.0f %%RH", outdoorClimate.tempC, outdoorClimate.rhPct)
                } else {
                    when (outdoorSource) {
                        is OutdoorSource.OpenMeteo -> "Wetterdaten werden geladen..."
                        is OutdoorSource.SensorSlot -> "Sensor ohne gültige Daten"
                    }
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (outdoorClimate != null) Color.White else Color.Gray
            )
            Text(text = "Tippen wechselt die Quelle", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun SelfComparisonCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF37474F))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "SELBSTVERGLEICH",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFB74D)
            )
            Text(
                text = "Sensor ist als Außen- und Raumklima gewählt. Wähle eine andere Quelle.",
                fontSize = 13.sp,
                color = Color.LightGray
            )
        }
    }
}

@Composable
private fun VentilationStopCard(
    roomName: String,
    durationMinutes: Int,
    onToggleVentilation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF37474F))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "LÜFTUNG: $roomName • $durationMinutes Min",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFF59D)
            )
            Text(
                text = "Keine aktuellen Daten für eine Empfehlung.",
                fontSize = 13.sp,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onToggleVentilation,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White
                )
            ) {
                Text("Fenster schließen (Beenden)")
            }
        }
    }
}

@Composable
private fun VentilationCard(
    roomName: String,
    advice: VentilationEmpfehlung,
    isVentilating: Boolean,
    durationMinutes: Int,
    onToggleVentilation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(advice.statusFarbe.hexColor))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "LÜFTUNG: $roomName",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (isVentilating) {
                    Text(
                        text = "$durationMinutes Min",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFF59D)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = advice.titel, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = advice.nachricht, fontSize = 13.sp, color = Color(0xFFECEFF1))
            Spacer(modifier = Modifier.height(8.dp))
            val buttonText = when {
                isVentilating -> "Fenster schließen (Beenden)"
                advice.aktion == VentilationAction.LUEFTUNG_STARTEN -> "Lüften starten"
                else -> "Lüften manuell starten"
            }
            Button(
                onClick = onToggleVentilation,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isVentilating) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                    contentColor = Color.White
                )
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun ClimateCard(
    name: String,
    badge: String? = null,
    tile: TelemetryTile,
    isFocused: Boolean,
    isStale: Boolean,
    batteryLow: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                    text = (if (isFocused) "▶ $name" else name) + (badge?.let { " $it" } ?: ""),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFocused) Color(0xFF90CAF9) else Color(0xFFCFD8DC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isStale) Badge(text = "STALE", color = Color(0xFF6D4C41))
                    if (batteryLow) Badge(text = "BAT LOW", color = Color(0xFFB71C1C))
                    Badge(text = comfort.label, color = comfortColor(comfort))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (tempC != null && rhPct != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f °C", tempC),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f %%RH", rhPct),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF80DEEA)
                    )
                }
                val details = buildList {
                    ClimateMath.dewPointC(tempC, rhPct)?.let { add(String.format(Locale.US, "Taupunkt %.1f °C", it)) }
                    ClimateMath.heatIndexC(tempC, rhPct)?.let {
                        if (it > tempC + 0.5) add(String.format(Locale.US, "Gefühlt %.1f °C", it))
                    }
                    ClimateMath.absoluteHumidityGm3(tempC, rhPct)?.let { add(String.format(Locale.US, "AH %.1f g/m³", it)) }
                }
                if (details.isNotEmpty()) {
                    Text(text = details.joinToString(" • "), fontSize = 12.sp, color = Color(0xFF90A4AE))
                }
            } else {
                Text(text = "-- °C   -- %RH", fontSize = 20.sp, color = Color.Gray)
            }
        }
    }
}

private fun comfortColor(zone: ComfortZone): Color = when (zone) {
    ComfortZone.OK -> Color(0xFF2E7D32)
    ComfortZone.COLD, ComfortZone.DRY -> Color(0xFF0277BD)
    ComfortZone.WARM, ComfortZone.HUMID -> Color(0xFFEF6C00)
    ComfortZone.INVALID -> Color(0xFF455A64)
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color = color, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun EmptyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = "Warte auf BLE-Frame (0xFFFF)", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "Setze ble_broadcast_enabled=1 auf dem RLCD-Panel.",
                fontSize = 12.sp,
                color = Color.LightGray
            )
        }
    }
}
