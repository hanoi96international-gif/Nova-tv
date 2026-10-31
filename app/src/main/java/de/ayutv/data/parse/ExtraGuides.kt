package de.ayutv.data.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Zusätzliche freie Programmquellen, die es nicht als XMLTV-Datei gibt. DnA-TV holt sie selbst und schreibt sie
 * als XMLTV, damit sie wie jede andere Quelle eingelesen werden (Zuordnung über den Namen, nur fehlende Sender):
 *
 * - **MagentaTV**: Programm aller rund 330 Sender der Telekom (alle Regionalprogramme, Sky Cinema mit den neuen
 *   Namen, Sky Sport Kompakt, MagentaSport, myTeamTV …). Anmeldung als Gast, eine Abfrage je Tag für alle Sender.
 * - **Dyn**: Spielplan von Dyn (Volleyball, Handball, Basketball, Hockey, Tischtennis) als Sender „Dyn Volleyball“,
 *   „Dyn Handball“ usw. Gleichzeitige Spiele stehen zusammen in einem Eintrag.
 *
 * Die Adressen in [EpgSources.PUBLIC] sind Platzhalter ([MAGENTA], [DYN]); [write] erzeugt die Datei.
 */
object ExtraGuides {
    const val MAGENTA = "https://api.prod.sngtv.magentatv.de/EPG/JSON#dna-guide.xml"
    const val DYN = "https://production-cdn.d3.dyn.sport/api#dna-guide.xml"

    fun isExtra(url: String) = url == MAGENTA || url == DYN

    fun label(url: String): String? = when (url) {
        MAGENTA -> "MagentaTV"
        DYN -> "Dyn"
        else -> null
    }

    /** Dateiname im Zwischenspeicher. */
    fun fileName(url: String): String = when (url) {
        MAGENTA -> "magentatv-guide.xml"
        DYN -> "dyn-guide.xml"
        else -> url.substringAfterLast('/')
    }

