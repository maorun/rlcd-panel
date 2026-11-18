package com.rlcdpanel.phone.finder

/** One row of the finder list: the strongest packet of one MAC. */
data class FoundSensor(val advertisement: SensorAdvertisement, val rssi: Int, val seenAtMillis: Long)

/** Keeps each MAC once. The reading and the time stamp follow the packet with the strongest RSSI. */
class SensorFinderList(private val maxRows: Int = MAX_ROWS) {
    private val rows = LinkedHashMap<String, FoundSensor>()

    /** Adds one packet. Returns false for a new MAC when the list is full. */
    fun add(ad: SensorAdvertisement, rssi: Int, nowMillis: Long): Boolean {
        val old = rows[ad.mac]
        if (old == null) {
            if (rows.size >= maxRows) return false
            rows[ad.mac] = FoundSensor(ad, rssi, nowMillis)
        } else if (rssi >= old.rssi) {
            rows[ad.mac] = FoundSensor(ad, rssi, nowMillis)
        }
        return true
    }

    /** Rows with the strongest RSSI first. */
    fun sorted(): List<FoundSensor> = rows.values.sortedByDescending { it.rssi }

    fun clear() = rows.clear()

    companion object {
        const val MAX_ROWS = 32
    }
}
