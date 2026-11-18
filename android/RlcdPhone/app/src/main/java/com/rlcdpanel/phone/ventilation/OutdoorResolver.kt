package com.rlcdpanel.phone.ventilation

import com.rlcdpanel.phone.protocol.TelemetryTile

/**
 * Pure freshness rules for the ventilation advice. The activity calls these functions on each render.
 */
object OutdoorResolver {
    /**
     * A telemetry frame counts as stale after this time without a packet. The panel advertises once for each
     * wake cycle (`wake_interval_s` up to 900 s, `night_wake_interval_s` up to 1800 s), so the limit is 35 min.
     */
    const val MAX_FRAME_AGE_MS: Long = 35 * 60 * 1000L

    /** An Open-Meteo reading counts as stale after this time. */
    const val MAX_OUTDOOR_AGE_MS: Long = 60 * 60 * 1000L

    /**
     * Returns the cached Open-Meteo reading only for the location it was fetched for. A changed
     * location drops the reading, so the next advice waits for a fetch of the new place.
     */
    fun cacheFor(
        cached: OutdoorClimate?,
        cachedLocation: OutdoorLocation?,
        current: OutdoorLocation
    ): OutdoorClimate? = cached?.takeIf { cachedLocation != null && isSamePlace(cachedLocation, current) }

    /** A rename keeps the coordinates, so it needs no new reading. */
    fun isSamePlace(a: OutdoorLocation, b: OutdoorLocation): Boolean =
        a.latitude == b.latitude && a.longitude == b.longitude

    /** A late reply counts only for the place that is current now. */
    fun acceptFetched(fetchedFor: OutdoorLocation, current: OutdoorLocation): Boolean =
        isSamePlace(fetchedFor, current)

    fun isFrameFresh(receivedAtMillis: Long?, nowMillis: Long): Boolean =
        receivedAtMillis != null && nowMillis - receivedAtMillis <= MAX_FRAME_AGE_MS

    /**
     * Returns the outdoor climate of the selected source, or `null` when the source has no fresh valid reading.
     */
    fun resolve(
        source: OutdoorSource,
        openMeteo: OutdoorClimate?,
        tiles: List<TelemetryTile>?,
        frameReceivedAtMillis: Long?,
        nowMillis: Long
    ): OutdoorClimate? = when (source) {
        is OutdoorSource.OpenMeteo ->
            openMeteo?.takeIf { nowMillis - it.timestampMillis <= MAX_OUTDOOR_AGE_MS }
        is OutdoorSource.SensorSlot -> {
            val tile = tiles?.getOrNull(source.tileIndex)
            val temp = tile?.temperatureC
            val rh = tile?.humidityPct
            if (temp != null && rh != null && frameReceivedAtMillis != null &&
                isFrameFresh(frameReceivedAtMillis, nowMillis)
            ) {
                OutdoorClimate(
                    tempC = temp,
                    rhPct = rh,
                    sourceName = source.displayName,
                    timestampMillis = frameReceivedAtMillis
                )
            } else {
                null
            }
        }
    }

    /**
     * Returns `true` when the focused room may get ventilation advice.
     */
    fun mayAdvise(
        isSelfComparison: Boolean,
        roomFrameFresh: Boolean,
        roomTile: TelemetryTile?,
        outdoor: OutdoorClimate?
    ): Boolean = !isSelfComparison && roomFrameFresh && roomTile?.isValid == true && outdoor != null
}
