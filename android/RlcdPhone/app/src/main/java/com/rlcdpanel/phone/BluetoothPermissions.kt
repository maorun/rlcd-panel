package com.rlcdpanel.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Permission check of the one-shot console senders (`AckReceiver`, `MuteTileService`). */
object BluetoothPermissions {
    /** `BleConsoleClient` needs both permissions on SDK 31 and later; it does not guard `connectGatt`. */
    fun hasConsolePermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
