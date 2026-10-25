package de.ayutv.data.parse

import java.text.Normalizer

/**
 * Ordnet Sender der Playlist den Sendern eines XMLTV-EPGs zu. IPTV-Listen schmücken Namen und Kennungen
 * aus ("DE: RTL 4K", "|DE| RTL HD+", "RTL ᵁᴴᴰ", "RTL.4K.de"), EPGs nennen denselben Sender schlicht
 * "RTL" mit Kennung "RTL.de". Beide Seiten werden deshalb auf einen Kern gebracht: ohne Länder-Vorsatz,
 * Qualitätsangaben, Klammern, Zeichen und Großschreibung; bekannte Zweitnamen (Pro7 = ProSieben) gelten
 * als gleich. Gibt es denselben Kern in mehreren Ländern, gewinnt das Land aus dem Vorsatz (sonst [country]).
 *
 * Reihenfolge: Kennung genau → Kennung ohne Groß/klein → Kern der Kennung → Kern des Namens →
 * Kern ohne Füllwörter ("TV", "Deutschland", "Channel").
 */
class EpgMatcher(private val country: String = "de") {
    private class Guide(val id: String, val country: String?)

    private val exact = HashMap<String, String>()
    private val lower = HashMap<String, String>()
    private val strong = HashMap<String, MutableList<Guide>>()
    private val weak = HashMap<String, MutableList<Guide>>()

    /** Ein Sender des EPGs mit seinen Anzeigenamen. */
    fun addGuideChannel(id: String, names: Collection<String>) {
        if (id.isBlank() || id in exact) return
        exact[id] = id
        lower.putIfAbsent(id.lowercase(), id)
        val idKey = analyse(id, isId = true)
        val guide = Guide(id, idKey.country ?: names.firstNotNullOfOrNull { analyse(it, isId = false).country })
        for (k in listOf(idKey) + names.map { analyse(it, isId = false) }) {
            if (k.strong.isNotEmpty()) strong.getOrPut(k.strong) { ArrayList() }.addUnique(guide)
            if (k.weak.isNotEmpty()) weak.getOrPut(k.weak) { ArrayList() }.addUnique(guide)
        }
    }

    val isEmpty: Boolean get() = exact.isEmpty()

    /** Kennung des EPG-Senders für einen Sender der Playlist, oder null. */
    fun match(epgId: String?, name: String): String? {
        val id = epgId?.trim().orEmpty()
        if (id.isNotEmpty()) {
            exact[id]?.let { return it }
            lower[id.lowercase()]?.let { return it }
        }
        val byName = analyse(name, isId = false)
        val byId = if (id.isNotEmpty()) analyse(id, isId = true) else null
        val wanted = byName.country ?: byId?.country ?: country
        byId?.let { k -> pick(strong[k.strong], wanted)?.let { return it } }
        pick(strong[byName.strong], wanted)?.let { return it }
        pick(weak[byName.weak], wanted)?.let { return it }
        byId?.let { k -> pick(weak[k.weak], wanted)?.let { return it } }
        return null
    }

    private fun pick(candidates: List<Guide>?, wanted: String): String? {
        if (candidates.isNullOrEmpty()) return null
        return (candidates.firstOrNull { it.country == wanted }
            ?: candidates.firstOrNull { it.country == null }
            ?: candidates.first()).id
    }

    private fun MutableList<Guide>.addUnique(guide: Guide) {
        if (none { it.id == guide.id }) add(guide)
    }

    class Key(val strong: String, val weak: String, val country: String?)

