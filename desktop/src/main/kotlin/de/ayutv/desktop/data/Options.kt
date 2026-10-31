package de.ayutv.desktop.data

import kotlinx.serialization.Serializable

/** Auswahl-Einstellungen wie in der Fire-TV-App. [label] steht in den Einstellungen. */
interface Choice {
    val label: String
}

/** Puffer: kurz = schnelles Umschalten, lang = stabil bei 4K und wackeliger Leitung. */
@Serializable
enum class Buffer(override val label: String, val ms: Int) : Choice {
    FAST("Schnell umschalten", 1_000),
    BALANCED("Ausgewogen", 3_000),
    STABLE("Stabil (4K)", 8_000),
}

/** Format der Live-Sender bei Xtream-Zugängen. */
@Serializable
enum class LiveFormat(override val label: String) : Choice {
    TS("MPEG-TS (schneller Start)"),
    HLS("HLS (m3u8)"),
}

/** Halbbilder (deutsches TV in 1080i/576i) zu ganzen Bildern zusammensetzen. */
@Serializable
enum class Deinterlace(override val label: String, val vlc: Int) : Choice {
    AUTO("Automatisch", -1),
    ON("Immer (50 Bilder/s)", 1),
    OFF("Aus", 0),
}

/** Wie das Bild ins Fenster passt. */
@Serializable
enum class PictureFit(override val label: String) : Choice {
    FIT("Original"),
    ZOOM("Bild füllen (Ränder abschneiden)"),
    STRETCH("Strecken"),
}

@Serializable
enum class AudioOutput(override val label: String) : Choice {
    AUTO("Automatisch"),
    STEREO("Immer Stereo"),
    PASSTHROUGH("Dolby/DTS durchreichen (HDMI, S/PDIF)"),
}

/** Ton: normal, Nachtmodus (leise Stellen lauter, laute leiser) oder Dialoge hervorheben. */
@Serializable
enum class AudioMode(override val label: String) : Choice {
    NORMAL("Normal"),
    NIGHT("Nachtmodus"),
    DIALOG("Dialoge verstärken"),
}

@Serializable
enum class SubtitleSize(override val label: String, val percent: Int) : Choice {
    SMALL("Klein", 80),
    NORMAL("Normal", 100),
    LARGE("Groß", 130),
    XL("Sehr groß", 160),
}

@Serializable
enum class SubtitleColor(override val label: String, val rgb: Int) : Choice {
    WHITE("Weiß", 0xFFFFFF),
    YELLOW("Gelb", 0xFFE14D),
    CYAN("Hellblau", 0x8FE3FF),
}

@Serializable
enum class SubtitleBackground(override val label: String) : Choice {
    OUTLINE("Schwarzer Rand"),
    SHADOW("Schatten"),
    BOX("Dunkler Kasten"),
}

/** Abstand der Untertitel vom unteren Bildrand (Bildpunkte im Video). */
@Serializable
enum class SubtitlePosition(override val label: String, val margin: Int) : Choice {
    LOW("Ganz unten", 10),
    NORMAL("Unten", 40),
    HIGH("Etwas höher", 120),
}

@Serializable
enum class Theme(override val label: String) : Choice {
    STANDARD("Standard"),
    NORDLICHT("Nordlicht"),
    HANNOVER("Hannover 96"),
}

/** Nächster Wert (für Einstellungen, die man durchschaltet). */
inline fun <reified T : Enum<T>> T.next(): T = enumValues<T>().let { it[(ordinal + 1) % it.size] }

/** Sprachen für Untertitel und Ton (Kürzel, Name). */
val LANGUAGES = listOf(
    "de" to "Deutsch", "en" to "Englisch", "tr" to "Türkisch", "fr" to "Französisch", "es" to "Spanisch",
    "it" to "Italienisch", "pl" to "Polnisch", "ru" to "Russisch", "ar" to "Arabisch", "nl" to "Niederländisch",
    "pt" to "Portugiesisch", "hr" to "Kroatisch", "sr" to "Serbisch", "el" to "Griechisch", "ro" to "Rumänisch",
    "uk" to "Ukrainisch", "fa" to "Persisch", "ku" to "Kurdisch", "sq" to "Albanisch", "vi" to "Vietnamesisch",
)

fun languageName(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: code.uppercase()

/** Sprachkürzel, wie sie in Tonspuren stehen (für VLC: „de“, „deu“, „ger“ …). */
fun languageCodes(code: String): String = when (code) {
    "de" -> "de,deu,ger"
    "en" -> "en,eng"
    "tr" -> "tr,tur"
    "fr" -> "fr,fra,fre"
    "es" -> "es,spa"
    "it" -> "it,ita"
    "pl" -> "pl,pol"
    "ru" -> "ru,rus"
    "ar" -> "ar,ara"
    "nl" -> "nl,nld,dut"
    "pt" -> "pt,por"
    "hr" -> "hr,hrv"
    "sr" -> "sr,srp"
    "el" -> "el,ell,gre"
    "ro" -> "ro,ron,rum"
    "uk" -> "uk,ukr"
    else -> code
}
