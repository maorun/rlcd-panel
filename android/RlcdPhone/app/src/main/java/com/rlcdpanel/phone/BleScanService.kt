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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.AlertTransitions
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.SilenceWatch
import com.rlcdpanel.phone.protocol.StaleWatch
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryHistory
import com.rlcdpanel.phone.protocol.TelemetryHistoryStore
import com.rlcdpanel.phone.widget.ClimateWidget
import com.rlcdpanel.phone.widget.ClimateWidgetModel
import com.rlcdpanel.phone.widget.WidgetSnapshotStore
import androidx.glance.appwidget.updateAll
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
        val store = TelemetryHistoryStore(File(filesDir, TelemetryHistoryStore.FILE_NAME))
        historyStore = store
        if (!historyLoadQueued) {
            historyLoadQueued = true
            // Parse on the writer thread. Rows that arrive meanwhile merge with the stored rows.
            historyWriter.execute {
                val stored = store.load()
                _history.update { current -> mergeHistory(stored, current) }
            }
        }
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
            startSilenceWatch()
            updateForegroundNotification(FOREGROUND_NOTIFICATION_TEXT)
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
        silenceTimerActive = true
        silenceHandler.postDelayed(silenceCheck, SILENCE_CHECK_MS)
    }

    private fun stopSilenceWatch() {
        silenceHandler.removeCallbacks(silenceCheck)
        silenceTimerActive = false
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
        if (silenceWatch.onFrame() == SilenceWatch.Event.BACK) {
            cancelSilenceNotification()
        }
        feedStaleWatch(incoming.frame)
        val transition = computeTransition(_snapshot.value, incoming)
        _snapshot.value = transition.snapshot
        _statusMessage.value = transition.statusMessage
        recordHistory(incoming.frame, incoming.receivedAtMillis)
        requestWidgetUpdate(transition.snapshot)

        if (!transition.risingAlerts.isEmpty) {
            vibrateAlert()
            postAlertNotification(transition.risingAlerts.names.joinToString(", "))
        }
    }

    /**
     * Stores the snapshot and refreshes the widget, only for a frame that the widget did not show.
     * The check, the key and the rollback run on the single writer thread, so they cannot race.
     */
    private fun requestWidgetUpdate(snapshot: TelemetrySnapshot) {
        val context = applicationContext
        widgetWriter.execute {
            if (!ClimateWidgetModel.shouldRequestUpdate(lastWidgetSequence, snapshot.frame)) return@execute
            val previous = lastWidgetSequence
            lastWidgetSequence = ClimateWidgetModel.updateKey(snapshot.frame)
            try {
                WidgetSnapshotStore.save(context, snapshot)
                kotlinx.coroutines.runBlocking { ClimateWidget().updateAll(context) }
            } catch (e: Exception) {
                android.util.Log.w("BleScanService", "Widget update failed", e)
                // Allow a retry with the next frame of the same sequence number.
                lastWidgetSequence = previous
            }
        }
    }

    private fun recordHistory(frame: TelemetryFrame, receivedAtMillis: Long) {
        // Only page 0 holds the tiles 0 to 5 of the CSV columns. A page 1 frame holds the slots 6 to 10.
        if (!shouldRecord(frame)) return
        // A duplicate sequence returns the same object. The load merge may change the value meanwhile, so retry.
        var updated: TelemetryHistory
        while (true) {
            val before = _history.value
            updated = before.addFrame(frame, receivedAtMillis)
            if (updated === before) return
            if (_history.compareAndSet(before, updated)) break
        }
        val store = historyStore ?: return
        historyWriter.execute { saveLatest(store, _history, receivedAtMillis, throttled = true) }
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
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RLCD Panel")
            .setContentText("Panel silent since $since")
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
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
                    postStaleNotification(result.tile, loaded.tileName(result.tile))
                }
                is StaleWatch.Result.Back -> cancelStaleNotification(result.tile)
            }
        }
    }

    private fun postStaleNotification(tile: Int, name: String) {
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
            .setContentTitle("RLCD Sensor")
            .setContentText("$name: keine Daten seit 35 min")
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager.notify(STALE_NOTIFICATION_BASE_ID + tile, notification)
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
            VibrationEffect.createWaveform(
                longArrayOf(0L, 200L, 100L, 200L, 100L, 400L),
                -1
            )
        )
    }

    override fun onDestroy() {
        stopSilenceWatch()
        historyStore?.let { store ->
            historyWriter.execute { saveLatest(store, _history, System.currentTimeMillis(), throttled = false) }
        }
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
        private var historyStore: TelemetryHistoryStore? = null
        private var historyLoadQueued = false
        // One thread writes the file, so the main thread never waits for the disk and writes stay in order.
        private val historyWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val widgetWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
        @Volatile private var silenceTimerActive = false

        /** True while the 60 s silence check timer of a running service is queued. */
        val isSilenceTimerActive: Boolean get() = silenceTimerActive

        @Volatile private var lastWidgetSequence: Int? = null // written on widgetWriter only

        private val _history = MutableStateFlow(TelemetryHistory())
        val history: StateFlow<TelemetryHistory> = _history.asStateFlow()

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
        const val SILENCE_NOTIFICATION_ID = 901
        /** Ids 910 to 920 for the tiles 0 to 10. */
        const val STALE_NOTIFICATION_BASE_ID = 910
        const val SILENCE_CHECK_MS = 60_000L
        const val SERVICE_CHANNEL_ID = "rlcd_scan_service_channel"
        const val ALERT_CHANNEL_ID = "rlcd_ventilation_channel"
        const val FOREGROUND_NOTIFICATION_TEXT = "Telemetrieempfang aktiv"

        /** Only page 0 holds the tiles 0 to 5 of the CSV columns. A page 1 frame holds the slots 6 to 10. */
        fun shouldRecord(frame: TelemetryFrame): Boolean = frame.page == 0

        /**
         * Saves the value that the flow holds when the writer thread runs. The writer thread runs a queued
         * load merge first, so a save never writes a history without the stored rows.
         */
        fun saveLatest(
            store: TelemetryHistoryStore,
            history: StateFlow<TelemetryHistory>,
            nowMillis: Long,
            throttled: Boolean
        ) {
            val latest = history.value
            if (throttled) store.saveThrottled(latest, nowMillis) else store.save(latest, nowMillis)
        }

        /** Rows of the file first, then the rows that arrived while the file loaded. */
        fun mergeHistory(stored: TelemetryHistory, live: TelemetryHistory): TelemetryHistory {
            var merged = stored
            for (r in live.rows) merged = merged.add(r)
            return merged
        }

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
            lastWidgetSequence = null
            _history.value = TelemetryHistory()
            historyStore = null
            historyLoadQueued = false
            _isScanning.value = false
            _statusMessage.value = "Bereit"
            instance = null
            pendingStop = false
            silenceTimerActive = false
        }
    }
}
