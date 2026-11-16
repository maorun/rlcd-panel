package com.rlcdpanel.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
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

        // Use a wildcard filter so the hardware filter does not drop non-connectable 0xFFFF packets.
        val filter = ScanFilter.Builder().build()

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
        val payload = extractPayload(record) ?: return
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

    companion object {
        /**
         * Extracts the 24-byte telemetry payload from the scan record.
         * Tries Android API SparseArray lookup first, then falls back to raw advertisement parsing.
         */
        fun extractPayload(record: ScanRecord): ByteArray? {
            // 1. Try standard getManufacturerSpecificData(0xFFFF)
            val fromApi = record.getManufacturerSpecificData(TelemetryFrame.COMPANY_ID)
            if (fromApi != null && fromApi.size == TelemetryFrame.PAYLOAD_SIZE) {
                return fromApi
            }

            // 2. Fallback: Parse raw advertisement bytes directly to handle Android SparseArray / signed short quirks
            val rawBytes = record.bytes ?: return null
            var pos = 0
            while (pos < rawBytes.size) {
                val len = rawBytes[pos].toInt() and 0xFF
                if (len == 0 || pos + 1 + len > rawBytes.size) {
                    break
                }
                val type = rawBytes[pos + 1].toInt() and 0xFF
                // 0xFF = Manufacturer Specific Data
                if (type == 0xFF && len >= 3) {
                    val compLo = rawBytes[pos + 2].toInt() and 0xFF
                    val compHi = rawBytes[pos + 3].toInt() and 0xFF
                    val companyId = compLo or (compHi shl 8)
                    val payloadLen = len - 3
                    if (companyId == TelemetryFrame.COMPANY_ID && payloadLen == TelemetryFrame.PAYLOAD_SIZE) {
                        return rawBytes.copyOfRange(pos + 4, pos + 1 + len)
                    }
                }
                pos += 1 + len
            }

            return null
        }
    }
}
