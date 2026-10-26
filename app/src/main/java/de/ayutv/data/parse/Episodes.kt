package de.ayutv.data.parse

/** Serienfolgen: Schlüssel, Reihenfolge und Titel. Folgen haben den Schlüssel „<Serie>:e:<Folge>“. */
object Episodes {
    private const val SEPARATOR = ":e:"

    fun key(seriesKey: String, episodeId: String) = "$seriesKey$SEPARATOR$episodeId"

    fun seriesKeyOf(episodeKey: String): String? =
        episodeKey.substringBeforeLast(SEPARATOR, "").takeIf { it.isNotEmpty() }

    fun episodeIdOf(episodeKey: String): String? =
        episodeKey.substringAfterLast(SEPARATOR, "").takeIf { it.isNotEmpty() && SEPARATOR in episodeKey }

    fun title(seriesName: String, episode: XtreamEpisode) = "$seriesName – S${episode.season} E${episode.episode}"

    /** Folge nach [currentId] (nach Staffel und Folgennummer, über das Staffelende hinweg), oder null. */
    fun next(episodes: List<XtreamEpisode>, currentId: String): XtreamEpisode? {
        val ordered = episodes.sortedWith(compareBy<XtreamEpisode> { it.season }.thenBy { it.episode })
        val index = ordered.indexOfFirst { it.id == currentId }
        return if (index < 0) null else ordered.getOrNull(index + 1)
    }
}

/**
 * Intro überspringen. Wo das Intro liegt, sagt kein Anbieter: Beim ersten Mal springt der Knopf
 * [DEFAULT_SKIP_MS] weiter, das Nachkorrigieren mit ◀ ▶ danach merkt sich DnA-TV pro Serie.
 */
object IntroSkip {
    data class Intro(val startMs: Long, val lengthMs: Long)

    const val DEFAULT_SKIP_MS = 80_000L
    /** So lange nach dem Überspringen zählt Spulen als Korrektur der Intro-Länge. */
    const val LEARN_WINDOW_MS = 20_000L
    private const val EARLIEST_MS = 5_000L
    private const val LATEST_UNKNOWN_MS = 5 * 60_000L
    private const val BEFORE_LEARNED_MS = 90_000L
    private const val MIN_LENGTH_MS = 10_000L
    private const val MAX_LENGTH_MS = 4 * 60_000L

    /** Knopf zeigen? Ohne gelerntes Intro in den ersten fünf Minuten, sonst rund um die gelernte Stelle. */
    fun visible(positionMs: Long, learned: Intro?): Boolean = when (learned) {
        null -> positionMs in EARLIEST_MS..LATEST_UNKNOWN_MS
        else -> positionMs in (learned.startMs - BEFORE_LEARNED_MS).coerceAtLeast(EARLIEST_MS)..(learned.startMs + learned.lengthMs - 5_000)
    }

    /** Wohin der Knopf springt. Läuft das Intro schon früher als gelernt, die gelernte Länge ab jetzt. */
    fun target(positionMs: Long, learned: Intro?): Long = when {
        learned == null -> positionMs + DEFAULT_SKIP_MS
        positionMs < learned.startMs -> positionMs + learned.lengthMs
        else -> maxOf(learned.startMs + learned.lengthMs, positionMs + 5_000)
    }

    /** Aus Startpunkt und endgültigem Ziel (nach Korrekturen) das Intro; unplausible Längen verwerfen. */
    fun learn(fromMs: Long, toMs: Long): Intro? =
        (toMs - fromMs).takeIf { it in MIN_LENGTH_MS..MAX_LENGTH_MS }?.let { Intro(fromMs, it) }
}

/** Serienfortschritt: welche Folgen gesehen sind und mit welcher es weitergeht. */
object SeriesProgress {
    /** Gesehen: bis auf die letzten 5 % (Abspann) oder 2 Minuten. */
    fun watched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && (positionMs >= durationMs * 0.95 || durationMs - positionMs < 120_000 && positionMs > durationMs / 2)

    fun fraction(positionMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * Die Folge, mit der es weitergeht: eine angefangene (die zuletzt geordnete), sonst die nach der letzten
     * gesehenen. Null, wenn noch nichts geschaut wurde oder alles gesehen ist.
     * [progress]: Folgen-Kennung → (Position, Länge).
     */
    fun upNext(episodes: List<XtreamEpisode>, progress: Map<String, Pair<Long, Long>>): XtreamEpisode? {
        if (progress.isEmpty()) return null
        val ordered = episodes.sortedWith(compareBy<XtreamEpisode> { it.season }.thenBy { it.episode })
        var lastSeen = -1
        var lastStarted = -1
        ordered.forEachIndexed { index, e ->
            val (position, duration) = progress[e.id] ?: return@forEachIndexed
            when {
                watched(position, duration) -> lastSeen = index
                position > 60_000 -> lastStarted = index
            }
        }
        if (lastStarted > lastSeen) return ordered[lastStarted]
        return if (lastSeen >= 0) ordered.getOrNull(lastSeen + 1) else null
    }
}
