package de.ayutv.data.sport

import de.ayutv.data.parse.Teams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Vereinswappen für jede Partie, nicht nur für die Bundesliga-Spiele im Spielplan:
 * 1. alle Vereine der 1. und 2. Bundesliga und der 3. Liga (OpenLigaDB, auch wenn sie im Pokal oder Europapokal spielen),
 * 2. sonst die freie Vereinsdatenbank TheSportsDB (Premier League, La Liga, Serie A, Nationalmannschaften …).
 * Ergebnisse (auch „kein Wappen“) werden gemerkt, auf Wunsch in [file], damit nicht bei jedem Start neu gefragt wird.
 */
class Crests(
    private val http: OkHttpClient,
    private val file: File? = null,
    private val openLigaDb: String = "https://api.openligadb.de",
    private val sportsDb: String = "https://www.thesportsdb.com/api/v1/json/123",
) {
    /** Vereinsname (vereinfacht) → Wappen-Adresse, "" = keins gefunden. */
    private val known = ConcurrentHashMap<String, String>()
    @Volatile private var german: List<Pair<String, String>> = emptyList()
    @Volatile private var germanAt = 0L
    private val germanLock = Mutex()
    /** TheSportsDB erlaubt nur wenige Anfragen pro Minute: nacheinander. */
    private val lookups = Semaphore(1)

    init {
        runCatching {
            file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val i = line.indexOf('\t')
                if (i > 0) known[line.substring(0, i)] = line.substring(i + 1)
            }
        }
    }

    /** Schon bekanntes Wappen (ohne Netz), sonst null. */
    fun cached(team: String): String? = known[key(team)]?.ifEmpty { null }

    /** Wappen des Vereins, notfalls aus dem Netz (einmal je Verein). */
    suspend fun of(team: String): String? {
        val key = key(team)
        if (key.isEmpty()) return null
        known[key]?.let { return it.ifEmpty { null } }
        val found = fromGerman(team) ?: lookups.withPermit {
            known[key]?.let { return@withPermit it.ifEmpty { null } }
            fromSportsDb(team)
        }
        remember(key, found.orEmpty())
        return found
    }

    private fun remember(key: String, url: String) {
        known[key] = url
        runCatching { file?.appendText("$key\t$url\n") }
    }

    private suspend fun fromGerman(team: String): String? {
        germanLock.withLock {
            if (System.currentTimeMillis() - germanAt > DAY_MS) {
                germanAt = System.currentTimeMillis()
                val season = Calendar.getInstance().let { if (it.get(Calendar.MONTH) >= Calendar.JULY) it.get(Calendar.YEAR) else it.get(Calendar.YEAR) - 1 }
                val loaded = withContext(Dispatchers.IO) {
                    listOf("bl1", "bl2", "bl3").flatMap { league ->
                        runCatching {
                            http.newCall(Request.Builder().url("$openLigaDb/getavailableteams/$league/$season").build()).execute().use { r ->
                                if (r.isSuccessful) parseOpenLigaTeams(r.body.string()) else emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }
                if (loaded.isNotEmpty()) german = loaded
            }
        }
        return match(team, german)
    }

    private suspend fun fromSportsDb(team: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$sportsDb/searchteams.php".toHttpUrl().newBuilder().addQueryParameter("t", team.trim()).build()
            http.newCall(Request.Builder().url(url).header("User-Agent", "DnA-TV").build()).execute().use { r ->
                if (r.isSuccessful) parseSportsDb(r.body.string(), team) else null
            }
        }.getOrNull()
    }

    companion object {
        private const val DAY_MS = 24 * 3_600_000L
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        private fun key(team: String) = team.lowercase().replace(NON_WORD, " ").trim()

        /** Antwort von /getavailableteams/<liga>/<saison>: (Vereinsname, Wappen) – Kurzname zusätzlich. */
        fun parseOpenLigaTeams(body: String): List<Pair<String, String>> {
            val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
            return root.flatMap { element ->
                val o = element as? JsonObject ?: return@flatMap emptyList()
                fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }.orEmpty()
                val icon = LiveScores.crest(str("teamIconUrl")) ?: return@flatMap emptyList()
                listOf(str("teamName"), str("shortName")).filter { it.length >= 3 }.map { it to icon }
            }
        }

        /** Antwort von searchteams.php: Wappen des ersten Fußballvereins, dessen Name passt. */
        fun parseSportsDb(body: String, team: String): String? {
            val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val teams = root["teams"] as? JsonArray ?: return null
            return teams.asSequence().mapNotNull { it as? JsonObject }
                .filter { it["strSport"]?.jsonPrimitive?.contentOrNull?.equals("Soccer", ignoreCase = true) != false }
                .filter { t -> match(team, listOf((t["strTeam"]?.jsonPrimitive?.contentOrNull.orEmpty()) to "x")) != null }
                .mapNotNull { t -> (t["strBadge"] ?: t["strTeamBadge"])?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } }
                .firstOrNull()
        }

        /**
         * Passender Verein aus [candidates] (Name, Wappen): alle aussagekräftigen Wörter des einen stecken im anderen
         * („Bor. Dortmund“ ↔ „Borussia Dortmund“, „Schalke“ ↔ „FC Schalke 04“). Frauen/Jugend nur bei genau dem Namen.
         */
        fun match(team: String, candidates: List<Pair<String, String>>): String? {
            val wanted = Teams.keywords(team)
            if (wanted.isEmpty()) return candidates.firstOrNull { it.first.equals(team.trim(), ignoreCase = true) }?.second
            return candidates.firstOrNull { (name, _) ->
                val have = Teams.keywords(name)
                have.isNotEmpty() && (have.containsAll(wanted) || wanted.containsAll(have)) && Teams.plays(team, "$name – x")
            }?.second
        }
    }
}
