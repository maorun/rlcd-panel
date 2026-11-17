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
import com.rlcdpanel.phone.protocol.AlertTransitions
import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.ParseException
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.StatusReply
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.ventilation.OutdoorClimate
import com.rlcdpanel.phone.ventilation.OutdoorResolver
import com.rlcdpanel.phone.ventilation.OutdoorSource
import com.rlcdpanel.phone.ventilation.OutdoorWeatherService
import com.rlcdpanel.phone.ventilation.VentilationAction
import com.rlcdpanel.phone.ventilation.VentilationGuide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class RoomVentilationState(
    val isVentilating: Boolean = false,
    val startMillis: Long? = null
)

class MainActivity : ComponentActivity() {

    private var snapshot by mutableStateOf<TelemetrySnapshot?>(null)
    private var isScanning by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Bereit")
    private var nowMillis by mutableLongStateOf(System.currentTimeMillis())

    private var outdoorSource by mutableStateOf<OutdoorSource>(OutdoorSource.OpenMeteo())
    private var openMeteoClimate by mutableStateOf<OutdoorClimate?>(null)
    private var focusedRoomIndex by mutableIntStateOf(0)

    private var roomStates by mutableStateOf<Map<Int, RoomVentilationState>>(emptyMap())
    private val lastObservedAktionen = mutableMapOf<Int, VentilationAction>()
    private var panelConfig by mutableStateOf(PanelConfig())

    private var notificationPermissionAsked = false
    private var scanPermissionDenied = false
    private var weatherJob: Job? = null

    private lateinit var bleScanner: BleTelemetryScanner
    private lateinit var consoleClient: BleConsoleClient
    private var showHealth by mutableStateOf(false)
    private var healthState by mutableStateOf(SensorHealthState())
    private lateinit var prefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Notifications are optional. Only the scan permissions block the BLE scan.
        val scanGranted = grants.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
            .values.all { it }
        if (scanGranted) {
            scanPermissionDenied = false
            startBleScan()
        } else {
            scanPermissionDenied = true
            statusMessage = "Bluetooth-Berechtigung fehlt"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rlcd_ventilation_prefs", Context.MODE_PRIVATE)
        panelConfig = PanelConfig.load(this)
        createNotificationChannel()
        restoreRoomStates()

        consoleClient = BleConsoleClient(this)
        bleScanner = BleTelemetryScanner(
            context = this,
            onSnapshot = { incoming ->
                val mergedFrame = TelemetryFrame.merge(snapshot?.frame, incoming.frame)
                val mergedSnapshot = incoming.copy(frame = mergedFrame)
                // The first frame after the app start reports each active alert as new.
                val rising = if (snapshot == null) {
                    incoming.frame.alerts
                } else {
                    AlertTransitions.risingAlerts(snapshot?.frame, incoming.frame)
                }
                snapshot = mergedSnapshot
                statusMessage = "Seq #${incoming.frame.sequence} empfangen"
                if (!rising.isEmpty) {
                    vibrateAlert()
                    postVentilationNotification(
                        title = "RLCD Alarm",
                        message = rising.names.joinToString(", "),
                        notificationId = ALERT_NOTIFICATION_ID
                    )
                }
            },
            onError = { message ->
                isScanning = false
                statusMessage = message
            }
        )

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
            if (showHealth) {
                SensorHealthScreen(
                    state = healthState,
                    panelConfig = panelConfig,
                    onRead = { ensureConsolePermissionAndRead() },
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
                onOpenSensorHealth = { showHealth = true }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // A denied scan permission shows a status hint. The scan button asks again on demand.
        if (!scanPermissionDenied) {
            ensurePermissionAndStart()
        }
    }

    override fun onStop() {
        consoleClient.cancel()
        healthState = healthState.copy(isReading = false)
        stopBleScan()
        lastObservedAktionen.clear()
        super.onStop()
    }

    private val consolePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (!showHealth) return@registerForActivityResult
        if (grants.values.all { it }) {
            readSensorHealth()
        } else {
            healthState = SensorHealthState(message = "Bluetooth-Berechtigung fehlt")
        }
    }

    private fun closeSensorHealth() {
        consoleClient.cancel()
        healthState = SensorHealthState()
        showHealth = false
        if (!scanPermissionDenied) ensurePermissionAndStart()
    }

    /** Sends STATUS only after a tap on the button. */
    private fun ensureConsolePermissionAndRead() {
        if (healthState.isReading) return
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            healthState = SensorHealthState(isReading = true)
            consolePermissionLauncher.launch(needed.toTypedArray())
            return
        }
        readSensorHealth()
    }

    private fun readSensorHealth() {
        // The telemetry scan stops, so the console scan and connection have the radio.
        stopBleScan()
        healthState = SensorHealthState(isReading = true)
        consoleClient.readStatus { reply ->
            healthState = when (reply) {
                is ConsoleReply.Frame -> try {
                    val status = StatusReply.parse(reply.lines)
                    val limit = panelConfig.values["sensor_battery_limit_pct"]?.toDoubleOrNull()
                    SensorHealthState(rows = SensorHealth.rows(status, limit))
                } catch (e: ParseException) {
                    SensorHealthState(message = "Antwort nicht lesbar: ${e.line.take(80)}")
                }
                else -> SensorHealthState(message = BleConsoleClient.errorText(reply))
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
        val totalTiles = snapshot?.frame?.tiles?.size ?: TelemetryFrame.TOTAL_TILES
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
        val started = bleScanner.start()
        isScanning = bleScanner.isScanning
        if (started) {
            statusMessage = if (snapshot == null) "Scanne nach 0xFFFF..." else "Scan aktiv"
        }
    }

    private fun stopBleScan() {
        bleScanner.stop()
        isScanning = false
        statusMessage = "Scan pausiert"
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
        const val ALERT_NOTIFICATION_ID = 900
        const val MAX_RESTORED_VENTILATION_MS = 6 * 60 * 60 * 1000L
    }
}
