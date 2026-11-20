package com.rlcdpanel.watch

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rlcdpanel.watch.complication.ClimateComplicationService
import com.rlcdpanel.watch.protocol.AckCommand
import com.rlcdpanel.watch.protocol.AlertMask
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.tile.ClimateTileService
import com.rlcdpanel.watch.protocol.AlertTransitions
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.SilenceWatch
import com.rlcdpanel.watch.protocol.StaleWatch
import com.rlcdpanel.watch.protocol.TelemetryFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result of computing a new incoming snapshot transition against the previous snapshot.
 */
data class SnapshotTransition(
    val snapshot: TelemetrySnapshot,
    val risingAlerts: AlertMask,
    val statusMessage: String
)

/**
 * Foreground Service that keeps BLE telemetry scanning active on Wear OS when the app is in the background.
 */
class BleScanService : Service() {

    private var bleScanner: BleTelemetryScanner? = null
    private var lastNotificationText: String? = null
    private var silenceWatch = SilenceWatch()
    private var staleWatch = StaleWatch()
    private val silenceHandler = Handler(Looper.getMainLooper())
    private val silenceCheck = object : Runnable {
        override fun run() {
            if (silenceWatch.check() == SilenceWatch.Event.SILENT) {
                postSilenceNotification(silenceWatch.lastFrameAtMillis)
            }
            silenceHandler.postDelayed(this, SILENCE_CHECK_MS)
        }
    }

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
        val notification = buildForegroundNotification(_statusMessage.value)
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
            updateForegroundNotification("Suche nach RLCD-Panel...")
            startSilenceWatch()
        } else {
            stopForegroundAndSelf()
        }
    }

    private fun startSilenceWatch() {
        silenceHandler.removeCallbacks(silenceCheck)
        cancelSilenceNotification()
        silenceWatch = SilenceWatch()
        cancelStaleNotifications()
        staleWatch = StaleWatch()
        silenceHandler.postDelayed(silenceCheck, SILENCE_CHECK_MS)
    }

    private fun stopSilenceWatch() {
        silenceHandler.removeCallbacks(silenceCheck)
        cancelSilenceNotification()
        cancelStaleNotifications()
    }

    fun stopScanning() {
        stopSilenceWatch()
        bleScanner?.stop()
        bleScanner = null
        _isScanning.value = false
        _statusMessage.value = "Bereit"
        lastNotificationText = null
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        stopSilenceWatch()
        lastNotificationText = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private var lastCachedSequence = -1
    private var lastCachedPage = -1

    private fun handleIncomingSnapshot(incoming: TelemetrySnapshot) {
        if (!_isScanning.value || bleScanner == null) {
            return
        }
        if (silenceWatch.onFrame() == SilenceWatch.Event.BACK) {
            cancelSilenceNotification()
        }
        val transition = computeTransition(_snapshot.value, incoming)
        _snapshot.value = transition.snapshot
        _statusMessage.value = transition.statusMessage
        cacheForTile(incoming)
        feedStaleWatch(incoming.frame)

        if (!transition.risingAlerts.isEmpty) {
            vibrateAlert()
            postAlertNotification(transition.risingAlerts)
        }

        updateForegroundNotification(transition.statusMessage)
    }

    /** Writes the payload of a frame with a new sequence number for the Wear OS tile and the complication. */
    private fun cacheForTile(snapshot: TelemetrySnapshot) {
        val payload = snapshot.payload ?: return
        val frame = snapshot.frame
        if (frame.sequence == lastCachedSequence && frame.page == lastCachedPage) {
            return
        }
        if (TelemetryCache(this).save(payload, snapshot.receivedAtMillis)) {
            lastCachedSequence = frame.sequence
            lastCachedPage = frame.page
            ClimateTileService.requestUpdate(this)
            ClimateComplicationService.requestUpdate(this)
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
                description = "Hält den BLE-Telemetrieempfang auf der Smartwatch aktiv"
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)

            // High-importance channel for alerts
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Lüftungsempfehlungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Lüftungsempfehlungen und Alarme"
                enableVibration(true)
            }
            manager.createNotificationChannel(alertChannel)

            // Default-importance channel without vibration for the panel silence notice
            val silenceChannel = NotificationChannel(
                SILENCE_CHANNEL_ID,
                "Panel-Stille",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Hinweis, wenn das Panel keine Telemetrie mehr sendet"
                enableVibration(false)
                setVibrationPattern(null)
            }
            manager.createNotificationChannel(silenceChannel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RLCD Watch")
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    private fun postAlertNotification(rising: AlertMask) {
        val ackBits = AckCommand.actionBits(rising)
        val otherText = AlertMask(rising.rawValue and AckCommand.REPEATABLE.rawValue.inv()).names.joinToString(", ")
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
        // One notification for each acknowledgeable bit, so that an ACK result and a later alert keep the other actions.
        for (bit in ackBits) {
            val ack = PendingIntent.getBroadcast(
                this,
                ACK_REQUEST_BASE + bit,
                Intent(this, AckReceiver::class.java).apply {
                    action = AckReceiver.ACTION_ACK
                    putExtra(AckReceiver.EXTRA_BIT, bit)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("RLCD Alarm")
                .setContentText(AlertMask(1 shl bit).names.joinToString(", "))
                .setContentIntent(openApp)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_agenda, "Acknowledge", ack)
                .build()
            manager.notify(ackNotificationId(bit), notification)
        }
        if (otherText.isEmpty()) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RLCD Alarm")
            .setContentText(otherText)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun postSilenceNotification(lastFrameMillis: Long?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val since = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastFrameMillis ?: System.currentTimeMillis()))
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SILENCE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RLCD Panel")
            .setContentText("Panel silent since $since")
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(null)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(SILENCE_NOTIFICATION_ID, notification)
    }

    /** Feeds one frame into the stale watch and posts or cancels the notifications. */
    private fun feedStaleWatch(frame: TelemetryFrame) {
        var config: PanelConfig? = null
        for (result in staleWatch.updateFrame(frame)) {
            when (result) {
                is StaleWatch.Result.Lost -> {
                    val loaded = config ?: PanelConfig.load(this).also { config = it }
                    if (postStaleNotification(result.tile, loaded.tileName(result.tile))) {
                        vibrateNotice()
                    }
                }
                is StaleWatch.Result.Back -> cancelStaleNotification(result.tile)
            }
        }
    }

    private fun postStaleNotification(tile: Int, name: String): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SILENCE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RLCD Sensor")
            .setContentText("$name: no data for 35 min")
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(null)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager.notify(STALE_NOTIFICATION_BASE_ID + tile, notification)
        return true
    }

    private fun cancelStaleNotification(tile: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(STALE_NOTIFICATION_BASE_ID + tile)
    }

    private fun cancelStaleNotifications() {
        for (tile in 0 until StaleWatch.TILE_COUNT) cancelStaleNotification(tile)
    }

    private fun cancelSilenceNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(SILENCE_NOTIFICATION_ID)
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
            VibrationEffect.createWaveform(longArrayOf(0L, 250L, 120L, 250L, 120L, 250L), -1)
        )
    }

    private fun vibrateNotice() {
        getVibrator()?.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        stopSilenceWatch()
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

        const val ACTION_START = "com.rlcdpanel.watch.action.START_SCAN"
        const val ACTION_STOP = "com.rlcdpanel.watch.action.STOP_SCAN"
        const val NOTIFICATION_ID = 801
        const val ALERT_NOTIFICATION_ID = 901
        const val SILENCE_NOTIFICATION_ID = 902
        private const val SILENCE_CHECK_MS = 60_000L
        const val SILENCE_CHANNEL_ID = "rlcd_silence_channel"
        private const val ACK_REQUEST_BASE = 100
        private const val ACK_NOTIFICATION_BASE = 910
        const val STALE_NOTIFICATION_BASE_ID = 940

        /** Notification id of the alert with [bit]; the ACK result replaces it. */
        fun ackNotificationId(bit: Int): Int = ACK_NOTIFICATION_BASE + bit
        const val SERVICE_CHANNEL_ID = "rlcd_watch_scan_service_channel"
        const val ALERT_CHANNEL_ID = "rlcd_ventilation_channel"

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
                true
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
