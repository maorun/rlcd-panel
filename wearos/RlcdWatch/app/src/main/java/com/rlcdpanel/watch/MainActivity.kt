package com.rlcdpanel.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import com.rlcdpanel.watch.complication.ClimateComplicationService
import com.rlcdpanel.watch.console.BleConsoleClient
import com.rlcdpanel.watch.protocol.ChimeCommand
import com.rlcdpanel.watch.protocol.AlertTransitions
import com.rlcdpanel.watch.protocol.ConfigCache
import com.rlcdpanel.watch.protocol.PanelClock
import com.rlcdpanel.watch.protocol.ParseException
import com.rlcdpanel.watch.protocol.StatusReply
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.EventsReply
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import com.rlcdpanel.watch.tile.ClimateTileService
import com.rlcdpanel.watch.ventilation.OutdoorClimate
import com.rlcdpanel.watch.ventilation.OutdoorSource
import com.rlcdpanel.watch.ventilation.OutdoorWeatherService
import com.rlcdpanel.watch.ventilation.VentilationAction
import com.rlcdpanel.watch.ventilation.VentilationGuide
import kotlinx.coroutines.Dispatchers
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

    private var configStatus by mutableStateOf<String?>(null)
    private var isReadingConfig by mutableStateOf(false)
    private lateinit var consoleClient: BleConsoleClient
    private var showSensorHealth by mutableStateOf(false)
    private var healthState by mutableStateOf(SensorHealthState())
    private var pendingHealthRead = false
    private var clockState by mutableStateOf(ClockReadState())
    private var pendingClockRead = false
    private var showEvents by mutableStateOf(false)
    private var eventState by mutableStateOf(EventLogState())
    private var pendingEventsRead = false

    private var scanPermissionDenied = false
    private var notificationPermissionAsked = false
    private lateinit var prefs: SharedPreferences
    private lateinit var telemetryCache: TelemetryCache

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
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

    private val consolePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val health = pendingHealthRead
        val events = pendingEventsRead
        val clock = pendingClockRead
        pendingHealthRead = false
        pendingEventsRead = false
        pendingClockRead = false
        if (grants.isNotEmpty() && grants.values.all { it }) {
            if (clock) {
                startClockRead()
            } else if (events) {
                startEventsRead()
            } else if (health) {
                healthState = healthState.copy(isReading = true, message = null)
                startHealthRead()
            } else {
                startConfigRead()
            }
        } else if (clock) {
            clockState = ClockReadState(clockMessage = "Bluetooth-Berechtigung fehlt")
        } else if (events) {
            eventState = EventLogState(message = "Bluetooth-Berechtigung fehlt")
        } else if (health) {
            healthState = healthState.copy(isReading = false, message = "Bluetooth-Berechtigung fehlt")
        } else {
            isReadingConfig = false
            configStatus = "Bluetooth-Berechtigung fehlt"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consoleClient = BleConsoleClient(this)
        prefs = getSharedPreferences("rlcd_ventilation_prefs", Context.MODE_PRIVATE)
        panelConfig = PanelConfig.load(this)
        telemetryCache = TelemetryCache(this)
        focusedRoomIndex = telemetryCache.focusedRoom()
        val cachedOutdoor = telemetryCache.readOutdoor()
        cachedOutdoor.slotIndex?.let {
            outdoorSource = OutdoorSource.SensorSlot(tileIndex = it, slotName = panelConfig.tileName(it))
        }
        createNotificationChannel()
        restoreRoomStates()

        // Periodic background fetch of Open-Meteo weather
        lifecycleScope.launch {
            while (true) {
                fetchWeatherIfNeeded()
                delay(15 * 60 * 1000L) // 15 minutes
            }
        }

        setContent {
            val snapshot by BleScanService.snapshot.collectAsState()
            val isScanning by BleScanService.isScanning.collectAsState()
            val statusMessage by BleScanService.statusMessage.collectAsState()

            LaunchedEffect(Unit) {
                while (true) {
                    nowMillis = System.currentTimeMillis()
                    delay(1000L)
                }
            }

            // Determine effective outdoor climate
            val effectiveOutdoor: OutdoorClimate? = when (val src = outdoorSource) {
                is OutdoorSource.OpenMeteo -> openMeteoClimate
                is OutdoorSource.SensorSlot -> {
                    val tile = snapshot?.frame?.tiles?.getOrNull(src.tileIndex)
                    if (tile?.isValid == true) {
                        OutdoorClimate(
                            tempC = tile.temperatureC!!,
                            rhPct = tile.humidityPct!!,
                            sourceName = src.displayName,
                            timestampMillis = snapshot?.receivedAtMillis ?: nowMillis
                        )
                    } else null
                }
            }

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
            val advice = if (!isSelfComparison && roomTile?.isValid == true && effectiveOutdoor != null) {
                VentilationGuide.analysiereLueftung(
                    tempInnen = roomTile.temperatureC!!,
                    rFInnen = roomTile.humidityPct!!,
                    tempAussen = effectiveOutdoor.tempC,
                    rFAussen = effectiveOutdoor.rhPct,
                    istGeradeAmLueften = isVentilating,
                    lueftungsDauerMinuten = durationMinutes
                )
            } else {
                null
            }

            // Check for recommendation transitions and trigger vibrations/notifications
            LaunchedEffect(focusedRoomIndex, advice?.aktion) {
                val aktion = advice?.aktion
                val lastAktion = lastObservedAktionen[focusedRoomIndex]

                if (aktion != null) {
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
                                        title = advice.titel,
                                        message = advice.nachricht,
                                        notificationId = 1000 + focusedRoomIndex
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                    lastObservedAktionen[focusedRoomIndex] = aktion
                }
            }

            if (showSensorHealth && showEvents) {
                BackHandler { closeEvents() }
                EventLogScreen(state = eventState, onRead = { readEvents() })
                return@setContent
            }

            if (showSensorHealth) {
                BackHandler { showSensorHealth = false }
                SensorHealthScreen(
                    state = healthState,
                    onRead = { readSensorHealth() },
                    onSetMute = { setMute(it) },
                    onOpenEvents = { showEvents = true },
                    clockState = clockState,
                    onReadClock = { readClock() }
                )
                return@setContent
            }

            WatchDashboardScreen(
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
                configStatus = configStatus,
                isReadingConfig = isReadingConfig,
                onReadConfig = { readPanelConfig() },
                onOpenSensorHealth = { showSensorHealth = true },
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
                    telemetryCache.saveFocusedRoom(index)
                    ClimateTileService.requestUpdate(this@MainActivity)
                },
                onToggleVentilation = {
                    toggleVentilationForFocusedRoom()
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!scanPermissionDenied && !BleScanService.isScanning.value) {
            ensurePermissionAndStart()
        }
        fetchWeatherIfNeeded()
    }

    override fun onStop() {
        consoleClient.cancel()
        isReadingConfig = false
        // An open permission dialog keeps pendingHealthRead, so its result still starts the tapped STATUS read.
        healthState = healthState.copy(isReading = false, isMuting = false)
        eventState = EventLogState()
        pendingEventsRead = false
        // An open permission dialog keeps pendingClockRead, so its result still starts the tapped TIME read.
        clockState = ClockReadState()
        // Background scanning continues in BleScanService.
        super.onStop()
    }

    private fun fetchWeatherIfNeeded() {
        if (outdoorSource is OutdoorSource.OpenMeteo) {
            val src = outdoorSource as OutdoorSource.OpenMeteo
            lifecycleScope.launch {
                val weather = OutdoorWeatherService.fetchCurrent(
                    latitude = src.latitude,
                    longitude = src.longitude,
                    locationName = src.displayName
                )
                if (weather != null) {
                    openMeteoClimate = weather
                    telemetryCache.saveOpenMeteoReading(weather.tempC, weather.rhPct, weather.timestampMillis)
                    ClimateTileService.requestUpdate(this@MainActivity)
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
            if (isVent && startMs > 0L) {
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
        when (nextSource) {
            is OutdoorSource.SensorSlot -> telemetryCache.saveOutdoorSlot(nextSource.tileIndex)
            is OutdoorSource.OpenMeteo -> telemetryCache.saveOutdoorOpenMeteo()
        }
        ClimateTileService.requestUpdate(this)
        fetchWeatherIfNeeded()
    }

    /** Asks for BLUETOOTH_CONNECT and BLUETOOTH_SCAN (SDK 31 and later), then sends `CONFIG` once. */
    private fun readPanelConfig() {
        if (isReadingConfig || healthState.isReading || pendingClockRead || consoleClient.isRunning) return
        isReadingConfig = true
        configStatus = null
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startConfigRead()
    }

    /** Asks for the console permissions, then sends `TIME` once. Only a tap in the sensor health screen calls it. The watch never sends `TIME SET`. */
    private fun readClock() {
        if (clockState.isReadingClock || pendingClockRead || pendingHealthRead || pendingEventsRead || healthState.isReading || healthState.isMuting || isReadingConfig || consoleClient.isRunning) return
        clockState = ClockReadState(isReadingClock = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingClockRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startClockRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startClockRead() {
        if (consoleClient.isRunning) {
            clockState = ClockReadState(clockMessage = "Panel-Verbindung ist belegt")
            return
        }
        clockState = ClockReadState(isReadingClock = true)
        consoleClient.send("TIME") { reply ->
            clockState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val r = PanelClock.parseTimeReply(reply.lines)
                    ClockReadState(
                        clockReply = r,
                        clockOffsetSeconds = r.epochMillis?.let { PanelClock.offsetSeconds(it, System.currentTimeMillis()) }
                    )
                } catch (e: ParseException) {
                    ClockReadState(clockMessage = "Antwort nicht lesbar")
                }
                else -> ClockReadState(clockMessage = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closeEvents() {
        consoleClient.cancel()
        pendingEventsRead = false
        eventState = EventLogState()
        showEvents = false
    }

    /** Asks for the console permissions, then sends `EVENTS` once. Only a tap in the event log screen calls it. */
    private fun readEvents() {
        if (eventState.isReading || pendingEventsRead || pendingHealthRead || pendingClockRead || healthState.isReading || isReadingConfig || consoleClient.isRunning) return
        eventState = EventLogState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingEventsRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startEventsRead()
    }

    /** A failed CRC-32 or a timeout gives one error text. The command is not repeated. */
    private fun startEventsRead() {
        if (consoleClient.isRunning) {
            eventState = EventLogState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send("EVENTS") { reply ->
            eventState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val log = EventsReply.parse(reply.lines)
                    EventLogState(rows = log.rows, dropped = log.dropped, loaded = true)
                } catch (e: ParseException) {
                    EventLogState(message = "Antwort nicht lesbar")
                }
                else -> EventLogState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Asks for the console permissions, then sends `STATUS` once. Only a tap in the sensor health screen calls it. */
    private fun readSensorHealth() {
        if (healthState.isReading || pendingHealthRead || pendingClockRead || isReadingConfig || consoleClient.isRunning) return
        healthState = healthState.copy(isReading = true, message = null)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingHealthRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startHealthRead()
    }

    /** A good reply replaces the rows; a bad CRC-32, a timeout or an `ERR` reply keeps the last good reply. */
    private fun startHealthRead() {
        if (consoleClient.isRunning) {
            healthState = healthState.copy(isReading = false, message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send("STATUS") { reply ->
            healthState = try {
                SensorHealthState(status = StatusReply.parseReply(reply))
            } catch (e: Exception) {
                val reason = if (reply is ConsoleReply.Frame) "Antwort nicht lesbar" else BleConsoleClient.errorText(reply)
                healthState.copy(isReading = false, message = reason)
            }
        }
    }

    /**
     * Sends `MUTE <0|1>`, then `STATUS`. The toggle follows the `STATUS` reply only; a failed command
     * keeps the old state and is not retried.
     */
    private fun setMute(on: Boolean) {
        if (healthState.isReading || healthState.isMuting || pendingClockRead || clockState.isReadingClock || isReadingConfig || consoleClient.isRunning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        ) {
            healthState = healthState.copy(message = "Bluetooth-Berechtigung fehlt")
            return
        }
        healthState = healthState.copy(isMuting = true, message = null)
        consoleClient.send(ChimeCommand.mute(on)) { reply ->
            val failure = ChimeCommand.failureText(reply) { SensorHealthText.muteFallback(it) }
            if (failure != null) {
                healthState = healthState.copy(isMuting = false, message = failure)
            } else {
                consoleClient.send(ChimeCommand.STATUS) { status ->
                    healthState = try {
                        SensorHealthState(status = StatusReply.parseReply(status))
                    } catch (e: Exception) {
                        val reason = if (status is ConsoleReply.Frame) "Antwort nicht lesbar" else SensorHealthText.muteFallback(status)
                        healthState.copy(isMuting = false, message = "Mute gesendet, Stand unbestätigt. $reason")
                    }
                }
            }
        }
    }

    /** A good reply replaces the cache; a bad CRC-32, a timeout or an `ERR` reply keeps the old one. */
    private fun startConfigRead() {
        isReadingConfig = true
        consoleClient.send("CONFIG") { reply ->
            // The write and the reload touch the disk, so they run off the main thread.
            lifecycleScope.launch {
                val (info, loaded) = withContext(Dispatchers.IO) {
                    val stored = ConfigCache.store(filesDir, reply, System.currentTimeMillis())
                    stored to stored?.let { PanelConfig.load(this@MainActivity) }
                }
                isReadingConfig = false
                if (info != null && loaded != null) {
                    panelConfig = loaded
                    ClimateTileService.requestUpdate(this@MainActivity)
                    ClimateComplicationService.requestUpdate(this@MainActivity)
                    configStatus = "Config gelesen" +
                        if (info.rejectedLines.isEmpty()) "" else ", ${info.rejectedLines.size} Zeilen abgelehnt"
                } else {
                    val reason = when {
                        reply !is ConsoleReply.Frame -> BleConsoleClient.errorText(reply)
                        PanelConfig.parseConfigReply(reply.lines).source != "SD" -> "Panel ohne SD-Config"
                        else -> "Cache nicht beschreibbar"
                    }
                    configStatus = "$reason. Alter Stand bleibt."
                }
            }
        }
    }

    private fun ensurePermissionAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
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
                "Lüftungsempfehlungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Lüftungsempfehlungen und Alarme"
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun postVentilationNotification(title: String, message: String, notificationId: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }

    companion object {
        const val CHANNEL_ID = "rlcd_ventilation_channel"
    }
}
