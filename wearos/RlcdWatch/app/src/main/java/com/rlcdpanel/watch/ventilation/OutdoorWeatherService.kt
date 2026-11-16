package com.rlcdpanel.watch.ventilation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Service to fetch current outdoor climate data from Open-Meteo weather API.
 */
object OutdoorWeatherService {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    /**
     * Fetches current weather for the given coordinates.
     */
    suspend fun fetchCurrent(
        latitude: Double = 52.52,
        longitude: Double = 13.41,
        locationName: String = "Open-Meteo"
    ): OutdoorClimate? = withContext(Dispatchers.IO) {
        val urlString = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m",
            latitude,
            longitude
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }

            val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseResponse(responseText, locationName)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parses the Open-Meteo JSON response.
     */
    fun parseResponse(jsonText: String, locationName: String = "Open-Meteo"): OutdoorClimate? {
        return try {
            val root = JSONObject(jsonText)
            val current = root.getJSONObject("current")
            val tempC = current.getDouble("temperature_2m")
            val rhPct = current.getDouble("relative_humidity_2m")

            if (tempC in -50.0..60.0 && rhPct in 0.0..100.0) {
                OutdoorClimate(
                    tempC = tempC,
                    rhPct = rhPct,
                    sourceName = locationName,
                    timestampMillis = System.currentTimeMillis()
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
