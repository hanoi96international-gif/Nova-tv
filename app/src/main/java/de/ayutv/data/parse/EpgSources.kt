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

    /**
     * Freies EPG von epgshare01 (täglich aktualisiert) für Sender, die beim Anbieter fehlen, oft Sky und DAZN.
     * Reihenfolge: Deutschland, Österreich, Schweiz (dort u. a. Sky Bundesliga unter altem Namen).
     */
    val PUBLIC = listOf(
        "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_AT1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_CH1.xml.gz",
    )

    /** Kurzer Name einer Quelle für die Anzeige ("epgshare01 DE", "tv.example.com"). */
    fun label(url: String): String {
        Regex("""epg_ripper_([A-Z]{2})\d*\.xml""").find(url)?.let { return "epgshare01 ${it.groupValues[1]}" }
        return runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/').take(40)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
