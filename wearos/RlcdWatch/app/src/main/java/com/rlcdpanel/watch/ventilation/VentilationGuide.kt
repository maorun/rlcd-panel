package com.rlcdpanel.watch.ventilation

import com.rlcdpanel.watch.protocol.CachedOutdoor
import com.rlcdpanel.watch.protocol.CachedTelemetry
import com.rlcdpanel.watch.protocol.TelemetryFrame
import java.util.Locale
import kotlin.math.exp

/**
 * Action recommendations from ventilation analysis.
 */
enum class VentilationAction {
    LUEFTUNG_STARTEN,
    LUEFTUNG_BEENDEN,
    LUEFTUNG_ABBRECHEN,
    WEITER_LUEFTEN,
    NICHT_LUEFTEN,
    NEUTRAL
}

/**
 * Semantic status colors for the ventilation UI.
 */
enum class StatusFarbe(val hexColor: Long) {
    BLAU(0xFF0D47A1),
    ORANGE(0xFFE65100),
    ROT(0xFFB71C1C),
    GRUEN(0xFF1B5E20),
    GRAU(0xFF37474F),
    GELB(0xFFF57F17)
}

/**
 * Calculated absolute humidity values and difference (g/m3).
 */
data class VentilationDaten(
    val afInnen: Double,
    val afAussen: Double,
    val differenz: Double
)

/**
 * Result of the ventilation analysis.
 */
data class VentilationEmpfehlung(
    val aktion: VentilationAction,
    val statusFarbe: StatusFarbe,
    val titel: String,
    val nachricht: String,
    val daten: VentilationDaten,
    val maxDauerMinuten: Int? = null
)

/**
 * Ventilation guidance logic ported from user specification.
 */
object VentilationGuide {
    const val ZIEL_FEUCHTE_STOPP = 48.0
    const val ZIEL_FEUCHTE_START = 53.0
    const val HYSTERESE_START = 0.5
    const val HYSTERESE_STOPP = 0.1
    const val KAELTE_GRENZE = 5.0
    const val MIN_TEMP_INNEN = 17.5

    const val MIN_TEMP_C = -50.0
    const val MAX_TEMP_C = 60.0

    /**
     * Calculates absolute humidity in g/m3.
     * Returns null for invalid or out-of-range inputs.
     */
    fun berechneAbsoluteFeuchte(tempCelsius: Double, relFeuchteProzent: Double): Double? {
        if (!tempCelsius.isFinite() || !relFeuchteProzent.isFinite() ||
            tempCelsius < MIN_TEMP_C || tempCelsius > MAX_TEMP_C ||
            relFeuchteProzent < 0.0 || relFeuchteProzent > 100.0
        ) {
            return null
        }
        val saettigungsDampfdruck = 6.112 * exp((17.67 * tempCelsius) / (tempCelsius + 243.5))
        val dampfdruck = saettigungsDampfdruck * (relFeuchteProzent / 100.0)
        return (dampfdruck * 216.74) / (tempCelsius + 273.15)
    }

    /**
     * Advice for the focused room from cached data, or null. No advice without an outdoor
     * source, for the outdoor tile as focused room, for a stale outdoor frame and for an
     * Open-Meteo reading older than 1 hour. The advice never assumes an open window.
     */
    fun advise(
        indoorTempC: Double?,
        indoorRhPct: Double?,
        focusedRoom: Int,
        outdoor: CachedOutdoor?,
        outdoorCached: CachedTelemetry?,
        nowMillis: Long
    ): VentilationEmpfehlung? {
        if (outdoor == null || indoorTempC == null || indoorRhPct == null) {
            return null
        }
        val outTemp: Double?
        val outRh: Double?
        val slot = outdoor.slotIndex
        if (slot != null) {
            if (slot == focusedRoom || outdoorCached == null || outdoorCached.isStale(nowMillis)) {
                return null
            }
            val outTile = outdoorCached.frame.tiles.getOrNull(slot % TelemetryFrame.TILES_PER_PAGE)
            if (outdoorCached.frame.page != slot / TelemetryFrame.TILES_PER_PAGE ||
                outTile == null || !outTile.isValid
            ) {
                return null
            }
            outTemp = outTile.temperatureC
            outRh = outTile.humidityPct
        } else {
            val meteo = outdoor.meteo
            if (meteo == null || meteo.isStale(nowMillis)) {
                return null
            }
            outTemp = meteo.tempC
            outRh = meteo.rhPct
        }
        if (outTemp == null || outRh == null) {
            return null
        }
        return analysiereLueftung(indoorTempC, indoorRhPct, outTemp, outRh)
    }

