package com.rlcdpanel.phone

import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rlcdpanel.phone.console.BleConsoleClient
import com.rlcdpanel.phone.protocol.ChimeCommand
import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.MuteTileModel
import com.rlcdpanel.phone.protocol.MuteTileState

/**
 * Quick Settings tile for the panel mute flag. It shows `muted` of the last `STATUS` reply ([MuteStateStore]). A tap sends
 * `MUTE 0` or `MUTE 1` through [BleConsoleClient], then `STATUS`. Without a cached reply the tile is unavailable (Android
 * delivers no tap); the app fills the cache after each `STATUS`. It sends nothing without a tap and does not retry.
 */
class MuteTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())
    private var error: String? = null

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        // A tap on the lock screen must not change the panel without an unlock.
        if (isLocked) unlockAndRun { sendTap() } else sendTap()
    }

    private fun sendTap() {
        val action = MuteTileModel.of(MuteStateStore.load(applicationContext)).tapAction ?: return
        if (!BluetoothPermissions.hasConsolePermissions(applicationContext)) {
            error = "Bluetooth-Berechtigung fehlt"
            render()
            return
        }
        // One command at a time: the panel accepts one central.
        if (!inFlight.compareAndSet(false, true)) return
        val app = applicationContext
        val client = BleConsoleClient(app)
        var done = false
        lateinit var watchdog: Runnable
        val finish = { text: String? ->
            if (!done) {
                done = true
                handler.removeCallbacks(watchdog)
                inFlight.set(false)
                error = text
                render()
            }
        }
        watchdog = Runnable {
            client.cancel()
            finish("Keine Antwort vom Panel")
        }
        handler.postDelayed(watchdog, WATCHDOG_MS)
        try {
            client.send(ChimeCommand.mute(action.muted)) { reply: ConsoleReply ->
                val failure = MuteTileModel.failureText(reply) { BleConsoleClient.errorText(it) }
                if (failure != null) {
                    finish(failure)
                } else if (!MuteTileModel.isMuteAck(reply, action.muted)) {
                    finish(MuteTileModel.UNREADABLE_TEXT)
                } else {
                    // The panel accepted the command: keep the cache right even when the STATUS read fails.
                    MuteStateStore.save(app, action.muted)
                    client.send(ChimeCommand.STATUS) { status ->
                        val muted = MuteTileModel.mutedFrom(status)
                        if (muted != null) {
                            MuteStateStore.save(app, muted)
                            finish(null)
                        } else {
                            finish(MuteTileModel.failureText(status) { BleConsoleClient.errorText(it) } ?: MuteTileModel.UNREADABLE_TEXT)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            finish("Keine Antwort vom Panel")
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val model = MuteTileModel.of(MuteStateStore.load(applicationContext), error)
        val hasSubtitle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        tile.label = MuteTileModel.labelWith(model, hasSubtitle)
        tile.state = when (model.state) {
            MuteTileState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            MuteTileState.MUTED -> Tile.STATE_ACTIVE
            MuteTileState.UNMUTED -> Tile.STATE_INACTIVE
        }
        if (hasSubtitle) tile.subtitle = model.subtitle
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_silent_mode)
        tile.updateTile()
    }

    companion object {
        private const val WATCHDOG_MS = 30_000L
        private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
