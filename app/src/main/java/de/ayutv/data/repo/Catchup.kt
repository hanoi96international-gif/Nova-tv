package de.ayutv.data.repo

import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Adressen für das Archiv (Catch-up / zeitversetzt) eines Senders. Unterstützt die gängigen Arten:
 * Xtream (`/timeshift/…`), M3U `catchup="default|append|shift|flussonic|xc"` mit `catchup-source`
 * und den üblichen Platzhaltern (`{utc}`, `${start}`, `{lutc}`, `{duration}`, `{Y}-{m}-{d}` …).
 */
object Catchup {
    /** Archiv-Adresse oder null, wenn der Sender kein Archiv hat oder die Sendung zu alt ist. */
    fun url(playlist: PlaylistEntity?, channel: ChannelEntity, start: Long, stop: Long, now: Long = System.currentTimeMillis()): String? {
        if (channel.catchupDays <= 0 || start >= now) return null
        if (now - start > channel.catchupDays * DAY) return null
        val end = minOf(stop, now)
        val type = channel.catchupType ?: if (playlist?.type == PlaylistType.XTREAM || XTREAM_LIVE.containsMatchIn(channel.url)) "xc" else "default"
        val source = channel.catchupSource
        return when (type) {
            "xc", "xtream" -> xtream(channel.url, start, end, playlist?.timezone)
            "append" -> source?.let { channel.url + expand(it, start, end, now) }
            "shift", "timeshift" -> channel.url + (if ('?' in channel.url) "&" else "?") + "utc=${start / 1000}&lutc=${now / 1000}"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(channel.url, start, end)
            else -> when {
                source == null -> xtream(channel.url, start, end, playlist?.timezone)
                source.startsWith("http", ignoreCase = true) -> expand(source, start, end, now)
                else -> channel.url + expand(source, start, end, now)
            }
        }
    }

    /** `…/live/user/pass/123.ts` → `…/timeshift/user/pass/<Minuten>/<yyyy-MM-dd:HH-mm>/123.ts` (in Serverzeit). */
    internal fun xtream(liveUrl: String, start: Long, end: Long, timezone: String?): String? {
        val match = XTREAM_LIVE.find(liveUrl) ?: return null
        val (base, user, pass, id, ext) = match.destructured
        val minutes = ((end - start) / 60_000L).coerceAtLeast(1)
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = timezone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
        }
        return "$base/timeshift/$user/$pass/$minutes/${format.format(Date(start))}/$id.${ext.ifEmpty { "ts" }}"
    }

    /** Flussonic: `…/index.m3u8` → `…/archive-<start>-<dauer>.m3u8`, `…/mpegts` → `…/timeshift_abs-<start>.ts`. */
    internal fun flussonic(url: String, start: Long, end: Long): String {
        val startSec = start / 1000
        val duration = (end - start) / 1000
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val dir = base.substringBeforeLast('/')
        return when {
            base.endsWith(".m3u8") -> "$dir/archive-$startSec-$duration.m3u8$query"
            else -> "$dir/timeshift_abs-$startSec.ts$query"
        }
    }

    /** Platzhalter ersetzen; `{duration:60}` teilt (Minuten), `{utc:Y-m-d}` formatiert. */
    internal fun expand(template: String, start: Long, end: Long, now: Long): String {
        val startSec = start / 1000
        val endSec = end / 1000
        val nowSec = now / 1000
        return PLACEHOLDER.replace(template) { m ->
            val raw = m.groupValues[1]
            val name = raw.lowercase()
            val arg = m.groupValues[2].takeIf { it.isNotEmpty() }
            // Datumsfelder unterscheiden Groß/Klein: {m} = Monat, {M} = Minute.
            if (raw in DATE_FIELDS) return@replace dateFormat(raw, start)
            val value: String? = when (name) {
                "utc", "start", "timestamp" -> if (arg != null && !arg.all(Char::isDigit)) dateFormat(arg, start) else startSec.toString()
                "utcend", "end" -> if (arg != null && !arg.all(Char::isDigit)) dateFormat(arg, end) else endSec.toString()
                "lutc", "now" -> nowSec.toString()
                "duration" -> ((endSec - startSec) / (arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
                "offset" -> ((nowSec - startSec) / (arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
                else -> null
            }
            value ?: m.value
        }
    }

    /** Einzelne Felder ({Y}, {m}, {d}, {H}, {M}, {S}) oder Muster wie "Y-m-d:H-M" in UTC. */
    private fun dateFormat(pattern: String, time: Long): String {
        val javaPattern = buildString {
            for (c in pattern) append(
                when (c) {
                    'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"; 'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"
                    else -> if (c.isLetter()) "'$c'" else c.toString()
                }
            )
        }
        return SimpleDateFormat(javaPattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
    }

    private const val DAY = 24 * 3_600_000L
    private val DATE_FIELDS = setOf("Y", "m", "d", "H", "M", "S")
    private val XTREAM_LIVE = Regex("""^(https?://[^/]+(?:/[^/]+)*?)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(\w+))?$""")
    private val PLACEHOLDER = Regex("""\$?\{(\w+)(?::([^}]*))?\}""")
}
