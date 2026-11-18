package com.rlcdpanel.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.AlertTransitions
import com.rlcdpanel.phone.protocol.TelemetryFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result of computing a new incoming snapshot transition against the previous snapshot.
 */
data class SnapshotTransition(
    val snapshot: TelemetrySnapshot,
    val risingAlerts: AlertMask,
    val statusMessage: String
)

/**
 * Foreground Service that keeps BLE telemetry scanning active when the app is in the background.
 */
class BleScanService : Service() {

    private var bleScanner: BleTelemetryScanner? = null
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pendingStop || intent?.action == ACTION_STOP) {
            pendingStop = false
            try {
                val notification = buildForegroundNotification("Beendet")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
            }
            stopScanning()
            return START_NOT_STICKY
        }
        startScanning()
        return START_STICKY
    }

    fun startScanning() {
        if (_isScanning.value && bleScanner?.isScanning == true) {
            return
        }

        // Always satisfy startForeground first to comply with Android 8+ foreground service contract
        val notification = buildForegroundNotification(FOREGROUND_NOTIFICATION_TEXT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            _statusMessage.value = "Bluetooth-Berechtigung fehlt"
            _isScanning.value = false
            stopForegroundAndSelf()
            return
        } catch (_: Exception) {
            _statusMessage.value = "Hintergrunddienst-Fehler"
            _isScanning.value = false
            stopForegroundAndSelf()
            return
        }

        if (!hasScanPermission(this)) {
            _statusMessage.value = "Bluetooth-Berechtigung fehlt"
            _isScanning.value = false
            stopForegroundAndSelf()
            return
        }

        if (bleScanner == null) {
            bleScanner = BleTelemetryScanner(
                context = this,
                onSnapshot = { incoming ->
                    handleIncomingSnapshot(incoming)
                },
                onError = { message ->
                    _isScanning.value = false
                    _statusMessage.value = message
                    stopForegroundAndSelf()
                }
            )
        }

        val started = bleScanner?.start() ?: false
        _isScanning.value = started
        if (started) {
            _statusMessage.value = "Suche..."
            updateForegroundNotification(FOREGROUND_NOTIFICATION_TEXT)
        } else {
            stopForegroundAndSelf()
        }
    }

    fun stopScanning() {
        bleScanner?.stop()
        bleScanner = null
        _isScanning.value = false
        _statusMessage.value = "Bereit"
        lastNotificationText = null
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        lastNotificationText = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun handleIncomingSnapshot(incoming: TelemetrySnapshot) {
        if (!_isScanning.value || bleScanner == null) {
            return
        }
        val transition = computeTransition(_snapshot.value, incoming)
        _snapshot.value = transition.snapshot
        _statusMessage.value = transition.statusMessage

        if (!transition.risingAlerts.isEmpty) {
            vibrateAlert()
            postAlertNotification(transition.risingAlerts.names.joinToString(", "))
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Channel for the persistent foreground service notification
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "RLCD Hintergrunddienst",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält den BLE-Telemetrieempfang im Hintergrund aktiv"
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)

            // High-importance channel for alerts and ventilation warnings
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "RLCD Meldungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarme und Lüftungsempfehlungen"
                enableVibration(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildForegroundNotification(contentText: String = FOREGROUND_NOTIFICATION_TEXT): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BleScanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RLCD Panel Scanner")
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    private fun postAlertNotification(alertText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RLCD Alarm")
            .setContentText(alertText)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrateAlert() {
        getVibrator()?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0L, 200L, 100L, 200L, 100L, 400L),
                -1
            )
        )
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        bleScanner?.stop()
        bleScanner = null
        _isScanning.value = false
        super.onDestroy()
    }

    companion object {
        private var instance: BleScanService? = null
        private var pendingStop: Boolean = false

        private val _snapshot = MutableStateFlow<TelemetrySnapshot?>(null)
        val snapshot: StateFlow<TelemetrySnapshot?> = _snapshot.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        private val _statusMessage = MutableStateFlow("Bereit")
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        const val ACTION_START = "com.rlcdpanel.phone.action.START_SCAN"
        const val ACTION_STOP = "com.rlcdpanel.phone.action.STOP_SCAN"
        const val NOTIFICATION_ID = 800
        const val ALERT_NOTIFICATION_ID = 900
        const val SERVICE_CHANNEL_ID = "rlcd_scan_service_channel"
        const val ALERT_CHANNEL_ID = "rlcd_ventilation_channel"
        const val FOREGROUND_NOTIFICATION_TEXT = "Telemetrieempfang aktiv"

        fun computeTransition(
            current: TelemetrySnapshot?,
            incoming: TelemetrySnapshot
        ): SnapshotTransition {
            val mergedFrame = TelemetryFrame.merge(current?.frame, incoming.frame)
            val mergedSnapshot = incoming.copy(frame = mergedFrame)
            val rising = if (current == null) {
                incoming.frame.alerts
            } else {
                AlertTransitions.risingAlerts(current.frame, incoming.frame)
            }
            val msg = "Seq #${incoming.frame.sequence} empfangen"
            return SnapshotTransition(mergedSnapshot, rising, msg)
        }

        fun hasScanPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun start(context: Context) {
            if (_isScanning.value && instance != null) {
                return
            }
            if (!hasScanPermission(context)) {
                _statusMessage.value = "Bluetooth-Berechtigung fehlt"
                return
            }
            pendingStop = false
            val intent = Intent(context, BleScanService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                _isScanning.value = false
                _statusMessage.value = "Hintergrunddienst-Fehler"
            }
        }

        fun stop(context: Context) {
            pendingStop = true
            if (instance != null) {
                instance?.stopScanning()
            } else if (_isScanning.value) {
                context.stopService(Intent(context, BleScanService::class.java))
                _isScanning.value = false
                _statusMessage.value = "Bereit"
            }
        }

        fun updateStatusMessage(message: String) {
            _statusMessage.value = message
        }

        fun updateSnapshotForTest(newSnapshot: TelemetrySnapshot?) {
            _snapshot.value = newSnapshot
        }

        fun setScanningForTest(scanning: Boolean) {
            _isScanning.value = scanning
        }

        fun resetForTest() {
            _snapshot.value = null
            _isScanning.value = false
            _statusMessage.value = "Bereit"
            instance = null
            pendingStop = false
        }
    }
}
