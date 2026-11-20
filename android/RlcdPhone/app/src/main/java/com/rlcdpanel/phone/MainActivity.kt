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
import com.rlcdpanel.phone.finder.SensorFinderList
import com.rlcdpanel.phone.finder.SensorFinderScanner
import com.rlcdpanel.phone.finder.SlotAssignment
import com.rlcdpanel.phone.protocol.CalibrationBatch
import com.rlcdpanel.phone.protocol.ChimeCommand
import com.rlcdpanel.phone.protocol.CalibrationProposal
import com.rlcdpanel.phone.protocol.CalibrationReply
import com.rlcdpanel.phone.protocol.SaltCommand
import com.rlcdpanel.phone.protocol.SaltReply
import com.rlcdpanel.phone.protocol.SaltText
import com.rlcdpanel.phone.protocol.TwoPointCalibration
import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.PanelClock
import com.rlcdpanel.phone.protocol.ParseException
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.StatusReply
import com.rlcdpanel.phone.protocol.ConfigCache
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryHistory
import com.rlcdpanel.phone.protocol.EventCsv
import com.rlcdpanel.phone.protocol.EventRow
import com.rlcdpanel.phone.protocol.EventsReply
import com.rlcdpanel.phone.protocol.RejectsReply
import com.rlcdpanel.phone.protocol.WeekReply
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
    private var panelConfig by mutableStateOf(PanelConfig())

    private var pendingConsoleCommand = CONSOLE_STATUS
    private var notificationPermissionAsked = false
    private var scanPermissionDenied = false
    private var weatherJob: Job? = null

    private lateinit var consoleClient: BleConsoleClient
    private var showHealth by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    private var showCalib by mutableStateOf(false)
    private var showWeek by mutableStateOf(false)
    private var showEvents by mutableStateOf(false)
    private var eventState by mutableStateOf(EventLogState())
    private var showFinder by mutableStateOf(false)
    private var showChime by mutableStateOf(false)
    private var showSlots by mutableStateOf(false)
    private var showRejects by mutableStateOf(false)
    private var clockState by mutableStateOf(ClockState())
    private var rejectsState by mutableStateOf(RejectsState())
    private var slotState by mutableStateOf(SlotConfigState())
    private var chimeState by mutableStateOf(ChimeState())
    private var finderState by mutableStateOf(SensorFinderState())
    private val finderList = SensorFinderList()
    private var finderScanner: SensorFinderScanner? = null
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
                    onBack = { closeWeek() }
                )
                BackHandler { closeWeek() }
                return@setContent
            }
            if (showCalib) {
                CalibrationScreen(
                    state = calibState,
                    currentConfig = panelConfig.values,
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
                    onBack = { closeCalibration() }
                )
                BackHandler { closeCalibration() }
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
            if (showSlots) {
                SlotConfigScreen(
                    state = slotState,
                    panelConfig = panelConfig,
                    formats = sourceBadges,
                    onApply = { commands -> withConsolePermission { applySlotCommands(commands) } },
                    onBack = { closeSlots() }
                )
                BackHandler { closeSlots() }
                return@setContent
            }
            if (showChime) {
                ChimeScreen(
                    state = chimeState,
                    onRead = { withConsolePermission { readChimeState() } },
                    onMute = { on -> withConsolePermission { setMute(on) } },
                    onTest = { bit -> withConsolePermission { testChime(bit) } },
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
                    onOpenFinder = { showHealth = false; showFinder = true },
                    onOpenChime = { showHealth = false; showChime = true },
                    onOpenSlots = { showHealth = false; showSlots = true },
                    onOpenRejects = { showHealth = false; showRejects = true },
                    clockState = clockState,
                    onReadClock = { ensureConsolePermissionAndRead(CONSOLE_TIME) },
                    onSyncClock = { ensureConsolePermissionAndRead(CONSOLE_TIME_SET) },
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
        stopFinderScan()
        if (finderState.isBusy) {
            finderState = finderState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, CONFIG lesen")
        }
        healthState = healthState.copy(isReading = false)
        configState = configState.copy(isReading = false)
        if (weekState.isReading) weekState = WeekState(message = "Abgebrochen. Erneut lesen.")
        if (eventState.isReading) eventState = EventLogState(message = "Abgebrochen. Erneut lesen.")
        if (rejectsState.isReading) rejectsState = RejectsState(message = "Abgebrochen. Erneut lesen.")
        if (clockState.isBusy) clockState = ClockState(message = "Abgebrochen. Zustand der Panel-Uhr unbekannt, Read clock tippen")
        if (chimeState.isBusy) chimeState = chimeState.copy(isBusy = false, isError = consoleWasRunning, message = if (!consoleWasRunning) null else "Abgebrochen. Zustand des Panels unbekannt, STATUS lesen")
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
            else if (showEvents) eventState = EventLogState(message = "Bluetooth-Berechtigung fehlt")
            else if (showRejects) rejectsState = RejectsState(message = "Bluetooth-Berechtigung fehlt")
            else if (showWeek) weekState = WeekState(message = "Bluetooth-Berechtigung fehlt")
            else if (showFinder) finderState = finderState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else if (showSlots) slotState = slotState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else if (showChime) chimeState = chimeState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            else calibState = calibState.copy(isBusy = false, isError = true, message = "Bluetooth-Berechtigung fehlt")
            return@registerForActivityResult
        }
        if (!showHealth) return@registerForActivityResult
        val command = pendingConsoleCommand
        if (grants.values.all { it }) {
            runConsoleCommand(command)
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

    /** Sends the event rows as CSV text, oldest first. */
    private fun shareEventsCsv(rows: List<EventRow>) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, EventCsv.format(rows))
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

    /** Starts the finder scan after a tap; the background scan of the telemetry pauses meanwhile. */
    private fun startFinderScan() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            finderState = finderState.copy(isError = true, message = "Bluetooth-Berechtigung fehlt. Erst Sensor-Status öffnen und erlauben.")
            return
        }
        stopBleScan()
        finderList.clear()
        val scanner = finderScanner ?: SensorFinderScanner(
            this,
            onResult = { ad, rssi ->
                // A result that arrives after stop or close must not refill the list.
                if (showFinder && finderState.isScanning) {
                    finderList.add(ad, rssi, System.currentTimeMillis())
                    finderState = finderState.copy(rows = finderList.sorted())
                }
            },
            onError = { text -> finderState = finderState.copy(isScanning = false, isError = true, message = text) }
        ).also { finderScanner = it }
        val started = scanner.start()
        finderState = SensorFinderState(isScanning = started, message = if (started) null else finderState.message, isError = !started)
    }

    /** Stops the finder scan. Called from the button, `closeFinder` and `onStop`. */
    private fun stopFinderScan() {
        finderScanner?.stop()
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
        rejectsState = RejectsState(isReading = true)
        consoleClient.send("REJECTS") { reply ->
            rejectsState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val log = RejectsReply.parse(reply.lines)
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
            return chimeState.copy(isBusy = false, isError = true, message = BleConsoleClient.errorText(reply))
        }
        return try {
            val muted = StatusReply.parse(reply.lines).muted
            chimeState.copy(
                isBusy = false, isError = false, muted = muted ?: expected,
                message = if (expected != null) "Stumm ${if (expected) "an" else "aus"}" else null
            )
        } catch (e: ParseException) {
            chimeState.copy(isBusy = false, isError = true, message = "Antwort nicht lesbar: ${e.line.take(80)}")
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

    private fun closeCalibration() {
        consoleClient.cancel()
        pendingConsoleAction = null
        calibState = CalibrationState(reference = calibState.reference)
        showCalib = false
        showHealth = true
    }

    /** Runs [action] after the Bluetooth permissions; the action sends console commands. */
    private fun withConsolePermission(action: () -> Unit) {
        if (calibState.isBusy || chimeState.isBusy || weekState.isReading || eventState.isReading || rejectsState.isReading || finderState.isBusy || slotState.isBusy || consoleClient.isRunning) return
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            pendingConsoleAction = action
            if (showEvents) eventState = EventLogState(isReading = true)
            else if (showRejects) rejectsState = RejectsState(isReading = true)
            else if (showWeek) weekState = WeekState(isReading = true)
            else if (showFinder) finderState = finderState.copy(isBusy = true, message = null)
            else if (showSlots) slotState = slotState.copy(isBusy = true, message = null)
            else if (showChime) chimeState = chimeState.copy(isBusy = true, message = null)
            else calibState = calibState.copy(isBusy = true, message = null)
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

    /** Sends `SALT N` or `SALT N MGCL2` after a tap. With both checks of one sensor, the two-point result follows. */
    private fun readSalt(magnesium: Boolean) {
        stopBleScan()
        val n = calibState.reference
        val line = SaltCommand.line(n, magnesium) ?: return
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
                mgCl2 = if (changed) null else calibState.mgCl2
            )
        }
    }

    private fun closeSensorHealth() {
        consoleClient.cancel()
        healthState = SensorHealthState()
        clockState = ClockState()
        configState = configState.copy(isReading = false, message = null)
        showHealth = false
        if (!scanPermissionDenied && !BleScanService.isScanning.value) ensurePermissionAndStart()
    }

    /** Sends [command] (`STATUS` or `CONFIG`) only after a tap on a button. */
    private fun ensureConsolePermissionAndRead(command: String) {
        if (healthState.isReading || configState.isReading || clockState.isBusy || consoleClient.isRunning) return
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
        if (command == CONSOLE_TIME || command == CONSOLE_TIME_SET) {
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
            CONSOLE_TIME -> readClock()
            CONSOLE_TIME_SET -> syncClock()
            else -> readSensorHealth()
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
        consoleClient.send(CONSOLE_STATUS) { reply ->
            healthState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val status = StatusReply.parse(reply.lines)
                    val limit = panelConfig.values["sensor_battery_limit_pct"]?.toDoubleOrNull()
                    SensorHealthState(rows = SensorHealth.rows(status, limit), ntp = status.ntp, sdFreeMb = status.sdFreeMb).also { s ->
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
private const val CONSOLE_TIME = "TIME"
private const val CONSOLE_TIME_SET = "TIME SET"
private const val CONSOLE_CONFIG = "CONFIG"
