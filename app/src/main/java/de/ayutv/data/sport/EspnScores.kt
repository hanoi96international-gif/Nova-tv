package de.ayutv.data.sport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Spielpläne und Stände außerhalb der deutschen Ligen (NHL, NBA, NFL, Champions League, Premier League …) aus dem
 * frei zugänglichen Scoreboard von ESPN. Damit steht auch bei „Live: NHL“ die Partie („Toronto Maple Leafs – Boston
 * Bruins“). Heimmannschaft zuerst, wie bei OpenLigaDB.
 */
object EspnScores {
    /** Eigene Liga-Kennung → Pfad bei ESPN. */
    val LEAGUES = linkedMapOf(
        "nhl" to "hockey/nhl",
        "nba" to "basketball/nba",
        "nfl" to "football/nfl",
        "uefa.champions" to "soccer/uefa.champions",
        "uefa.europa" to "soccer/uefa.europa",
        "uefa.europa.conf" to "soccer/uefa.europa.conf",
        "eng.1" to "soccer/eng.1",
        "esp.1" to "soccer/esp.1",
        "ita.1" to "soccer/ita.1",
        "fra.1" to "soccer/fra.1",
        "ger.dfb_pokal" to "soccer/ger.dfb_pokal",
    )

    /** Anzeigename der Liga für die Anzeigetafel. */
    val NAMES = mapOf(
        "nhl" to "NHL", "nba" to "NBA", "nfl" to "NFL", "uefa.champions" to "Champions League", "uefa.europa" to "Europa League",
        "uefa.europa.conf" to "Conference League", "eng.1" to "Premier League", "esp.1" to "LaLiga", "ita.1" to "Serie A",
        "fra.1" to "Ligue 1", "ger.dfb_pokal" to "DFB-Pokal",
    )

    /** Adresse für die Spiele von gestern bis morgen (UTC-Tage; US-Abendspiele liegen bei uns in der Nacht). */
    fun url(league: String, now: Long, base: String = "https://site.api.espn.com"): String? {
        val path = LEAGUES[league] ?: return null
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val from = day.format(now - DAY)
        val to = day.format(now + DAY)
        return "$base/apis/site/v2/sports/$path/scoreboard?dates=$from-$to&limit=200"
    }

    private const val DAY = 86_400_000L
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Antwort von /scoreboard lesen. */
    fun parse(body: String, league: String): List<LiveScores.Match> {
        val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val events = root["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { element ->
            runCatching {
                val event = element.jsonObject
                fun JsonObject.str(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" }.orEmpty()
                val competition = (event["competitions"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@runCatching null
                val competitors = (competition["competitors"] as? JsonArray).orEmpty().map { it.jsonObject }
                val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: competitors.getOrNull(0) ?: return@runCatching null
                val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: competitors.getOrNull(1) ?: return@runCatching null
                val homeTeam = home["team"]?.jsonObject ?: return@runCatching null
                val awayTeam = away["team"]?.jsonObject ?: return@runCatching null
                val kickoff = parseUtc(event.str("date")) ?: return@runCatching null
                val type = (competition["status"] as? JsonObject ?: event["status"] as? JsonObject)?.get("type") as? JsonObject
                val state = type?.str("state").orEmpty()
                val finished = type?.get("completed")?.jsonPrimitive?.booleanOrNull ?: (state == "post")
                val venue = competition["venue"] as? JsonObject
                val g1 = home.str("score").toIntOrNull() ?: 0
                val g2 = away.str("score").toIntOrNull() ?: 0
                LiveScores.Match(
                    id = ID_BASE + (event.str("id").toLongOrNull() ?: kickoff),
                    league = league,
                    team1 = homeTeam.str("displayName"), short1 = homeTeam.str("shortDisplayName"),
                    team2 = awayTeam.str("displayName"), short2 = awayTeam.str("shortDisplayName"),
                    kickoff = kickoff, goals1 = g1, goals2 = g2, finished = finished,
                    goalCount = if (state == "pre") 0 else g1 + g2, lastGoal = null,
                    city = (venue?.get("address") as? JsonObject)?.str("city")?.ifEmpty { null },
                    stadium = venue?.str("fullName")?.ifEmpty { null },
                    icon1 = homeTeam.str("logo").ifEmpty { null }, icon2 = awayTeam.str("logo").ifEmpty { null },
                    state = state.ifEmpty { null },
                    statusText = type?.str("shortDetail")?.ifEmpty { null },
                ).takeIf { it.team1.isNotEmpty() && it.team2.isNotEmpty() }
            }.getOrNull()
        }
    }

    /** Getrennt von den OpenLigaDB-Kennungen. */
    private const val ID_BASE = 9_000_000_000_000L

    private fun parseUtc(text: String): Long? {
        if (text.isEmpty()) return null
        // ESPN: „2026-11-08T00:00Z“ (ohne Sekunden) oder mit Sekunden
        val plain = text.removeSuffix("Z").substringBefore('.')
        val pattern = if (plain.count { it == ':' } >= 2) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd'T'HH:mm"
        return runCatching { SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(plain)?.time }.getOrNull()
    }
}
