package de.ayutv.data.parse

/**
 * Bildoptimierung für Full HD und kleiner (SDR): Den Bildmodus des Fernsehers kann keine App umschalten, deshalb
 * verbessert DnA-TV das Bild selbst, bevor es zum Fernseher geht – je nach Inhalt verschieden:
 *
 * - **Film:** nur leicht nachgeschärft, Farben und Kontrast bleiben, wie der Film gedreht wurde.
 * - **Serie, Show, Doku:** etwas schärfer, minimal mehr Kontrast.
 * - **Sport:** deutlich schärfer (Ball, Schrift, Gesichter), mehr Kontrast und etwas kräftigere Farben.
 * - **Fußball:** wie Sport, dazu ein satteres Rasengrün.
 * - **Nachrichten:** schärfere Schrift und Laufbänder.
 *
 * 4K und HDR bleiben unverändert: Dort ist das Bild schon gut und die Rechenzeit fehlt dem Gerät. Gemeinsam für
 * Fire TV (GPU-Effekte von Media3) und Windows (Filter von VLC).
 */
object PictureTuning {
    enum class Mode(val label: String) {
        OFF("Aus"),
        AUTO("Automatisch je Inhalt"),
        STRONG("Kräftig je Inhalt"),
        ;

        fun next(): Mode = entries[(ordinal + 1) % entries.size]

        companion object {
            fun of(name: String?): Mode = entries.firstOrNull { it.name == name } ?: AUTO
        }
    }

    enum class Content(val label: String) {
        FILM("Film"),
        SERIES("Serie"),
        SPORT("Sport"),
        FOOTBALL("Fußball"),
        NEWS("Nachrichten"),
        OTHER("Allgemein"),
    }

    /**
     * [sharpen]: Stärke der Nachschärfung (0 = aus, 0,3 = deutlich), [contrast]: −1…1 (0 = unverändert),
     * [saturation]: Farbsättigung in Prozentpunkten (−100…100, 0 = unverändert).
     */
    data class Profile(val content: Content, val sharpen: Float, val contrast: Float, val saturation: Float) {
        val label: String get() = content.label
    }

    private val FOOTBALL = Regex("""(?i)fu(ß|ss)ball|soccer|bundesliga|2\. ?bl\b|champions league|europa league|conference league|dfb.?pokal|premier league|la ?liga|serie a\b|ligue 1|länderspiel|uefa|fifa""")
    /** Andere Sportarten mit „Bundesliga“ oder Vereinsnamen wie im Fußball: kein Fußball-Profil. */
    private val OTHER_SPORT = Regex("""(?i)volleyball|handball|basketball|hockey|tischtennis|tennis|eishockey|formel|motorsport|golf|darts|boxen|ski|biathlon|radsport|nfl|nba|nhl|football americ|american football""")
    private val FOOTBALL_CHANNEL = Regex("""(?i)bundesliga|sportdigital fu|dazn|sky sport top event|premier league""")

    /**
     * Inhalt bestimmen: Film/Folge aus der Mediathek des Anbieters ([vodMovie]/[vodEpisode]), sonst aus der laufenden
     * Sendung ([title], [category]) und dem Sendernamen.
     */
    fun classify(
        channelName: String,
        title: String? = null,
        subtitle: String? = null,
        category: String? = null,
        vodMovie: Boolean = false,
        vodEpisode: Boolean = false,
    ): Content {
        if (vodEpisode) return Content.SERIES
        if (vodMovie) return Content.FILM
        val text = listOfNotNull(title, subtitle, category).joinToString(" ")
        val sportChannel = ProgrammeText.isSportChannel(channelName)
        val sport = sportChannel || (title != null && ProgrammeText.isSport(title, subtitle, category))
        if (sport) {
            val other = OTHER_SPORT.containsMatchIn(text) || OTHER_SPORT.containsMatchIn(channelName)
            return if (!other && (FOOTBALL.containsMatchIn(text) || (text.isBlank() && FOOTBALL_CHANNEL.containsMatchIn(channelName)))) Content.FOOTBALL
            else Content.SPORT
        }
        return when (ProgrammeGenre.of(title.orEmpty(), category, channelName)) {
            ProgrammeGenre.FILM -> Content.FILM
            ProgrammeGenre.SERIES, ProgrammeGenre.SHOW, ProgrammeGenre.DOCU, ProgrammeGenre.KIDS -> Content.SERIES
            ProgrammeGenre.NEWS -> Content.NEWS
            ProgrammeGenre.SPORT -> Content.SPORT
            ProgrammeGenre.OTHER -> if (Regex("""(?i)cinema|film|kino|movie""").containsMatchIn(channelName)) Content.FILM else Content.OTHER
        }
    }

    /** Werte für [content] in [mode]; null = nichts tun. */
    fun profile(content: Content, mode: Mode): Profile? {
        if (mode == Mode.OFF) return null
        val base = when (content) {
            Content.FILM -> Profile(content, sharpen = 0.12f, contrast = 0f, saturation = 0f)
            Content.SERIES -> Profile(content, sharpen = 0.2f, contrast = 0.03f, saturation = 3f)
            Content.SPORT -> Profile(content, sharpen = 0.3f, contrast = 0.06f, saturation = 8f)
            Content.FOOTBALL -> Profile(content, sharpen = 0.35f, contrast = 0.07f, saturation = 12f)
            Content.NEWS -> Profile(content, sharpen = 0.25f, contrast = 0.03f, saturation = 0f)
            Content.OTHER -> Profile(content, sharpen = 0.2f, contrast = 0.03f, saturation = 3f)
        }
        return if (mode == Mode.STRONG) base.copy(sharpen = base.sharpen * 1.6f, contrast = base.contrast * 1.5f, saturation = base.saturation * 1.5f) else base
    }

    /** Nur Full HD und kleiner in SDR ([height] 0 = noch unbekannt: zunächst ja, bis das Bild etwas anderes zeigt). */
    fun eligible(height: Int, hdr: Boolean): Boolean = !hdr && height <= 1100
}
