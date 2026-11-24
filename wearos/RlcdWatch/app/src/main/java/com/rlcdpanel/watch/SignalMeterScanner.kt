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
import android.os.ParcelUuid
import com.rlcdpanel.watch.protocol.SensorAdvertisement

/**
 * Foreground scan with a `ScanFilter` on one sensor address. [onPacket] gets the RSSI of each packet that
 * [SensorAdvertisement.decode] accepts. The caller stops the scan when the screen closes and after 5 minutes.
 */
class SignalMeterScanner(
    private val context: Context,
    private val onPacket: (Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private var scanner: BluetoothLeScanner? = null
    var isScanning: Boolean = false
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            onError("BLE scan failed (code $errorCode)")
        }
    }

    /** [mac] is `AA:BB:CC:DD:EE:FF`. Returns false and calls [onError] when the scan cannot start. */
    @SuppressLint("MissingPermission")
    fun start(mac: String): Boolean {
        if (isScanning) return true
        if (!BluetoothAddressCheck.isValid(mac)) {
            onError("Bad sensor address")
            return false
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onError("Bluetooth disabled")
            return false
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            onError("BLE scanner unavailable")
            return false
        }
        val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
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
    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return
        val address = try { result.device?.address } catch (_: SecurityException) { null } ?: return
        val mac = parseAddress(address) ?: return
        val svc = firstServiceData(record)
        SensorAdvertisement.decode(mac, record.deviceName, rawManufacturerData(record), svc?.first, svc?.second) ?: return
        onPacket(result.rssi)
    }

    companion object {
        fun parseAddress(address: String): ByteArray? {
            val parts = address.split(":")
            if (parts.size != 6) return null
            val mac = ByteArray(6)
            for (i in 0 until 6) mac[i] = (parts[i].toIntOrNull(16) ?: return null).toByte()
            return mac
        }

        /** First Manufacturer Data AD structure with the company ID bytes. */
        fun rawManufacturerData(record: ScanRecord): ByteArray? {
            val raw = record.bytes ?: return null
            var pos = 0
            while (pos < raw.size) {
                val len = raw[pos].toInt() and 0xFF
                if (len == 0 || pos + 1 + len > raw.size) break
                if ((raw[pos + 1].toInt() and 0xFF) == 0xFF && len >= 3) return raw.copyOfRange(pos + 2, pos + 1 + len)
                pos += 1 + len
            }
            return null
        }

        private val SERVICE_ORDER = listOf(
            SensorAdvertisement.BTHOME_SERVICE_UUID,
            SensorAdvertisement.ATC_SERVICE_UUID,
            SensorAdvertisement.SWITCHBOT_SERVICE_UUID
        )

        /** The 16-bit UUID and bytes of the first Service Data in the firmware check order. */
        fun firstServiceData(record: ScanRecord): Pair<Int, ByteArray>? {
            for (id in SERVICE_ORDER) {
                val data = record.serviceData?.get(ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(id)))
                if (data != null) return id to data
            }
            return null
        }
    }
}

/** Address text check that needs no Android class, so a unit test can call it. */
object BluetoothAddressCheck {
    private val PATTERN = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    fun isValid(mac: String): Boolean = PATTERN.matches(mac)
}