    /** Quelle abrufen und als XMLTV nach [target] schreiben. Wirft [IOException], wenn nichts zu holen war. */
    fun write(url: String, http: OkHttpClient, target: File, days: Int = 3) {
        val xml = when (url) {
            MAGENTA -> magenta(http, days)
            DYN -> dyn(http)
            else -> throw IOException("Unbekannte Quelle $url")
        }
        val tmp = File(target.path + ".tmp")
        tmp.writeText(xml)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Datei nicht schreibbar: $target")
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val JSON_TYPE = "application/json".toMediaType()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    // ---------------------------------------------------------------- MagentaTV

    private const val MAGENTA_API = "https://api.prod.sngtv.magentatv.de/EPG/JSON"

    private fun magenta(http: OkHttpClient, days: Int): String {
        val auth = http.newCall(
            Request.Builder().url("$MAGENTA_API/Authenticate?SID=firstup&T=Windows_chrome_118").header("User-Agent", UA)
                .post(
                    """{"terminalid":"00:00:00:00:00:00","mac":"00:00:00:00:00:00","terminaltype":"WEBTV","utcEnable":1,"timezone":"Etc/GMT0","userType":3,"terminalvendor":"Unknown"}"""
                        .toRequestBody(JSON_TYPE)
                ).build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MagentaTV: Anmeldung HTTP ${response.code}")
            val token = runCatching { json.parseToJsonElement(response.body.string()).jsonObject["csrfToken"]?.jsonPrimitive?.content }.getOrNull()
                ?: throw IOException("MagentaTV: keine Anmeldung")
            val cookies = response.headers("Set-Cookie").map { it.substringBefore(';') }
                .filter { c -> MAGENTA_COOKIES.any { c.startsWith("$it=") } }
            token to cookies.joinToString("; ")
        }
        fun post(path: String, body: String): String = http.newCall(
            Request.Builder().url("$MAGENTA_API/$path").header("User-Agent", UA)
                .header("X_CSRFToken", auth.first).header("Cookie", auth.second)
                .post(body.toRequestBody(JSON_TYPE)).build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MagentaTV: $path HTTP ${response.code}")
            response.body.string()
        }
        val channels = post(
            "AllChannel",
            """{"channelNamespace":2,"filterlist":[{"key":"IsHide","value":"-1"}],"metaDataVer":"Channel/1.1","properties":[{"include":"/channellist/logicalChannel/contentId,/channellist/logicalChannel/name","name":"logicalChannel"}],"returnSatChannel":0}""",
        )
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val playbills = (0 until days.coerceIn(1, 7)).mapNotNull { offset ->
            val date = day.format(Date(now + offset * DAY_MS))
            runCatching {
                post(
                    "PlayBillList",
                    """{"count":-1,"isFillProgram":1,"offset":0,"properties":[{"include":"endtime,genres,name,starttime,channelid,introduce,subName","name":"playbill"}],"type":2,"begintime":"${date}000000","endtime":"${date}235959"}""",
                )
            }.getOrNull()
        }
        if (playbills.isEmpty()) throw IOException("MagentaTV: kein Programm")
        // Die Sammelabfrage lässt Sky und die Regionalprogramme aus: diese einzeln abfragen (je Sender alle Tage auf
        // einmal, sechs gleichzeitig).
        val withProgramme = HashSet<String>()
        for (body in playbills) Regex(""""channelid":"(\d+)"""").findAll(body).forEach { withProgramme += it.groupValues[1] }
        val missing = runCatching {
            json.parseToJsonElement(channels).jsonObject["channellist"]?.jsonArray.orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val id = o.str("contentId") ?: return@mapNotNull null
                id.takeIf { it !in withProgramme && !MAGENTA_SKIP.containsMatchIn(o.str("name").orEmpty()) }
            }
        }.getOrDefault(emptyList())
        val first = day.format(Date(now))
        val last = day.format(Date(now + days.coerceIn(1, 7) * DAY_MS))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val single = try {
            missing.map { id ->
                pool.submit<String?> {
                    runCatching {
                        post(
                            "PlayBillList",
                            """{"count":-1,"isFillProgram":1,"offset":0,"properties":[{"include":"endtime,genres,name,starttime,channelid,introduce,subName","name":"playbill"}],"type":2,"begintime":"${first}000000","channelid":"$id","endtime":"${last}000000"}""",
                        )
                    }.getOrNull()
                }
            }.mapNotNull { runCatching { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
        return magentaXmltv(channels, playbills + single)
    }

    private val MAGENTA_COOKIES = listOf("JSESSIONID", "CSESSIONID", "CSRFSESSION")
    private val MAGENTA_SKIP = Regex("""(?i)^(testkanal|meine aufnahmen|magentatv\+|lokal-tv)""")
    private val NO_PROGRAMME = Regex("""(?i)momentan kein programm""")

    /** Antworten von AllChannel und PlayBillList (je Tag eine) als XMLTV. Öffentlich für Tests. */
    fun magentaXmltv(channelsJson: String, playbillJsons: List<String>): String {
        val channels = json.parseToJsonElement(channelsJson).jsonObject["channellist"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { c -> (c.str("contentId") ?: return@mapNotNull null) to (c.str("name") ?: return@mapNotNull null) }
            .filter { (_, name) -> !MAGENTA_SKIP.containsMatchIn(name) }
        val ids = HashMap<String, String>()
        val used = HashSet<String>()
        val out = StringBuilder(1 shl 20)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"DnA-TV MagentaTV\">\n")
        for ((contentId, name) in channels) {
            var id = xmlId(name) + ".de@MagentaTV"
            if (!used.add(id)) {
                id = xmlId(name) + "." + contentId + ".de@MagentaTV"
                used += id
            }
            ids[contentId] = id
            out.append("  <channel id=\"").append(esc(id)).append("\"><display-name>").append(esc(name)).append("</display-name>")
            // „Warner TV Serie (Sky)“: auch ohne den Zusatz.
            name.replace(Regex("""\s*\(Sky\)\s*$"""), "").takeIf { it != name }?.let {
                out.append("<display-name>").append(esc(it)).append("</display-name>")
            }
            out.append("</channel>\n")
        }
        val seen = HashSet<String>()
        for (body in playbillJsons) {
            val list = runCatching { json.parseToJsonElement(body).jsonObject["playbilllist"]?.jsonArray }.getOrNull() ?: continue
            for (element in list) {
                val p = element as? JsonObject ?: continue
                val id = ids[p.str("channelid") ?: continue] ?: continue
                val title = p.str("name")?.takeIf { it.isNotBlank() } ?: continue
                if (NO_PROGRAMME.containsMatchIn(title)) continue
                val start = magentaTime(p.str("starttime")) ?: continue
                val stop = magentaTime(p.str("endtime")) ?: continue
                if (!seen.add("$id|$start")) continue
                programme(out, id, start, stop, title, p.str("subName"), p.str("introduce"), p.str("genres")?.substringBefore(','))
            }
        }
        out.append("</tv>\n")
        return out.toString()
    }

    /** "2026-10-30 21:00:00 UTC+00:00" → XMLTV-Zeit. */
    private fun magentaTime(value: String?): String? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2}) UTC([+-])(\d{2}):(\d{2})""").find(value ?: return null) ?: return null
        val g = m.groupValues
        return "${g[1]}${g[2]}${g[3]}${g[4]}${g[5]}${g[6]} ${g[7]}${g[8]}${g[9]}"
    }

    // ---------------------------------------------------------------- Dyn

    private const val DYN_API = "https://production-cdn.d3.dyn.sport/api"

    /** Seite je Sportart → Name des Senders. */
    private val DYN_SPORTS = listOf(
        listOf("sport_volleyball") to "Volleyball",
        listOf("sport_handball") to "Handball",
        listOf("sport_basketball") to "Basketball",
        listOf("sport_hockey") to "Hockey",
        listOf("sport_tischtennis", "sport_tabletennis", "sport_table_tennis") to "Tischtennis",
    )

    data class DynEvent(val id: String, val title: String, val competition: String?, val start: Long, val stop: Long)

    private fun dyn(http: OkHttpClient): String {
        fun get(url: String): String? = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()).execute().use { r ->
                if (r.isSuccessful) r.body.string() else null
            }
        }.getOrNull()
        val bySport = LinkedHashMap<String, List<DynEvent>>()
        for ((paths, sport) in DYN_SPORTS) {
            val events = LinkedHashMap<String, DynEvent>()
            for (path in paths) {
                val page = get("$DYN_API/page?path=/$path&device=web_browser&sub=Anonymous") ?: continue
                val root = runCatching { json.parseToJsonElement(page).jsonObject }.getOrNull() ?: continue
                if (root["entries"] == null) continue
                dynEvents(root).forEach { events.putIfAbsent(it.id, it) }
                // Weitere Seiten der Listen mit kommenden Spielen („Live & Demnächst“).
                for (next in dynNextPages(root).take(6)) {
                    var url: String? = next
                    var pages = 0
                    while (url != null && pages++ < 4) {
                        val body = get(DYN_API + url + "&device=web_browser") ?: break
                        val list = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: break
                        val found = dynItems(list["items"])
                        if (found.isEmpty()) break
                        found.forEach { events.putIfAbsent(it.id, it) }
                        url = (list["paging"] as? JsonObject)?.str("next")
                    }
                }
                break
            }
            if (events.isNotEmpty()) bySport[sport] = events.values.toList()
        }
        if (bySport.isEmpty()) throw IOException("Dyn: kein Spielplan")
        return dynXmltv(bySport)
    }

    /** Alle Spiele mit Termin auf einer Seite (alle Listen). */
    fun dynEvents(page: JsonObject): List<DynEvent> =
        page["entries"]?.let { it as? JsonArray }.orEmpty().flatMap { entry -> dynItems(((entry as? JsonObject)?.get("list") as? JsonObject)?.get("items")) }

    /** Weitere Seiten der Listen „Live & Demnächst“ (relative Adressen wie "/lists/75024?page=2&sub=Anonymous"). */
    private fun dynNextPages(page: JsonObject): List<String> =
        page["entries"]?.let { it as? JsonArray }.orEmpty().mapNotNull { entry ->
            val e = entry as? JsonObject ?: return@mapNotNull null
            val title = e.str("title").orEmpty()
            if (!title.contains("Live", ignoreCase = true) && !title.contains("Demnächst", ignoreCase = true)) return@mapNotNull null
            ((e["list"] as? JsonObject)?.get("paging") as? JsonObject)?.str("next")
        }

    private fun dynItems(items: JsonElement?): List<DynEvent> = (items as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item.str("type") != "event") return@mapNotNull null
        val meta = (item["customMetadata"] as? JsonArray).orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            (o.str("name") ?: return@mapNotNull null) to (o.str("value") ?: return@mapNotNull null)
        }.toMap()
        val start = isoTime(meta["ScheduleStart"] ?: item.str("eventStartDate")) ?: return@mapNotNull null
        val stop = isoTime(meta["ScheduleEnd"] ?: item.str("eventEndDate")) ?: return@mapNotNull null
        if (stop <= start) return@mapNotNull null
        val teams = listOfNotNull(meta["TeamAname"], meta["TeamBname"]).takeIf { it.size == 2 }?.joinToString(" – ")
        DynEvent(item.str("id") ?: "$start${item.str("title")}", teams ?: item.str("title") ?: return@mapNotNull null, meta["CompetitionName"], start, stop)
    }

    /** Spiele je Sportart als Sender; überlappende Spiele werden ein Eintrag („A – B · C – D“). */
    fun dynXmltv(bySport: Map<String, List<DynEvent>>): String {
        val out = StringBuilder(64 * 1024)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"DnA-TV Dyn\">\n")
        for (sport in bySport.keys) {
            out.append("  <channel id=\"Dyn.").append(sport).append(".de@Dyn\"><display-name>Dyn ").append(sport)
                .append("</display-name></channel>\n")
        }
        val time = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for ((sport, events) in bySport) {
            val sorted = events.sortedWith(compareBy({ it.start }, { it.stop }))
            var block = ArrayList<DynEvent>()
            var blockStop = 0L
            fun flush() {
                if (block.isEmpty()) return
                val start = block.minOf { it.start }
                val stop = block.maxOf { it.stop }
                val title = if (block.size == 1) block[0].title else
                    block.take(2).joinToString(" · ") { it.title } + if (block.size > 2) " (+${block.size - 2})" else ""
                val clock = SimpleDateFormat("HH:mm", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }
                val description = block.joinToString("\n") { e -> "${clock.format(Date(e.start))} ${e.title}" + (e.competition?.let { " ($it)" } ?: "") }
                val sub = block.mapNotNull { it.competition }.distinct().joinToString(", ").ifEmpty { null }
                programme(out, "Dyn.$sport.de@Dyn", time.format(Date(start)), time.format(Date(stop)), "$sport: $title", sub, description, "Sport")
                block = ArrayList()
            }
            for (e in sorted) {
                if (block.isNotEmpty() && e.start >= blockStop) flush()
                if (block.isEmpty()) blockStop = e.stop
                block += e
                blockStop = maxOf(blockStop, e.stop)
            }
            flush()
        }
        out.append("</tv>\n")
        return out.toString()
    }

    /** "2026-11-01T10:00:00.0000000Z" → Millisekunden. */
    private fun isoTime(value: String?): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""").find(value ?: return null) ?: return null
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return runCatching { format.parse(m.value)?.time }.getOrNull()
    }

    // ---------------------------------------------------------------- XMLTV

    private fun programme(out: StringBuilder, channel: String, start: String, stop: String, title: String, sub: String?, desc: String?, category: String?) {
        out.append("  <programme start=\"").append(start).append("\" stop=\"").append(stop).append("\" channel=\"").append(esc(channel)).append("\">")
        out.append("<title>").append(esc(title)).append("</title>")
        sub?.takeIf { it.isNotBlank() }?.let { out.append("<sub-title>").append(esc(it)).append("</sub-title>") }
        desc?.takeIf { it.isNotBlank() }?.let { out.append("<desc>").append(esc(it)).append("</desc>") }
        category?.takeIf { it.isNotBlank() }?.let { out.append("<category>").append(esc(it.trim())).append("</category>") }
        out.append("</programme>\n")
    }

    private fun xmlId(name: String) = name.replace(Regex("""[^\p{L}\p{N}+]+"""), ".").trim('.').ifEmpty { "Sender" }

    private fun esc(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> if (c >= ' ' || c == '\n' || c == '\t') sb.append(c)
        }
        return sb.toString()
    }

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private const val DAY_MS = 24 * 3_600_000L
}
