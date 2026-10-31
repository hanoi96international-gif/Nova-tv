package de.ayutv.desktop.data

import de.ayutv.data.parse.ChannelQuality
import de.ayutv.data.parse.ChannelVariants
import de.ayutv.data.parse.ProgrammeText

/** Sportsendung mit dem besten Sender und den anderen, auf denen sie auch läuft. */
data class SportEvent(val programme: Programme, val channel: Channel, val alsoOn: List<Channel> = emptyList()) {
    val headline: String get() = ProgrammeText.sportHeadline(programme.title, programme.subtitle, programme.description)
    val pairing: String? get() = ProgrammeText.pairing(programme.title, programme.subtitle, programme.description)
}

/** Sport aus dem TV-Guide, wie in der Fire-TV-App: gleiche Erkennung, gleiche Zusammenfassung über Sender. */
object SportEvents {
    private const val HOUR = 3_600_000L

    fun of(channels: List<Channel>, guide: Map<String, List<Programme>>, favorites: Set<String>, hours: Int = 36, now: Long = System.currentTimeMillis()): List<SportEvent> {
        val until = now + hours * HOUR
        val best = compareBy<Pair<Programme, Channel>>({ if (it.second.key in favorites) 0 else 1 }, { ChannelQuality.score(it.second.key, it.second.name) })
        fun variant(channel: Channel) = ChannelVariants.key(channel.name) ?: channel.key
        return channels.asSequence()
            .flatMap { c ->
                val sportChannel = ProgrammeText.isSportChannel(c.name)
                guide[c.key].orEmpty().asSequence()
                    .filter { it.stop > now && it.start < until }
                    .filter { p ->
                        (sportChannel || ProgrammeText.isSport(p.title, p.subtitle, p.category)) &&
                            ProgrammeText.isLive(p.title, p.subtitle, p.category, p.flags, sportChannel, p.description)
                    }
                    .map { it to c }
            }
            .groupBy { (p, c) -> (p.start / (5 * 60_000L)) to ProgrammeText.sportKey(p.title, p.subtitle, p.description, c.epgId ?: c.key) }
            .values.map { same ->
                val (p, c) = same.minWith(best)
                val others = same.filter { variant(it.second) != variant(c) }
                    .groupBy { variant(it.second) }.values
                    .map { it.minWith(best).second }
                    .sortedWith(compareBy({ if (it.key in favorites) 0 else 1 }, { it.name }))
                SportEvent(p, c, others)
            }
            .sortedWith(compareBy({ it.programme.start }, { it.channel.name }))
    }
}
