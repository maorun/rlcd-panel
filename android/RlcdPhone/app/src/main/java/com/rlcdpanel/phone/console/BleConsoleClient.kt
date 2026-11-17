package com.rlcdpanel.phone.console

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.ReplyAssembler
import com.rlcdpanel.phone.protocol.ReplyError
import java.util.UUID

/** Nordic UART UUIDs of the panel console (`BleConsoleUuids` of the firmware). */
object ConsoleUuids {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * One-shot client for the panel console: scans for the console service, opens one GATT connection, requests MTU 185,
 * subscribes to the TX notifications, writes `STATUS\n` once on the RX characteristic, waits for the reply (10 s in
 * total) and disconnects. The only command that the client sends is `STATUS`. The caller holds the
 * `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` permissions (SDK 31 and later; `ACCESS_FINE_LOCATION` before).
 */
@SuppressLint("MissingPermission")
class BleConsoleClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var assembler = ReplyAssembler()
    private var onResult: ((ConsoleReply) -> Unit)? = null
    private var running = false

    private val timeoutTask = Runnable { finish(assembler.timeout()) }

    val isRunning: Boolean get() = running

    /** Starts one `STATUS` read. [callback] runs once on the main thread; a second call while running is ignored. */
    fun readStatus(callback: (ConsoleReply) -> Unit) {
        if (running) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val leScanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (leScanner == null) {
            callback(ConsoleReply.Status("ERR BLUETOOTH_OFF"))
            return
        }
        running = true
        onResult = callback
        assembler = ReplyAssembler()
        scanner = leScanner
        main.postDelayed(timeoutTask, TIMEOUT_MS)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(ConsoleUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            finish(ConsoleReply.Status("ERR PERMISSION"))
        }
    }

    /** Aborts a running read without a callback. */
    fun cancel() {
        if (!running) return
        onResult = null
        finish(null)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running || gatt != null) return
            runCatching { scanner?.stopScan(this) }
            gatt = result.device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { finish(ConsoleReply.Status("ERR SCAN_FAILED")) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(MTU)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { if (running) finish(assembler.timeout()) }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(ConsoleUuids.SERVICE)?.getCharacteristic(ConsoleUuids.TX)
            val cccd = tx?.getDescriptor(ConsoleUuids.CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null || cccd == null) {
                main.post { finish(ConsoleReply.Status("ERR NO_CONSOLE")) }
                return
            }
            g.setCharacteristicNotification(tx, true)
            writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val rx = g.getService(ConsoleUuids.SERVICE)?.getCharacteristic(ConsoleUuids.RX)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null) {
                main.post { finish(ConsoleReply.Status("ERR NO_CONSOLE")) }
                return
            }
            writeCharacteristic(g, rx, COMMAND)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) main.post { finish(ConsoleReply.Status("ERR WRITE_FAILED")) }
        }

        @Deprecated("Used below SDK 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value)
        }
    }

    private fun deliver(chunk: ByteArray) {
        main.post {
            if (!running) return@post
            assembler.append(chunk)?.let { finish(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    /** Ends the read once: stops the scan, closes the connection and reports [reply] (null reports nothing). */
    private fun finish(reply: ConsoleReply?) {
        if (!running) return
        running = false
        main.removeCallbacks(timeoutTask)
        runCatching { scanner?.stopScan(scanCallback) }
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        val cb = onResult
        onResult = null
        if (reply != null) cb?.invoke(reply)
    }

    companion object {
        const val MTU = 185
        const val TIMEOUT_MS = 10_000L
        /** The only command that the app sends. */
        val COMMAND = "STATUS\n".toByteArray()

        /** Text for a reply that is not a [ConsoleReply.Frame]. */
        fun errorText(reply: ConsoleReply): String = when (reply) {
            is ConsoleReply.Frame -> ""
            is ConsoleReply.Status -> when {
                reply.line.startsWith("ERR READ_ONLY") || reply.line.startsWith("ERR NOT_OVER_BLE") ->
                    "Panel lehnt den Befehl ab: ${reply.line.take(80)}"
                else -> "Panel meldet: ${reply.line.take(80)}"
            }
            is ConsoleReply.Corrupt -> when (reply.error) {
                ReplyError.Timeout -> "Keine Antwort in 10 s. Panel an USB und ble_console 1 oder 2?"
                ReplyError.TooLarge -> "Antwort zu groß"
                is ReplyError.BadCrc -> "Prüfsumme der Antwort falsch"
                is ReplyError.MalformedCrcLine -> "CRC-Zeile der Antwort fehlerhaft"
            }
        }
    }
}
