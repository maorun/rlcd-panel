package com.rlcdpanel.phone.finder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

/** Foreground scan for nearby sensors. The caller stops it in `onStop` and before a console command. */
class SensorFinderScanner(
    private val context: Context,
    private val onResult: (SensorAdvertisement, Int) -> Unit,
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

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isScanning) return true
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
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            // No filter: the sensors advertise with different company IDs and service UUIDs.
            leScanner.startScan(null, settings, callback)
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
        val address = try { result.device?.address } catch (_: SecurityException) { null }
        if (address == null) return
        val mac = parseAddress(address) ?: return
        val svc = firstServiceData(record)
        val ad = SensorAdvertisement.decode(mac, record.deviceName, rawManufacturerData(record), svc?.first, svc?.second)
            ?: return
        onResult(ad, result.rssi)
    }

    companion object {
        fun parseAddress(address: String): ByteArray? {
            val parts = address.split(":")
            if (parts.size != 6) return null
            val mac = ByteArray(6)
            for (i in 0 until 6) {
                val v = parts[i].toIntOrNull(16) ?: return null
                mac[i] = v.toByte()
            }
            return mac
        }

        /** First Manufacturer Data AD structure with the company ID bytes (the API drops them for the key). */
        fun rawManufacturerData(record: ScanRecord): ByteArray? {
            val raw = record.bytes ?: return null
            var pos = 0
            while (pos < raw.size) {
                val len = raw[pos].toInt() and 0xFF
                if (len == 0 || pos + 1 + len > raw.size) break
                if ((raw[pos + 1].toInt() and 0xFF) == 0xFF && len >= 3) {
                    return raw.copyOfRange(pos + 2, pos + 1 + len)
                }
                pos += 1 + len
            }
            return null
        }

        /** UUIDs in the dispatch order of the firmware: BTHome, ATC, SwitchBot. */
        private val SERVICE_ORDER = listOf(
            SensorAdvertisement.BTHOME_SERVICE_UUID,
            SensorAdvertisement.ATC_SERVICE_UUID,
            SensorAdvertisement.SWITCHBOT_SERVICE_UUID
        )

        /**
         * The 16-bit UUID and bytes of the Service Data that the firmware looks at first. A packet with two of these
         * UUIDs is rare; the lookup then follows the firmware check order, not the packet order.
         */
        fun firstServiceData(record: ScanRecord): Pair<Int, ByteArray>? {
            for (id in SERVICE_ORDER) {
                val data = record.serviceData?.get(ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(id)))
                if (data != null) return id to data
            }
            return null
        }
    }
}
