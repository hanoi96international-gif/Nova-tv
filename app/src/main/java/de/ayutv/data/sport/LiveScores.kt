package de.ayutv.data.sport

import de.ayutv.data.parse.Teams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Spielstände der Bundesliga, 2. und 3. Liga von OpenLigaDB (frei, ohne Konto). Für die Sport-Übersicht
 * (Stand und Spielminute bei laufenden Spielen) und für Tor-Hinweise im Bild. Ohne Netz oder bei Fehlern: nichts.
 */
class LiveScores(private val http: OkHttpClient, private val base: String = "https://api.openligadb.de") {
    data class Match(
        val id: Long,
        val league: String,
        val team1: String,
        val short1: String,
        val team2: String,
        val short2: String,
        val kickoff: Long,
        val goals1: Int,
        val goals2: Int,
        val finished: Boolean,
        /** Wie viele Tore bisher eingetragen sind (für Tor-Hinweise). */
        val goalCount: Int,
        /** Letzter Torschütze mit Minute, falls bekannt („Füllkrug 67'“). */
        val lastGoal: String?,
        /** Spielort laut OpenLigaDB (Stadt, Stadion), falls angegeben. */
        val city: String? = null,
        val stadium: String? = null,
        /** Vereinswappen (Bild-Adresse laut OpenLigaDB), falls angegeben. */
        val icon1: String? = null,
        val icon2: String? = null,
    ) {
        val score: String get() = "$goals1:$goals2"
        fun started(now: Long) = now >= kickoff
        fun live(now: Long) = !finished && now >= kickoff && now < kickoff + MAX_DURATION_MS
        val pairing: String get() = "$team1 – $team2"

        /** Ungefähre Spielminute aus der Anstoßzeit (15 min Pause); „HZ“ in der Pause. */
        fun minute(now: Long): String? {
            if (!live(now)) return null
            val elapsed = ((now - kickoff) / 60_000).toInt()
            return when {
                elapsed <= 45 -> "${elapsed.coerceAtLeast(1)}'"
                elapsed <= 47 -> "45+'"
                elapsed < 62 -> "HZ"
                elapsed - 15 <= 90 -> "${elapsed - 15}'"
                else -> "90+'"
            }
        }

        /** Spielt diese Partie in der TV-Paarung [pairing] (beide Vereine, Lang- oder Kurzname)? */
        fun matches(pairing: String, context: String = ""): Boolean {
            fun side(name: String, short: String) = Teams.plays(name, pairing, context) || (short.length >= 3 && Teams.plays(short, pairing, context))
            return side(team1, short1) && side(team2, short2)
        }
    }

    private val lock = Mutex()
    @Volatile private var cached: List<Match> = emptyList()
    @Volatile private var cachedAt = 0L

    /** Aktuelle Spieltage aller Ligen, höchstens einmal pro Minute neu geladen. */
    suspend fun current(now: Long = System.currentTimeMillis()): List<Match> = lock.withLock {
        if (now - cachedAt < CACHE_MS) return@withLock cached
        val fresh = withContext(Dispatchers.IO) {
            LEAGUES.flatMap { league ->
                runCatching {
                    http.newCall(Request.Builder().url("$base/getmatchdata/$league").build()).execute().use { response ->
                        if (!response.isSuccessful) emptyList() else parse(response.body.string(), league)
                    }
                }.getOrDefault(emptyList())
            }
        }
        // Fehlschlag (kein Netz): die alten Stände behalten, aber nicht sofort wieder fragen.
        if (fresh.isNotEmpty() || cached.isEmpty()) cached = fresh
        cachedAt = now
        cached
    }

