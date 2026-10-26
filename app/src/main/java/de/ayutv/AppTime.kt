package de.ayutv

import java.util.Locale
import java.util.TimeZone

/**
 * Zeitzone für Uhr und TV-Guide. Manche Fire TV stehen auf „GMT/UTC“ statt auf Berlin (z. B. nach
 * dem Zurücksetzen): Dann zeigte DnA-TV alle Zeiten eine Stunde (Sommer: zwei) zu früh. Bei deutscher
 * Sprache wird in dem Fall Mitteleuropa angenommen – nur für DnA-TV, die Geräteeinstellung bleibt.
 */
object AppTime {
    private val UTC_IDS = setOf("GMT", "UTC", "Etc/UTC", "Etc/GMT", "Etc/Universal", "Universal", "Zulu", "Etc/Zulu", "Etc/GMT+0", "Etc/GMT-0", "GMT0")

    /** War die Gerätezeitzone UTC und wurde ersetzt? (für den Hinweis in den Einstellungen) */
    @Volatile var corrected: Boolean = false
        private set

    fun fixZone() {
        val device = TimeZone.getDefault()
        val zone = replacement(device.id, Locale.getDefault()) ?: return
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        corrected = true
    }

    /** Ersatzzone für [deviceZone], oder null, wenn die Gerätezeitzone bleiben soll. */
    fun replacement(deviceZone: String, locale: Locale): String? {
        if (deviceZone !in UTC_IDS) return null
        return when {
            locale.country == "AT" -> "Europe/Vienna"
            locale.country == "CH" -> "Europe/Zurich"
            locale.language == "de" -> "Europe/Berlin"
            else -> null
        }
    }
}
