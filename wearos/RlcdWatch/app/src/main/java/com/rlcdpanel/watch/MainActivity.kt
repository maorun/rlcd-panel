package com.rlcdpanel.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.rlcdpanel.watch.protocol.AlertTransitions
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var snapshot by mutableStateOf<TelemetrySnapshot?>(null)
    private var isScanning by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Bereit")
    private var nowMillis by mutableLongStateOf(System.currentTimeMillis())

    private lateinit var bleScanner: BleTelemetryScanner

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted) {
            startBleScan()
        } else {
            statusMessage = "Bluetooth-Berechtigung fehlt"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleScanner = BleTelemetryScanner(
            context = this,
            onSnapshot = { incoming ->
                val rising = AlertTransitions.risingAlerts(snapshot?.frame, incoming.frame)
                snapshot = incoming
                statusMessage = "Seq #${incoming.frame.sequence} empfangen"
                if (!rising.isEmpty) {
                    vibrateAlert()
                }
            },
            onError = { message ->
                isScanning = false
                statusMessage = message
            }
        )

        setContent {
            LaunchedEffect(Unit) {
                while (true) {
                    nowMillis = System.currentTimeMillis()
                    delay(1000L)
                }
            }

            WatchDashboardScreen(
                snapshot = snapshot,
                isScanning = isScanning,
                statusMessage = statusMessage,
                nowMillis = nowMillis,
                onToggleScan = {
                    if (isScanning) {
                        stopBleScan()
                    } else {
                        ensurePermissionAndStart()
                    }
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        ensurePermissionAndStart()
    }

    override fun onStop() {
        stopBleScan()
        super.onStop()
    }

    private fun ensurePermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
                return
            }
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

    private fun vibrateAlert() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 180L, 100L, 180L), -1)
        )
    }
}