    /**
     * Sendung ohne Paarung („Live: 2. BL“, in der Beschreibung „Aus der Schüco-Arena in Bielefeld.“): das Spiel, das in
     * ihrer Zeit angepfiffen wird und dessen Stadion, Stadt oder Verein im Text vorkommt. Bei mehreren gleich guten: keins.
     * Konferenzen und Multiview zeigen mehrere Spiele: keins.
     */
    fun byVenue(matches: List<Match>, title: String, subtitle: String?, description: String?, start: Long, stop: Long): Match? {
        val head = title + " " + subtitle.orEmpty()
        if (MULTI.containsMatchIn(head)) return null
        val text = Teams.textWords(head + " " + description.orEmpty())
        val league = leagueOf(head)
        val scored = matches
            .filter { it.kickoff in (start - 15 * MINUTE) until stop && (league == null || it.league == league) }
            .map { m ->
                val venue = listOfNotNull(m.city, m.stadium).flatMap { Teams.textWords(it) }.filter { it.length >= 4 && it !in VENUE_WORDS }.toSet()
                // Stadion oder Stadt zählen doppelt (so nennt Sky Einzelspiele), Vereinsnamen einfach.
                val score = 2 * venue.count { it in text } + Teams.keywords(m.team1).count { it in text } + Teams.keywords(m.team2).count { it in text }
                m to score
            }
            .filter { it.second > 0 }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { scored.count { it.second == best.second } == 1 }
    }

    /** Eine Sport-Sendung ohne bekannte Paarung (für [byElimination]). */
    data class Open(val title: String, val subtitle: String?, val start: Long, val stop: Long)

    /**
     * Sendungen ohne Paarung, die weder Paarung noch Stadion zuordnen konnten („Live: 2. BL – Aus dem Millerntor-Stadion“,
     * der Spielplan kennt das Stadion nicht): Laufen zur selben Zeit drei Zweitliga-Spiele und sind zwei schon anderen
     * Sendern zugeordnet ([taken]), gehört das übrige zu dieser Sendung. Nur wenn es genau ein übriges Spiel gibt und
     * keine andere offene Sendung es auch beanspruchen könnte; Konferenzen nie. Ergebnis: Index in [open] → Spiel.
     */
    fun byElimination(matches: List<Match>, open: List<Open>, taken: Set<Match>): Map<Int, Match> {
        val candidates = open.map { o ->
            val head = o.title + " " + o.subtitle.orEmpty()
            val league = leagueOf(head)
            if (MULTI.containsMatchIn(head) || league == null) emptyList()
            else matches.filter { it.kickoff in (o.start - 15 * MINUTE) until o.stop && it.league == league && it !in taken }
        }
        val out = HashMap<Int, Match>()
        for ((index, list) in candidates.withIndex()) {
            val only = list.singleOrNull() ?: continue
            // Eine andere offene Sendung, die dasselbe Spiel (auch unter mehreren) beanspruchen könnte: nicht raten.
            if (candidates.withIndex().any { (i, other) -> i != index && only in other }) continue
            out[index] = only
        }
        return out
    }

    /** Das Spiel zur TV-Paarung, falls es eins gibt (Anstoß höchstens 3 Stunden neben der Sendung). */
    fun find(matches: List<Match>, pairing: String?, start: Long, context: String = ""): Match? {
        if (pairing == null) return null
        return matches.firstOrNull { kotlin.math.abs(it.kickoff - start) <= 3 * HOUR && it.matches(pairing, context) }
    }

