package de.ayutv.data.parse

import de.ayutv.data.db.ProgrammeEntity

fun ProgrammeEntity.headline(): String = ProgrammeText.headline(title, subtitle)
fun ProgrammeEntity.isLive(): Boolean = ProgrammeText.isLive(title, subtitle, category, flags)
fun ProgrammeEntity.isSportEvent(sportChannel: Boolean = false): Boolean =
    (sportChannel || ProgrammeText.isSport(title, subtitle, category)) && ProgrammeText.isLive(title, subtitle, category, flags, sportChannel, description)
fun ProgrammeEntity.sportHeadline(): String = ProgrammeText.sportHeadline(title, subtitle, description)
fun ProgrammeEntity.pairing(): String? = ProgrammeText.pairing(title, subtitle, description)

/** Titel mit „LIVE“-Kennzeichen davor, wenn die Sendung live läuft. */
fun ProgrammeEntity.headlineWithBadge(): String = (if (isLive()) "🔴 LIVE  " else "") + headline()
