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
import com.rlcdpanel.watch.protocol.AlertMask
import com.rlcdpanel.watch.protocol.AlertTransitions
import com.rlcdpanel.watch.protocol.AlertVibration
import com.rlcdpanel.watch.protocol.SignalMeter
import com.rlcdpanel.watch.protocol.ConfigCache
import com.rlcdpanel.watch.protocol.PanelClock
import com.rlcdpanel.watch.protocol.VersionReply
import com.rlcdpanel.watch.protocol.ParseException
import com.rlcdpanel.watch.protocol.StatusReply
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.EventsReply
import com.rlcdpanel.watch.protocol.ConfigChangedReply
import com.rlcdpanel.watch.protocol.ConfigReset
import com.rlcdpanel.watch.protocol.RejectsReply
import com.rlcdpanel.watch.protocol.SelfTestReply
import com.rlcdpanel.watch.protocol.SpreadApply
import com.rlcdpanel.watch.protocol.SpreadCommand
import com.rlcdpanel.watch.protocol.SpreadReply
import com.rlcdpanel.watch.protocol.TodayReply
import com.rlcdpanel.watch.protocol.DaysReply
import com.rlcdpanel.watch.protocol.MoldSummary
import com.rlcdpanel.watch.protocol.HistoryReply
import com.rlcdpanel.watch.protocol.SaltCommand
import com.rlcdpanel.watch.protocol.SaltReply
import com.rlcdpanel.watch.protocol.SaltText
import com.rlcdpanel.watch.protocol.WeekReply
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import com.rlcdpanel.watch.protocol.WallText
import com.rlcdpanel.watch.protocol.WallWatch
import com.rlcdpanel.watch.protocol.DriftWatch
import com.rlcdpanel.watch.protocol.ReceptionWatch
import com.rlcdpanel.watch.protocol.WindowWatch
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
    private val wallWatch = WallWatch()
    private val windowWatch = WindowWatch()
    private val receptionWatch = ReceptionWatch()
    private val driftWatch = DriftWatch()

    private var configStatus by mutableStateOf<String?>(null)
    private var isReadingConfig by mutableStateOf(false)
    private lateinit var consoleClient: BleConsoleClient
    private var showSensorHealth by mutableStateOf(false)
    private var healthState by mutableStateOf(SensorHealthState())
    private var pendingHealthRead = false
    private var clockState by mutableStateOf(ClockReadState())
    private var pendingClockRead = false
    private var versionState by mutableStateOf(VersionReadState())
    private var pendingVersionRead = false
    private var chimeState by mutableStateOf(ChimeTestState())
    private var showEvents by mutableStateOf(false)
    private var eventState by mutableStateOf(EventLogState())
    private var pendingEventsRead = false
    private var pendingEventsAll = false
    private var showSignal by mutableStateOf(false)
    private var signalState by mutableStateOf(SignalMeterState())
    /** Elapsed time does not jump with a clock change, so the 5 minute limit holds. */
    private val signalMeter = SignalMeter { android.os.SystemClock.elapsedRealtime() }
    private var signalScanner: SignalMeterScanner? = null
    private var showWeek by mutableStateOf(false)
    private var weekSensor by mutableIntStateOf(0)
    private var weekState by mutableStateOf(WeekState())
    private var pendingWeekRead = false
    private var showHistory by mutableStateOf(false)
    private var historyState by mutableStateOf(HistoryState())
    private var pendingHistoryRead = false
    /** Room of the open history screen; set by the tap on the chip, so a read and its title use the same room. */
    private var historyRoom by mutableIntStateOf(0)
    private var showSalt by mutableStateOf(false)
    private var saltState by mutableStateOf(SaltState())
    private var pendingSaltRead = false
    /** Room of the open salt screen; set by the tap on the chip, so a read and its title use the same room. */
    private var saltRoom by mutableIntStateOf(0)
    private var showRejects by mutableStateOf(false)
    private var rejectsState by mutableStateOf(RejectsState())
    private var pendingRejectsRead = false
    private var showConfigChanged by mutableStateOf(false)
    private var configChangedState by mutableStateOf(ConfigChangedState())
    private var pendingConfigChangedRead = false
    private var showSelfTest by mutableStateOf(false)
    private var selfTestState by mutableStateOf(SelfTestUiState())
    private var pendingSelfTestRead = false
    private var showSpread by mutableStateOf(false)
    private var spreadState by mutableStateOf(SpreadState())
    private var pendingSpreadRead = false
    private var showToday by mutableStateOf(false)
    private var todayState by mutableStateOf(TodayState())
    private var pendingTodayRead = false
    private var showMold by mutableStateOf(false)
    private var moldState by mutableStateOf(MoldSummaryState())
    private var pendingMoldRead = false

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
        val week = pendingWeekRead
        val salt = pendingSaltRead
        val rejects = pendingRejectsRead
        val configChanged = pendingConfigChangedRead
        val selfTest = pendingSelfTestRead
        val spread = pendingSpreadRead
        val today = pendingTodayRead
        val mold = pendingMoldRead
        val history = pendingHistoryRead
        val clock = pendingClockRead
        val version = pendingVersionRead
        pendingHealthRead = false
        pendingEventsRead = false
        pendingWeekRead = false
        pendingSaltRead = false
        pendingRejectsRead = false
        pendingConfigChangedRead = false
        pendingSelfTestRead = false
        pendingSpreadRead = false
        pendingTodayRead = false
        pendingMoldRead = false
        pendingHistoryRead = false
        pendingClockRead = false
        pendingVersionRead = false
        if (grants.isNotEmpty() && grants.values.all { it }) {
            if (version) {
                startVersionRead()
            } else if (clock) {
                startClockRead()
            } else if (history) {
                startHistoryRead()
            } else if (week) {
                startWeekRead()
            } else if (salt) {
                startSaltRead()
            } else if (rejects) {
                startRejectsRead()
            } else if (configChanged) {
                startConfigChangedRead()
            } else if (selfTest) {
                startSelfTest()
            } else if (spread) {
                startSpreadRead()
            } else if (today) {
                startTodayRead()
            } else if (mold) {
                startMoldRead()
            } else if (events) {
                startEventsRead(pendingEventsAll)
            } else if (health) {
                healthState = healthState.copy(isReading = true, message = null)
                startHealthRead()
            } else {
                startConfigRead()
            }
        } else if (version) {
            versionState = VersionReadState(message = "Bluetooth-Berechtigung fehlt")
        } else if (clock) {
            clockState = ClockReadState(clockMessage = "Bluetooth-Berechtigung fehlt")
        } else if (history) {
            historyState = HistoryState(message = "Bluetooth-Berechtigung fehlt")
        } else if (week) {
            weekState = WeekState(message = "Bluetooth-Berechtigung fehlt")
        } else if (salt) {
            saltState = SaltState(message = "Bluetooth-Berechtigung fehlt")
        } else if (rejects) {
            rejectsState = RejectsState(message = "Bluetooth-Berechtigung fehlt")
        } else if (configChanged) {
            configChangedState = ConfigChangedState(message = "Bluetooth-Berechtigung fehlt")
        } else if (selfTest) {
            selfTestState = SelfTestUiState(message = "Bluetooth-Berechtigung fehlt")
        } else if (spread) {
            spreadState = SpreadState(message = "Bluetooth-Berechtigung fehlt")
        } else if (today) {
            todayState = TodayState(message = "Bluetooth-Berechtigung fehlt")
        } else if (mold) {
            moldState = MoldSummaryState(message = "Bluetooth-Berechtigung fehlt")
        } else if (events) {
            eventState = eventState.copy(isReading = false, message = "Bluetooth-Berechtigung fehlt")
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

            // Feed the wall humidity watch on each new frame
            LaunchedEffect(snapshot) {
                val snap = snapshot ?: return@LaunchedEffect
                checkWallNotifications(snap.frame)
            }

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
                                    // The ventilation advice uses the pattern of the VENTILATE alert bit.
                                    vibrateAlert(AlertMask.VENTILATE)
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

            if (showHistory) {
                BackHandler { closeHistory() }
                HistoryScreen(
                    state = historyState,
                    roomName = panelConfig.tileName(historyRoom),
                    sensor = historyRoom,
                    onRead = { readHistory() }
                )
                return@setContent
            }

            if (showSensorHealth && showSignal) {
                BackHandler { closeSignal() }
                SignalMeterScreen(
                    state = signalState,
                    onTick = { tickSignal() },
                    onRestart = { startSignal() }
                )
                return@setContent
            }

            if (showSensorHealth && showWeek) {
                BackHandler { closeWeek() }
                WeekScreen(
                    state = weekState,
                    roomName = panelConfig.tileName(weekSensor),
                    sensor = weekSensor,
                    onRead = { readWeek() },
                    onNextSensor = { nextWeekSensor() }
                )
                return@setContent
            }

            if (showSensorHealth && showSalt) {
                BackHandler { closeSalt() }
                SaltScreen(
                    state = saltState,
                    roomName = panelConfig.tileName(saltRoom),
                    onRead = { readSalt() }
                )
                return@setContent
            }

            if (showSensorHealth && showSpread) {
                BackHandler { closeSpread() }
                SpreadScreen(
state = spreadState,
onRead = { readSpread() },
onAsk = { spreadState = spreadState.copy(confirming = it) },
onCancel = { spreadState = spreadState.copy(confirming = null) },
onApply = { applySpread(it) }
)
                return@setContent
            }

            if (showSensorHealth && showToday) {
                BackHandler { closeToday() }
                TodayScreen(state = todayState, onRead = { readToday() })
                return@setContent
            }

            if (showSensorHealth && showMold) {
                BackHandler { closeMold() }
                MoldSummaryScreen(state = moldState, onRead = { readMold() })
                return@setContent
            }

            if (showSensorHealth && showSelfTest) {
                BackHandler { closeSelfTest() }
                SelfTestScreen(
                    state = selfTestState,
                    onAskStart = { selfTestState = SelfTestUiState(confirming = true) },
                    onConfirm = { readSelfTest() },
                    onCancel = { selfTestState = SelfTestUiState() }
                )
                return@setContent
            }

            if (showSensorHealth && showConfigChanged) {
                BackHandler { closeConfigChanged() }
                ConfigChangedScreen(
                    state = configChangedState,
                    onRead = { readConfigChanged() },
                    onAsk = { configChangedState = configChangedState.copy(confirming = it) },
                    onCancel = { configChangedState = configChangedState.copy(confirming = null) },
                    onReset = { resetConfigKey(it) }
                )
                return@setContent
            }

            if (showSensorHealth && showRejects) {
                BackHandler { closeRejects() }
                RejectsScreen(state = rejectsState, onRead = { readRejects() })
                return@setContent
            }

            if (showSensorHealth && showEvents) {
                BackHandler { closeEvents() }
                EventLogScreen(state = eventState, onRead = { readEvents(all = false) }, onReadAll = { readEvents(all = true) })
                return@setContent
            }

            if (showSensorHealth) {
                BackHandler { showSensorHealth = false }
                SensorHealthScreen(
                    state = healthState,
                    onRead = { readSensorHealth() },
                    onSetMute = { setMute(it) },
                    onOpenEvents = { showEvents = true },
                    onOpenWeek = {
                        weekSensor = focusedRoomIndex.coerceIn(0, WeekReply.MAX_SENSOR)
                        showWeek = true
                    },
                    onOpenSignal = { sensor ->
                        val mac = sensor.mac ?: return@SensorHealthScreen
                        signalState = SignalMeterState(name = sensor.name, mac = mac)
                        showSignal = true
                        startSignal()
                    },
                    onOpenRejects = { showRejects = true },
                    onOpenConfigChanged = { showConfigChanged = true },
                    onOpenSelfTest = { showSelfTest = true },
                    onOpenSpread = { showSpread = true },
                    onOpenToday = { showToday = true },
                    onOpenMold = { showMold = true },
                    onOpenSalt = {
                        saltRoom = focusedRoomIndex
                        showSalt = true
                    },
                    clockState = clockState,
                    onReadClock = { readClock() },
                    chimeState = chimeState,
                    onSelectChime = { if (!chimeState.isTesting) chimeState = chimeState.copy(bit = it, message = null) },
                    onTestChime = { testChime() },
                    onIdentify = { identifyPanel() },
                    versionState = versionState,
                    onReadVersion = { readVersion() }
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
                onOpenHistory = {
                    historyRoom = focusedRoomIndex
                    showHistory = true
                },
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
                    ClimateComplicationService.requestUpdate(this@MainActivity)
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
        closeSignal()
        consoleClient.cancel()
        isReadingConfig = false
        // An open permission dialog keeps pendingHealthRead, so its result still starts the tapped STATUS read.
        healthState = healthState.copy(isReading = false, isMuting = false)
        eventState = EventLogState()
        pendingEventsRead = false
        pendingEventsAll = false
        weekState = WeekState()
        pendingWeekRead = false
        saltState = SaltState()
        pendingSaltRead = false
        // An open permission dialog keeps pendingHistoryRead, so its result still starts the tapped HISTORY read.
        historyState = HistoryState()
        rejectsState = RejectsState()
        pendingRejectsRead = false
        configChangedState = ConfigChangedState()
        pendingConfigChangedRead = false
        selfTestState = SelfTestUiState()
        pendingSelfTestRead = false
        spreadState = SpreadState()
        pendingSpreadRead = false
        todayState = TodayState()
        pendingTodayRead = false
        moldState = MoldSummaryState()
        pendingMoldRead = false
        // An open permission dialog keeps pendingClockRead, so its result still starts the tapped TIME read.
        clockState = ClockReadState()
        versionState = VersionReadState()
        chimeState = chimeState.copy(isTesting = false)
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
                    ClimateComplicationService.requestUpdate(this@MainActivity)
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
        ClimateComplicationService.requestUpdate(this)
        fetchWeatherIfNeeded()
    }

    /** Asks for BLUETOOTH_CONNECT and BLUETOOTH_SCAN (SDK 31 and later), then sends `CONFIG` once. */
    private fun readPanelConfig() {
        if (isReadingConfig || healthState.isReading || pendingClockRead || pendingVersionRead || versionState.isReading || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingSaltRead || pendingMoldRead || moldState.isReading || saltState.isReading || consoleClient.isRunning) return
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
        if (clockState.isReadingClock || pendingClockRead || pendingVersionRead || versionState.isReading || pendingHealthRead || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || healthState.isReading || healthState.isMuting || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
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

    /** Asks for the console permissions, then sends `VERSION` once. Only a tap in the sensor health screen calls it. */
    private fun readVersion() {
        if (versionState.isReading || pendingVersionRead || pendingClockRead || pendingHealthRead || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || healthState.isReading || healthState.isMuting || clockState.isReadingClock || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
        versionState = VersionReadState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingVersionRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startVersionRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startVersionRead() {
        if (consoleClient.isRunning) {
            versionState = VersionReadState(message = "Panel-Verbindung ist belegt")
            return
        }
        versionState = VersionReadState(isReading = true)
        consoleClient.send("VERSION") { reply ->
            versionState = when (reply) {
                is ConsoleReply.Frame -> try {
                    VersionReadState(reply = VersionReply.parse(reply.lines))
                } catch (e: ParseException) {
                    VersionReadState(message = "Antwort nicht lesbar")
                }
                else -> VersionReadState(message = SensorHealthText.versionFailure(reply))
            }
        }
    }

    private fun closeHistory() {
        consoleClient.cancel()
        pendingHistoryRead = false
        historyState = HistoryState()
        showHistory = false
    }

    /** Asks for the console permissions, then sends `HISTORY <N>` once. Only a tap in the history screen calls it. */
    private fun readHistory() {
        if (historyState.isReading || healthState.isMuting || clockState.isReadingClock || pendingHistoryRead || pendingWeekRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
        historyState = HistoryState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingHistoryRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startHistoryRead()
    }

    /** Sends `HISTORY <N>`. An older firmware answers with `ERR`; then one `HISTORY` follows and the rows of the room stay. */
    private fun startHistoryRead() {
        if (consoleClient.isRunning) {
            historyState = HistoryState(message = "Panel-Verbindung ist belegt")
            return
        }
        historyState = HistoryState(isReading = true)
        val sensor = historyRoom
        consoleClient.send("HISTORY $sensor") { first ->
            if (HistoryText.needsFallback(first)) {
                consoleClient.send("HISTORY") { full -> historyState = historyResult(full) }
            } else {
                historyState = historyResult(first)
            }
        }
    }

    private fun historyResult(reply: ConsoleReply): HistoryState = when (reply) {
        is ConsoleReply.Frame -> try {
            HistoryState(rows = HistoryReply.parse(reply.lines), loaded = true)
        } catch (e: ParseException) {
            HistoryState(message = "Antwort nicht lesbar")
        }
        else -> HistoryState(message = HistoryText.failure(reply))
    }

    /** Starts the filtered scan of the open signal meter. A missing permission or a failed start shows a text. */
    private fun startSignal() {
        val mac = signalState.mac
        stopSignalScan()
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else null
        if (needed != null && ContextCompat.checkSelfPermission(this, needed) != PackageManager.PERMISSION_GRANTED) {
            signalState = signalState.copy(running = false, message = "Bluetooth-Berechtigung fehlt")
            return
        }
        signalMeter.start()
        // A queued callback of an old scanner must not act on a new run.
        val scannerRef = arrayOfNulls<SignalMeterScanner>(1)
        val scanner = SignalMeterScanner(
            this,
            onPacket = { rssi -> runOnUiThread { if (signalScanner === scannerRef[0]) onSignalPacket(rssi) } },
            onError = { text ->
                runOnUiThread {
                    if (showSignal && signalScanner === scannerRef[0]) {
                        stopSignalScan()
                        signalMeter.stop()
                        signalState = signalState.copy(running = false, message = text)
                    }
                }
            }
        )
        scannerRef[0] = scanner
        signalScanner = scanner
        if (scanner.start(mac)) {
            signalState = signalState.copy(reading = signalMeter.reading(), running = true, message = null)
        } else {
            signalMeter.stop()
        }
    }

    private fun onSignalPacket(rssi: Int) {
        if (!showSignal) return
        if (signalMeter.onPacket(rssi)) vibrateOnce()
        signalState = signalState.copy(reading = signalMeter.reading())
    }

    /** One tick each second: moves the age and stops the scan after 5 minutes. */
    private fun tickSignal() {
        if (!showSignal) return
        if (signalMeter.check()) {
            stopSignalScan()
            signalState = signalState.copy(reading = signalMeter.reading(), running = false, message = SignalMeterText.STOPPED_AFTER_LIMIT)
        } else {
            signalState = signalState.copy(reading = signalMeter.reading())
        }
    }

    private fun stopSignalScan() {
        signalScanner?.stop()
        signalScanner = null
    }

    private fun closeSignal() {
        stopSignalScan()
        signalMeter.stop()
        signalState = SignalMeterState()
        showSignal = false
    }

    private fun closeWeek() {
        consoleClient.cancel()
        pendingWeekRead = false
        weekState = WeekState()
        showWeek = false
    }

    /** Selects the next sensor (wrap 5 to 0) and reads its 7 rows. */
    private fun nextWeekSensor() {
        if (weekState.isReading || pendingWeekRead || consoleClient.isRunning) return
        val previousSensor = weekSensor
        val previousState = weekState
        weekSensor = (weekSensor + 1) % (WeekReply.MAX_SENSOR + 1)
        weekState = WeekState()
        readWeek()
        // readWeek returns early while another console read runs. Then keep the old sensor and rows.
        if (!weekState.isReading && !pendingWeekRead) {
            weekSensor = previousSensor
            weekState = previousState
        }
    }

    /** Asks for the console permissions, then sends `WEEK <N>` for the selected sensor once. Only a tap in the week screen calls it. */
    private fun readWeek() {
        if (weekState.isReading || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
        weekState = WeekState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingWeekRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startWeekRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startWeekRead() {
        if (consoleClient.isRunning) {
            weekState = WeekState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send(WeekReply.sensorCommand(weekSensor)) { reply ->
            weekState = when (reply) {
                is ConsoleReply.Frame -> try {
                    WeekState(rows = WeekReply.parse(reply.lines), loaded = true)
                } catch (e: ParseException) {
                    WeekState(message = "Antwort nicht lesbar")
                }
                else -> WeekState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closeSalt() {
        consoleClient.cancel()
        pendingSaltRead = false
        saltState = SaltState()
        showSalt = false
    }

    /** Asks for the console permissions, then sends `SALT <N>` once. Only a tap in the salt screen calls it. It sends no `SET`. */
    private fun readSalt() {
        if (saltState.isReading || pendingSaltRead || healthState.isMuting || clockState.isReadingClock || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || consoleClient.isRunning) return
        saltState = SaltState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingSaltRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startSaltRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one text. The command is not repeated. */
    private fun startSaltRead() {
        if (consoleClient.isRunning) {
            saltState = SaltState(message = "Panel-Verbindung ist belegt")
            return
        }
        if (saltRoom !in 0..SaltCommand.MAX_SENSOR) {
            saltState = SaltState(message = "Raum ohne Sensor")
            return
        }
        consoleClient.send(SaltCommand.line(saltRoom, false)) { reply ->
            saltState = when (reply) {
                is ConsoleReply.Frame -> try {
                    SaltState(reply = SaltReply.parse(reply.lines))
                } catch (e: ParseException) {
                    SaltState(message = "Antwort nicht lesbar")
                }
                is ConsoleReply.Status -> SaltState(message = SaltText.status(reply.line))
                else -> SaltState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closeRejects() {
        consoleClient.cancel()
        pendingRejectsRead = false
        rejectsState = RejectsState()
        showRejects = false
    }

    /** Asks for the console permissions, then sends `REJECTS` once. Only a tap in the rejects screen calls it. */
    private fun readRejects() {
        if (rejectsState.isReading || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
        rejectsState = RejectsState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingRejectsRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startRejectsRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startRejectsRead() {
        if (consoleClient.isRunning) {
            rejectsState = RejectsState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send("REJECTS") { reply ->
            rejectsState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val log = RejectsReply.parse(reply.lines)
                    RejectsState(rows = log.rows, dropped = log.dropped, loaded = true)
                } catch (e: ParseException) {
                    RejectsState(message = "Antwort nicht lesbar")
                }
                else -> RejectsState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Sends the shared reset `SET` of one key, then `CONFIG CHANGED`. The first `ERR` stops and shows its text. */
    private fun resetConfigKey(key: String) {
        val entry = configChangedState.entries.firstOrNull { it.key == key } ?: return
        val plan = ConfigReset.planFor(entry) as? ConfigReset.RowPlan.Sendable ?: return
        if (configChangedState.isApplying || configChangedState.isReading) return
        if (consoleClient.isRunning) {
            configChangedState = configChangedState.copy(confirming = null, message = "Panel-Verbindung ist belegt")
            return
        }
        val before = configChangedState
        configChangedState = before.copy(confirming = null, isApplying = true, message = null)
        ConfigReset.run(listOf(plan.command), consoleClient::send) { result ->
            if (result.failure == null) {
                configChangedState = ConfigChangedState(isReading = true)
                startConfigChangedRead()
            } else if (result.unknownState) {
                // The SET may have applied, so the old list is stale. Only the text stays.
                configChangedState = ConfigChangedState(message = result.failure)
            } else {
                configChangedState = before.copy(
                    confirming = null,
                    isApplying = false,
                    readOnly = result.readOnly,
                    message = result.failure
                )
            }
        }
    }

    private fun closeConfigChanged() {
        consoleClient.cancel()
        pendingConfigChangedRead = false
        configChangedState = ConfigChangedState()
        showConfigChanged = false
    }

    /** Asks for the console permissions, then sends `CONFIG CHANGED` once. Only a tap in the settings screen calls it. */
    private fun readConfigChanged() {
        if (configChangedState.isReading || pendingConfigChangedRead) return
        if (selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSpreadRead || spreadState.isReading || pendingTodayRead || todayState.isReading || pendingRejectsRead || rejectsState.isReading || pendingSelfTestRead || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) {
            configChangedState = ConfigChangedState(message = "Panel-Verbindung ist belegt")
            return
        }
        configChangedState = ConfigChangedState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingConfigChangedRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startConfigChangedRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. No `SET` is sent. */
    private fun startConfigChangedRead() {
        if (consoleClient.isRunning) {
            configChangedState = ConfigChangedState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send(ConfigChangedText.COMMAND) { reply ->
            configChangedState = when (reply) {
                is ConsoleReply.Frame -> try {
                    ConfigChangedState(entries = ConfigChangedReply.parse(reply.lines), loaded = true)
                } catch (e: ParseException) {
                    ConfigChangedState(message = "Antwort nicht lesbar")
                }
                else -> ConfigChangedState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closeSpread() {
        consoleClient.cancel()
        pendingSpreadRead = false
        spreadState = SpreadState()
        showSpread = false
    }

    /** Asks for the console permissions, then sends `SPREAD` once. Only a tap in the co-location screen calls it. */
    private fun readSpread() {
        if (spreadState.isReading || pendingSpreadRead) return
        if (selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) {
            spreadState = SpreadState(message = "Panel-Verbindung ist belegt")
            return
        }
        spreadState = SpreadState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingSpreadRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startSpreadRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startSpreadRead() {
        if (consoleClient.isRunning) {
            spreadState = SpreadState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send(SpreadCommand.LINE) { reply ->
            spreadState = when (reply) {
                is ConsoleReply.Frame -> try {
                    SpreadState(reply = SpreadReply.parse(reply.lines))
                } catch (e: ParseException) {
                    SpreadState(message = "Antwort nicht lesbar")
                }
                else -> SpreadState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /**
     * Sends the `SET` commands of one proposal after the confirmation, then `SPREAD` again. The first `ERR` stops the
     * batch and shows its text; `ERR READ_ONLY` shows `Nur lesen` and hides the chips.
     */
    private fun applySpread(index: Int) {
        val measured = spreadState.reply?.outcome as? SpreadReply.Outcome.Measured ?: return
        val proposal = SpreadApply.proposalFor(measured, index) ?: return
        if (spreadState.isApplying || spreadState.isReading || consoleClient.isRunning) return
        val before = spreadState
        spreadState = before.copy(confirming = null, isApplying = true, message = null)
        SpreadApply.batch(proposal).run(consoleClient::send) { result ->
            val failure = result.failure
            if (failure == null) {
                spreadState = before.copy(confirming = null, isApplying = false, isReading = true)
                startSpreadRead()
            } else {
                val readOnly = SpreadApply.isReadOnly(result)
                // After an applied key the shown deviations are old, so the chips go until the next read.
                spreadState = before.copy(
                    reply = if (result.applied.isEmpty() && !SpreadApply.isUnknownState(result)) before.reply else null,
                    confirming = null,
                    isApplying = false,
                    readOnly = readOnly,
                    message = if (readOnly) SpreadApply.READ_ONLY_TEXT else result.summary
                )
            }
        }
    }

    private fun closeMold() {
        consoleClient.cancel()
        pendingMoldRead = false
        moldState = MoldSummaryState()
        showMold = false
    }

    /** Asks for the console permissions, then sends `DAYS 31` once. Only a tap in the mold screen calls it. */
    private fun readMold() {
        if (moldState.isReading || pendingMoldRead) return
        if (todayState.isReading || pendingTodayRead || spreadState.isReading || pendingSpreadRead || selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) {
            moldState = MoldSummaryState(message = "Panel-Verbindung ist belegt")
            return
        }
        moldState = MoldSummaryState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingMoldRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startMoldRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startMoldRead() {
        if (consoleClient.isRunning) {
            moldState = MoldSummaryState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send(MoldSummary.COMMAND) { reply ->
            moldState = when (reply) {
                is ConsoleReply.Frame -> try {
                    MoldSummaryState(rooms = MoldSummary.build(DaysReply.parse(reply.lines), panelConfig), hasRead = true)
                } catch (e: ParseException) {
                    MoldSummaryState(message = "Antwort nicht lesbar")
                }
                else -> MoldSummaryState(message = reply.let { r ->
                    (r as? ConsoleReply.Status)?.let { DaysReply.errorText(it.line) } ?: BleConsoleClient.errorText(r)
                })
            }
        }
    }

    private fun closeToday() {
        consoleClient.cancel()
        pendingTodayRead = false
        todayState = TodayState()
        showToday = false
    }

    /** Asks for the console permissions, then sends `TODAY` once. Only a tap in the today screen calls it. */
    private fun readToday() {
        if (todayState.isReading || pendingTodayRead) return
        if (spreadState.isReading || pendingSpreadRead || selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) {
            todayState = TodayState(message = "Panel-Verbindung ist belegt")
            return
        }
        todayState = TodayState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingTodayRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startTodayRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startTodayRead() {
        if (consoleClient.isRunning) {
            todayState = TodayState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send("TODAY") { reply ->
            todayState = when (reply) {
                is ConsoleReply.Frame -> try {
                    TodayState(rows = TodayReply.parse(reply.lines), hasRead = true)
                } catch (e: ParseException) {
                    TodayState(message = "Antwort nicht lesbar")
                }
                else -> TodayState(message = reply.let { r ->
                    (r as? ConsoleReply.Status)?.let { TodayReply.statusText(it.line) } ?: BleConsoleClient.errorText(r)
                })
            }
        }
    }

    private fun closeSelfTest() {
        consoleClient.cancel()
        pendingSelfTestRead = false
        selfTestState = SelfTestUiState()
        showSelfTest = false
    }

    /** Asks for the console permissions, then sends `SELFTEST` once. Only the confirm chip of the self-test screen calls it. */
    private fun readSelfTest() {
        if (selfTestState.isRunning || pendingSelfTestRead) return
        if (clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) {
            selfTestState = SelfTestUiState(confirming = true, message = "Panel-Verbindung ist belegt")
            return
        }
        selfTestState = SelfTestUiState(isRunning = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingSelfTestRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startSelfTest()
    }

    /** A failed CRC-32, a timeout, a missing `END SELFTEST` line or an `ERR` reply gives one text. The command is not repeated. */
    private fun startSelfTest() {
        if (consoleClient.isRunning) {
            selfTestState = SelfTestUiState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send("SELFTEST") { reply ->
            selfTestState = when (reply) {
                is ConsoleReply.Frame -> try {
                    SelfTestUiState(reply = SelfTestReply.parse(reply.lines))
                } catch (e: ParseException) {
                    SelfTestUiState(message = SelfTestText.parseError(reply.lines))
                }
                else -> SelfTestUiState(message = SelfTestText.linkError(reply))
            }
        }
    }

    private fun closeEvents() {
        consoleClient.cancel()
        pendingEventsRead = false
        pendingEventsAll = false
        eventState = EventLogState()
        showEvents = false
    }

    /** Asks for the console permissions, then sends `EVENTS 8` or, for [all], `EVENTS` once. Only a tap in the event log screen calls it. */
    private fun readEvents(all: Boolean) {
        if (eventState.isReading || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
        eventState = eventState.copy(isReading = true, message = null)
        pendingEventsAll = all
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
        startEventsRead(all)
    }

    /** A failed CRC-32 or a timeout gives one error text. The command is not repeated. */
    private fun startEventsRead(all: Boolean) {
        if (consoleClient.isRunning) {
            eventState = eventState.copy(isReading = false, message = "Panel-Verbindung ist belegt")
            return
        }
        val command = if (all) "EVENTS" else EventsReply.newestCommand(EventLogState.SHORT_COUNT)
        consoleClient.send(command) { reply ->
            eventState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val log = EventsReply.parse(reply.lines)
                    EventLogState(rows = log.rows, dropped = log.dropped, loaded = true, all = all)
                } catch (e: ParseException) {
                    eventState.copy(isReading = false, message = "Antwort nicht lesbar")
                }
                else -> eventState.copy(isReading = false, message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Asks for the console permissions, then sends `STATUS` once. Only a tap in the sensor health screen calls it. */
    private fun readSensorHealth() {
        if (healthState.isReading || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
        chimeState = chimeState.copy(message = null)
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
                SensorHealthState(status = parseStatusReply(reply))
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
        if (healthState.isReading || healthState.isMuting || pendingClockRead || pendingVersionRead || versionState.isReading || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || consoleClient.isRunning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        ) {
            healthState = healthState.copy(message = "Bluetooth-Berechtigung fehlt")
            return
        }
        chimeState = chimeState.copy(message = null)
        healthState = healthState.copy(isMuting = true, message = null)
        consoleClient.send(ChimeCommand.mute(on)) { reply ->
            val failure = ChimeCommand.failureText(reply) { SensorHealthText.muteFallback(it) }
            if (failure != null) {
                healthState = healthState.copy(isMuting = false, message = failure)
            } else {
                consoleClient.send(ChimeCommand.STATUS) { status ->
                    healthState = try {
                        SensorHealthState(status = parseStatusReply(status))
                    } catch (e: Exception) {
                        val reason = if (status is ConsoleReply.Frame) "Antwort nicht lesbar" else SensorHealthText.muteFallback(status)
                        healthState.copy(isMuting = false, message = "Mute gesendet, Stand unbestätigt. $reason")
                    }
                }
            }
        }
    }

    /**
     * Sends `BEEP <bit>` once. Only a tap on the `Test chime` chip calls it. A failed command is not retried.
     */
    private fun testChime() {
        if (chimeState.isTesting || healthState.isReading || healthState.isMuting || pendingClockRead || pendingVersionRead || versionState.isReading || pendingHealthRead ||
            pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || moldState.isReading || isReadingConfig || consoleClient.isRunning
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        ) {
            chimeState = chimeState.copy(message = "Bluetooth-Berechtigung fehlt", isError = true)
            return
        }
        chimeState = chimeState.copy(isTesting = true, message = null, isError = false)
        consoleClient.send(ChimeCommand.beep(chimeState.bit)) { reply ->
            val failure = ChimeCommand.failureText(reply) { SensorHealthText.muteFallback(it) }
            chimeState = SensorHealthText.chimeResult(chimeState, reply, failure)
        }
    }

    /** Sends `IDENTIFY` once. Only a tap on the `Panel finden` chip calls it. A failed command is not retried. */
    private fun identifyPanel() {
        if (chimeState.isTesting || healthState.isReading || healthState.isMuting || pendingClockRead || pendingVersionRead || versionState.isReading || pendingHealthRead ||
            pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || moldState.isReading || isReadingConfig || consoleClient.isRunning
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        ) {
            chimeState = chimeState.copy(message = "Bluetooth-Berechtigung fehlt", isError = true)
            return
        }
        chimeState = chimeState.copy(isTesting = true, message = null, isError = false)
        consoleClient.send(ChimeCommand.IDENTIFY) { reply ->
            val failure = ChimeCommand.failureText(reply) { SensorHealthText.muteFallback(it) }
            chimeState = SensorHealthText.identifyResult(chimeState, reply, failure)
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

    private fun vibrateAlert(alerts: AlertMask) {
        val timings = AlertVibration.pattern(alerts)
        if (timings.isEmpty()) return
        getVibrator()?.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private fun vibrateOnce() {
        getVibrator()?.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
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

    private fun checkWallNotifications(frame: TelemetryFrame) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val outdoorTile = WallText.outdoorTile(frame.tiles, panelConfig)
        for (tileIndex in 0 until WallWatch.TILE_COUNT) {
            val tile = frame.tiles.getOrNull(tileIndex)
            val rh = tile?.let { WallText.value(it, tileIndex, outdoorTile, panelConfig) }
            val result = wallWatch.update(tileIndex, rh)
            if (result is WallWatch.Result.Notify) {
                val rounded = Math.round(result.rh).toInt()
                postVentilationNotification(
                    title = "RLCD Alarm",
                    message = "${panelConfig.tileName(tileIndex)}: Wand $rounded %",
                    notificationId = NOTIFICATION_ID_WALL_BASE + tileIndex
                )
                vibrateNotice()
            }
        }
    }

    /**
     * Parses one `STATUS` reply and feeds its window mask to [WindowWatch] its weak signal mask to [ReceptionWatch] and its drift mask to [DriftWatch]. A reply that does not parse throws and
     * does not reach the watch. A failed notification or vibration does not turn a good reply into a read error.
     */
    private fun parseStatusReply(reply: ConsoleReply): StatusReply {
        val status = StatusReply.parseReply(reply)
        runCatching { checkWindowNotifications(status) }
        runCatching { checkWeakSignalNotifications(status) }
        runCatching { checkDriftNotifications(status) }
        return status
    }

    /** Posts one notification with a short vibration for each window bit of `LATCH` that rose since the previous read. */
    private fun checkWindowNotifications(status: StatusReply) {
        val rising = windowWatch.update(status.latch?.window)
        if (rising.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        for (tileIndex in rising) {
            postVentilationNotification(
                title = "RLCD Alarm",
                message = WindowWatch.message(panelConfig.tileName(tileIndex)),
                notificationId = WindowWatch.notificationId(tileIndex)
            )
        }
        vibrateNotice()
    }

    /** Posts one notification with a short vibration for each `rxlow` bit of `LATCH` that rose since the previous read. */
    private fun checkWeakSignalNotifications(status: StatusReply) {
        val rising = receptionWatch.update(status.latch?.receptionLow)
        if (rising.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        for (slot in rising) {
            postVentilationNotification(
                title = "RLCD Alarm",
                message = ReceptionWatch.message(panelConfig.tileName(slot)),
                notificationId = ReceptionWatch.notificationId(slot)
            )
        }
        vibrateNotice()
    }

    /** Posts one notification with a short vibration for each `drift` bit of `LATCH` that rose since the previous read. */
    private fun checkDriftNotifications(status: StatusReply) {
        val rising = driftWatch.update(status.latch?.drift)
        if (rising.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        for (tile in rising) {
            postVentilationNotification(
                title = "RLCD Alarm",
                message = DriftWatch.message(panelConfig.tileName(tile)),
                notificationId = DriftWatch.notificationId(tile)
            )
        }
        vibrateNotice()
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
        const val NOTIFICATION_ID_WALL_BASE = 2000
    }
}
