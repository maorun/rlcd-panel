package com.rlcdpanel.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.lifecycle.lifecycleScope
import com.rlcdpanel.watch.complication.ClimateComplicationService
import com.rlcdpanel.watch.console.BleConsoleClient
import com.rlcdpanel.watch.protocol.ChimeCommand
import com.rlcdpanel.watch.protocol.RtcTrimReply
import com.rlcdpanel.watch.protocol.AlertMask
import com.rlcdpanel.watch.protocol.AlertTransitions
import com.rlcdpanel.watch.protocol.AlertVibration
import com.rlcdpanel.watch.protocol.SignalMeter
import com.rlcdpanel.watch.protocol.AlertLimitStepper
import com.rlcdpanel.watch.protocol.AlertLimits
import com.rlcdpanel.watch.protocol.CalibrationBatch
import com.rlcdpanel.watch.protocol.ConfigCache
import com.rlcdpanel.watch.protocol.CalibrationProposal
import com.rlcdpanel.watch.protocol.PanelClock
import com.rlcdpanel.watch.protocol.VersionReply
import com.rlcdpanel.watch.protocol.DiagnosticsReport
import com.rlcdpanel.watch.protocol.RejectsLog
import com.rlcdpanel.watch.protocol.ParseException
import com.rlcdpanel.watch.protocol.StatusReply
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.EventsReply
import com.rlcdpanel.watch.protocol.JumpAdvice
import com.rlcdpanel.watch.protocol.ConfigChangedReply
import com.rlcdpanel.watch.protocol.ConfigReset
import com.rlcdpanel.watch.protocol.ReceptionReply
import com.rlcdpanel.watch.protocol.RejectsReply
import com.rlcdpanel.watch.protocol.AlertsReply
import com.rlcdpanel.watch.protocol.AlertCauseRows
import com.rlcdpanel.watch.protocol.SelfTestReply
import com.rlcdpanel.watch.protocol.SlotClearCommand
import com.rlcdpanel.watch.protocol.SlotSwapCommand
import com.rlcdpanel.watch.protocol.FrsiApply
import com.rlcdpanel.watch.protocol.FrsiCommand
import com.rlcdpanel.watch.protocol.FrsiReply
import com.rlcdpanel.watch.protocol.FrsiText
import com.rlcdpanel.watch.protocol.CalibrationReply
import com.rlcdpanel.watch.protocol.CalibrationText
import com.rlcdpanel.watch.protocol.SpreadApply
import com.rlcdpanel.watch.protocol.SpreadCommand
import com.rlcdpanel.watch.protocol.SpreadReply
import com.rlcdpanel.watch.protocol.BatteryForecast
import com.rlcdpanel.watch.protocol.TailReply
import com.rlcdpanel.watch.protocol.TodayCommand
import com.rlcdpanel.watch.protocol.TodayReply
import com.rlcdpanel.watch.protocol.DaysReply
import com.rlcdpanel.watch.protocol.MoldSummary
import com.rlcdpanel.watch.protocol.HistoryReply
import com.rlcdpanel.watch.protocol.SaltCommand
import com.rlcdpanel.watch.protocol.SaltPicker
import com.rlcdpanel.watch.protocol.SaltProgress
import com.rlcdpanel.watch.protocol.SaltReply
import com.rlcdpanel.watch.protocol.SaltText
import com.rlcdpanel.watch.protocol.TwoPointCalibration
import com.rlcdpanel.watch.protocol.WeekReply
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import com.rlcdpanel.watch.protocol.WallText
import com.rlcdpanel.watch.protocol.WallWatch
import com.rlcdpanel.watch.protocol.DriftWatch
import com.rlcdpanel.watch.protocol.DryWatch
import com.rlcdpanel.watch.protocol.ReceptionWatch
import com.rlcdpanel.watch.protocol.WindowWatch
import com.rlcdpanel.watch.tile.ClimateTileService
import com.rlcdpanel.watch.ventilation.OutdoorClimate
import com.rlcdpanel.watch.ventilation.OutdoorSource
import com.rlcdpanel.watch.ventilation.OutdoorWeatherService
import com.rlcdpanel.watch.ventilation.VentilationAction
import com.rlcdpanel.watch.ventilation.VentilationGuide
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
    private val wallWatch = WallWatch()
    private val windowWatch = WindowWatch()
    private val receptionWatch = ReceptionWatch()
    private val driftWatch = DriftWatch()
    private val dryWatch = DryWatch()

    private var configStatus by mutableStateOf<String?>(null)
    private var isReadingConfig by mutableStateOf(false)
    private lateinit var consoleClient: BleConsoleClient
    private var showSensorHealth by mutableStateOf(false)
    private var healthState by mutableStateOf(SensorHealthState())
    private var pendingHealthRead = false
    private var clockState by mutableStateOf(ClockReadState())
    private var pendingClockRead = false
    private var versionState by mutableStateOf(VersionReadState())
    private var setupState by mutableStateOf(SetupProposalState())
    private var pendingVersionRead = false
    private var chimeState by mutableStateOf(ChimeTestState())
    private var rtcTrimState by mutableStateOf(RtcTrimState())
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
    private var pendingSetupRead = false
    private var setupGeneration = 0
    private var showHistory by mutableStateOf(false)
    private var historyState by mutableStateOf(HistoryState())
    private var pendingHistoryRead = false
    /** Room of the open history screen; set by the tap on the chip, so a read and its title use the same room. */
    private var historyRoom by mutableIntStateOf(0)
    private var showSalt by mutableStateOf(false)
    private val saltProgress = SaltProgress()
    private var saltWatchJob: Job? = null
    /** Counts each start of the watch; a reply of an older run never counts for a newer one. */
    private var saltWatchRun = 0
    private var saltWatchText by mutableStateOf<String?>(null)
    private var isSaltWatching by mutableStateOf(false)
    private var isSaltWatchReady by mutableStateOf(false)
    private var saltState by mutableStateOf(SaltState())
    private var pendingSaltRead = false
    /** True after a salt apply until a fresh `CONFIG` is cached; the salt screen then evaluates with no config. */
    private var saltConfigStale by mutableStateOf(false)
    /** Salt of the read that waits for the permission dialog: true for `SALT N MGCL2`. */
    private var pendingSaltMagnesium = false
    /** Room of the open salt screen; set by the tap on the chip, so a read and its title use the same room. */
    private var saltRoom by mutableIntStateOf(0)
    private var showRejects by mutableStateOf(false)
    private var rejectsState by mutableStateOf(RejectsState())
    private var pendingRejectsRead = false
    private var showAlertCauses by mutableStateOf(false)
    private var alertCausesState by mutableStateOf(AlertCausesUiState())
    private var pendingAlertCausesRead = false
    private var showReception by mutableStateOf(false)
    private var receptionState by mutableStateOf(ReceptionState())
    private var pendingReceptionRead = false
    private var showConfigChanged by mutableStateOf(false)
    private var configChangedState by mutableStateOf(ConfigChangedState())
    private var pendingConfigChangedRead = false
    private var showSelfTest by mutableStateOf(false)
    private var selfTestState by mutableStateOf(SelfTestUiState())
    private var pendingSelfTestRead = false
    private var showSpread by mutableStateOf(false)
    private var spreadState by mutableStateOf(SpreadState())
    private var spreadHours by mutableStateOf(SpreadCommand.DEFAULT_HOURS)
    private var slotClearState by mutableStateOf(SlotClearState())
    private var slotSwapState by mutableStateOf(SlotSwapState())
    private var pendingSpreadRead = false
    private var showCalib by mutableStateOf(false)
    private var calibState by mutableStateOf(CalibrationState())
    private var pendingCalibRead = false
    private var showAlertLimits by mutableStateOf(false)
    private var alertLimitState by mutableStateOf(AlertLimitState())
    private var showFrsi by mutableStateOf(false)
    private var frsiState by mutableStateOf(FrsiState())
    private var pendingFrsiRead = false
    private var showToday by mutableStateOf(false)
    private var todayState by mutableStateOf(TodayState())
    private var pendingTodayRead = false
    private var showTail by mutableStateOf(false)
    private var tailState by mutableStateOf(TailState())
    private var pendingTailRead = false
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
        val alertCauses = pendingAlertCausesRead
        val reception = pendingReceptionRead
        val configChanged = pendingConfigChangedRead
        val selfTest = pendingSelfTestRead
        val spread = pendingSpreadRead
        val calib = pendingCalibRead
        val frsi = pendingFrsiRead
        val today = pendingTodayRead
        val tail = pendingTailRead
        val mold = pendingMoldRead
        val history = pendingHistoryRead
        val clock = pendingClockRead
        val version = pendingVersionRead
        val setup = pendingSetupRead
        pendingSetupRead = false
        pendingHealthRead = false
        pendingEventsRead = false
        pendingWeekRead = false
        pendingSaltRead = false
        pendingRejectsRead = false
        pendingAlertCausesRead = false
        pendingReceptionRead = false
        pendingConfigChangedRead = false
        pendingSelfTestRead = false
        pendingSpreadRead = false
        pendingCalibRead = false
        pendingFrsiRead = false
        pendingTodayRead = false
        pendingTailRead = false
        pendingMoldRead = false
        pendingHistoryRead = false
        pendingClockRead = false
        pendingVersionRead = false
        if (grants.isNotEmpty() && grants.values.all { it }) {
            if (setup) {
                startSetupRead()
            } else if (version) {
                startVersionRead()
            } else if (clock) {
                startClockRead()
            } else if (history) {
                startHistoryRead()
            } else if (week) {
                startWeekRead()
            } else if (salt) {
                startSaltRead()
            } else if (alertCauses) {
                startAlertCausesRead()
            } else if (rejects) {
                startRejectsRead()
            } else if (reception) {
                startReceptionRead()
            } else if (configChanged) {
                startConfigChangedRead()
            } else if (selfTest) {
                startSelfTest()
            } else if (spread) {
                startSpreadRead()
            } else if (calib) {
                startCalibRead()
            } else if (frsi) {
                startFrsiRead()
            } else if (today) {
                startTodayRead()
            } else if (tail) {
                startTailRead()
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
        } else if (setup) {
            setupState = SetupProposalState(message = "Bluetooth-Berechtigung fehlt")
        } else if (version) {
            versionState = VersionReadState(message = "Bluetooth-Berechtigung fehlt")
        } else if (clock) {
            clockState = ClockReadState(clockMessage = "Bluetooth-Berechtigung fehlt")
        } else if (history) {
            historyState = HistoryState(message = "Bluetooth-Berechtigung fehlt")
        } else if (week) {
            weekState = WeekState(message = "Bluetooth-Berechtigung fehlt")
        } else if (salt) {
            saltState = saltState.withFailure(pendingSaltMagnesium, "Bluetooth-Berechtigung fehlt")
        } else if (alertCauses) {
            alertCausesState = AlertCausesUiState(message = "Bluetooth-Berechtigung fehlt")
        } else if (rejects) {
            rejectsState = RejectsState(message = "Bluetooth-Berechtigung fehlt")
        } else if (reception) {
            receptionState = ReceptionState(message = "Bluetooth-Berechtigung fehlt")
        } else if (configChanged) {
            configChangedState = ConfigChangedState(message = "Bluetooth-Berechtigung fehlt")
        } else if (selfTest) {
            selfTestState = SelfTestUiState(message = "Bluetooth-Berechtigung fehlt")
        } else if (spread) {
            spreadState = SpreadState(message = "Bluetooth-Berechtigung fehlt")
        } else if (calib) {
            calibState = calibState.copy(isReading = false, reply = null, isError = true, message = "Bluetooth-Berechtigung fehlt")
        } else if (frsi) {
            frsiState = frsiState.copy(isReading = false, reply = null, confirming = false, message = "Bluetooth-Berechtigung fehlt")
        } else if (today) {
            todayState = TodayState(message = "Bluetooth-Berechtigung fehlt", sensor = todayState.sensor)
        } else if (tail) {
            tailState = TailState(message = "Bluetooth-Berechtigung fehlt")
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
                    onRead = { readHistory() },
                    panelConfig = panelConfig
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
                    sensors = SaltPicker.sensors(panelConfig, saltRoom).map { it to panelConfig.tileName(it) },
                    selected = saltRoom,
                    onSelect = { selectSaltSensor(it) },
                    currentConfig = if (saltConfigStale) emptyMap() else panelConfig.values,
                    onRead = { readSalt(it) },
                    onApply = { applySalt(it) },
                    watchText = saltWatchText,
                    isWatching = isSaltWatching,
                    isWatchReady = isSaltWatchReady,
                    onWatch = { startSaltWatch() },
                    onStopWatch = { stopSaltWatch(SaltProgress.StopReason.USER) }
                )
                return@setContent
            }

            if (showSensorHealth && showAlertLimits) {
                BackHandler { if (alertLimitState.editing != null && !alertLimitState.isBusy) alertLimitState = AlertLimitState() else closeAlertLimits() }
                AlertLimitScreen(
                    state = alertLimitState,
                    config = panelConfig,
                    onOpen = { limit -> alertLimitState = AlertLimitState(editing = limit.key, value = AlertLimitStepper.startText(limit, panelConfig)) },
                    onStep = { dir ->
                        val limit = AlertLimits.LIMITS.firstOrNull { it.key == alertLimitState.editing }
                        if (limit != null) alertLimitState = alertLimitState.copy(value = AlertLimitStepper.move(limit, alertLimitState.value, dir), message = null)
                    },
                    onConfirm = { applyAlertLimit() },
                    onCancel = { alertLimitState = AlertLimitState() }
                )
                return@setContent
            }

            if (showSensorHealth && showFrsi) {
                BackHandler { closeFrsi() }
                FrsiScreen(
                    state = frsiState,
                    roomName = panelConfig.tileName(frsiState.room),
                    wallName = panelConfig.tileName(frsiState.wall),
                    onNextRoom = { frsiState = frsiState.copy(room = FrsiScreenText.nextRoom(frsiState.room), reply = null, message = null, confirming = false, readOnly = false) },
                    onNextHours = { frsiState = frsiState.copy(hours = FrsiScreenText.nextHours(frsiState.hours), reply = null, message = null, confirming = false, readOnly = false) },
                    onNextWall = { frsiState = frsiState.copy(wall = FrsiScreenText.nextWall(frsiState.wall), reply = null, message = null, confirming = false, readOnly = false) },
                    onRead = { readFrsi() },
                    onAsk = { frsiState = frsiState.copy(confirming = true) },
                    onCancel = { frsiState = frsiState.copy(confirming = false) },
                    onApply = { applyFrsi() }
                )
                return@setContent
            }

            if (showSensorHealth && showCalib) {
                BackHandler { closeCalib() }
                CalibrationScreen(
                    state = calibState,
                    picker = CalibrationText.picker(panelConfig),
                    onPick = { calibState = CalibrationState(reference = it) },
                    onRead = { readCalib() },
                    onApply = { applyCalib() }
                )
                return@setContent
            }

            if (showSensorHealth && showSpread) {
                BackHandler { closeSpread() }
                SpreadScreen(
state = spreadState,
hours = spreadHours,
onPickHours = { spreadHours = it; spreadState = spreadState.copy(reply = null, confirming = null, message = null) },
onRead = { readSpread() },
onAsk = { spreadState = spreadState.copy(confirming = it) },
onCancel = { spreadState = spreadState.copy(confirming = null) },
onApply = { applySpread(it) }
)
                return@setContent
            }

            if (showSensorHealth && showToday) {
                BackHandler { closeToday() }
                TodayScreen(
                    state = todayState,
                    roomNames = panelConfig.tileNames,
                    onSelect = { sensor -> if (!todayState.isReading && !pendingTodayRead) todayState = TodayState(sensor = sensor) },
                    onRead = { readToday() }
                )
                return@setContent
            }

            if (showSensorHealth && showTail) {
                BackHandler { closeTail() }
                TailScreen(state = tailState, roomNames = panelConfig.tileNames, forecastLimitPct = BatteryForecast.limitPct(panelConfig), onRead = { readTail() })
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

            if (showSensorHealth && showReception) {
                BackHandler { closeReception() }
                ReceptionScreen(state = receptionState, onRead = { readReception() })
                return@setContent
            }

            if (showSensorHealth && showAlertCauses) {
                BackHandler { closeAlertCauses() }
                AlertCausesScreen(state = alertCausesState, onRead = { readAlertCauses() })
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
                BackHandler {
                    if (!slotClearState.isSending) slotClearState = SlotClearState()
                    if (!slotSwapState.isSending) slotSwapState = SlotSwapState()
                    showSensorHealth = false
                }
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
                    onOpenAlertCauses = { showAlertCauses = true },
                    onOpenReception = { showReception = true },
                    onOpenConfigChanged = { showConfigChanged = true },
                    onOpenSelfTest = { showSelfTest = true },
                    onOpenSpread = { showSpread = true },
                    onOpenCalib = { showCalib = true },
                    onApplyJumpAdvice = { applyJumpAdvice(it) },
                    setupState = setupState,
                    onReadSetupProposals = { readSetupProposals() },
                    onApplySetupProposal = { key, value -> applySetupProposal(key, value) },
                    onOpenFrsi = { showFrsi = true },
                    onOpenAlertLimits = { showAlertLimits = true },
                    onOpenToday = { showToday = true },
                    onOpenTail = { showTail = true },
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
                    rtcTrimState = rtcTrimState,
                    onTrimRtc = { trimRtc() },
                    panelConfig = panelConfig,
                    slotClearState = slotClearState,
                    onAskClear = {
                        if (!slotClearState.isSending && !slotSwapState.isSending) {
                            slotSwapState = SlotSwapState()
                            slotClearState = SlotClearState(confirming = it)
                        }
                    },
                    onCancelClear = { if (!slotClearState.isSending) slotClearState = SlotClearState() },
                    onClearSlot = { clearSlot(it) },
                    slotSwapState = slotSwapState,
                    onPickSwap = { pickSwapSlot(it) },
                    onCancelSwap = { if (!slotSwapState.isSending) slotSwapState = SlotSwapState() },
                    onSwapSlots = { swapSlots() },
                    versionState = versionState,
                    onReadVersion = { readVersion() },
                    onShareReport = { shareDiagnosticsReport() }
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
        stopSaltWatch(SaltProgress.StopReason.VIEW_CLOSED)
        saltWatchText = null
        isSaltWatchReady = false
        consoleClient.cancel()
        isReadingConfig = false
        // An open permission dialog keeps pendingHealthRead, so its result still starts the tapped STATUS read.
        healthState = healthState.copy(isReading = false, isMuting = false, jumpBusy = false)
        eventState = EventLogState()
        pendingEventsRead = false
        pendingEventsAll = false
        weekState = WeekState()
        pendingWeekRead = false
        // An open permission dialog keeps pendingSetupRead and the busy state, so its result still starts the tapped read.
        if (!pendingSetupRead) {
            setupGeneration++
            setupState = SetupProposalState()
        }
        markSaltConfigStaleWhileApplying()
        saltState = SaltState()
        pendingSaltRead = false
        // An open permission dialog keeps pendingHistoryRead, so its result still starts the tapped HISTORY read.
        historyState = HistoryState()
        rejectsState = RejectsState()
        pendingRejectsRead = false
        alertCausesState = AlertCausesUiState()
        pendingAlertCausesRead = false
        showAlertCauses = false
        receptionState = ReceptionState()
        pendingReceptionRead = false
        showReception = false
        configChangedState = ConfigChangedState()
        pendingConfigChangedRead = false
        selfTestState = SelfTestUiState()
        pendingSelfTestRead = false
        spreadState = SpreadState()
        pendingSpreadRead = false
        calibState = CalibrationState()
        pendingCalibRead = false
        frsiState = FrsiState()
        pendingFrsiRead = false
        showFrsi = false
        alertLimitState = AlertLimitState()
        showAlertLimits = false
        todayState = TodayState()
        pendingTodayRead = false
        tailState = TailState()
        pendingTailRead = false
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
        if (isReadingConfig || healthState.isReading || pendingClockRead || pendingVersionRead || versionState.isReading || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingSaltRead || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
        if (clockState.isReadingClock || pendingClockRead || pendingVersionRead || versionState.isReading || pendingHealthRead || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || healthState.isReading || healthState.isMuting || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
        if (versionState.isReading || pendingVersionRead || pendingClockRead || pendingHealthRead || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || healthState.isReading || healthState.isMuting || clockState.isReadingClock || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
        if (historyState.isReading || healthState.isMuting || clockState.isReadingClock || pendingHistoryRead || pendingWeekRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
        if (weekState.isReading || pendingWeekRead || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
        if (weekState.isReading || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
        stopSaltWatch(SaltProgress.StopReason.VIEW_CLOSED)
        saltWatchText = null
        isSaltWatchReady = false
        markSaltConfigStaleWhileApplying()
        consoleClient.cancel()
        pendingSaltRead = false
        saltState = SaltState()
        showSalt = false
    }

    /**
     * A cancel drops the callback of a running apply batch, and a `SET` may have run. The cached gain and offset
     * are then stale until the next `CONFIG` read, so the salt screen evaluates with no config.
     * The `CALIB` apply batch sets the same offset keys, so it counts too.
     */
    private fun markSaltConfigStaleWhileApplying() {
        if (saltState.isApplying || calibState.isApplying) saltConfigStale = true
    }

    /** Text of the salt watch: the count while it runs, the stop reason after a stop. */
    private fun watchTextNow(): String {
        val reason = when (saltProgress.stopReason) {
            null, SaltProgress.StopReason.COMPLETED -> return saltProgress.text
            SaltProgress.StopReason.TIMEOUT -> "nach ${SaltProgress.MAX_DURATION_SECONDS / 3600} Stunden beendet"
            SaltProgress.StopReason.LINK_LOST -> "Verbindung verloren"
            SaltProgress.StopReason.REPLY_FAILED -> "Panel antwortet mit Fehler"
            SaltProgress.StopReason.USER -> "vom Benutzer beendet"
            SaltProgress.StopReason.VIEW_CLOSED -> "Ansicht geschlossen"
        }
        return "${saltProgress.text}. Beobachtung beendet: $reason."
    }

    /**
     * Starts the salt watch after a tap: `HISTORY N 6` every 10 minutes (`HISTORY N` after one `ERR BAD_ARG`).
     * It sends no `SALT` and no `SET`; at 6 stable hours the screen shows `Bereit` and the user taps NaCl or MgCl2.
     */
    private fun startSaltWatch() {
        val n = saltRoom
        if (n !in 0..SaltCommand.MAX_SENSOR || saltProgress.isRunning || saltState.isReading || saltState.isApplying || pendingSaltRead) return
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            saltWatchText = "Bluetooth-Berechtigung fehlt. Tippe zuerst auf NaCl, um sie zu erteilen."
            return
        }
        saltWatchRun++
        val run = saltWatchRun
        saltProgress.start()
        isSaltWatching = true
        refreshSaltWatchText()
        saltWatchJob?.cancel()
        saltWatchJob = lifecycleScope.launch {
            while (saltProgress.shouldPoll()) {
                while (saltProgress.shouldPoll() && (consoleClient.isRunning || saltState.isReading || saltState.isApplying)) delay(2_000L)
                if (!saltProgress.shouldPoll()) break
                pollSaltWatch(n, run)
                if (!saltProgress.isRunning) break
                delay(SaltProgress.INTERVAL_SECONDS * 1000L)
            }
            finishSaltWatch()
        }
    }

    private fun pollSaltWatch(sensor: Int, run: Int) {
        stopBleScan()
        consoleClient.send(saltProgress.historyLine(sensor)) { reply ->
            if (run != saltWatchRun || !saltProgress.isRunning) return@send
            if (reply is ConsoleReply.Status && saltProgress.fallBackToFullHistory(reply.line)) {
                pollSaltWatch(sensor, run)
                return@send
            }
            saltProgress.record(reply, sensor)
            if (saltProgress.isReady && saltProgress.isRunning) {
                saltProgress.stop(SaltProgress.StopReason.COMPLETED)
                saltWatchJob?.cancel()
                finishSaltWatch()
            } else {
                refreshSaltWatchText()
                isSaltWatching = saltProgress.isRunning
                if (!saltProgress.isRunning) saltWatchJob?.cancel()
            }
        }
    }

    private fun refreshSaltWatchText() {
        saltWatchText = watchTextNow()
        isSaltWatchReady = saltProgress.isReady
    }

    private fun finishSaltWatch() {
        isSaltWatching = false
        refreshSaltWatchText()
    }

    /** Ends the salt watch for [reason]. The first reason stays. */
    private fun stopSaltWatch(reason: SaltProgress.StopReason) {
        if (!saltProgress.isRunning) return
        saltProgress.stop(reason)
        saltWatchJob?.cancel()
        saltWatchJob = null
        if (reason != SaltProgress.StopReason.VIEW_CLOSED) finishSaltWatch() else isSaltWatching = false
    }

    /** Asks for the console permissions, then sends `SALT <N>` or, for [magnesium], `SALT <N> MGCL2` once. Only a tap in the salt screen calls it. It sends no `SET`. */
    private fun readSalt(magnesium: Boolean) {
        if (saltState.isReading || saltState.isApplying || pendingSaltRead || healthState.isMuting || clockState.isReadingClock || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        saltState = saltState.reading()
        pendingSaltMagnesium = magnesium
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

    /** Picks the sensor of the salt test. Both replies go; a running read or apply keeps the sensor. */
    private fun selectSaltSensor(sensor: Int) {
        if (sensor == saltRoom || sensor !in 0..SaltCommand.MAX_SENSOR) return
        if (saltState.isReading || saltState.isApplying || pendingSaltRead || saltProgress.isRunning) return
        saltRoom = sensor
        saltState = saltState.selectSensor()
        saltWatchText = null
        isSaltWatchReady = false
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one text. The command is not repeated. */
    private fun startSaltRead() {
        val magnesium = pendingSaltMagnesium
        if (consoleClient.isRunning) {
            saltState = saltState.withoutRead("Panel-Verbindung ist belegt")
            return
        }
        if (saltRoom !in 0..SaltCommand.MAX_SENSOR) {
            saltState = saltState.withoutRead("Raum ohne Sensor")
            return
        }
        consoleClient.send(SaltCommand.line(saltRoom, magnesium)) { reply ->
            saltState = when (reply) {
                is ConsoleReply.Frame -> try {
                    saltState.withReply(magnesium, SaltReply.parse(reply.lines))
                } catch (e: ParseException) {
                    saltState.withFailure(magnesium, "Antwort nicht lesbar")
                }
                is ConsoleReply.Status -> saltState.withFailure(magnesium, SaltText.status(reply.line))
                else -> saltState.withFailure(magnesium, BleConsoleClient.errorText(reply))
            }
        }
    }

    /**
     * Sends `SET <gain key>=<gain>`, `SET <offset key>=<offset>`, then `CONFIG`. Only the `Übernehmen` chip calls it,
     * and only for a result inside the key ranges. The first `ERR` stops the batch. No command is retried.
     * After a finished batch the two salt results are dropped, because the raw means depend on the active gain and offset.
     */
    private fun applySalt(result: TwoPointCalibration.Result) {
        if (saltState.isApplying || saltState.isReading || pendingSaltRead || isReadingConfig || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        val before = saltState
        saltState = before.copy(isApplying = true, applyMessage = null, applyIsError = false)
        CalibrationBatch(result.proposals).run(consoleClient::send) { batch ->
            saltState = before.afterApply(batch)
            if (SaltScreenText.changedConfig(batch)) {
                // The cached config holds the old gain and offset. Use the CONFIG reply of the batch, else read again.
                saltConfigStale = true
                val frame = batch.config
                if (frame != null) {
                    isReadingConfig = true
                    handleConfigReply(frame)
                } else {
                    startConfigRead()
                }
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
        if (rejectsState.isReading || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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

    private fun closeAlertCauses() {
        consoleClient.cancel()
        pendingAlertCausesRead = false
        alertCausesState = AlertCausesUiState()
        showAlertCauses = false
    }

    /** Asks for the console permissions, then sends `ALERTS` once. Only a tap in the alert causes screen calls it. */
    private fun readAlertCauses() {
        if (alertCausesState.isReading || pendingAlertCausesRead || pendingSpreadRead || spreadState.isReading || pendingCalibRead || pendingTodayRead || todayState.isReading || selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || pendingRejectsRead || rejectsState.isReading || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        alertCausesState = AlertCausesUiState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingAlertCausesRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startAlertCausesRead()
    }

    private fun startAlertCausesRead() {
        if (consoleClient.isRunning) {
            alertCausesState = AlertCausesUiState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send("ALERTS") { reply ->
            alertCausesState = when (reply) {
                is ConsoleReply.Frame -> try {
                    AlertCausesUiState(lines = AlertCauseRows.lines(AlertsReply.parse(reply.lines), panelConfig.tileNames))
                } catch (e: ParseException) {
                    AlertCausesUiState(message = "Antwort nicht lesbar")
                }
                is ConsoleReply.Status -> AlertCausesUiState(message = AlertsReply.errorText(reply.line))
                else -> AlertCausesUiState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closeReception() {
        consoleClient.cancel()
        pendingReceptionRead = false
        receptionState = ReceptionState()
        showReception = false
    }

    /** Asks for the console permissions, then sends `RECEPTION` once. Only a tap in the reception screen calls it. */
    private fun readReception() {
        if (receptionState.isReading || pendingReceptionRead || rejectsState.isReading || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        receptionState = ReceptionState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingReceptionRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startReceptionRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startReceptionRead() {
        if (consoleClient.isRunning) {
            receptionState = ReceptionState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send("RECEPTION") { reply ->
            receptionState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val rows = ReceptionReply.parse(reply.lines)
                    ReceptionState(rows = rows, loaded = true)
                } catch (e: ParseException) {
                    ReceptionState(message = "Antwort nicht lesbar")
                }
                else -> ReceptionState(message = BleConsoleClient.errorText(reply))
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
        if (setupState.isReading || setupState.isApplying || pendingSetupRead || configChangedState.isReading || pendingConfigChangedRead) return
        if (selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSpreadRead || spreadState.isReading || pendingTodayRead || todayState.isReading || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || rejectsState.isReading || pendingSelfTestRead || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning) {
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
        if (setupState.isReading || setupState.isApplying || pendingSetupRead || spreadState.isReading || pendingSpreadRead) return
        if (selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning) {
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

    /**
     * A failed CRC-32, a timeout or an `ERR` reply gives one error text. A panel firmware that answers `ERR BAD_ARG` to
     * a window above 6 h gets one more request with 6 h and the update text.
     */
    private fun startSpreadRead(hours: Int = spreadHours, note: String? = null) {
        if (consoleClient.isRunning) {
            spreadState = SpreadState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send(SpreadCommand.line(hours)) { reply ->
            if (reply is ConsoleReply.Status && SpreadCommand.isOldFirmware(hours, reply.line)) {
                spreadHours = SpreadCommand.DEFAULT_HOURS
                spreadState = SpreadState(isReading = true, message = SpreadCommand.OLD_FIRMWARE_TEXT)
                startSpreadRead(SpreadCommand.DEFAULT_HOURS, SpreadCommand.OLD_FIRMWARE_TEXT)
                return@send
            }
            spreadState = when (reply) {
                is ConsoleReply.Frame -> try {
                    SpreadState(reply = SpreadReply.parse(reply.lines), replyHours = hours, message = note)
                } catch (e: ParseException) {
                    SpreadState(message = "Antwort nicht lesbar")
                }
                else -> SpreadState(message = listOfNotNull(note, BleConsoleClient.errorText(reply)).joinToString(". "))
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
        if (spreadState.isApplying || spreadState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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

    private fun closeCalib() {
        markSaltConfigStaleWhileApplying()
        consoleClient.cancel()
        pendingCalibRead = false
        calibState = CalibrationState(reference = calibState.reference)
        showCalib = false
    }

    private fun calibBusy(): Boolean =
        setupState.isReading || setupState.isApplying || pendingSetupRead || selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead ||
            pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading ||
            pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead ||
            versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading ||
            isReadingConfig || pendingSaltRead || saltState.isReading || pendingSpreadRead || spreadState.isReading || spreadState.isApplying ||
            pendingTodayRead || todayState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning

    /** Asks for the console permissions, then sends `CALIB <N>` once. Only the `Vergleichen` chip calls it. It sends no `SET`. */
    private fun readCalib() {
        if (calibState.isReading || calibState.isApplying || pendingCalibRead) return
        val reference = calibState.reference
        if (calibBusy()) {
            calibState = CalibrationState(reference = reference, isError = true, message = "Panel-Verbindung ist belegt")
            return
        }
        calibState = CalibrationState(reference = reference, isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingCalibRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startCalibRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startCalibRead() {
        val reference = calibState.reference
        if (consoleClient.isRunning) {
            calibState = CalibrationState(reference = reference, isError = true, message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send(CalibrationText.command(reference)) { reply ->
            calibState = when (reply) {
                is ConsoleReply.Frame -> try {
                    CalibrationState(reference = reference, reply = CalibrationReply.parse(reply.lines))
                } catch (e: ParseException) {
                    CalibrationState(reference = reference, isError = true, message = "Antwort nicht lesbar")
                }
                else -> CalibrationState(reference = reference, isError = true, message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /**
     * Sends the proposal lines as `SET` commands in reply order, then `CONFIG`. Only the `Übernehmen` chip calls it.
     * The first `ERR` stops the batch; `ERR READ_ONLY` shows `Set ble_console=2 to apply`.
     */
    private fun applyCalib() {
        val reply = calibState.reply ?: return
        if (reply.proposals.isEmpty() || calibState.isApplying || calibState.isReading || calibBusy()) return
        val before = calibState
        calibState = before.copy(isApplying = true, message = null, isError = false)
        CalibrationBatch(reply.proposals).run(consoleClient::send) { result ->
            val outcome = CalibrationText.applyOutcome(result)
            // A `SET` may have run, so the shown differences are old: the proposals go until the next read.
            calibState = before.copy(
                isApplying = false,
                reply = if (outcome.changed) null else before.reply,
                isError = outcome.isError,
                message = outcome.message
            )
            if (outcome.changed) {
                // The cached config holds the old offsets. Use the CONFIG reply of the batch, else read again.
                saltConfigStale = true
                val frame = result.config
                if (frame != null) {
                    isReadingConfig = true
                    handleConfigReply(frame)
                } else {
                    startConfigRead()
                }
            }
        }
    }

    private fun closeAlertLimits() {
        val wasBusy = alertLimitState.isBusy
        consoleClient.cancel()
        alertLimitState = AlertLimitState()
        showAlertLimits = false
        // A cancelled batch never delivers its CONFIG reply, and a SET may already run: read the config again.
        if (wasBusy && !isReadingConfig) startConfigRead()
    }

    /**
     * Sends `SET <key>=<value>` of the open stepper, then `CONFIG`. Only the `Senden` chip calls it. The batch needs
     * a free link and the console level 2; `ERR READ_ONLY` shows `Set ble_console=2 to apply`. No command is retried.
     */
    private fun applyAlertLimit() {
        val before = alertLimitState
        val key = before.editing ?: return
        if (before.isBusy || isReadingConfig || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        val commands = AlertLimitScreenText.commands(key, before.value, panelConfig)
        if (commands.isEmpty() || AlertLimits.LIMITS.firstOrNull { it.key == key }?.let { AlertLimits.error(it, before.value) } != null) return
        alertLimitState = before.copy(isBusy = true, message = null, isError = false)
        AlertLimits.run(commands, consoleClient::send) { result ->
            result.config?.let { frame ->
                isReadingConfig = true
                handleConfigReply(frame)
            }
            // cancel() drops the callback; this guard covers a callback that the main thread already queued.
            if (!showAlertLimits) return@run
            alertLimitState = if (result.ok) AlertLimitState(message = result.summary)
            else before.copy(isBusy = false, isError = true, message = result.summary)
        }
    }

    private fun closeFrsi() {
        consoleClient.cancel()
        pendingFrsiRead = false
        frsiState = FrsiState()
        showFrsi = false
    }

    /** Asks for the console permissions, then sends `FRSI <R> <W> [<H>]` once. Only the `Messen` chip calls it. It sends no `SET`. */
    private fun readFrsi() {
        if (setupState.isReading || setupState.isApplying || pendingSetupRead || frsiState.isReading || frsiState.isApplying || pendingFrsiRead) return
        val picked = frsiState
        if (picked.room == picked.wall) {
            frsiState = picked.copy(reply = null, confirming = false, message = FrsiApply.DIFFERENT_TEXT)
            return
        }
        if (selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingSpreadRead || spreadState.isReading || spreadState.isApplying || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning) {
            frsiState = picked.copy(reply = null, confirming = false, message = "Panel-Verbindung ist belegt")
            return
        }
        frsiState = picked.copy(isReading = true, reply = null, confirming = false, readOnly = false, message = null)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingFrsiRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startFrsiRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one text. The command is not repeated. */
    private fun startFrsiRead() {
        val picked = frsiState
        if (consoleClient.isRunning) {
            frsiState = picked.copy(isReading = false, message = "Panel-Verbindung ist belegt")
            return
        }
        val command = try {
            FrsiCommand.line(picked.room, picked.wall, picked.hours)
        } catch (e: IllegalArgumentException) {
            frsiState = picked.copy(isReading = false, message = FrsiApply.DIFFERENT_TEXT)
            return
        }
        consoleClient.send(command) { reply ->
            val current = frsiState
            frsiState = when (reply) {
                is ConsoleReply.Frame -> try {
                    current.copy(isReading = false, reply = FrsiReply.parse(reply.lines), replyHours = picked.hours, message = null)
                } catch (e: ParseException) {
                    current.copy(isReading = false, message = "Antwort nicht lesbar")
                }
                is ConsoleReply.Status -> current.copy(isReading = false, replyHours = picked.hours, message = FrsiText.status(reply.line, picked.hours))
                else -> current.copy(isReading = false, replyHours = picked.hours, message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /**
     * Sends `SET wall_frsi_pct=<N>`, then `CONFIG`. Only the `Senden` chip after the confirmation calls it.
     * `ERR READ_ONLY` shows the hint and hides the chip. No command is retried.
     */
    private fun applyFrsi() {
        val before = frsiState
        val batch = before.reply?.let { FrsiApply.batch(it) } ?: return
        if (before.isApplying || before.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        frsiState = before.copy(confirming = false, isApplying = true, message = null)
        batch.run(consoleClient::send) { result ->
            frsiState = before.copy(
                confirming = false,
                isApplying = false,
                readOnly = FrsiApply.isReadOnly(result),
                message = FrsiApply.resultText(result),
                messageIsError = result.failure != null || result.configFailed
            )
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
        if (setupState.isReading || setupState.isApplying || pendingSetupRead || moldState.isReading || pendingMoldRead) return
        if (tailState.isReading || pendingTailRead || todayState.isReading || pendingTodayRead || spreadState.isReading || pendingSpreadRead || selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning) {
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

    private fun closeTail() {
        consoleClient.cancel()
        pendingTailRead = false
        tailState = TailState()
        showTail = false
    }

    /** Asks for the console permissions, then sends `TAIL 6` once. Only a tap in the tail screen calls it. */
    private fun readTail() {
        if (setupState.isReading || setupState.isApplying || pendingSetupRead || tailState.isReading || pendingTailRead) return
        if (moldState.isReading || pendingMoldRead || todayState.isReading || pendingTodayRead || spreadState.isReading || pendingSpreadRead || selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning) {
            tailState = TailState(message = "Panel-Verbindung ist belegt")
            return
        }
        tailState = TailState(isReading = true)
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingTailRead = true
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        startTailRead()
    }

    /** A failed CRC-32, a timeout or an `ERR` reply gives one error text. The command is not repeated. */
    private fun startTailRead() {
        if (consoleClient.isRunning) {
            tailState = TailState(message = "Panel-Verbindung ist belegt")
            return
        }
        consoleClient.send(TailScreenText.COMMAND) { reply ->
            tailState = when (reply) {
                is ConsoleReply.Frame -> try {
                    TailState(rows = TailReply.parse(reply.lines), hasRead = true)
                } catch (e: ParseException) {
                    TailState(message = "Antwort nicht lesbar")
                }
                else -> TailState(message = reply.let { r ->
                    (r as? ConsoleReply.Status)?.let { TailScreenText.errorText(it.line) } ?: BleConsoleClient.errorText(r)
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
        if (setupState.isReading || setupState.isApplying || pendingSetupRead || todayState.isReading || pendingTodayRead) return
        val sensor = todayState.sensor
        if (spreadState.isReading || pendingSpreadRead || selfTestState.isRunning || clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingSelfTestRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning) {
            todayState = TodayState(message = "Panel-Verbindung ist belegt", sensor = sensor)
            return
        }
        todayState = TodayState(isReading = true, sensor = sensor)
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
        val sensor = todayState.sensor
        if (consoleClient.isRunning) {
            todayState = TodayState(message = "Panel-Verbindung ist belegt", sensor = sensor)
            return
        }
        consoleClient.send(TodayCommand.line(sensor)) { first ->
            val status = (first as? ConsoleReply.Status)?.line
            if (status != null && TodayCommand.needsFallback(sensor, status)) {
                consoleClient.send(TodayCommand.ALL) { full -> todayState = todayResult(full, sensor) }
            } else {
                todayState = todayResult(first, sensor)
            }
        }
    }

    private fun todayResult(reply: ConsoleReply, sensor: Int?): TodayState = when (reply) {
        is ConsoleReply.Frame -> try {
            TodayState(rows = TodayCommand.select(TodayReply.parse(reply.lines), sensor), hasRead = true, sensor = sensor)
        } catch (e: ParseException) {
            TodayState(message = "Antwort nicht lesbar", sensor = sensor)
        }
        else -> TodayState(
            message = (reply as? ConsoleReply.Status)?.let { TodayReply.statusText(it.line) } ?: BleConsoleClient.errorText(reply),
            sensor = sensor
        )
    }

    private fun closeSelfTest() {
        consoleClient.cancel()
        pendingSelfTestRead = false
        selfTestState = SelfTestUiState()
        showSelfTest = false
    }

    /** Asks for the console permissions, then sends `SELFTEST` once. Only the confirm chip of the self-test screen calls it. */
    private fun readSelfTest() {
        if (setupState.isReading || setupState.isApplying || pendingSetupRead || selfTestState.isRunning || pendingSelfTestRead) return
        if (clockState.isReadingClock || healthState.isMuting || chimeState.isTesting || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingWeekRead || pendingHistoryRead || pendingEventsRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || consoleClient.isRunning) {
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
        if (eventState.isReading || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || healthState.isReading || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
        if (healthState.isReading || pendingHealthRead || pendingClockRead || pendingVersionRead || versionState.isReading || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        chimeState = chimeState.copy(message = null)
        if (!rtcTrimState.isBusy) rtcTrimState = RtcTrimState()
        if (!slotClearState.isSending) slotClearState = SlotClearState()
        if (!slotSwapState.isSending) slotSwapState = SlotSwapState()
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
            if (reply is ConsoleReply.Frame && healthState.status != null && showSensorHealth) readJumpAdvice()
        }
    }

    /** Sends `EVENTS` after a good `STATUS` read and shows the jump guard advice. A failed read gives no advice. */
    private fun readJumpAdvice() {
        consoleClient.send("EVENTS") { reply ->
            if (!showSensorHealth || healthState.isReading || reply !is ConsoleReply.Frame) return@send
            val log = try { EventsReply.parse(reply.lines) } catch (e: ParseException) { return@send }
            healthState = healthState.copy(jumpAdvice = JumpAdvice.evaluate(log.rows, panelConfig, System.currentTimeMillis()))
        }
    }

    /** True while another console action runs, so the setup proposals never share the link. */
    private fun setupBlocked(): Boolean =
        setupState.isReading || setupState.isApplying || pendingSetupRead || healthState.isReading || healthState.isMuting || isReadingConfig ||
            weekState.isReading || pendingWeekRead || pendingHealthRead || pendingEventsRead || pendingHistoryRead || pendingClockRead ||
            pendingVersionRead || versionState.isReading || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead ||
            pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead ||
            pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || pendingSaltRead || saltState.isReading ||
            pendingFrsiRead || frsiState.isReading || frsiState.isApplying || pendingSpreadRead || spreadState.isReading || pendingCalibRead ||
            pendingTodayRead || todayState.isReading || selfTestState.isRunning || clockState.isReadingClock || chimeState.isTesting ||
            rtcTrimState.isBusy || slotClearState.isSending || slotSwapState.isSending || calibState.isReading || calibState.isApplying ||
            spreadState.isApplying || alertLimitState.isBusy || eventState.isReading || consoleClient.isRunning

    private fun hasConsolePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    /**
     * Sends `WEEK` (all sensors), then `CONFIG`, only after the tap on `Setup proposals`. Deviation from the roadmap
     * text (`HISTORY`): the phone and macOS proposals use the `WEEK` rows (3 days minimum), so the watch does too.
     * A failed command is not retried.
     */
    private fun readSetupProposals() {
        if (setupBlocked()) return
        setupGeneration++
        setupState = SetupProposalState(isReading = true)
        if (!hasConsolePermission()) {
            pendingSetupRead = true
            consolePermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
            return
        }
        startSetupRead()
    }

    /** A failed command, a corrupt reply or an unreadable `CONFIG` shows one text and no proposal. */
    private fun startSetupRead() {
        if (consoleClient.isRunning) {
            setupState = SetupProposalState(message = "Panel-Verbindung ist belegt")
            return
        }
        val generation = setupGeneration
        consoleClient.send("WEEK") { reply ->
            val rows = (reply as? ConsoleReply.Frame)?.let { frame ->
                try { WeekReply.parse(frame.lines) } catch (e: ParseException) { null }
            }
            if (generation != setupGeneration) return@send
            if (rows == null) {
                val text = if (reply is ConsoleReply.Frame) "Antwort nicht lesbar" else BleConsoleClient.errorText(reply)
                setupState = SetupProposalState(message = text)
                return@send
            }
            consoleClient.send("CONFIG") { configReply ->
                if (configReply !is ConsoleReply.Frame) {
                    setupState = SetupProposalState(message = BleConsoleClient.errorText(configReply))
                    return@send
                }
                lifecycleScope.launch {
                    val loaded = withContext(Dispatchers.IO) {
                        ConfigCache.store(filesDir, configReply, System.currentTimeMillis())?.let { PanelConfig.load(this@MainActivity) }
                    }
                    // A stopped view already reset the state; a late result must not revive it.
                    if (generation != setupGeneration || !setupState.isReading) return@launch
                    if (loaded != null) panelConfig = loaded
                    setupState = if (loaded != null) SetupProposalState(week = rows) else SetupProposalState(message = "CONFIG nicht lesbar")
                }
            }
        }
    }

    /** Sends one `SET <key>=<value>`, then `CONFIG`, only after the tap on `Apply`. `ERR READ_ONLY` shows the hint. */
    private fun applySetupProposal(key: String, value: String) {
        if (!panelConfig.canWrite || setupBlocked()) return
        if (!hasConsolePermission()) {
            setupState = setupState.copy(message = "Bluetooth-Berechtigung fehlt")
            return
        }
        val week = setupState.week
        val generation = setupGeneration
        setupState = setupState.copy(isApplying = true, message = null)
        CalibrationBatch(listOf(CalibrationProposal(key, value))).run(consoleClient::send) { result ->
            if (generation != setupGeneration) return@run
            val frame = result.config
            if (frame == null) {
                // A failed CONFIG read leaves the cached config old, so the proposals go until the next read.
                setupState = SetupProposalState(week = if (result.configFailed) null else week, message = result.summary)
                return@run
            }
            // The state stays busy until the new config is cached, so a second tap cannot send a duplicate SET.
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    ConfigCache.store(filesDir, frame, System.currentTimeMillis())?.let { PanelConfig.load(this@MainActivity) }
                }
                if (generation != setupGeneration) return@launch
                loaded?.let { panelConfig = it }
                // An unreadable cache keeps the old config, so the proposals go until the next read.
                setupState = if (loaded != null) SetupProposalState(week = week, message = result.summary)
                else SetupProposalState(message = "CONFIG nicht lesbar")
            }
        }
    }

    /** Sends `SET jump_limit_c=<value>`, then `CONFIG`, only after the tap on `Anwenden`. The first `ERR` stops it. */
    private fun applyJumpAdvice(advice: JumpAdvice) {
        if (!panelConfig.canWrite || healthState.jumpBusy || healthState.isReading || healthState.isMuting || isReadingConfig || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
        // The proposal came from the limit at read time. A changed cached limit makes it stale, so the advice goes.
        if (panelConfig.values[JumpAdvice.KEY]?.trim()?.toDoubleOrNull() != advice.currentC) {
            healthState = healthState.copy(jumpAdvice = emptyList())
            return
        }
        healthState = healthState.copy(jumpBusy = true, jumpMessage = null)
        CalibrationBatch(listOf(advice.proposal)).run(consoleClient::send) { result ->
            result.config?.let { frame ->
                lifecycleScope.launch {
                    val stored = withContext(Dispatchers.IO) {
                        val info = ConfigCache.store(filesDir, frame, System.currentTimeMillis())
                        info to info?.let { PanelConfig.load(this@MainActivity) }
                    }
                    stored.second?.let { panelConfig = it }
                }
            }
            val ok = result.failure == null && !result.configFailed
            // The limit changed or is unknown, so the old advice is stale. A new read gives a fresh one.
            healthState = healthState.copy(
                jumpBusy = false, jumpMessage = result.summary,
                jumpAdvice = if (result.applied.isNotEmpty() || ok) emptyList() else healthState.jumpAdvice
            )
        }
    }

    /**
     * Sends `MUTE <0|1>`, then `STATUS`. The toggle follows the `STATUS` reply only; a failed command
     * keeps the old state and is not retried.
     */
    private fun setMute(on: Boolean) {
        if (healthState.isReading || healthState.isMuting || pendingClockRead || pendingVersionRead || versionState.isReading || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingSaltRead || saltState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning) return
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
            pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning
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

    /**
     * Sends `RTC TRIM` after a tap, then `STATUS` after an `OK`. A failed command is not repeated.
     */
    private fun trimRtc() {
        if (rtcTrimState.isBusy || chimeState.isTesting || healthState.isReading || healthState.isMuting || pendingClockRead || pendingVersionRead || versionState.isReading || pendingHealthRead ||
            pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        ) {
            rtcTrimState = RtcTrimState(message = "Bluetooth-Berechtigung fehlt", isError = true)
            return
        }
        rtcTrimState = RtcTrimState(isBusy = true)
        consoleClient.send(RtcTrimReply.COMMAND) { reply ->
            val result = SensorHealthText.trimResult(reply) { SensorHealthText.muteFallback(it) }
            rtcTrimState = result
            // The panel has written the trim after every `OK` line, so STATUS also runs after an `OK` line that parse rejects.
            val sentOk = (reply as? ConsoleReply.Status)?.line?.trim()?.startsWith("OK RTC TRIM") == true
            if (sentOk) {
                consoleClient.send(ChimeCommand.STATUS) { status ->
                    try {
                        healthState = SensorHealthState(status = parseStatusReply(status))
                    } catch (e: Exception) {
                        val reason = if (status is ConsoleReply.Frame) "Antwort nicht lesbar" else SensorHealthText.muteFallback(status)
                        rtcTrimState = RtcTrimState(message = "${result.message}, Stand unbestätigt. $reason", isError = true)
                    }
                }
            }
        }
    }

    /** Sends `IDENTIFY` once. Only a tap on the `Panel finden` chip calls it. A failed command is not retried. */
    private fun identifyPanel() {
        if (chimeState.isTesting || healthState.isReading || healthState.isMuting || pendingClockRead || pendingVersionRead || versionState.isReading || pendingHealthRead ||
            pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading || pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading || isReadingConfig || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning
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

    /**
     * Sends `SLOT CLEAR <N>`, then `STATUS`. Only the `Senden` chip after the confirmation calls it.
     * An `ERR` reply sends no `STATUS`; a timeout or a corrupt reply still sends it, because the command can have run. No command is retried.
     */
    private fun clearSlot(slot: Int) {
        if (slotClearState.isSending || slotSwapState.isSending || slotClearState.confirming != slot || healthState.isReading || healthState.isMuting || chimeState.isTesting || pendingHealthRead ||
            pendingClockRead || pendingVersionRead || versionState.isReading || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading ||
            pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading ||
            isReadingConfig || pendingSaltRead || saltState.isReading || pendingSpreadRead || spreadState.isReading || spreadState.isApplying ||
            pendingTodayRead || todayState.isReading || selfTestState.isRunning || rejectsState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        ) {
            slotClearState = SlotClearState(message = "Bluetooth-Berechtigung fehlt", isError = true)
            return
        }
        slotClearState = SlotClearState(confirming = slot, isSending = true)
        consoleClient.send(SlotClearCommand.line(slot)) { reply ->
            val (text, isError) = SlotClearCommand.result(slot, reply)
            // A timeout or a corrupt reply can follow a command that ran, so STATUS also runs after it.
            val failure = if (reply is ConsoleReply.Corrupt) SensorHealthText.muteFallback(reply) else text
            val message = if (isError) failure else text
            if (isError && reply is ConsoleReply.Status) {
                slotClearState = SlotClearState(message = message, isError = true)
                return@send
            }
            slotClearState = SlotClearState(message = message, isError = isError)
            consoleClient.send(ChimeCommand.STATUS) { status ->
                try {
                    healthState = SensorHealthState(status = parseStatusReply(status))
                } catch (e: Exception) {
                    val reason = if (status is ConsoleReply.Frame) "Antwort nicht lesbar" else SensorHealthText.muteFallback(status)
                    slotClearState = SlotClearState(message = "$message, Stand unbestätigt. $reason", isError = true)
                }
            }
        }
    }

    /** The first tap picks a slot, the second tap picks the partner and shows the confirmation. Nothing is sent here. */
    private fun pickSwapSlot(slot: Int) {
        if (slotSwapState.isSending || slotClearState.isSending) return
        if (slotClearState.confirming != null) slotClearState = SlotClearState()
        val first = slotSwapState.first
        slotSwapState = when {
            first == null -> SlotSwapState(first = slot)
            slotSwapState.second == null && SlotSwapCommand.isValidPair(first, slot) -> slotSwapState.copy(second = slot)
            else -> slotSwapState
        }
    }

    /**
     * Sends `SLOT SWAP <A> <B>`, then `STATUS`. Only the `Senden` chip after the confirmation calls it.
     * An `ERR` reply sends no `STATUS`; a timeout or a corrupt reply still sends it, because the command can have run. No command is retried.
     */
    private fun swapSlots() {
        val first = slotSwapState.first ?: return
        val second = slotSwapState.second ?: return
        val line = SlotSwapCommand.line(first, second) ?: return
        if (slotSwapState.isSending || slotClearState.isSending || healthState.isReading || healthState.isMuting || chimeState.isTesting || pendingHealthRead ||
            pendingClockRead || pendingVersionRead || versionState.isReading || pendingEventsRead || pendingWeekRead || pendingHistoryRead || pendingAlertCausesRead || alertCausesState.isReading || pendingRejectsRead || pendingReceptionRead || receptionState.isReading ||
            pendingConfigChangedRead || configChangedState.isReading || pendingSelfTestRead || clockState.isReadingClock || pendingMoldRead || pendingTailRead || tailState.isReading || moldState.isReading ||
            isReadingConfig || pendingSaltRead || saltState.isReading || pendingSpreadRead || spreadState.isReading || spreadState.isApplying ||
            pendingTodayRead || todayState.isReading || selfTestState.isRunning || rejectsState.isReading || pendingFrsiRead || frsiState.isReading || frsiState.isApplying || setupState.isReading || setupState.isApplying || pendingSetupRead || consoleClient.isRunning
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        ) {
            slotSwapState = SlotSwapState(message = "Bluetooth-Berechtigung fehlt", isError = true)
            return
        }
        slotSwapState = SlotSwapState(first = first, second = second, isSending = true)
        consoleClient.send(line) { reply ->
            val (text, isError) = SlotSwapCommand.result(first, second, reply)
            // A timeout or a corrupt reply can follow a command that ran, so STATUS also runs after it.
            val failure = if (reply is ConsoleReply.Corrupt) SensorHealthText.muteFallback(reply) else text
            val message = if (isError) failure else text
            if (isError && reply is ConsoleReply.Status) {
                slotSwapState = SlotSwapState(message = message, isError = true)
                return@send
            }
            slotSwapState = SlotSwapState(message = message, isError = isError)
            consoleClient.send(ChimeCommand.STATUS) { status ->
                try {
                    healthState = SensorHealthState(status = parseStatusReply(status))
                } catch (e: Exception) {
                    val reason = if (status is ConsoleReply.Frame) "Antwort nicht lesbar" else SensorHealthText.muteFallback(status)
                    slotSwapState = SlotSwapState(message = "$message, Stand unbestätigt. $reason", isError = true)
                }
            }
        }
    }

    /** A good reply replaces the cache; a bad CRC-32, a timeout or an `ERR` reply keeps the old one. */
    private fun startConfigRead() {
        isReadingConfig = true
        consoleClient.send("CONFIG") { reply -> handleConfigReply(reply) }
    }

    /** Stores one `CONFIG` reply. The caller sets [isReadingConfig] first. */
    private fun handleConfigReply(reply: ConsoleReply) {
        // The write and the reload touch the disk, so they run off the main thread.
        lifecycleScope.launch {
            val (info, loaded) = withContext(Dispatchers.IO) {
                val stored = ConfigCache.store(filesDir, reply, System.currentTimeMillis())
                stored to stored?.let { PanelConfig.load(this@MainActivity) }
            }
            isReadingConfig = false
            if (info != null && loaded != null) {
                panelConfig = loaded
                saltConfigStale = false
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
     * Parses one `STATUS` reply and feeds its window mask to [WindowWatch] its weak signal mask to [ReceptionWatch] its drift mask to [DriftWatch] and its dry mask to [DryWatch]. A reply that does not parse throws and
     * does not reach the watch. A failed notification or vibration does not turn a good reply into a read error.
     */
    private fun parseStatusReply(reply: ConsoleReply): StatusReply {
        val status = StatusReply.parseReply(reply)
        // A fresh status can hide the row, so a waiting confirmation ends.
        if (!slotClearState.isSending && slotClearState.confirming != null) slotClearState = slotClearState.copy(confirming = null)
        if (!slotSwapState.isSending && slotSwapState.first != null) slotSwapState = SlotSwapState(message = slotSwapState.message, isError = slotSwapState.isError)
        runCatching { checkWindowNotifications(status) }
        runCatching { checkWeakSignalNotifications(status) }
        runCatching { checkDriftNotifications(status) }
        runCatching { checkDryNotifications(status) }
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

    /** Posts one notification with a short vibration for each `dry` bit of `LATCH` that rose since the previous read. */
    private fun checkDryNotifications(status: StatusReply) {
        val rising = dryWatch.update(status.latch?.dry)
        if (rising.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        for (tile in rising) {
            postVentilationNotification(
                title = "RLCD Alarm",
                message = DryWatch.message(panelConfig.tileName(tile)),
                notificationId = DryWatch.notificationId(tile)
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

    /**
     * Shares the text report of the replies read before with the paired phone. It sends no console
     * command: missing replies show as `not read`.
     */
    private fun shareDiagnosticsReport() {
        val rejects = rejectsState.takeIf { it.loaded }?.let { RejectsLog(it.rows, it.dropped) }
        val reception = receptionState.takeIf { it.loaded }?.rows
        val text = DiagnosticsReport.build(
            version = versionState.reply,
            status = healthState.status,
            rejects = rejects,
            selfTest = selfTestState.reply,
            reception = reception,
            missing = "not read",
            showMissingReception = true,
            title = "RLCD Watch Diagnostics"
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "RLCD Diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Bericht teilen"))
    }
}
