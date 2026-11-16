package com.rlcdpanel.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.rlcdpanel.watch.protocol.TelemetryException
import com.rlcdpanel.watch.protocol.TelemetryFrame

/**
 * Snapshot delivered when a valid RLCD Panel BLE telemetry packet arrives.
 */
data class TelemetrySnapshot(
    val frame: TelemetryFrame,
    val rssi: Int,
    val address: String?,
    val receivedAtMillis: Long
)

/**
 * Foreground BLE scanner filtered to Manufacturer Data company ID `0xFFFF` and format version `2`.
 */
class BleTelemetryScanner(
    private val context: Context,
    private val onSnapshot: (TelemetrySnapshot) -> Unit,
    private val onError: (String) -> Unit
) {
    private var scanner: BluetoothLeScanner? = null
    var isScanning: Boolean = false
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                handleResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            onError("BLE scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isScanning) return true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onError("Bluetooth disabled")
            return false
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            onError("BLE scanner unavailable")
            return false
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(
                TelemetryFrame.COMPANY_ID,
                byteArrayOf(TelemetryFrame.FORMAT_VERSION.toByte()),
                byteArrayOf(0xFF.toByte())
            )
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        return try {
            leScanner.startScan(listOf(filter), settings, callback)
            scanner = leScanner
            isScanning = true
            true
        } catch (e: SecurityException) {
            onError("Missing BLUETOOTH_SCAN permission")
            false
        } catch (e: IllegalStateException) {
            onError("Bluetooth not ready")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isScanning) return
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
        } finally {
            scanner = null
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val payload = record.getManufacturerSpecificData(TelemetryFrame.COMPANY_ID) ?: return
        try {
            val frame = TelemetryFrame.decodePayload(payload)
            onSnapshot(
                TelemetrySnapshot(
                    frame = frame,
                    rssi = result.rssi,
                    address = try { result.device?.address } catch (_: SecurityException) { null },
                    receivedAtMillis = System.currentTimeMillis()
                )
            )
        } catch (_: TelemetryException) {
            // Ignore unrelated or corrupted 0xFFFF advertisements.
        }
    }
}
