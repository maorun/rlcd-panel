package com.rlcdpanel.phone

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.LatchState
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.PanelFramebuffer
import com.rlcdpanel.phone.protocol.PanelRenderer
import com.rlcdpanel.phone.protocol.TelemetryFrame

object PanelPreviewText {
    const val LABEL = "Preview from telemetry, not the panel image"

    /** Hint under the image: what the telemetry frame cannot show. */
    const val LIMITS = "Die Vorschau zeigt Temperatur, Feuchte, Namen und den Akku aus dem Broadcast. " +
        "Kurven, Trendpfeile, Warnbanner, Uhr, STALE-Anzeige und die zweite Seite " +
        "ab 7 Kacheln trägt der Broadcast nicht. Die Badges V, D, W, C, R und S erscheinen nur nach einer STATUS-Antwort (LATCH-Zeile). Der Akku zeigt nur Prozent ohne Spannung, ein Slot ohne Akkuzeile. " +
        "Ein veralteter Sensor erscheint als NO SIGNAL, ohne feste MAC und ohne Namen gar nicht."

    /** Age line under the image: whole minutes since the frame arrived, `< 1 min` below one minute. */
    fun age(receivedAtMillis: Long, nowMillis: Long): String {
        val minutes = ((nowMillis - receivedAtMillis).coerceAtLeast(0L)) / 60_000L
        return if (minutes < 1) "Frame: vor < 1 min" else "Frame: vor $minutes min"
    }

    const val NO_FRAME = "Noch kein Telemetrie-Frame empfangen"

    /** Converts the framebuffer to a bitmap at the panel resolution. */
    fun bitmap(fb: PanelFramebuffer): Bitmap =
        Bitmap.createBitmap(fb.toArgb(), PanelFramebuffer.WIDTH, PanelFramebuffer.HEIGHT, Bitmap.Config.ARGB_8888)
}

@Composable
fun PanelPreviewScreen(
    frame: TelemetryFrame?,
    receivedAtMillis: Long?,
    nowMillis: Long,
    panelConfig: PanelConfig,
    latch: LatchState? = null,
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onBack) { Text("Zurück") }
            if (frame == null) {
                Text(PanelPreviewText.NO_FRAME, style = MaterialTheme.typography.bodyMedium)
            } else {
                val image = remember(frame, panelConfig, latch) {
                    PanelPreviewText.bitmap(PanelRenderer.render(frame, panelConfig, latch)).asImageBitmap()
                }
                Image(
                    bitmap = image,
                    contentDescription = PanelPreviewText.LABEL,
                    contentScale = ContentScale.FillWidth,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxWidth().aspectRatio(PanelFramebuffer.WIDTH.toFloat() / PanelFramebuffer.HEIGHT)
                )
                Text(PanelPreviewText.LABEL, style = MaterialTheme.typography.labelLarge)
                receivedAtMillis?.let { Text(PanelPreviewText.age(it, nowMillis), style = MaterialTheme.typography.bodySmall) }
                Text(PanelPreviewText.LIMITS, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
