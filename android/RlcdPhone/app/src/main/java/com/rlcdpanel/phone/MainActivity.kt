package com.rlcdpanel.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rlcdpanel.phone.console.BleConsoleClient
import com.rlcdpanel.phone.protocol.CalibrationBatch
import com.rlcdpanel.phone.protocol.CalibrationReply
import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.ParseException
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.StatusReply
import com.rlcdpanel.phone.protocol.ConfigCache
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryHistory
import com.rlcdpanel.phone.ventilation.OutdoorClimate
import com.rlcdpanel.phone.ventilation.OutdoorResolver
import com.rlcdpanel.phone.ventilation.OutdoorSource
import com.rlcdpanel.phone.ventilation.OutdoorWeatherService
import com.rlcdpanel.phone.ventilation.VentilationAction
import com.rlcdpanel.phone.ventilation.VentilationGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RoomVentilationState(
    val isVentilating: Boolean = false,
    val startMillis: Long? = null
)

class MainActivity : ComponentActivity() {

    private var nowMillis by mutableLongStateOf(System.currentTimeMillis())

    private var outdoorSource by mutableStateOf<OutdoorSource>(OutdoorSource.OpenMeteo())
    private var openMeteoClimate by mutableStateOf<OutdoorClimate?>(null)
    private var focusedRoomIndex by mutableIntStateOf(0)

    private var roomStates by mutableStateOf<Map<Int, RoomVentilationState>>(emptyMap())
    private val lastObservedAktionen = mutableMapOf<Int, VentilationAction>()
    private var panelConfig by mutableStateOf(PanelConfig())

    private var pendingConsoleCommand = CONSOLE_STATUS
    private var notificationPermissionAsked = false
    private var scanPermissionDenied = false
    private var weatherJob: Job? = null

