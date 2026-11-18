package com.rlcdpanel.phone.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rlcdpanel.phone.BleScanService
import com.rlcdpanel.phone.MainActivity
import com.rlcdpanel.phone.protocol.PanelConfig

/** Home screen widget (4 x 2 cells). It reads stored data only: no BLE scan and no network request. */
class ClimateWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = BleScanService.snapshot.value ?: WidgetSnapshotStore.load(context)
        val config = PanelConfig.load(context)
        val state = ClimateWidgetModel.build(snapshot, System.currentTimeMillis(), config)
        provideContent { WidgetContent(state) }
    }

    @Composable
    private fun WidgetContent(state: WidgetState) {
        val primary = ColorProvider(Color.White)
        val secondary = ColorProvider(Color(0xFFB0BEC5))
        val warning = ColorProvider(Color(0xFFFFB74D))
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF263238))
                .padding(8.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = state.headline,
                    style = TextStyle(color = primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "  ${state.ageText}",
                    style = TextStyle(color = secondary, fontSize = 11.sp)
                )
            }
            if (state.isEmpty) {
                Text(text = state.hint ?: ClimateWidgetModel.OPEN_HINT, style = TextStyle(color = secondary, fontSize = 13.sp))
            }
            for (row in state.rows) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(text = row.name, style = TextStyle(color = primary, fontSize = 12.sp), maxLines = 1)
                    Text(text = "  ${row.temperature}  ${row.humidity}", style = TextStyle(color = primary, fontSize = 12.sp), maxLines = 1)
                    if (row.badges.isNotEmpty()) {
                        Text(text = "  ${row.badges.joinToString(" ")}", style = TextStyle(color = warning, fontSize = 10.sp))
                    }
                }
            }
            if (state.alertNames.isNotEmpty()) {
                Text(
                    text = state.alertNames.joinToString(", "),
                    style = TextStyle(color = warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

class ClimateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClimateWidget()
}