    companion object {
        private val LEAGUES = listOf("bl1", "bl2", "bl3")
        private const val MINUTE = 60_000L
        /** Wörter, die in vielen Stadionnamen stehen: allein kein Hinweis auf den Ort. */
        private val VENUE_WORDS = setOf("arena", "stadion", "stadium", "park", "sportpark", "sportplatz", "kampfbahn")
        private val MULTI = Regex("""(?i)konferenz|multiview|alle spiele|highlights|zusammenfassung""")

        /** Welche Liga die Sendung nennt (bl1, bl2, bl3), sonst null. */
        fun leagueOf(text: String): String? = when {
            Regex("""(?i)\b3\.\s*liga\b""").containsMatchIn(text) -> "bl3"
            Regex("""(?i)\b2\.\s*(bl|bundesliga)\b""").containsMatchIn(text) -> "bl2"
            Regex("""(?i)\b(bl|bundesliga)\b""").containsMatchIn(text) -> "bl1"
            else -> null
        }
        private const val CACHE_MS = 60_000L
        private const val HOUR = 3_600_000L
        private const val MAX_DURATION_MS = 150 * 60_000L
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Antwort von /getmatchdata/<liga> lesen. */
        fun parse(body: String, league: String): List<Match> {
            val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
            return root.mapNotNull { element ->
                runCatching {
                    val o = element.jsonObject
                    fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }.orEmpty()
                    val t1 = o["team1"]?.jsonObject ?: return@runCatching null
                    val t2 = o["team2"]?.jsonObject ?: return@runCatching null
                    val kickoff = parseUtc(o.str("matchDateTimeUTC")) ?: return@runCatching null
                    val finished = o["matchIsFinished"]?.jsonPrimitive?.booleanOrNull ?: false
                    val goals = (o["goals"] as? JsonArray).orEmpty().map { it.jsonObject }
                    // Stand: das Tor mit den meisten Treffern (die Reihenfolge der Liste ist nicht verlässlich) …
                    val last = goals.maxByOrNull {
                        (it["scoreTeam1"]?.jsonPrimitive?.intOrNull ?: 0) + (it["scoreTeam2"]?.jsonPrimitive?.intOrNull ?: 0)
                    }
                    var g1 = last?.get("scoreTeam1")?.jsonPrimitive?.intOrNull ?: 0
                    var g2 = last?.get("scoreTeam2")?.jsonPrimitive?.intOrNull ?: 0
                    // … nach Spielende gilt das Endergebnis.
                    if (finished) {
                        (o["matchResults"] as? JsonArray).orEmpty().map { it.jsonObject }
                            .firstOrNull { it["resultTypeID"]?.jsonPrimitive?.intOrNull == 2 }?.let { result ->
                                g1 = result["pointsTeam1"]?.jsonPrimitive?.intOrNull ?: g1
                                g2 = result["pointsTeam2"]?.jsonPrimitive?.intOrNull ?: g2
                            }
                    }
                    val scorer = last?.let { goal ->
                        val name = goal.str("goalGetterName")
                        val minute = goal["matchMinute"]?.jsonPrimitive?.intOrNull
                        listOfNotNull(name.ifEmpty { null }, minute?.let { "$it'" }).joinToString(" ").ifEmpty { null }
                    }
                    Match(
                        id = o["matchID"]?.jsonPrimitive?.longOrNull ?: 0L,
                        league = league,
                        team1 = t1.str("teamName"), short1 = t1.str("shortName"),
                        team2 = t2.str("teamName"), short2 = t2.str("shortName"),
                        kickoff = kickoff, goals1 = g1, goals2 = g2, finished = finished,
                        goalCount = goals.size, lastGoal = scorer,
                        city = (o["location"] as? JsonObject)?.str("locationCity")?.ifEmpty { null },
                        stadium = (o["location"] as? JsonObject)?.str("locationStadium")?.ifEmpty { null },
                        icon1 = crest(t1.str("teamIconUrl")), icon2 = crest(t2.str("teamIconUrl")),
                    ).takeIf { it.team1.isNotEmpty() && it.team2.isNotEmpty() }
                }.getOrNull()
            }
        }

        /** Wappen-Adresse: nur Rastergrafiken über HTTP(S) (SVG zeigt der Bildlader nicht an). */
        fun crest(url: String): String? = url.trim().takeIf {
            it.startsWith("http") && !it.substringBefore('?').endsWith(".svg", ignoreCase = true)
        }

        private fun parseUtc(text: String): Long? = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(text.removeSuffix("Z").substringBefore('.'))?.time
        }.getOrNull()
    }
}