    companion object {
        /** Länderkürzel, die als Vorsatz ("DE: ", "|AT| ", "[CH] ") oder Endung ("RTL.de") vorkommen. */
        private val COUNTRIES = mapOf(
            "de" to "de", "ger" to "de", "deu" to "de", "germany" to "de", "deutschland" to "de", "d" to "de",
            "at" to "at", "aut" to "at", "ch" to "ch", "sui" to "ch", "uk" to "uk", "gb" to "uk", "en" to "uk",
            "us" to "us", "usa" to "us", "nl" to "nl", "be" to "be", "fr" to "fr", "it" to "it", "es" to "es",
            "pl" to "pl", "tr" to "tr", "pt" to "pt", "dk" to "dk", "se" to "se", "no" to "no", "fi" to "fi",
            "cz" to "cz", "hu" to "hu", "ro" to "ro", "gr" to "gr", "hr" to "hr", "rs" to "rs", "ru" to "ru",
            "ar" to "ar", "al" to "al", "ex" to "ex", "yu" to "ex",
        )

        /** Angaben zu Bild, Ton und Technik, die nichts über den Sender sagen. */
        private val NOISE = setOf(
            "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hdr", "hdr10", "dv", "hevc", "h265", "h264", "x265",
            "1080p", "1080i", "720p", "2160p", "576i", "50fps", "60fps", "25fps", "fps", "raw", "backup", "alt",
            "multi", "multiaudio", "dolby", "atmos", "ac3", "dd", "vip", "live", "feed", "geo", "geoblocked",
            "hdplus", "orig", "original", "low", "high", "test", "new", "neu", "only", "event",
        )

        /** Füllwörter, ohne die ein Name meist noch eindeutig ist. Nur für den zweiten, schwächeren Abgleich. */
        private val FILLER = setOf("tv", "television", "channel", "deutschland", "germany", "german", "sender", "fernsehen", "de")

        /** Zweitnamen → ein Name (nach dem Zusammenziehen, ohne Leer- und Sonderzeichen). */
        private val ALIASES = mapOf(
            "ard" to "daserste", "erste" to "daserste", "ardhd" to "daserste", "ardalpha" to "alpha", "bralpha" to "alpha",
            "pro7" to "prosieben", "pro7maxx" to "prosiebenmaxx", "prosiebenmax" to "prosiebenmaxx",
            "rtl2" to "rtlzwei", "rtlii" to "rtlzwei", "rtlplus" to "rtlup", "rtlnitro" to "nitro",
            "kabel1" to "kabeleins", "kabel1doku" to "kabeleinsdoku", "kabeleinsclassics" to "kabeleinsclassics",
            "sat1" to "sat1", "sateins" to "sat1", "ntv" to "ntv", "n24" to "welt", "weltn24" to "welt",
            "n24doku" to "n24doku", "superrtl" to "superrtl", "toggoplus" to "toggoplus",
            "eurosport" to "eurosport1", "sportdigital" to "sportdigitalfussball", "dazn" to "dazn1",
            "bayerischesfernsehen" to "br", "brfernsehen" to "br", "hrfernsehen" to "hr", "mdrfernsehen" to "mdr",
            "ndrfernsehen" to "ndr", "wdrfernsehen" to "wdr", "swrfernsehen" to "swr", "rbbfernsehen" to "rbb",
            "einsfestival" to "one", "ardone" to "one", "zdfneo" to "zdfneo", "zdfinfo" to "zdfinfo",
            "tele5" to "tele5", "dmax" to "dmax", "sixx" to "sixx", "kika" to "kika", "kikavonardundzdf" to "kika",
            "skysportbundesliga1" to "skysportbundesliga", "orfeins" to "orf1", "orfzwei" to "orf2", "orfiii" to "orf3",
            "nick" to "nickelodeon", "srfeins" to "srf1", "srfzwei" to "srf2", "anixeplus" to "anixeplus",
        )

        private val BRACKETS = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*}""")
        private val PLUS_SHIFT = Regex("""\+\s*(\d{1,2})\b""")
        private val HD_PLUS = Regex("""\bhd\s*\+""")
        private val LEADING_COUNTRY = Regex("""^[\s|\[]*([a-z]{1,3}|germany|deutschland)(?:\s*[|:\]▎│·•★]+|\s+[-–—]+)\s*""")
        private val LEADING_COUNTRY_SPACE = Regex("""^(de|ger|deu|at|ch|uk)\s+(?=\S)""")
        private val PLUS_NAME = Regex("""\+(?!\s*\d)""")
        private val ID_COUNTRY = Regex("""\.([a-z]{2,3})(?:@.*)?$""")
        private val ID_FEED = Regex("""@.*$""")
        private val TOKENS = Regex("""[^a-z0-9]+""")
        private val GLUED_NOISE = Regex("""(?:hd|fhd|uhd|4k|8k|hevc|h265)$""")
        private val MARKS = Regex("""\p{M}+""")

        /** Name oder Kennung auf ihren Kern bringen (öffentlich für Tests und die Suche nach Sendern). */
        fun analyse(raw: String, isId: Boolean): Key {
            var text = Normalizer.normalize(raw, Normalizer.Form.NFKD).replace(MARKS, "").lowercase()
                .replace("ß", "ss")
            var country: String? = null
            if (isId) {
                // "RTL.de", "RTLHD.de@HD", "rtl.4k.de"
                ID_COUNTRY.find(text)?.let { m -> COUNTRIES[m.groupValues[1]]?.let { country = it; text = text.substring(0, m.range.first) } }
                text = text.replace(ID_FEED, "").replace('.', ' ').replace('_', ' ')
            }
            text = text.replace(HD_PLUS, " hdplus ").replace(PLUS_SHIFT, " plus$1 ").replace(PLUS_NAME, " plus ")
            // Länder-Vorsatz, auch mehrfach ("DE | 4K | RTL")
            repeat(3) {
                val m = LEADING_COUNTRY.find(text) ?: return@repeat
                val code = m.groupValues[1]
                if (code in COUNTRIES || code in NOISE) {
                    COUNTRIES[code]?.let { country = country ?: it }
                    text = text.substring(m.range.last + 1)
                }
            }
            LEADING_COUNTRY_SPACE.find(text)?.let { m ->
                val code = m.groupValues[1]
                if (text.length > m.range.last + 2) {
                    country = country ?: COUNTRIES[code]
                    text = text.substring(m.range.last + 1)
                }
            }
            // Land in Klammern am Ende: "RTL (DE)"
            BRACKETS.findAll(text).forEach { m -> COUNTRIES[m.value.trim('[', ']', '(', ')', '{', '}', ' ').lowercase()]?.let { country = country ?: it } }
            text = text.replace(BRACKETS, " ")
            val tokens = text.split(TOKENS).filter { it.isNotEmpty() && it !in NOISE }.toMutableList()
            // "DE" am Ende ("RTL DE"), aber nicht, wenn der Name nur daraus besteht
            if (tokens.size > 1 && tokens.last() in COUNTRIES && tokens.last().length >= 2) {
                country = country ?: COUNTRIES[tokens.last()]
                tokens.removeAt(tokens.lastIndex)
            }
            val strong = canonical(tokens.joinToString(""))
            val weakTokens = tokens.filter { it !in FILLER }
            val weak = canonical(if (weakTokens.isEmpty()) tokens.joinToString("") else weakTokens.joinToString(""))
            return Key(strong, weak, country)
        }

        private fun canonical(joined: String): String {
            var key = joined
            // Angehängt statt getrennt: "RTLHD", "ZDF4K"
            while (key.length > 3) {
                val stripped = key.replace(GLUED_NOISE, "")
                if (stripped == key || stripped.length < 2) break
                key = stripped
            }
            return ALIASES[key] ?: key
        }
    }
}
