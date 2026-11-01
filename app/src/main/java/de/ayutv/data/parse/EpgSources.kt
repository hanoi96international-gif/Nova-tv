package de.ayutv.data.parse

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Woher der TV-Guide kommt, ohne dass jemand eine Adresse eintragen muss: eigene Adresse(n) zuerst, dann die
 * des Anbieters. Bei Xtream ist das xmltv.php; bei einer M3U die Adressen aus dem Kopf (url-tvg) und, wenn die
 * Liste von einem Xtream-Server kommt (get.php?username=…&password=…), dessen xmltv.php. Spätere Quellen
 * füllen nur noch Sender, für die eine frühere nichts hatte.
 */
object EpgSources {
    private val SPLIT = Regex("""[\s,;]+""")

    /** Eingetragene oder erkannte Adressen (durch Komma, Leerzeichen oder Zeilen getrennt). */
    fun split(urls: String?): List<String> =
        urls.orEmpty().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun forXtream(epgUrl: String?, providerUrl: String): List<String> = (split(epgUrl) + providerUrl).distinct()

    fun forM3u(epgUrl: String?, playlistUrl: String): List<String> =
        (split(epgUrl) + listOfNotNull(xtreamGuideFor(playlistUrl))).distinct()

    /** "http://host:8080/get.php?username=a&password=b&type=m3u_plus" → ".../xmltv.php?username=a&password=b". */
    fun xtreamGuideFor(playlistUrl: String): String? {
        val uri = runCatching { URI(playlistUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        if (!uri.path.orEmpty().endsWith("/get.php")) return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            if (key.isEmpty()) null else key.lowercase() to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }.toMap()
        val user = query["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val password = query["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val base = uri.path.removeSuffix("/get.php")
        return "${uri.scheme}://${uri.host}$port$base/xmltv.php?username=${enc(user)}&password=${enc(password)}"
    }

    /** Eine freie Quelle und die Länder, für deren Sender sie gedacht ist (null = Sender ohne Länderangabe). */
    class Source(val url: String, val countries: Set<String?>)

    private val GERMAN = setOf(null, "de", "at", "ch")
    private const val EPGSHARE = "https://epgshare01.online/epgshare01/"
    private const val OPEN_EPG = "https://www.open-epg.com/files/"

    /**
     * Freie Programmquellen für Sender, die beim Anbieter fehlen. Spätere füllen nur noch Sender ohne Programm, und
     * eine Quelle wird nur geladen, wenn noch Sender aus ihren Ländern offen sind (Stand der Prüfung November 2026):
     *
     * - epgshare01 Deutschland (266 Sender), open-epg Deutschland 3 (Telekom-Liste: Regionalprogramme, Sky mit den
     *   neuen Namen, MagentaSport) und 5 (waipu.tv-Sender, Spartenkanäle wie „Stromberg“, „Pastewka“, Musik Box)
     * - epgshare01 Österreich und Schweiz (u. a. Sky Bundesliga unter altem Namen, Paramount+, Amazon Prime Video),
     *   open-epg Österreich (ORF-2-Regionalfenster, Laola1, Sky Sport Austria 8, Sky Cinema Blockbuster/Feelgood)
     * - Pluto TV und Samsung TV Plus Deutschland (kostenlose 24/7-Sender, z. B. „Dyn Sport Mix“, „Pluto TV Movies“,
     *   DAZN Rise, Sony One, Moviedome, Rakuten-Kanäle)
     * - epgshare01 Griechenland nur für griechische Sender (Magenta Series, Magenta Cinema, Magenta Sport, Novacinema)
     */
    val SOURCES = listOf(
        Source(EPGSHARE + "epg_ripper_DE1.xml.gz", GERMAN),
        Source(EPGSHARE + "epg_ripper_AT1.xml.gz", GERMAN),
        Source(EPGSHARE + "epg_ripper_CH1.xml.gz", GERMAN),
        Source(OPEN_EPG + "germany3.xml", GERMAN),
        Source(OPEN_EPG + "germany5.xml", GERMAN),
        // Nachrecherche Kurzbericht 01.11.2026: Deutschland 1 (334 Sender, u. a. Sky Sport 1–10, Sky Sport Bundesliga
        // ohne Nummer (UHD-Fassung), Sky Cinema Action/Classics/Family/Highlights, HGTV, Cartoon Network, Warner TV)
        // und 4 (395 Sender, u. a. GEO Television, HSE, hr-fernsehen, Curiosity Channel, DAZN FAST, AXN). Werden wie
        // alle freien Quellen nur geladen, wenn noch Sender offen sind, und höchstens einmal am Tag.
        Source(OPEN_EPG + "germany1.xml", GERMAN),
        Source(OPEN_EPG + "germany4.xml", GERMAN),
        Source(OPEN_EPG + "austria1.xml", GERMAN),
        Source("https://i.mjh.nz/PlutoTV/de.xml.gz", GERMAN),
        Source("https://i.mjh.nz/SamsungTVPlus/de.xml.gz", GERMAN),
        Source(EPGSHARE + "epg_ripper_GR1.xml.gz", setOf("gr")),
    )

    val PUBLIC: List<String> = SOURCES.map { it.url }

    /**
     * Lohnt die freie Quelle [url] für diese Sender (Namen der noch offenen)? Eigene Quellen (nicht in [SOURCES])
     * immer. So lädt der Fire TV z. B. das griechische Programm nur, wenn griechische Sender in der Liste sind.
     */
    fun relevant(url: String, names: Iterable<String>): Boolean {
        val countries = SOURCES.firstOrNull { it.url == url }?.countries ?: return true
        return names.any { EpgMatcher.analyse(it, isId = false).country in countries }
    }

    /** Dateiname im Zwischenspeicher: eindeutig je Adresse (Pluto TV und Samsung TV Plus heißen beide „de.xml.gz“). */
    fun cacheName(url: String): String {
        val file = url.substringAfterLast('/').substringBefore('?').ifEmpty { "guide.xml" }
        if ("i.mjh.nz/" !in url) return file
        val service = url.substringAfter("i.mjh.nz/").substringBefore('/').lowercase().filter { it.isLetterOrDigit() }
        return "$service-$file"
    }

    /** Kurzer Name einer Quelle für die Anzeige ("epgshare01 DE", "tv.example.com"). */
    fun label(url: String): String {
        Regex("""epg_ripper_([A-Z]{2})\d*\.xml""").find(url)?.let { return "epgshare01 ${it.groupValues[1]}" }
        Regex("""open-epg\.com/files/([a-z]+\d*)\.xml""").find(url)?.let { return "open-epg ${it.groupValues[1]}" }
        if ("i.mjh.nz/PlutoTV" in url) return "Pluto TV"
        if ("i.mjh.nz/SamsungTVPlus" in url) return "Samsung TV Plus"
        return runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/').take(40)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
