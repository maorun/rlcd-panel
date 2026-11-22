package com.rlcdpanel.watch

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
import com.rlcdpanel.watch.console.BleConsoleClient
import com.rlcdpanel.watch.protocol.AckCommand
import com.rlcdpanel.watch.protocol.ChimeCommand
import com.rlcdpanel.watch.protocol.ConsoleReply

/**
 * Handles the tap on the Acknowledge, Acknowledge all or `1 h stumm` action of the alert notification. Sends `ACK <N>`,
 * `ACK ALL` or `SNOOZE 60` once through
 * [BleConsoleClient] and shows the result. It sends nothing without a tap and does not retry a failed command.
 */
class AckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val snooze = intent.action == ACTION_SNOOZE
        val all = !snooze && intent.getBooleanExtra(EXTRA_ALL, false)
        val bit = intent.getIntExtra(EXTRA_BIT, -1)
        val command = if (snooze) {
            ChimeCommand.snooze(SNOOZE_MINUTES)
        } else if (all) {
            AckCommand.ALL
        } else {
            try {
                AckCommand.line(bit)
            } catch (_: IllegalArgumentException) {
                return
            }
        }
        val notificationId = when {
            snooze -> BleScanService.SNOOZE_NOTIFICATION_ID
            all -> BleScanService.ACK_ALL_NOTIFICATION_ID
            else -> BleScanService.ackNotificationId(bit)
        }
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
                if (all && reply is ConsoleReply.Status && reply.line.startsWith("OK ACK ALL")) cancelBitNotifications(app)
                showResult(app, notificationId, if (snooze) snoozeResultText(reply) else AckCommand.resultText(reply))
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
        } catch (_: SecurityException) {
            finishOnce(null)
        }
    }

    private fun snoozeResultText(reply: ConsoleReply?): String {
        if (reply == null) return "Keine Antwort vom Panel"
        return ChimeCommand.failureText(reply) { BleConsoleClient.errorText(it) }
            ?: ChimeCommand.okText((reply as ConsoleReply.Status).line)
    }

    /** `ACK ALL` covers each bit, so the per-bit notifications and their actions are obsolete. */
    private fun cancelBitNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        for (bit in AckCommand.MIN_BIT..AckCommand.MAX_BIT) manager.cancel(BleScanService.ackNotificationId(bit))
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
        const val ACTION_ACK = "com.rlcdpanel.watch.action.ACK"
        const val ACTION_SNOOZE = "com.rlcdpanel.watch.action.SNOOZE"
        const val SNOOZE_MINUTES = 60
        const val EXTRA_BIT = "com.rlcdpanel.watch.extra.ALERT_BIT"
        const val EXTRA_ALL = "com.rlcdpanel.watch.extra.ACK_ALL"
        private const val RESULT_TIMEOUT_MS = 30_000L
        private const val WATCHDOG_MS = 8_000L
        private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
