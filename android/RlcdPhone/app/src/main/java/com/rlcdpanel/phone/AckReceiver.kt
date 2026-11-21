package com.rlcdpanel.phone

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.rlcdpanel.phone.console.BleConsoleClient
import com.rlcdpanel.phone.protocol.AckCommand
import com.rlcdpanel.phone.protocol.ConsoleReply

/**
 * Handles the tap on the Acknowledge action of the alert notification. Sends `ACK <N>` once through
 * [BleConsoleClient] and shows the result. It sends nothing without a tap and does not retry a failed command.
 */
class AckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bit = intent.getIntExtra(EXTRA_BIT, -1)
        val command = try {
            AckCommand.line(bit)
        } catch (_: IllegalArgumentException) {
            return
        }
        val notificationId = BleScanService.ackNotificationId(bit)
        if (!hasBluetoothPermissions(context)) {
            showResult(context.applicationContext, notificationId, "Bluetooth permission missing")
            return
        }
        // One command at a time: the panel accepts one central, so a second tap would fail.
        if (!inFlight.compareAndSet(false, true)) return
        val app = context.applicationContext
        val pending = goAsync()
        val client = BleConsoleClient(app)
        val handler = Handler(Looper.getMainLooper())
        var done = false
        lateinit var watchdog: Runnable
        val finishOnce = { reply: ConsoleReply? ->
            if (!done) {
                done = true
                handler.removeCallbacks(watchdog)
                inFlight.set(false)
                showResult(app, notificationId, AckCommand.resultText(reply))
                pending.finish()
            }
        }
        // The broadcast limit is about 10 s, like the client timeout. End before it.
        watchdog = Runnable {
            client.cancel()
            finishOnce(null)
        }
        handler.postDelayed(watchdog, WATCHDOG_MS)
        try {
            client.send(command) { reply -> finishOnce(reply) }
        } catch (_: Exception) {
            finishOnce(null)
        }
    }

    /** `BleConsoleClient` needs both permissions on SDK 31 and later; it does not guard `connectGatt`. */
    private fun hasBluetoothPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showResult(context: Context, notificationId: Int, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, BleScanService.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RLCD Alarm")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(RESULT_TIMEOUT_MS)
            .build()
        manager.notify(notificationId, notification)
    }

    companion object {
        const val ACTION_ACK = "com.rlcdpanel.phone.action.ACK"
        const val EXTRA_BIT = "com.rlcdpanel.phone.extra.ALERT_BIT"
        private const val RESULT_TIMEOUT_MS = 30_000L
        private const val WATCHDOG_MS = 8_000L
        private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
