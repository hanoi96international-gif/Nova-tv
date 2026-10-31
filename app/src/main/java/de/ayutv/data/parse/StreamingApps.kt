package de.ayutv.data.parse

/**
 * Reiter „Apps“: Verknüpfungen zu Streaming-Apps. DnA-TV spielt deren Inhalte nicht selbst ab (Kopierschutz und
 * Nutzungsbedingungen der Anbieter), sondern startet die offizielle App: auf dem Fire TV die Fire-TV-Fassung, auf dem
 * Handy die Handy-Fassung, unter Windows die Webseite im Standard-Browser. Mit Zurück kommt man wieder zu DnA-TV.
 */
object StreamingApps {
    /**
     * [tvPackages]: Paketnamen auf Fire TV / Android TV (bevorzugt), [phonePackages]: auf dem Handy; die erste
     * installierte wird gestartet. [web]: Adresse für Windows. [color]: Kachelfarbe (ARGB).
     */
    data class App(
        val id: String,
        val name: String,
        val tvPackages: List<String>,
        val phonePackages: List<String>,
        val web: String,
        val color: Long,
    )

    val ALL = listOf(
        App("netflix", "Netflix", listOf("com.netflix.ninja"), listOf("com.netflix.mediaclient"), "https://www.netflix.com/browse", 0xFFE50914),
        App(
            "youtube", "YouTube",
            listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube.googletv"),
            listOf("com.google.android.youtube"),
            "https://www.youtube.com", 0xFFFF0000,
        ),
        App(
            "prime", "Prime Video",
            listOf("com.amazon.avod", "com.amazon.amazonvideo.livingroom"),
            listOf("com.amazon.avod.thirdpartyclient"),
            "https://www.primevideo.com", 0xFF1A98FF,
        ),
        App("spotify", "Spotify", listOf("com.spotify.tv.android"), listOf("com.spotify.music"), "https://open.spotify.com", 0xFF1DB954),
    )

    /** Alle Paketnamen (für die Sichtbarkeit unter Android 11+ im Manifest und für Tests). */
    val packages: List<String> get() = ALL.flatMap { it.tvPackages + it.phonePackages }

    /**
     * Reihenfolge für die Anzeige: zuerst wie gespeichert ([order], Kennungen), neue Apps hinten; ohne die
     * ausgeblendeten ([hidden]).
     */
    fun arrange(order: List<String>, hidden: Set<String>): List<App> {
        val byId = ALL.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] } + ALL.filter { it.id !in order }
        return ordered.distinctBy { it.id }.filter { it.id !in hidden }
    }

    /** Neue Reihenfolge, nachdem [id] um [step] verschoben wurde (−1 nach vorn, +1 nach hinten). */
    fun move(order: List<String>, id: String, step: Int): List<String> {
        val full = (order.filter { o -> ALL.any { it.id == o } } + ALL.map { it.id }.filter { it !in order }).distinct().toMutableList()
        val from = full.indexOf(id)
        if (from < 0) return full
        val to = (from + step).coerceIn(0, full.size - 1)
        full.removeAt(from)
        full.add(to, id)
        return full
    }
}
