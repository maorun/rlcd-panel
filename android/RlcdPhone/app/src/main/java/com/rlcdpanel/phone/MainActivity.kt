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
import com.rlcdpanel.phone.finder.FoundSensor
import com.rlcdpanel.phone.finder.SensorFinderList
import com.rlcdpanel.phone.protocol.SetupAssistant
import com.rlcdpanel.phone.finder.SensorFinderScanner
import com.rlcdpanel.phone.finder.SignalSeries
import com.rlcdpanel.phone.finder.SlotAssignment
import com.rlcdpanel.phone.protocol.AckCommand
import com.rlcdpanel.phone.protocol.AlertLimits
import com.rlcdpanel.phone.protocol.CalibrationBatch
import com.rlcdpanel.phone.protocol.ChimeCommand
import com.rlcdpanel.phone.protocol.ChimeText
import com.rlcdpanel.phone.protocol.CalibrationProposal
import com.rlcdpanel.phone.protocol.CalibrationReply
import com.rlcdpanel.phone.protocol.CalibrationSensors
import com.rlcdpanel.phone.protocol.FrsiCommand
import com.rlcdpanel.phone.protocol.FrsiReply
import com.rlcdpanel.phone.protocol.FrsiText
import com.rlcdpanel.phone.protocol.SaltCommand
import com.rlcdpanel.phone.protocol.SaltReply
import com.rlcdpanel.phone.protocol.SaltText
import com.rlcdpanel.phone.protocol.TwoPointCalibration
import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.SlotConfig
import com.rlcdpanel.phone.protocol.PanelClock
import com.rlcdpanel.phone.protocol.ParseException
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.StatusReply
import com.rlcdpanel.phone.protocol.ConfigBackup
import com.rlcdpanel.phone.protocol.ConfigBackupException
import com.rlcdpanel.phone.protocol.ConfigCache
import com.rlcdpanel.phone.protocol.ConfigRestorePlan
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryHistory
import com.rlcdpanel.phone.protocol.WallText
import com.rlcdpanel.phone.protocol.DiagnosticsReport
import com.rlcdpanel.phone.protocol.EventCsv
import com.rlcdpanel.phone.protocol.RejectsLog
import com.rlcdpanel.phone.protocol.EventRow
import com.rlcdpanel.phone.protocol.EventsReply
import com.rlcdpanel.phone.protocol.RejectsReply
import com.rlcdpanel.phone.protocol.VersionReply
import com.rlcdpanel.phone.protocol.SelfTestReply
import com.rlcdpanel.phone.protocol.WeekCsv
import com.rlcdpanel.phone.protocol.WeekReply
import com.rlcdpanel.phone.protocol.WeekRow
import com.rlcdpanel.phone.protocol.HistoryCommand
import com.rlcdpanel.phone.protocol.HistoryReply
import com.rlcdpanel.phone.ventilation.OutdoorClimate
import com.rlcdpanel.phone.ventilation.LocationInput
import com.rlcdpanel.phone.ventilation.OutdoorLocation
import com.rlcdpanel.phone.ventilation.OutdoorLocationStore
import com.rlcdpanel.phone.ventilation.SharedLocationPrefs
import com.rlcdpanel.phone.ventilation.toSource
import com.rlcdpanel.phone.ventilation.OutdoorResolver
import com.rlcdpanel.phone.ventilation.OutdoorSource
import com.rlcdpanel.phone.ventilation.OutdoorWeatherService
import com.rlcdpanel.phone.ventilation.VentilationAction
import com.rlcdpanel.phone.ventilation.VentilationGuide
import com.rlcdpanel.phone.protocol.WallWatch
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
    private var outdoorLocation by mutableStateOf(OutdoorLocation())
    // Place of the cached reading; a changed place drops the reading.
    private var openMeteoLocation by mutableStateOf<OutdoorLocation?>(null)
    private var showLocationDialog by mutableStateOf(false)
    private lateinit var locationStore: OutdoorLocationStore
    private var focusedRoomIndex by mutableIntStateOf(0)

    private var roomStates by mutableStateOf<Map<Int, RoomVentilationState>>(emptyMap())
    private val lastObservedAktionen = mutableMapOf<Int, VentilationAction>()
    private val wallWatch = WallWatch()
    private var panelConfig by mutableStateOf(PanelConfig())

    private var pendingConsoleCommand = CONSOLE_STATUS
    private var notificationPermissionAsked = false
    private var scanPermissionDenied = false
    private var weatherJob: Job? = null

    private lateinit var consoleClient: BleConsoleClient
    private var showHealth by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    private var showCalib by mutableStateOf(false)
    private var showBackup by mutableStateOf(false)
    private var backupState by mutableStateOf(ConfigBackupState())
    private var backupLoadJob: Job? = null
    private var showWeek by mutableStateOf(false)
    private var panelHistory by mutableStateOf(PanelHistoryState())
    private var showPanelHistory by mutableStateOf(false)
    private var showEvents by mutableStateOf(false)
    private var eventState by mutableStateOf(EventLogState())
    private var showFinder by mutableStateOf(false)
    private var showSetup by mutableStateOf(false)
    private var setupState by mutableStateOf(SetupState())
    private val setupAssistant = SetupAssistant()
    /** Counts up on restart, close and stop. A callback of an older session does nothing. */
    private var setupSession = 0
    private var showChime by mutableStateOf(false)
    private var showSlots by mutableStateOf(false)
    private var showAlertLimits by mutableStateOf(false)
    private var showRejects by mutableStateOf(false)
    private var clockState by mutableStateOf(ClockState())
    private var rtcTrimState by mutableStateOf(RtcTrimState())
    private var versionState by mutableStateOf(VersionState())
    private var selfTestState by mutableStateOf(SelfTestRunState())
    private var rejectsState by mutableStateOf(RejectsState())
    /** The last good `REJECTS` reply; stays after the rejects screen closes. */
    private var lastRejects: RejectsLog? = null
    private var slotState by mutableStateOf(SlotConfigState())
    private var alertLimitState by mutableStateOf(AlertLimitState())
    private var chimeState by mutableStateOf(ChimeState())
    private var finderState by mutableStateOf(SensorFinderState())
    private val finderList = SensorFinderList()
    private var finderScanner: SensorFinderScanner? = null
    private val finderSeries = SignalSeries { android.os.SystemClock.elapsedRealtime() }
    private var finderTicker: Job? = null
    private var weekState by mutableStateOf(WeekState())
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
        locationStore = OutdoorLocationStore(
            SharedLocationPrefs(getSharedPreferences(OutdoorLocationStore.PREFS_NAME, Context.MODE_PRIVATE))
        )
        outdoorLocation = locationStore.load()
        outdoorSource = outdoorLocation.toSource()
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

            // Check for wall humidity notifications on each new fresh telemetry frame
            LaunchedEffect(snapshot) {
                val snap = snapshot ?: return@LaunchedEffect
                if (!OutdoorResolver.isFrameFresh(snap.receivedAtMillis, System.currentTimeMillis())) return@LaunchedEffect
                checkWallNotifications(snap.frame)
            }

            if (showHistory) {
                val history by BleScanService.history.collectAsState()
                HistoryScreen(
                    history = history,
                    panelConfig = panelConfig,
                    nowMillis = nowMillis,
                    panel = panelHistory,
                    showPanel = showPanelHistory,
                    onReadPanel = { withConsolePermission { readPanelHistory(null) } },
                    onReadSensor = { sensor -> withConsolePermission { readPanelHistory(sensor) } },
                    onLocal = { showPanelHistory = false },
                    onShare = { shareHistoryCsv(history) },
                    onBack = { closePanelHistory() }
                )
                BackHandler { closePanelHistory() }
                return@setContent
            }
            if (showEvents) {
                EventLogScreen(
                    state = eventState,
                    onRead = { withConsolePermission { readEvents() } },
                    onShare = { shareEventsCsv(eventState.rows) },
                    onBack = { closeEvents() }
                )
                BackHandler { closeEvents() }
                return@setContent
            }
            if (showWeek) {
                WeekScreen(
                    state = weekState,
                    panelConfig = panelConfig,
                    onRead = { withConsolePermission { readWeek() } },
                    onShare = { shareWeekCsv(weekState.rows) },
                    onBack = { closeWeek() }
                )
                BackHandler { closeWeek() }
                return@setContent
            }
            if (showBackup) {
                ConfigBackupScreen(
                    state = backupState,
                    hasConfig = configState.info != null,
                    consoleWritable = panelConfig.canWrite,
                    onSave = { withConsolePermission { saveBackup() } },
                    onPickFile = { pickBackupFile() },
                    onRestore = { withConsolePermission { applyBackup() } },
                    onBack = { closeBackup() }
                )
                BackHandler { closeBackup() }
                return@setContent
            }
            if (showCalib) {
                CalibrationScreen(
                    state = calibState,
                    currentConfig = panelConfig.values,
                    sensors = CalibrationSensors.selectable(healthState.rows, calibState.reference),
                    frsiSensors = (CalibrationSensors.selectable(healthState.rows, calibState.frsiRoom) +
                        CalibrationSensors.selectable(healthState.rows, calibState.frsiWall)).distinct().sorted(),
                    onPickReference = { calibState = calibState.copy(reference = it, reply = null, selected = emptySet(), message = null, isError = false, naCl = null, mgCl2 = null) },
                    onRead = { withConsolePermission { readCalibration() } },
                    onToggle = { key ->
                        calibState = calibState.copy(
                            selected = if (key in calibState.selected) calibState.selected - key else calibState.selected + key
                        )
                    },
                    onApply = { withConsolePermission { applyCalibration() } },
                    onSalt = { magnesium -> withConsolePermission { readSalt(magnesium) } },
                    onApplyTwoPoint = { withConsolePermission { applyTwoPoint() } },
                    onPickFrsiRoom = { calibState = calibState.copy(frsiRoom = it, frsi = null, message = null, isError = false) },
                    onPickFrsiWall = { calibState = calibState.copy(frsiWall = it, frsi = null, message = null, isError = false) },
                    onFrsi = { withConsolePermission { readFrsi() } },
                    onApplyFrsi = { withConsolePermission { applyFrsi() } },
                    onBack = { closeCalibration() }
                )
                BackHandler { closeCalibration() }
                return@setContent
            }
            if (showSetup) {
                SetupAssistantScreen(
                    state = setupState,
                    onScan = { toggleSetupScan() },
                    onUseScan = { useSetupScan() },
                    onChoose = { mac, name -> chooseSetupSensor(mac, name) },
                    onAssign = { withConsolePermission { setupAssign() } },
                    onCheck = { withConsolePermission { setupSend { setupAssistant.checkReception() } } },
                    onSalt = { withConsolePermission { setupSend { setupAssistant.beginCalibrate() } } },
                    onApply = { withConsolePermission { setupSend { setupAssistant.beginApply() } } },
                    onFinish = { setupAssistant.finish(); refreshSetup() },
                    onRestart = { restartSetup() },
                    onBack = { closeSetup() }
                )
                BackHandler { closeSetup() }
                return@setContent
            }
            if (showFinder) {
                SensorFinderScreen(
                    state = finderState,
                    panelConfig = panelConfig,
                    onScan = { startFinderScan() },
                    onStopScan = { stopFinderScan() },
                    onAssign = { slot, mac, name -> withConsolePermission { assignSensor(slot, mac, name) } },
                    onBack = { closeFinder() }
                )
                BackHandler { closeFinder() }
                return@setContent
            }
            if (showRejects) {
                RejectsScreen(
                    state = rejectsState,
                    onRead = { withConsolePermission { readRejects() } },
                    onBack = { closeRejects() }
                )
                BackHandler { closeRejects() }
                return@setContent
            }
            if (showAlertLimits) {
                AlertLimitScreen(
                    state = alertLimitState,
                    panelConfig = panelConfig,
                    onRead = { withConsolePermission { applyAlertLimits(emptyList()) } },
                    onApply = { commands -> withConsolePermission { applyAlertLimits(commands) } },
                    onBack = { closeAlertLimits() }
                )
                BackHandler { closeAlertLimits() }
                return@setContent
            }
            if (showSlots) {
                SlotConfigScreen(
                    state = slotState,
                    panelConfig = panelConfig,
                    formats = sourceBadges,
                    onApply = { commands -> withConsolePermission { applySlotCommands(commands) } },
                    onSwap = { command -> withConsolePermission { swapSlots(command) } },
                    onBack = { closeSlots() }
                )
                BackHandler { closeSlots() }
                return@setContent
            }
            if (showChime) {
                ChimeScreen(
                    state = chimeState,
                    panelConfig = panelConfig,
                    onRead = { withConsolePermission { readChimeState() } },
                    onMute = { on -> withConsolePermission { setMute(on) } },
                    onTest = { bit -> withConsolePermission { testChime(bit) } },
                    onAckAll = { withConsolePermission { acknowledgeAll() } },
                    onSnooze = { minutes -> withConsolePermission { snoozeChime(minutes) } },
                    onBack = { closeChime() }
                )
                BackHandler { closeChime() }
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
                    onOpenBackup = { showHealth = false; showBackup = true },
                    onOpenFinder = { showHealth = false; showFinder = true },
                    onOpenChime = { showHealth = false; showChime = true },
                    onOpenSlots = { showHealth = false; showSlots = true },
                    onOpenAlertLimits = { showHealth = false; showAlertLimits = true },
                    onOpenRejects = { showHealth = false; showRejects = true },
                    clockState = clockState,
                    versionState = versionState,
                    onReadVersion = { ensureConsolePermissionAndRead(CONSOLE_VERSION) },
                    onReadClock = { ensureConsolePermissionAndRead(CONSOLE_TIME) },
                    onSyncClock = { ensureConsolePermissionAndRead(CONSOLE_TIME_SET) },
                    rtcTrimState = rtcTrimState,
                    onTrimRtc = { ensureConsolePermissionAndRead(CONSOLE_RTC_TRIM) },
                    onShareReport = { shareDiagnosticsReport() },
                    selfTestState = selfTestState,
                    onRunSelfTest = { ensureConsolePermissionAndRead(CONSOLE_SELFTEST) },
                    onBack = { closeSensorHealth() }
                )
                BackHandler { closeSensorHealth() }
                return@setContent
            }
            // Determine effective outdoor climate
            val effectiveOutdoor: OutdoorClimate? = OutdoorResolver.resolve(
                source = outdoorSource,
                openMeteo = OutdoorResolver.cacheFor(openMeteoClimate, openMeteoLocation, outdoorLocation),
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
                onOpenWeek = { showWeek = true },
                onOpenEvents = { showEvents = true },
                onOpenSetup = { restartSetup(); showSetup = true },
                sourceBadges = sourceBadges,
                outdoorLocation = outdoorLocation,
                showLocationDialog = showLocationDialog,
                onOpenLocation = { showLocationDialog = true },
                onDismissLocation = { showLocationDialog = false },
                onSaveLocation = { lat, lon, name -> saveOutdoorLocation(lat, lon, name) }
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
        stopFinderScan(stopFromScreen = true)
        if (showSetup) onSetupStop(consoleWasRunning)
        if (finderState.isBusy) {
            finderState = finderState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, CONFIG lesen")
        }
        healthState = healthState.copy(isReading = false)
        configState = configState.copy(isReading = false)
        if (weekState.isReading) weekState = WeekState(message = "Abgebrochen. Erneut lesen.")
        if (panelHistory.isReading) panelHistory = PanelHistoryState(message = "Abgebrochen. Erneut lesen.")
        if (eventState.isReading) eventState = EventLogState(message = "Abgebrochen. Erneut lesen.")
        if (rejectsState.isReading) rejectsState = RejectsState(message = "Abgebrochen. Erneut lesen.")
        if (versionState.isBusy) versionState = VersionState(message = "Abgebrochen. Erneut lesen.")
        if (selfTestState.isBusy) selfTestState = SelfTestRunState(message = "Abgebrochen. Erneut starten.")
        if (clockState.isBusy) clockState = ClockState(message = "Abgebrochen. Zustand der Panel-Uhr unbekannt, Read clock tippen")
        if (chimeState.isBusy) chimeState = chimeState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, STATUS lesen")
        if (alertLimitState.isBusy) {
            alertLimitState = alertLimitState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, CONFIG lesen")
        }
        if (backupState.isBusy) {
            backupState = backupState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, CONFIG lesen")
        }
        if (calibState.isBusy) {
            calibState = calibState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, CONFIG lesen")
        }
        // Background scanning continues in BleScanService.
        lastObservedAktionen.clear()
        super.onStop()
    }

    private val backupFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadBackupFile(uri)
    }

    private val consolePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingConsoleAction
        if (action != null) {
            pendingConsoleAction = null
            if (grants.isNotEmpty() && grants.values.all { it }) action()
            else if (showEvents) eventState = EventLogState(message = "Bluetooth-Berechtigung fehlt")
            else if (showRejects) rejectsState = RejectsState(message = "Bluetooth-Berechtigung fehlt")
            else if (showHistory) panelHistory = PanelHistoryState(message = "Bluetooth-Berechtigung fehlt")
            else if (showWeek) weekState = WeekState(message = "Bluetooth-Berechtigung fehlt")
            else if (showSetup) setupState = setupState.copy(isBusy = false, message = "Bluetooth-Berechtigung fehlt")
            else if (showFinder) finderState = finderState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else if (showAlertLimits) alertLimitState = alertLimitState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else if (showSlots) slotState = slotState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else if (showChime) chimeState = chimeState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else if (showBackup) backupState = backupState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else calibState = calibState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            return@registerForActivityResult
        }
        if (!showHealth) return@registerForActivityResult
        val command = pendingConsoleCommand
        if (grants.values.all { it }) {
            runConsoleCommand(command)
        } else if (command == CONSOLE_VERSION) {
            versionState = VersionState(message = "Bluetooth-Berechtigung fehlt")
        } else if (command == CONSOLE_SELFTEST) {
            selfTestState = SelfTestRunState(message = "Bluetooth-Berechtigung fehlt")
        } else if (command == CONSOLE_RTC_TRIM) {
            rtcTrimState = RtcTrimState(message = "Bluetooth-Berechtigung fehlt")
        } else if (command == CONSOLE_TIME || command == CONSOLE_TIME_SET) {
            clockState = ClockState(message = "Bluetooth-Berechtigung fehlt")
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

    /** Sends the diagnostics report as text. Names and MACs stay hidden; the report holds no Wi-Fi data. */
    private fun shareDiagnosticsReport() {
        val text = DiagnosticsReport.build(versionState.reply, healthState.status, lastRejects, selfTest = selfTestState.reply)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Bericht teilen"))
    }

    /** Sends the event rows as CSV text, oldest first. */
    private fun shareEventsCsv(rows: List<EventRow>) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, EventCsv.format(rows))
        }
        startActivity(Intent.createChooser(send, "Share CSV"))
    }

    /** Sends the seven-day rows as CSV text. */
    private fun shareWeekCsv(rows: List<WeekRow>) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_TEXT, WeekCsv.format(rows, panelConfig))
        }
        startActivity(Intent.createChooser(send, "Share CSV"))
    }

    private fun closeEvents() {
        consoleClient.cancel()
        pendingConsoleAction = null
        eventState = EventLogState()
        showEvents = false
        if (!scanPermissionDenied && !BleScanService.isScanning.value) ensurePermissionAndStart()
    }

    /** Sends `EVENTS` after a tap. A failed CRC-32 or a timeout gives one error text; the command is not repeated. */
    private fun readEvents() {
        stopBleScan()
        eventState = EventLogState(isReading = true)
        consoleClient.send("EVENTS") { reply ->
            eventState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val log = EventsReply.parse(reply.lines)
                    EventLogState(rows = log.rows, dropped = log.dropped, loaded = true)
                } catch (e: ParseException) {
                    EventLogState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                else -> EventLogState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closePanelHistory() {
        consoleClient.cancel()
        pendingConsoleAction = null
        panelHistory = PanelHistoryState()
        showPanelHistory = false
        showHistory = false
        if (!scanPermissionDenied && !BleScanService.isScanning.value) ensurePermissionAndStart()
    }

    /** Sends `HISTORY <N>` for one [sensor], `HISTORY` for `null`, after a tap. A failed read keeps the local curves. */
    private fun readPanelHistory(sensor: Int?) {
        stopBleScan()
        panelHistory = PanelHistoryState(isReading = true)
        showPanelHistory = false
        consoleClient.send(HistoryCommand.line(sensor)) { reply ->
            // An older firmware answers `HISTORY <N>` with `ERR`. Send `HISTORY` once and keep the rows of the sensor.
            if (reply is ConsoleReply.Status && HistoryCommand.shouldFallBack(sensor, reply.line)) {
                consoleClient.send(HistoryCommand.line(null)) { full -> finishPanelHistory(full, sensor) }
            } else {
                finishPanelHistory(reply, sensor)
            }
        }
    }

    private fun finishPanelHistory(reply: ConsoleReply, sensor: Int?) {
        val readAt = System.currentTimeMillis()
        when (reply) {
            is ConsoleReply.Frame -> try {
                val parsed = HistoryReply.parse(reply.lines)
                val rows = if (sensor == null) parsed else HistoryCommand.filter(parsed, sensor)
                if (sensor != null && PanelHistoryChart.sensors(rows).isEmpty()) {
                    panelHistory = PanelHistoryState(message = "Keine Werte für diesen Raum")
                } else {
                    panelHistory = PanelHistoryState(rows = rows, readAtMillis = readAt, loaded = true)
                    showPanelHistory = true
                }
            } catch (e: ParseException) {
                panelHistory = PanelHistoryState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
            }
            else -> panelHistory = PanelHistoryState(message = BleConsoleClient.errorText(reply))
        }
    }

    private fun closeWeek() {
        consoleClient.cancel()
        pendingConsoleAction = null
        weekState = WeekState()
        showWeek = false
        if (!scanPermissionDenied && !BleScanService.isScanning.value) ensurePermissionAndStart()
    }

    /** Sends `WEEK` after a tap. */
    private fun readWeek() {
        stopBleScan()
        weekState = WeekState(isReading = true)
        consoleClient.send("WEEK") { reply ->
            weekState = when (reply) {
                is ConsoleReply.Frame -> try {
                    WeekState(rows = WeekReply.parse(reply.lines))
                } catch (e: ParseException) {
                    WeekState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                else -> WeekState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closeFinder() {
        stopFinderScan()
        consoleClient.cancel()
        pendingConsoleAction = null
        finderList.clear()
        finderState = SensorFinderState()
        showFinder = false
        showHealth = true
    }

    private fun hasFinderScanPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(this, needed) == PackageManager.PERMISSION_GRANTED
    }

    /** Publishes the series statistics and the stop reason to the finder screen. */
    private fun publishFinderSeries() {
        finderState = finderState.copy(
            series = finderSeries.stats().associateBy { it.mac },
            seriesStop = finderSeries.stopReason
        )
    }

    /** Stops the series with [reason] and keeps its values on the screen. */
    private fun stopFinderSeries(reason: SignalSeries.StopReason) {
        finderTicker?.cancel()
        finderTicker = null
        finderSeries.stop(reason)
        if (showFinder) publishFinderSeries()
    }

    /** Closes elapsed windows each second and stops the series when the scan permission is lost. */
    private fun startFinderTicker() {
        finderTicker?.cancel()
        finderTicker = lifecycleScope.launch {
            while (finderSeries.isRunning) {
                delay(1000)
                if (!hasFinderScanPermission()) {
                    finderScanner?.stop()
                    finderState = finderState.copy(isScanning = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
                    stopFinderSeries(SignalSeries.StopReason.PERMISSION_LOST)
                    return@launch
                }
                finderSeries.advance()
                publishFinderSeries()
            }
        }
    }

    /** Starts the finder scan after a tap; the background scan of the telemetry pauses meanwhile. */
    private fun startFinderScan() {
        if (!hasFinderScanPermission()) {
            finderState = finderState.copy(isError = true, message = "Bluetooth-Berechtigung fehlt. Erst Sensor-Status öffnen und erlauben.")
            return
        }
        stopBleScan()
        finderList.clear()
        finderSeries.start()
        val scanner = finderScanner ?: SensorFinderScanner(
            this,
            onResult = { ad, rssi ->
                // A result that arrives after stop or close must not refill the list.
                if (showFinder && finderState.isScanning) {
                    finderList.add(ad, rssi, System.currentTimeMillis())
                    finderSeries.record(ad.mac, rssi)
                    finderState = finderState.copy(rows = finderList.sorted())
                }
            },
            onError = { text ->
                finderState = finderState.copy(isScanning = false, isError = true, message = text)
                stopFinderSeries(
                    if (!hasFinderScanPermission()) SignalSeries.StopReason.PERMISSION_LOST
                    else SignalSeries.StopReason.SCAN_FAILED
                )
            }
        ).also { finderScanner = it }
        val started = scanner.start()
        finderState = SensorFinderState(isScanning = started, message = if (started) null else finderState.message, isError = !started)
        if (started) startFinderTicker() else stopFinderSeries(SignalSeries.StopReason.SCAN_FAILED)
    }

    /** Stops the finder scan. Called from the button, `closeFinder` and `onStop`. */
    private fun stopFinderScan(stopFromScreen: Boolean = false) {
        finderScanner?.stop()
        stopFinderSeries(if (stopFromScreen) SignalSeries.StopReason.SCREEN_STOPPED else SignalSeries.StopReason.USER)
        if (finderState.isScanning) finderState = finderState.copy(isScanning = false)
    }

    /** Sends `SET slotN_mac`, `SET slotN_name` and `CONFIG` after a tap. The first `ERR` stops the batch. */
    private fun assignSensor(slot: Int, mac: String, name: String) {
        val commands = SlotAssignment.batch(slot, mac, name)
        if (commands == null || SlotAssignment.warning(slot, mac, panelConfig) != null) {
            finderState = finderState.copy(isBusy = false, isError = true, message = "Eingabe ungültig oder MAC doppelt")
            return
        }
        stopFinderScan()
        stopBleScan()
        finderState = finderState.copy(isBusy = true, isError = false, message = null)
        SlotAssignment.run(commands, consoleClient::send) { result ->
            result.config?.let { frame ->
                lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, frame, System.currentTimeMillis()) }
                    if (info != null) {
                        panelConfig = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                        configState = ConfigReadState(info = info)
                    }
                }
            }
            finderState = finderState.copy(isBusy = false, isError = !result.ok, message = result.summary)
        }
    }

    // MARK: Setup assistant

    private fun refreshSetup(message: String? = setupState.message, busy: Boolean = false) {
        val a = setupAssistant
        setupState = SetupState(
            step = a.step,
            stopText = a.stop?.text,
            notice = a.notice,
            candidates = a.candidates(panelConfig),
            chosenMac = a.chosenMac,
            slot = a.slot,
            verifyTries = a.verifyTries,
            proposalText = a.proposal?.let { "${it.key} = ${it.value}" },
            isScanning = setupState.isScanning,
            isBusy = busy,
            message = message
        )
    }

    private fun restartSetup() {
        setupSession++
        stopFinderScan()
        consoleClient.cancel()
        pendingConsoleAction = null
        setupAssistant.restart()
        finderList.clear()
        setupState = SetupState()
    }

    private fun closeSetup() {
        restartSetup()
        showSetup = false
    }

    /** `onStop` ends a running console command and drops each callback of the old session. The assistant stops. */
    private fun onSetupStop(consoleWasRunning: Boolean) {
        setupSession++
        setupState = setupState.copy(isScanning = false, isBusy = false)
        if (consoleWasRunning || setupAssistant.pending != null) {
            setupAssistant.linkLost("App im Hintergrund")
        }
        refreshSetup()
    }

    private fun toggleSetupScan() {
        if (setupState.isScanning) {
            finderScanner?.stop()
            setupState = setupState.copy(isScanning = false)
            return
        }
        showFinderForScan()
    }

    /** Starts the phone scan for the assistant. The rows land in [finderList]; the user takes them with a tap. */
    private fun showFinderForScan() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            setupState = setupState.copy(message = "Bluetooth-Berechtigung fehlt. Erst Sensor-Status öffnen und erlauben.")
            return
        }
        stopBleScan()
        finderList.clear()
        val scanner = finderScanner ?: SensorFinderScanner(
            this,
            onResult = { ad, rssi ->
                if (showFinder && finderState.isScanning) {
                    finderList.add(ad, rssi, System.currentTimeMillis())
                    finderState = finderState.copy(rows = finderList.sorted())
                } else if (showSetup && setupState.isScanning) {
                    finderList.add(ad, rssi, System.currentTimeMillis())
                }
            },
            onError = { text ->
                finderState = finderState.copy(isScanning = false, isError = true, message = text)
                setupState = setupState.copy(isScanning = false, message = text)
            }
        ).also { finderScanner = it }
        val started = scanner.start()
        setupState = setupState.copy(isScanning = started, message = if (started) "Suche läuft…" else setupState.message)
    }

    private fun useSetupScan() {
        finderScanner?.stop()
        setupState = setupState.copy(isScanning = false)
        setupAssistant.scanned(finderList.sorted(), panelConfig)
        refreshSetup(message = null)
    }

    private fun chooseSetupSensor(mac: String, name: String) {
        setupAssistant.choose(mac, name, panelConfig)
        refreshSetup(message = null)
    }

    /** Sends [command] for the current session. A busy console ends the assistant with a stop text. */
    private fun setupConsole(command: String, session: Int, onReply: (ConsoleReply) -> Unit) {
        if (consoleClient.isRunning) {
            setupAssistant.commandNotSent()
            refreshSetup()
            return
        }
        consoleClient.send(command) { reply -> if (session == setupSession) onReply(reply) }
    }

    /** One tap: read `STATUS` and `CONFIG` (read only), then the assistant gives the first `SET`. */
    private fun setupAssign() {
        stopBleScan()
        stopFinderScan()
        val session = setupSession
        refreshSetup(message = null, busy = true)
        setupConsole(CONSOLE_STATUS, session) { statusReply ->
            val status = (statusReply as? ConsoleReply.Frame)?.let {
                try { StatusReply.parse(it.lines) } catch (_: ParseException) { null }
            }
            if (status == null) {
                setupAssistant.failure(statusReply)
                refreshSetup()
                return@setupConsole
            }
            setupConsole(CONSOLE_CONFIG, session) { configReply ->
                lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, configReply, System.currentTimeMillis()) }
                    if (session != setupSession) return@launch
                    if (info == null) {
                        setupAssistant.failure(configReply)
                        refreshSetup()
                        return@launch
                    }
                    val loaded = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                    if (session != setupSession) return@launch
                    panelConfig = loaded
                    configState = ConfigReadState(info = info)
                    setupSend { setupAssistant.beginAssign(status, panelConfig) }
                }
            }
        }
    }

    /** Sends the command that [next] gives, then each following command of the same tap. */
    private fun setupSend(next: () -> String?) {
        stopBleScan()
        stopFinderScan()
        val session = setupSession
        val command = next()
        if (command == null) { refreshSetup(); return }
        refreshSetup(message = null, busy = true)
        setupConsole(command, session) { reply ->
            setupAssistant.receive(reply)
            if (command == CONSOLE_CONFIG && reply is ConsoleReply.Frame) {
                lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, reply, System.currentTimeMillis()) }
                    if (session != setupSession) return@launch
                    if (info != null) {
                        val loaded = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                        if (session != setupSession) return@launch
                        panelConfig = loaded
                        configState = ConfigReadState(info = info)
                    }
                    setupSend { setupAssistant.nextCommand() }
                }
            } else {
                setupSend { setupAssistant.nextCommand() }
            }
        }
    }

    private fun closeRejects() {
        consoleClient.cancel()
        pendingConsoleAction = null
        rejectsState = RejectsState()
        showRejects = false
        showHealth = true
    }

    /** Sends `REJECTS` after a tap. A failed CRC-32 or a timeout gives one error text; the command is not repeated. */
    private fun readRejects() {
        stopBleScan()
        lastRejects = null
        rejectsState = RejectsState(isReading = true)
        consoleClient.send("REJECTS") { reply ->
            rejectsState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val log = RejectsReply.parse(reply.lines)
                    lastRejects = log
                    RejectsState(rows = log.rows, dropped = log.dropped, loaded = true)
                } catch (e: ParseException) {
                    RejectsState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                else -> RejectsState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    private fun closeSlots() {
        consoleClient.cancel()
        pendingConsoleAction = null
        slotState = SlotConfigState()
        showSlots = false
        showHealth = true
    }

    /** Sends the slot [commands] one by one, then `CONFIG`. The first `ERR` stops the batch. */
    private fun applySlotCommands(commands: List<String>) {
        stopBleScan()
        slotState = slotState.copy(isBusy = true, isError = false, readOnly = false, message = null)
        SlotAssignment.run(commands, consoleClient::send) { result ->
            result.config?.let { frame ->
                lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, frame, System.currentTimeMillis()) }
                    if (info != null) {
                        panelConfig = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                        configState = ConfigReadState(info = info)
                    } else if (showSlots) {
                        slotState = slotState.copy(isError = true, message = "Panel liefert keine SD-Einstellungen. Anzeige ist alt, CONFIG lesen")
                    }
                }
            }
            // A reply that arrives after closeSlots must not mark the reset screen as busy or failed.
            if (showSlots) {
                slotState = slotState.copy(isBusy = false, isError = !result.ok, readOnly = result.readOnly, message = result.summary)
            }
        }
    }

    /** Sends one `SLOT SWAP <A> <B>`, then `STATUS` after an `OK` reply. */
    private fun swapSlots(command: String) {
        stopBleScan()
        slotState = slotState.copy(isBusy = true, isError = false, readOnly = false, message = null)
        consoleClient.send(command) { reply ->
            val line = (reply as? ConsoleReply.Status)?.line
            if (line != null && SlotConfig.swapSucceeded(line)) {
                consoleClient.send(CONSOLE_STATUS) { status ->
                    // A reply after closeSlots must not overwrite the state of another screen.
                    if (showSlots) {
                        applyStatusReply(status)
                        val note = if (status is ConsoleReply.Frame) "" else ". STATUS lesen fehlgeschlagen"
                        slotState = slotState.copy(isBusy = false, isError = false, message = SlotConfig.swapResultText(line) + note)
                    }
                }
            } else if (showSlots) {
                val text = line?.let { SlotConfig.swapResultText(it) } ?: SlotAssignment.failureText(reply)
                slotState = slotState.copy(isBusy = false, isError = true, readOnly = line?.startsWith("ERR READ_ONLY") == true, message = text)
            }
        }
    }

    private fun closeAlertLimits() {
        consoleClient.cancel()
        pendingConsoleAction = null
        alertLimitState = AlertLimitState()
        showAlertLimits = false
        showHealth = true
    }

    /** Sends the alert limit [commands] one by one, then `CONFIG`; no command only reads `CONFIG`. The first `ERR` stops the batch. */
    private fun applyAlertLimits(commands: List<String>) {
        stopBleScan()
        alertLimitState = alertLimitState.copy(isBusy = true, isError = false, message = null)
        AlertLimits.run(commands, consoleClient::send) { result ->
            result.config?.let { frame ->
                lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, frame, System.currentTimeMillis()) }
                    if (info != null) {
                        panelConfig = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                        configState = ConfigReadState(info = info)
                    } else if (showAlertLimits) {
                        alertLimitState = alertLimitState.copy(isError = true, message = "Panel liefert keine SD-Einstellungen. Anzeige ist alt, CONFIG lesen")
                    }
                }
            }
            // A reply that arrives after closeAlertLimits must not mark the reset screen as busy or failed.
            if (showAlertLimits) {
                alertLimitState = alertLimitState.copy(isBusy = false, isError = !result.ok, message = result.summary)
            }
        }
    }

    private fun closeChime() {
        consoleClient.cancel()
        pendingConsoleAction = null
        chimeState = ChimeState()
        showChime = false
        showHealth = true
    }

    /** Sends `STATUS` after a tap and shows the mute state of the `POWER` line. */
    private fun readChimeState() {
        stopBleScan()
        chimeState = chimeState.copy(isBusy = true, isError = false, message = null)
        consoleClient.send(ChimeCommand.STATUS) { reply -> chimeState = chimeStateAfterStatus(reply, null) }
    }

    /** Sends `MUTE <0|1>`, then `STATUS` to confirm the state. */
    private fun setMute(on: Boolean) {
        stopBleScan()
        chimeState = chimeState.copy(isBusy = true, isError = false, message = null)
        consoleClient.send(ChimeCommand.mute(on)) { reply ->
            val failure = ChimeCommand.failureText(reply) { BleConsoleClient.errorText(it) }
            if (failure != null) {
                chimeState = chimeState.copy(isBusy = false, isError = true, message = failure)
            } else {
                consoleClient.send(ChimeCommand.STATUS) { status -> chimeState = chimeStateAfterStatus(status, on) }
            }
        }
    }

    private fun chimeStateAfterStatus(reply: ConsoleReply, expected: Boolean?): ChimeState {
        if (reply !is ConsoleReply.Frame) {
            return chimeState.copy(isBusy = false, isError = true, reminder = null, alertMask = null, ackedMask = null, snoozeSeconds = null, message = BleConsoleClient.errorText(reply))
        }
        return try {
            val status = StatusReply.parse(reply.lines)
            chimeState.copy(
                isBusy = false, isError = false, muted = status.muted ?: expected,
                reminder = status.chime?.let { ChimeText.reminder(it) },
                alertMask = status.alertMask, ackedMask = status.ackedMask, snoozeSeconds = status.chime?.snoozeSeconds,
                message = if (expected != null) "Stumm ${if (expected) "an" else "aus"}" else null
            )
        } catch (e: ParseException) {
            chimeState.copy(isBusy = false, isError = true, reminder = null, alertMask = null, ackedMask = null, snoozeSeconds = null, message = "Antwort nicht lesbar: ${e.line.take(80)}")
        }
    }

    /** Sends `SNOOZE <M>` after a tap, then `STATUS`; the result text stays visible after the read. */
    private fun snoozeChime(minutes: Int) {
        stopBleScan()
        chimeState = chimeState.copy(isBusy = true, isError = false, message = null)
        consoleClient.send(ChimeCommand.snooze(minutes)) { reply ->
            val failure = ChimeCommand.failureText(reply) { BleConsoleClient.errorText(it) }
            if (failure != null) {
                chimeState = chimeState.copy(isBusy = false, isError = true, message = failure)
            } else {
                val text = ChimeCommand.okText((reply as ConsoleReply.Status).line)
                consoleClient.send(ChimeCommand.STATUS) { status ->
                    val next = chimeStateAfterStatus(status, null)
                    chimeState = if (!next.isError) next.copy(message = text) else next.copy(message = "$text; ${next.message}")
                }
            }
        }
    }

    /** Sends `ACK ALL` after a tap, then `STATUS`; the result text stays visible after the read. */
    private fun acknowledgeAll() {
        stopBleScan()
        chimeState = chimeState.copy(isBusy = true, isError = false, message = null)
        consoleClient.send(AckCommand.ALL) { reply ->
            val text = AckCommand.resultText(reply)
            val ok = (reply as? ConsoleReply.Status)?.line?.startsWith("OK ACK") == true
            consoleClient.send(ChimeCommand.STATUS) { status ->
                val next = chimeStateAfterStatus(status, null)
                chimeState = if (!next.isError) next.copy(isError = !ok, message = text)
                else next.copy(message = "$text; ${next.message}")
            }
        }
    }

    /** Sends `BEEP <bit>` after a tap. */
    private fun testChime(bit: Int) {
        stopBleScan()
        chimeState = chimeState.copy(isBusy = true, isError = false, message = null)
        consoleClient.send(ChimeCommand.beep(bit)) { reply ->
            val failure = ChimeCommand.failureText(reply) { BleConsoleClient.errorText(it) }
            chimeState = chimeState.copy(
                isBusy = false, isError = failure != null,
                message = failure ?: ChimeCommand.okText((reply as ConsoleReply.Status).line)
            )
        }
    }

    private fun closeBackup() {
        backupLoadJob?.cancel()
        consoleClient.cancel()
        pendingConsoleAction = null
        backupState = ConfigBackupState()
        showBackup = false
        showHealth = true
    }

    /** Sends `CONFIG` after a tap and shares the values as a `config.ini` text. */
    private fun saveBackup() {
        backupLoadJob?.cancel()
        stopBleScan()
        backupState = backupState.copy(isBusy = true, isError = false, message = null)
        consoleClient.send("CONFIG") { reply ->
            if (reply !is ConsoleReply.Frame) {
                backupState = backupState.copy(isBusy = false, isError = true, message = BleConsoleClient.errorText(reply))
                return@send
            }
            val parsed = PanelConfig.parseConfigReply(reply.lines)
            if (parsed.text.isEmpty()) {
                backupState = backupState.copy(isBusy = false, isError = true, message = "Antwort enthält keine Werte")
                return@send
            }
            val text = ConfigBackup.render(PanelConfig.parseText(parsed.text).values)
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, reply, System.currentTimeMillis()) }
                if (info != null) {
                    panelConfig = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                    configState = ConfigReadState(info = info)
                }
            }
            val note = if (parsed.source == "SD") null else "Das Panel nutzt Standardwerte (keine Karte). Die Datei hält diese."
            // The saved read replaces the cached values, so a loaded review is stale.
            backupState = backupState.copy(isBusy = false, isError = false, message = note, plan = null, fileName = null, needsRead = false)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, ConfigBackup.FILE_NAME)
                putExtra(Intent.EXTRA_TITLE, ConfigBackup.FILE_NAME)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, "config.ini sichern"))
        }
    }

    private fun pickBackupFile() {
        if (backupState.isBusy) return
        if (configState.info == null || backupState.needsRead) {
            backupState = backupState.copy(isError = true, message = "Erst CONFIG lesen (Save backup oder Read panel config)")
            return
        }
        backupFileLauncher.launch(arrayOf("*/*"))
    }

    /** Reads the picked file (8192 bytes at most) and opens the restore review. Nothing is sent yet. */
    private fun loadBackupFile(uri: android.net.Uri) {
        if (backupState.isBusy) return
        backupLoadJob?.cancel()
        val current = panelConfig.values // read on the main thread
        backupLoadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readCapped(ConfigBackup.MAX_BYTES + 1) }
                        ?: return@withContext Result.failure<ConfigRestorePlan>(IllegalStateException("Datei nicht lesbar"))
                    Result.success(ConfigRestorePlan.build(ConfigBackup.parse(bytes), current))
                } catch (e: ConfigBackupException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(IllegalStateException("Datei nicht lesbar"))
                }
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')
            backupState = result.fold(
                onSuccess = { backupState.copy(plan = it, fileName = name, isError = false, message = null) },
                onFailure = {
                    val text = (it as? ConfigBackupException)?.reason ?: it.message ?: "Datei nicht lesbar"
                    backupState.copy(plan = null, fileName = null, isError = true, message = "Backup abgelehnt: $text")
                }
            )
        }
    }

    /** Sends the changed keys as `SET` in batch order (`ble_console` last), then `CONFIG`. The first `ERR` stops it. */
    private fun applyBackup() {
        val plan = backupState.plan ?: return
        if (!plan.canRestore(panelConfig.canWrite)) return
        backupLoadJob?.cancel()
        stopBleScan()
        backupState = backupState.copy(isBusy = true, isError = false, message = null)
        CalibrationBatch(plan.proposals).run(consoleClient::send) { result ->
            result.config?.let { frame ->
                lifecycleScope.launch {
                    val info = withContext(Dispatchers.IO) { ConfigCache.store(filesDir, frame, System.currentTimeMillis()) }
                    if (info != null) {
                        panelConfig = withContext(Dispatchers.IO) { PanelConfig.load(this@MainActivity) }
                        configState = ConfigReadState(info = info)
                    }
                }
            }
            val ok = result.failure == null && !result.configFailed
            // The panel values changed or are unknown, so the old review is stale.
            backupState = backupState.copy(
                isBusy = false, isError = !ok, message = result.summary,
                plan = if (result.applied.isNotEmpty() || ok) null else backupState.plan,
                // A stop after applied keys leaves the cached values behind the panel until the next CONFIG read.
                needsRead = result.config == null && result.applied.isNotEmpty()
            )
        }
    }

    private fun closeCalibration() {
        consoleClient.cancel()
        pendingConsoleAction = null
        calibState = CalibrationState(reference = calibState.reference, frsiRoom = calibState.frsiRoom, frsiWall = calibState.frsiWall)
        showCalib = false
        showHealth = true
    }

    /** Runs [action] after the Bluetooth permissions; the action sends console commands. */
    private fun withConsolePermission(action: () -> Unit) {
        if (backupState.isBusy || calibState.isBusy || chimeState.isBusy || weekState.isReading || panelHistory.isReading || eventState.isReading || rejectsState.isReading || finderState.isBusy || setupState.isBusy || slotState.isBusy || alertLimitState.isBusy || consoleClient.isRunning) return
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingConsoleAction = action
            if (showEvents) eventState = EventLogState(isReading = true)
            else if (showRejects) rejectsState = RejectsState(isReading = true)
            else if (showHistory) panelHistory = PanelHistoryState(isReading = true)
            else if (showWeek) weekState = WeekState(isReading = true)
            else if (showSetup) setupState = setupState.copy(isBusy = true, message = null)
            else if (showFinder) finderState = finderState.copy(isBusy = true, message = null)
            else if (showAlertLimits) alertLimitState = alertLimitState.copy(isBusy = true, message = null)
            else if (showSlots) slotState = slotState.copy(isBusy = true, message = null)
            else if (showChime) chimeState = chimeState.copy(isBusy = true, message = null)
            else if (showBackup) backupState = backupState.copy(isBusy = true, message = null)
            else calibState = calibState.copy(isBusy = true, message = null)
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        action()
    }

    /** Sends `CALIB N` after a tap; the proposals that the key range rejects never reach the list. */
    private fun readCalibration() {
        if (calibState.reference !in 0..SaltCommand.MAX_SENSOR) return
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

    /** Sends `SALT N` or `SALT N MGCL2` after a tap. With both checks of one sensor, the two-point result follows. */
    private fun readSalt(magnesium: Boolean) {
        stopBleScan()
        val n = calibState.reference
        if (n !in 0..SaltCommand.MAX_SENSOR) return
        val line = SaltCommand.line(n, magnesium)
        calibState = calibState.copy(isBusy = true, isError = false, message = null)
        consoleClient.send(line) { reply ->
            calibState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val salt = SaltReply.parse(reply.lines)
                    val expected = if (n == 0) "onboard" else "slot$n"
                    val name = when (val o = salt.outcome) {
                        is SaltReply.Outcome.Measured -> o.measurement.name
                        is SaltReply.Outcome.Skipped -> o.name
                        is SaltReply.Outcome.Unstable -> o.name
                    }
                    if (name != expected) {
                        calibState.copy(isBusy = false, isError = true, message = "Antwort gehört zu $name, nicht zu $expected")
                    } else if (magnesium) {
                        calibState.copy(isBusy = false, mgCl2 = salt)
                    } else {
                        calibState.copy(isBusy = false, naCl = salt)
                    }
                } catch (e: ParseException) {
                    calibState.copy(isBusy = false, isError = true, message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                is ConsoleReply.Status -> calibState.copy(isBusy = false, isError = true, message = SaltText.status(reply.line))
                else -> calibState.copy(isBusy = false, isError = true, message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Sends `FRSI R W` after a tap. */
    private fun readFrsi() {
        val room = calibState.frsiRoom
        val wall = calibState.frsiWall
        if (room !in 0..SaltCommand.MAX_SENSOR || wall !in 1..SaltCommand.MAX_SENSOR || room == wall) return
        stopBleScan()
        calibState = calibState.copy(isBusy = true, isError = false, message = null, frsi = null)
        consoleClient.send(FrsiCommand.line(room, wall)) { reply ->
            calibState = when (reply) {
                is ConsoleReply.Frame -> try {
                    calibState.copy(isBusy = false, frsi = FrsiReply.parse(reply.lines))
                } catch (e: ParseException) {
                    calibState.copy(isBusy = false, isError = true, message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                is ConsoleReply.Status -> calibState.copy(isBusy = false, isError = true, message = FrsiText.status(reply.line))
                else -> calibState.copy(isBusy = false, isError = true, message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Sends `SET wall_frsi_pct=<N>`, then `CONFIG`. */
    private fun applyFrsi() {
        val proposal = calibState.frsi?.proposal ?: return
        runBatch(listOf(proposal), clearReply = false)
    }

    /** Sends the selected proposals as `SET`, then `CONFIG`. The first `ERR` stops the batch and skips `CONFIG`. */
    private fun applyCalibration() {
        runBatch(calibState.chosen, clearReply = true)
    }

    /** Sends `SET` for the gain, `SET` for the offset, then `CONFIG`. */
    private fun applyTwoPoint() {
        val current = panelConfig.values.takeIf { it.isNotEmpty() }
        val result = TwoPointCalibration.evaluate(calibState.naCl, calibState.mgCl2, current).result ?: return
        runBatch(result.proposals, clearReply = false)
    }

    private fun runBatch(chosen: List<CalibrationProposal>, clearReply: Boolean) {
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
            // The values changed on the panel, so the old proposals and salt checks are stale. A new read gives fresh ones.
            val ok = result.failure == null && !result.configFailed
            val changed = result.applied.isNotEmpty()
            calibState = calibState.copy(
                isBusy = false, message = result.summary, isError = !ok,
                reply = if (changed || (clearReply && result.failure == null)) null else calibState.reply,
                selected = if (changed || (clearReply && result.failure == null)) emptySet() else calibState.selected,
                naCl = if (changed) null else calibState.naCl,
                mgCl2 = if (changed) null else calibState.mgCl2,
                frsi = if (changed) null else calibState.frsi
            )
        }
    }

    private fun closeSensorHealth() {
        consoleClient.cancel()
        healthState = SensorHealthState()
        lastRejects = null
        clockState = ClockState()
        rtcTrimState = RtcTrimState()
        versionState = VersionState()
        selfTestState = SelfTestRunState()
        configState = configState.copy(isReading = false, message = null)
        showHealth = false
        if (!scanPermissionDenied && !BleScanService.isScanning.value) ensurePermissionAndStart()
    }

    /** Sends [command] (`STATUS` or `CONFIG`) only after a tap on a button. */
    private fun ensureConsolePermissionAndRead(command: String) {
        if (healthState.isReading || configState.isReading || clockState.isBusy || rtcTrimState.isBusy || versionState.isBusy || selfTestState.isBusy || consoleClient.isRunning) return
        pendingConsoleCommand = command
        if (command == CONSOLE_STATUS) rtcTrimState = RtcTrimState()
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
        if (command == CONSOLE_VERSION) {
            versionState = VersionState(isBusy = true)
        } else if (command == CONSOLE_SELFTEST) {
            selfTestState = SelfTestRunState(isBusy = true)
        } else if (command == CONSOLE_RTC_TRIM) {
            rtcTrimState = RtcTrimState(isBusy = true)
        } else if (command == CONSOLE_TIME || command == CONSOLE_TIME_SET) {
            clockState = ClockState(isBusy = true)
        } else if (command == CONSOLE_CONFIG) {
            configState = configState.copy(isReading = true, message = null)
        } else {
            healthState = SensorHealthState(isReading = true)
        }
    }

    private fun runConsoleCommand(command: String) {
        when (command) {
            CONSOLE_CONFIG -> readPanelConfig()
            CONSOLE_VERSION -> readVersion()
            CONSOLE_SELFTEST -> runSelfTest()
            CONSOLE_RTC_TRIM -> trimRtc()
            CONSOLE_TIME -> readClock()
            CONSOLE_TIME_SET -> syncClock()
            else -> readSensorHealth()
        }
    }

    /** Sends `VERSION` after a tap. A failed command is not repeated. */
    private fun readVersion() {
        stopBleScan()
        versionState = VersionState(isBusy = true)
        consoleClient.send(CONSOLE_VERSION) { reply ->
            versionState = when (reply) {
                is ConsoleReply.Frame -> try {
                    VersionState(reply = VersionReply.parse(reply.lines))
                } catch (e: ParseException) {
                    VersionState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                else -> VersionState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Sends `SELFTEST` after a tap. A failed command is not repeated. */
    private fun runSelfTest() {
        stopBleScan()
        selfTestState = SelfTestRunState(isBusy = true)
        consoleClient.send(CONSOLE_SELFTEST) { reply ->
            selfTestState = when (reply) {
                is ConsoleReply.Frame -> try {
                    SelfTestRunState(reply = SelfTestReply.parse(reply.lines))
                } catch (e: ParseException) {
                    SelfTestRunState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                else -> SelfTestRunState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Sends `RTC TRIM` after a tap, then `STATUS` after an `OK`. A failed command is not repeated. */
    private fun trimRtc() {
        stopBleScan()
        rtcTrimState = RtcTrimState(isBusy = true)
        consoleClient.send(CONSOLE_RTC_TRIM) { reply ->
            if (reply is ConsoleReply.Status) {
                rtcTrimState = RtcTrimState(message = SensorHealthText.trimResult(reply.line))
                if (reply.line.startsWith("OK")) readSensorHealth()
            } else {
                rtcTrimState = RtcTrimState(message = BleConsoleClient.errorText(reply))
            }
        }
    }

    /** Sends `TIME` after a tap. A failed command is not repeated. */
    private fun readClock() {
        stopBleScan()
        clockState = ClockState(isBusy = true)
        consoleClient.send("TIME") { reply -> clockState = clockResult(reply) }
    }

    /** Sends `TIME SET <phone time>` after a tap, then `TIME`. A failed command is not repeated. */
    private fun syncClock() {
        val text = PanelClock.format(System.currentTimeMillis())
        if (text == null) {
            clockState = ClockState(message = "Handy-Zeit außerhalb 2025 bis 2099")
            return
        }
        stopBleScan()
        clockState = ClockState(isBusy = true)
        consoleClient.send("TIME SET $text") { setReply ->
            if (setReply is ConsoleReply.Status && setReply.line.startsWith("OK")) {
                consoleClient.send("TIME") { reply ->
                    val result = clockResult(reply)
                    clockState = if (result.reply == null) {
                        result.copy(message = "Uhr gesetzt, Rücklesen fehlgeschlagen: ${result.message}")
                    } else result
                }
            } else {
                clockState = ClockState(message = clockError(setReply))
            }
        }
    }

    private fun clockResult(reply: ConsoleReply): ClockState = when (reply) {
        is ConsoleReply.Frame -> try {
            val r = PanelClock.parseTimeReply(reply.lines)
            ClockState(reply = r, offsetSeconds = r.epochMillis?.let { PanelClock.offsetSeconds(it, System.currentTimeMillis()) })
        } catch (e: ParseException) {
            ClockState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
        }
        else -> ClockState(message = clockError(reply))
    }

    private fun clockError(reply: ConsoleReply): String =
        if (reply is ConsoleReply.Status && reply.line.startsWith("ERR READ_ONLY")) "Set ble_console=2 to apply"
        else BleConsoleClient.errorText(reply).ifEmpty { "Panel meldet: Antwort unerwartet" }

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
        consoleClient.send(CONSOLE_STATUS) { reply -> applyStatusReply(reply) }
    }

    /** Stores the `STATUS` [reply] in the health state and refreshes the slot format badges. */
    private fun applyStatusReply(reply: ConsoleReply) {
        healthState = when (reply) {
            is ConsoleReply.Frame -> try {
                val status = StatusReply.parse(reply.lines)
                val limit = panelConfig.values["sensor_battery_limit_pct"]?.toDoubleOrNull()
                SensorHealthState(rows = SensorHealth.rows(status, limit), ntp = status.ntp, sdFreeMb = status.sdFreeMb, reset = status.reset, shtc3 = status.shtc3, memory = status.memory, rtc = status.rtc, noise = status.noise, runtime = status.runtime, awake = status.awake, status = status).also { s ->
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

    private fun fetchWeatherIfNeeded() {
        if (outdoorSource is OutdoorSource.OpenMeteo) {
            val location = outdoorLocation
            // Cancel an older fetch, so a late reply cannot overwrite a newer one.
            weatherJob?.cancel()
            weatherJob = lifecycleScope.launch {
                val weather = OutdoorWeatherService.fetchCurrent(location)
                if (weather != null && OutdoorResolver.acceptFetched(location, outdoorLocation)) {
                    openMeteoClimate = weather
                    openMeteoLocation = location
                }
            }
        }
    }

    private fun saveOutdoorLocation(latitude: String, longitude: String, name: String): String? {
        return when (val result = locationStore.save(latitude, longitude, name)) {
            is LocationInput.Invalid -> result.message
            is LocationInput.Valid -> {
                if (result.location != outdoorLocation) {
                    val moved = !OutdoorResolver.isSamePlace(result.location, outdoorLocation)
                    outdoorLocation = result.location
                    if (outdoorSource is OutdoorSource.OpenMeteo) outdoorSource = result.location.toSource()
                    if (moved) {
                        // Drop the reading of the old place, so no advice uses it.
                        openMeteoClimate = null
                        openMeteoLocation = null
                        fetchWeatherIfNeeded()
                    }
                }
                showLocationDialog = false
                null
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
                    outdoorLocation.toSource()
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
                val name = panelConfig.tileName(tileIndex)
                val rounded = Math.round(result.rh).toInt()
                postVentilationNotification(
                    title = "RLCD Alarm",
                    message = "$name: Wand $rounded %",
                    notificationId = NOTIFICATION_ID_WALL_BASE + tileIndex
                )
            }
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
        const val NOTIFICATION_ID_WALL_BASE = 2000
    }
}

/** Console commands that a tap on a button sends. */
private const val CONSOLE_STATUS = "STATUS"
private const val CONSOLE_VERSION = "VERSION"
private const val CONSOLE_SELFTEST = "SELFTEST"
private const val CONSOLE_TIME = "TIME"
private const val CONSOLE_RTC_TRIM = "RTC TRIM"
private const val CONSOLE_TIME_SET = "TIME SET"
private const val CONSOLE_CONFIG = "CONFIG"

/** Reads at most [limit] bytes, so a huge picked file never fills the memory. */
private fun java.io.InputStream.readCapped(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