    /** One short word for a watch face: `Lüften`, `Zu` or `--`. */
    fun shortWord(advice: VentilationEmpfehlung?): String = when (advice?.aktion) {
        VentilationAction.LUEFTUNG_STARTEN -> "Lüften"
        VentilationAction.NICHT_LUEFTEN, VentilationAction.NEUTRAL -> "Zu"
        else -> "--"
    }

    /** Absolute humidity difference (indoor minus outdoor) in g/m3, for example `+2.3`. */
    fun shortDifference(advice: VentilationEmpfehlung): String =
        String.format(Locale.US, "%+.1f", Math.round(advice.daten.differenz * 10) / 10.0)

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    private fun formatDiff(value: Double): String {
        return String.format(Locale.US, "%.1f", value)
    }

    /**
     * Analyses indoor and outdoor climate and produces ventilation recommendation.
     * Returns null if any climate value is invalid or out of range.
     *
     * @param tempInnen Room temperature in Celsius
     * @param rFInnen Room relative humidity in percent
     * @param tempAussen Outdoor temperature in Celsius
     * @param rFAussen Outdoor relative humidity in percent
     * @param istGeradeAmLueften True if user has window open / active ventilation
     * @param lueftungsDauerMinuten Duration in minutes window has been open
     */
    fun analysiereLueftung(
        tempInnen: Double,
        rFInnen: Double,
        tempAussen: Double,
        rFAussen: Double,
        istGeradeAmLueften: Boolean = false,
        lueftungsDauerMinuten: Int = 0
    ): VentilationEmpfehlung? {
        val afInnen = berechneAbsoluteFeuchte(tempInnen, rFInnen) ?: return null
        val afAussen = berechneAbsoluteFeuchte(tempAussen, rFAussen) ?: return null
        val differenz = afInnen - afAussen
        val daten = VentilationDaten(afInnen = afInnen, afAussen = afAussen, differenz = differenz)

        // FALL A: Nutzer lüftet gerade (Prüfung auf Erfolg oder Abbruch)
        if (istGeradeAmLueften) {
            val maxErlaubteDauer = if (tempAussen < KAELTE_GRENZE) 5 else 15

            // 1. Erfolg: Ziel-Luftfeuchtigkeit erreicht!
            if (rFInnen <= ZIEL_FEUCHTE_STOPP) {
                return VentilationEmpfehlung(
                    aktion = VentilationAction.LUEFTUNG_BEENDEN,
                    statusFarbe = StatusFarbe.BLAU,
                    titel = "Ziel erreicht – Fenster schließen!",
                    nachricht = "Die Raumluft liegt jetzt bei optimalen ${formatNumber(rFInnen)} %. Du kannst das Fenster schließen.",
                    daten = daten
                )
            }

            // 2. Abbruch wegen Auskühlung (Raumtemperatur fällt zu stark)
            if (tempInnen <= MIN_TEMP_INNEN) {
                return VentilationEmpfehlung(
                    aktion = VentilationAction.LUEFTUNG_ABBRECHEN,
                    statusFarbe = StatusFarbe.ORANGE,
                    titel = "Lüftung abbrechen (Auskühlung)",
                    nachricht = "Die Raumtemperatur ist auf ${formatNumber(tempInnen)} °C gefallen. Fenster schließen, um Heizenergie zu sparen.",
                    daten = daten
                )
            }

            // 3. Abbruch wegen Zeitüberschreitung (Schutz der Bausubstanz)
            if (lueftungsDauerMinuten >= maxErlaubteDauer) {
                return VentilationEmpfehlung(
                    aktion = VentilationAction.LUEFTUNG_ABBRECHEN,
                    statusFarbe = StatusFarbe.ORANGE,
                    titel = "Lüftung abbrechen (Zeitüberschreitung)",
                    nachricht = "Du lüftest schon $lueftungsDauerMinuten Minuten. Jetzt schließen, damit die Wände nicht auskühlen.",
                    daten = daten
                )
            }

            // 4. Abbruch wegen Wetterumschwung (Draußen wird es plötzlich feuchter)
            if (differenz <= HYSTERESE_STOPP) {
                return VentilationEmpfehlung(
                    aktion = VentilationAction.LUEFTUNG_ABBRECHEN,
                    statusFarbe = StatusFarbe.ROT,
                    titel = "Lüftung abbrechen (Wetterumschwung)",
                    nachricht = "Draußen ist die Luftfeuchtigkeit gestiegen. Weiteres Lüften bringt nichts mehr.",
                    daten = daten
                )
            }

            // 5. Alles läuft noch gut -> Weiterlüften
            val restDiff = rFInnen - ZIEL_FEUCHTE_STOPP
            val restZeit = (maxErlaubteDauer - lueftungsDauerMinuten).coerceAtLeast(0)
            return VentilationEmpfehlung(
                aktion = VentilationAction.WEITER_LUEFTEN,
                statusFarbe = StatusFarbe.GRUEN,
                titel = "Lüftung läuft...",
                nachricht = "Noch ${formatNumber(restDiff)} % bis zum Zielwert. Restzeit: ca. $restZeit Min.",
                daten = daten
            )
        }

        // FALL B: Fenster ist geschlossen (Prüfung auf Start-Empfehlung)

        // 1. Raumluft ist im grünen Bereich (unter 53 %)
        if (rFInnen < ZIEL_FEUCHTE_START) {
            return VentilationEmpfehlung(
                aktion = VentilationAction.NICHT_LUEFTEN,
                statusFarbe = StatusFarbe.GRAU,
                titel = "Raumluft in Ordnung",
                nachricht = "Aktuell ${formatNumber(rFInnen)} % Luftfeuchtigkeit. Entfeuchten ist nicht nötig.",
                daten = daten
            )
        }

        // 2. Draußen ist es feuchter
        if (differenz < -HYSTERESE_START) {
            return VentilationEmpfehlung(
                aktion = VentilationAction.NICHT_LUEFTEN,
                statusFarbe = StatusFarbe.ROT,
                titel = "Fenster geschlossen halten",
                nachricht = "Draußen ist es feuchter (${formatDiff(afAussen)} g/m³ vs. ${formatDiff(afInnen)} g/m³).",
                daten = daten
            )
        }

        // 3. Unterschied zu gering
        if (differenz <= HYSTERESE_START) {
            return VentilationEmpfehlung(
                aktion = VentilationAction.NEUTRAL,
                statusFarbe = StatusFarbe.GELB,
                titel = "Kaum Entfeuchtungseffekt",
                nachricht = "Innen- und Außenfeuchte sind fast gleich.",
                daten = daten
            )
        }

        // 4. Start-Empfehlung geben
        val istSehrKalt = tempAussen < KAELTE_GRENZE
        val titel = if (istSehrKalt) "Kurz Stoßlüften (Kälteschutz!)" else "Jetzt optimal zum Lüften"
        val nachricht = if (istSehrKalt) {
            "Draußen sind es ${formatNumber(tempAussen)} °C. Maximal 5 Minuten stoßlüften."
        } else {
            "Du sparst ca. ${formatDiff(differenz)} g Wasser pro m³ ein."
        }
        val maxDauer = if (istSehrKalt) 5 else 15

        return VentilationEmpfehlung(
            aktion = VentilationAction.LUEFTUNG_STARTEN,
            statusFarbe = StatusFarbe.GRUEN,
            titel = titel,
            nachricht = nachricht,
            maxDauerMinuten = maxDauer,
            daten = daten
        )
    }
}
