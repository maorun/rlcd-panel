package com.rlcdpanel.watch.protocol

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal key-value storage behind [TelemetryCache], so host tests need no Android runtime.
 */
interface TelemetryCacheStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun put(strings: Map<String, String>, longs: Map<String, Long>, ints: Map<String, Int>)
}

class SharedPreferencesCacheStore(private val prefs: SharedPreferences) : TelemetryCacheStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun put(strings: Map<String, String>, longs: Map<String, Long>, ints: Map<String, Int>) {
        val editor = prefs.edit()
        strings.forEach { (key, value) -> editor.putString(key, value) }
        longs.forEach { (key, value) -> editor.putLong(key, value) }
        ints.forEach { (key, value) -> editor.putInt(key, value) }
        editor.apply()
    }
}

/**
 * Last valid telemetry frame of one page, decoded from the cached payload bytes.
 */
data class CachedTelemetry(
    val frame: TelemetryFrame,
    val receivedAtMillis: Long
) {
    /** True when the frame is older than [TelemetryCache.MAX_AGE_MILLIS] (35 minutes). */
    fun isStale(nowMillis: Long): Boolean =
        nowMillis < receivedAtMillis || ageMillis(nowMillis) > TelemetryCache.MAX_AGE_MILLIS

    fun ageMillis(nowMillis: Long): Long = (nowMillis - receivedAtMillis).coerceAtLeast(0L)
}

/**
 * Cache of the last valid telemetry payload (hex text) and its receive time, one entry for each
 * telemetry page, plus the focused room index. The Wear OS tile reads it and does not scan BLE.
 */
class TelemetryCache(private val store: TelemetryCacheStore) {

    constructor(context: Context) : this(
        SharedPreferencesCacheStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    )

    /**
     * Stores [payload] (24 bytes, as `ScanRecord` delivers it) with its receive time.
     * Returns false and keeps the old entry when the payload does not decode.
     */
    fun save(payload: ByteArray, receivedAtMillis: Long): Boolean {
        val frame = try {
            TelemetryFrame.decodePayload(payload)
        } catch (_: TelemetryException) {
            return false
        }
        store.put(
            strings = mapOf(payloadKey(frame.page) to toHex(payload)),
            longs = mapOf(receivedKey(frame.page) to receivedAtMillis),
            ints = emptyMap()
        )
        return true
    }

    /**
     * Cached entry of [page], or null for an empty cache, a bad hex text or a payload that fails
     * the length, version or CRC-8 check.
     */
    fun read(page: Int = 0): CachedTelemetry? {
        val hex = store.getString(payloadKey(page)) ?: return null
        val receivedAt = store.getLong(receivedKey(page), NO_TIME)
        if (receivedAt == NO_TIME) {
            return null
        }
        val payload = fromHex(hex) ?: return null
        val frame = try {
            TelemetryFrame.decodePayload(payload)
        } catch (_: TelemetryException) {
            return null
        }
        if (frame.page != page) {
            return null
        }
        return CachedTelemetry(frame, receivedAt)
    }

    fun saveFocusedRoom(index: Int) {
        store.put(emptyMap(), emptyMap(), mapOf(KEY_FOCUS to index))
    }

    fun focusedRoom(): Int =
        store.getInt(KEY_FOCUS, 0).coerceIn(0, TelemetryFrame.TOTAL_TILES - 1)

    companion object {
        const val PREFS_NAME = "rlcd_telemetry_cache"

        /** Freshness rule of `OutdoorResolver`: a frame older than 35 minutes is stale. */
        const val MAX_AGE_MILLIS: Long = 35L * 60L * 1000L

        private const val KEY_FOCUS = "focused_room"
        private const val NO_TIME = -1L

        private fun payloadKey(page: Int) = "payload_$page"
        private fun receivedKey(page: Int) = "received_$page"

        fun toHex(bytes: ByteArray): String =
            bytes.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }

        /** Decodes hex text; null for an odd length or a non-hex character. */
        fun fromHex(text: String): ByteArray? {
            if (text.length % 2 != 0) {
                return null
            }
            val out = ByteArray(text.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(text[i * 2], 16)
                val lo = Character.digit(text[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) {
                    return null
                }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