    private lateinit var consoleClient: BleConsoleClient
    private var showHealth by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    private var showCalib by mutableStateOf(false)
    private var calibState by mutableStateOf(CalibrationState())
    private var pendingConsoleAction: (() -> Unit)? = null
    private var healthState by mutableStateOf(SensorHealthState())
    private var configState by mutableStateOf(ConfigReadState())
    // Kept after the health screen closes, so the dashboard tiles keep their protocol badges.
    private var sourceBadges by mutableStateOf<Map<Int, String>>(emptyMap())
    private lateinit var prefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Notifications are optional. Only the scan permissions block the BLE scan.
        val scanGranted = grants.isNotEmpty() &&
            grants.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it } &&
            BleScanService.hasScanPermission(this)
        if (scanGranted) {
            scanPermissionDenied = false
            startBleScan()
        } else {
            scanPermissionDenied = true
            BleScanService.updateStatusMessage("Bluetooth-Berechtigung fehlt")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rlcd_ventilation_prefs", Context.MODE_PRIVATE)
        panelConfig = PanelConfig.load(this)
        configState = ConfigReadState(info = ConfigCache.info(filesDir))
        createNotificationChannel()
        restoreRoomStates()

        consoleClient = BleConsoleClient(this)

        // Fetch Open-Meteo weather every 15 minutes while the activity is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    fetchWeatherIfNeeded()
                    delay(15 * 60 * 1000L)
                }
            }
        }

        // Tick the clock once a second while the activity is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    nowMillis = System.currentTimeMillis()
                    delay(1000L)
                }
            }
        }

        setContent {
            val snapshot by BleScanService.snapshot.collectAsState()
            val isScanning by BleScanService.isScanning.collectAsState()
            val statusMessage by BleScanService.statusMessage.collectAsState()

            if (showHistory) {
                val history by BleScanService.history.collectAsState()
                HistoryScreen(
                    history = history,
                    panelConfig = panelConfig,
                    nowMillis = nowMillis,
                    onShare = { shareHistoryCsv(history) },
                    onBack = { showHistory = false }
                )
                BackHandler { showHistory = false }
                return@setContent
            }
            if (showCalib) {
                CalibrationScreen(
                    state = calibState,
                    onPickReference = { calibState = calibState.copy(reference = it, reply = null, selected = emptySet(), message = null, isError = false) },
                    onRead = { withConsolePermission { readCalibration() } },
                    onToggle = { key ->
                        calibState = calibState.copy(
                            selected = if (key in calibState.selected) calibState.selected - key else calibState.selected + key
                        )
                    },
                    onApply = { withConsolePermission { applyCalibration() } },
                    onBack = { closeCalibration() }
                )
                BackHandler { closeCalibration() }
                return@setContent
            }
            if (showHealth) {
                SensorHealthScreen(
                    state = healthState,
                    panelConfig = panelConfig,
                    onRead = { ensureConsolePermissionAndRead(CONSOLE_STATUS) },
                    configState = configState,
                    nowMillis = System.currentTimeMillis(),
                    onReadConfig = { ensureConsolePermissionAndRead(CONSOLE_CONFIG) },
                    onOpenCalibration = { showHealth = false; showCalib = true },
                    onBack = { closeSensorHealth() }
                )
                BackHandler { closeSensorHealth() }
                return@setContent
            }
            // Determine effective outdoor climate
            val effectiveOutdoor: OutdoorClimate? = OutdoorResolver.resolve(
                source = outdoorSource,
                openMeteo = openMeteoClimate,
                tiles = snapshot?.frame?.tiles,
                frameReceivedAtMillis = snapshot?.receivedAtMillis,
                nowMillis = nowMillis
            )

            val currentRoomState = roomStates[focusedRoomIndex] ?: RoomVentilationState()
            val isVentilating = currentRoomState.isVentilating
            val durationMinutes = if (isVentilating && currentRoomState.startMillis != null) {
                (((nowMillis - currentRoomState.startMillis) / 60000L).coerceAtLeast(0L)).toInt()
            } else {
                0
            }

            val isSelfComparison = outdoorSource is OutdoorSource.SensorSlot &&
                (outdoorSource as OutdoorSource.SensorSlot).tileIndex == focusedRoomIndex

            val roomTile = snapshot?.frame?.tiles?.getOrNull(focusedRoomIndex)
            val roomFrameFresh = OutdoorResolver.isFrameFresh(snapshot?.receivedAtMillis, nowMillis)
            val advice = if (OutdoorResolver.mayAdvise(isSelfComparison, roomFrameFresh, roomTile, effectiveOutdoor)) {
                VentilationGuide.analysiereLueftung(
                    tempInnen = roomTile!!.temperatureC!!,
                    rFInnen = roomTile.humidityPct!!,
                    tempAussen = effectiveOutdoor!!.tempC,
                    rFAussen = effectiveOutdoor.rhPct,
                    istGeradeAmLueften = isVentilating,
                    lueftungsDauerMinuten = durationMinutes
                )
            } else {
                null
            }

            // Check for recommendation transitions and trigger vibrations/notifications
            LaunchedEffect(focusedRoomIndex, advice?.aktion, isVentilating) {
                val currentAdvice = advice
                val aktion = currentAdvice?.aktion
                val lastAktion = lastObservedAktionen[focusedRoomIndex]

                if (currentAdvice != null && aktion != null) {
                    if (lastAktion != null && lastAktion != aktion) {
                        when (aktion) {
                            VentilationAction.LUEFTUNG_STARTEN -> {
                                if (!isVentilating) {
                                    vibrateNotice()
                                }
                            }
                            VentilationAction.LUEFTUNG_BEENDEN,
                            VentilationAction.LUEFTUNG_ABBRECHEN -> {
                                if (isVentilating) {
                                    vibrateAlert()
                                    postVentilationNotification(
                                        title = currentAdvice.titel,
                                        message = currentAdvice.nachricht,
                                        notificationId = 1000 + focusedRoomIndex
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                    lastObservedAktionen[focusedRoomIndex] = aktion
                } else {
                    // A gap in the advice (stale frame, no weather) must not compare against an old action.
                    lastObservedAktionen.remove(focusedRoomIndex)
                }
            }

            PhoneDashboardScreen(
                snapshot = snapshot,
                panelConfig = panelConfig,
                isScanning = isScanning,
                statusMessage = statusMessage,
                nowMillis = nowMillis,
                outdoorClimate = effectiveOutdoor,
                outdoorSource = outdoorSource,
                focusedRoomIndex = focusedRoomIndex,
                isVentilating = isVentilating,
                ventilationDurationMinutes = durationMinutes,
                ventilationAdvice = advice,
                isSelfComparison = isSelfComparison,
                onToggleScan = {
                    if (isScanning) {
                        stopBleScan()
                    } else {
                        ensurePermissionAndStart()
                    }
                },
                onCycleOutdoorSource = {
                    cycleOutdoorSource()
                },
                onSelectRoomIndex = { index ->
                    focusedRoomIndex = index
                },
                onToggleVentilation = {
                    toggleVentilationForFocusedRoom()
                },
                onOpenSensorHealth = { showHealth = true },
                onOpenHistory = { showHistory = true },
                sourceBadges = sourceBadges
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // A denied scan permission shows a status hint. The scan button asks again on demand.
        if (!scanPermissionDenied && !BleScanService.isScanning.value) {
            ensurePermissionAndStart()
        }
    }

    override fun onStop() {
        val consoleWasRunning = consoleClient.isRunning
        consoleClient.cancel()
        healthState = healthState.copy(isReading = false)
        configState = configState.copy(isReading = false)
        if (calibState.isBusy) {
            calibState = calibState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, CONFIG lesen")
        }
        // Background scanning continues in BleScanService.
        lastObservedAktionen.clear()
        super.onStop()
    }

    private val consolePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingConsoleAction
        if (action != null) {
            pendingConsoleAction = null
            if (grants.isNotEmpty() && grants.values.all { it }) action()
            else calibState = calibState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            return@registerForActivityResult
        }
        if (!showHealth) return@registerForActivityResult
        val command = pendingConsoleCommand
        if (grants.values.all { it }) {
            runConsoleCommand(command)
        } else if (command == CONSOLE_CONFIG) {
            configState = configState.copy(isReading = false, message = "Bluetooth-Berechtigung fehlt")
        } else {
            healthState = SensorHealthState(message = "Bluetooth-Berechtigung fehlt")
        }
    }

    /** Sends the newest rows as CSV text. The cap keeps the intent below the binder limit. */
    private fun shareHistoryCsv(history: TelemetryHistory) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, history.toCsv(HistoryScreenText.SHARE_MAX_ROWS))
        }
        startActivity(Intent.createChooser(send, "Share CSV"))
    }

    private fun closeCalibration() {
        consoleClient.cancel()
        pendingConsoleAction = null
        calibState = CalibrationState(reference = calibState.reference)
        showCalib = false
        showHealth = true
    }

    /** Runs [action] after the Bluetooth permissions; the action sends console commands. */
    private fun withConsolePermission(action: () -> Unit) {
        if (calibState.isBusy || consoleClient.isRunning) return
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingConsoleAction = action
            calibState = calibState.copy(isBusy = true, message = null)
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        action()
    }

    /** Sends `CALIB N` after a tap; the proposals that the key range rejects never reach the list. */
    private fun readCalibration() {
        stopBleScan()
        calibState = calibState.copy(isBusy = true, isError = false, message = null, reply = null, selected = emptySet())
        consoleClient.send("CALIB ${calibState.reference}") { reply ->
            calibState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val parsed = CalibrationReply.parse(reply.lines)
                    calibState.copy(isBusy = false, reply = parsed, selected = parsed.proposals.map { it.key }.toSet())
                } catch (e: ParseException) {
                    calibState.copy(isBusy = false, isError = true, message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                is ConsoleReply.Status -> calibState.copy(
                    isBusy = false, isError = true,
                    message = if (reply.line.startsWith("ERR READ_ONLY")) CalibrationBatch.failureText(reply.line)
                    else BleConsoleClient.errorText(reply)
                )
                else -> calibState.copy(isBusy = false, isError = true, message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Sends the selected proposals as `SET`, then `CONFIG`. The first `ERR` stops the batch and skips `CONFIG`. */
    private fun applyCalibration() {
        val chosen = calibState.chosen
        if (chosen.isEmpty()) return
        stopBleScan()
        calibState = calibState.copy(isBusy = true, message = null)
        CalibrationBatch(chosen).run(consoleClient::send) { result ->
            result.config?.let { frame ->
                lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, frame, System.currentTimeMillis()) }
                    if (info != null) {
                        panelConfig = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                        configState = ConfigReadState(info = info)
                    }
                }
            }
            // The offsets changed on the panel, so the old proposals are stale. A new read gives fresh ones.
            val ok = result.failure == null && !result.configFailed
            calibState = calibState.copy(
                isBusy = false, message = result.summary, isError = !ok,
                reply = if (result.failure == null) null else calibState.reply,
                selected = if (result.failure == null) emptySet() else calibState.selected
            )
        }
    }

    private fun closeSensorHealth() {
        consoleClient.cancel()
        healthState = SensorHealthState()
        configState = configState.copy(isReading = false, message = null)
        showHealth = false
        if (!scanPermissionDenied && !BleScanService.isScanning.value) ensurePermissionAndStart()
    }

    /** Sends [command] (`STATUS` or `CONFIG`) only after a tap on a button. */
    private fun ensureConsolePermissionAndRead(command: String) {
        if (healthState.isReading || configState.isReading) return
        pendingConsoleCommand = command
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            markConsoleReading(command)
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        runConsoleCommand(command)
    }

    private fun markConsoleReading(command: String) {
        if (command == CONSOLE_CONFIG) {
            configState = configState.copy(isReading = true, message = null)
        } else {
            healthState = SensorHealthState(isReading = true)
        }
    }

    private fun runConsoleCommand(command: String) {
        if (command == CONSOLE_CONFIG) readPanelConfig() else readSensorHealth()
    }

    /** Reads `CONFIG`. A good reply replaces the cache; every other reply keeps the old cache. */
    private fun readPanelConfig() {
        stopBleScan()
        configState = configState.copy(isReading = true, message = null)
        consoleClient.send(CONSOLE_CONFIG) { reply ->
            // The file write syncs to flash, so it stays off the main thread.
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, reply, System.currentTimeMillis()) }
                if (info != null) {
                    panelConfig = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                    configState = ConfigReadState(info = info)
                } else {
                    val text = if (reply is ConsoleReply.Frame) "Panel liefert keine SD-Einstellungen" else BleConsoleClient.errorText(reply)
                    configState = configState.copy(isReading = false, message = "$text. Alter Stand bleibt erhalten.")
                }
            }
        }
    }

    private fun readSensorHealth() {
        // The telemetry scan stops, so the console scan and connection have the radio.
        stopBleScan()
        healthState = SensorHealthState(isReading = true)
        consoleClient.send(CONSOLE_STATUS) { reply ->
            healthState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val status = StatusReply.parse(reply.lines)
                    val limit = panelConfig.values["sensor_battery_limit_pct"]?.toDoubleOrNull()
                    SensorHealthState(rows = SensorHealth.rows(status, limit), ntp = status.ntp).also { s ->
                        sourceBadges = s.rows.mapNotNull { r -> r.badge?.let { r.slot to it } }.toMap()
                    }
                } catch (e: ParseException) {
                    sourceBadges = emptyMap()
                    SensorHealthState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                else -> {
                    sourceBadges = emptyMap()
                    SensorHealthState(message = BleConsoleClient.errorText(reply))
                }
            }
        }
    }

    private fun fetchWeatherIfNeeded() {
        if (outdoorSource is OutdoorSource.OpenMeteo) {
            val src = outdoorSource as OutdoorSource.OpenMeteo
            // Cancel an older fetch, so a late reply cannot overwrite a newer one.
            weatherJob?.cancel()
            weatherJob = lifecycleScope.launch {
                val weather = OutdoorWeatherService.fetchCurrent(
                    latitude = src.latitude,
                    longitude = src.longitude,
                    locationName = src.displayName
                )
                if (weather != null) {
                    openMeteoClimate = weather
                }
            }
        }
    }

    private fun toggleVentilationForFocusedRoom() {
        val current = roomStates[focusedRoomIndex] ?: RoomVentilationState()
        val newState = if (current.isVentilating) {
            RoomVentilationState(isVentilating = false, startMillis = null)
        } else {
            RoomVentilationState(isVentilating = true, startMillis = System.currentTimeMillis())
        }
        val updated = roomStates.toMutableMap()
        updated[focusedRoomIndex] = newState
        roomStates = updated
        persistRoomState(focusedRoomIndex, newState)
    }

    private fun persistRoomState(roomIndex: Int, state: RoomVentilationState) {
        prefs.edit()
            .putBoolean("is_vent_${roomIndex}", state.isVentilating)
            .putLong("start_ms_${roomIndex}", state.startMillis ?: 0L)
            .apply()
    }

    private fun restoreRoomStates() {
        val map = mutableMapOf<Int, RoomVentilationState>()
        for (i in 0 until TelemetryFrame.TOTAL_TILES) {
            val isVent = prefs.getBoolean("is_vent_${i}", false)
            val startMs = prefs.getLong("start_ms_${i}", 0L)
            // Drop a timer that is older than the maximum ventilation age (the app was away).
            val ageMs = System.currentTimeMillis() - startMs
            if (isVent && startMs > 0L && ageMs in 0..MAX_RESTORED_VENTILATION_MS) {
                map[i] = RoomVentilationState(isVentilating = true, startMillis = startMs)
            }
        }
        roomStates = map
    }

    private fun cycleOutdoorSource() {
        val totalTiles = BleScanService.snapshot.value?.frame?.tiles?.size ?: TelemetryFrame.TOTAL_TILES
        val nextSource = when (val src = outdoorSource) {
            is OutdoorSource.OpenMeteo -> {
                OutdoorSource.SensorSlot(
                    tileIndex = 1,
                    slotName = panelConfig.tileName(1)
                )
            }
            is OutdoorSource.SensorSlot -> {
                val nextIndex = src.tileIndex + 1
                if (nextIndex < totalTiles) {
                    OutdoorSource.SensorSlot(
                        tileIndex = nextIndex,
                        slotName = panelConfig.tileName(nextIndex)
                    )
                } else {
                    OutdoorSource.OpenMeteo()
                }
            }
        }
        outdoorSource = nextSource
        fetchWeatherIfNeeded()
    }

    private fun ensurePermissionAndStart() {
        val needed = mutableListOf<String>()
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            // SDK 26 to 30 hide BLE scan results without the location permission.
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, scanPermission) != PackageManager.PERMISSION_GRANTED) {
            needed.add(scanPermission)
        }
        // Ask for notifications once for each activity instance, so a denial does not re-prompt on each resume.
        if (!notificationPermissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            notificationPermissionAsked = true
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return
        }
        startBleScan()
    }

    private fun startBleScan() {
        BleScanService.start(this)
    }

    private fun stopBleScan() {
        BleScanService.stop(this)
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
        getVibrator()?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 150L, 100L, 150L), -1)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RLCD Meldungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarme und Lüftungsempfehlungen"
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun postVentilationNotification(title: String, message: String, notificationId: Int) {
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }

    companion object {
        const val CHANNEL_ID = "rlcd_ventilation_channel"
        const val MAX_RESTORED_VENTILATION_MS = 6 * 60 * 60 * 1000L
    }
}

/** Console commands that a tap on a button sends. */
private const val CONSOLE_STATUS = "STATUS"
private const val CONSOLE_CONFIG = "CONFIG"
