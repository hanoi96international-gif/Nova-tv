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
    /** [uhd]: eigener UHD-Sender des EPGs („Sky Sport Bundesliga UHD“ zeigt nur ausgewählte Spiele). */
    private class Guide(val id: String, val country: String?, val uhd: Boolean)

    private val exact = HashMap<String, String>()
    private val lower = HashMap<String, String>()
    private val strong = HashMap<String, MutableList<Guide>>()
    private val weak = HashMap<String, MutableList<Guide>>()
    /** Namenskerne je EPG-Sender (aus Kennung und Anzeigenamen), um eine Zuordnung zu prüfen. */
    private val keysOf = HashMap<String, MutableSet<String>>()

    /** Ein Sender des EPGs mit seinen Anzeigenamen. */
    fun addGuideChannel(id: String, names: Collection<String>) {
        if (id.isBlank() || id in exact) return
        exact[id] = id
        lower.putIfAbsent(id.lowercase(), id)
        val idKey = analyse(id, isId = true)
        val nameKeys = names.map { analyse(it, isId = false) }
        val guide = Guide(id, idKey.country ?: nameKeys.firstNotNullOfOrNull { it.country }, idKey.uhd || nameKeys.any { it.uhd })
        val keys = keysOf.getOrPut(id) { HashSet() }
        for (k in listOf(idKey) + nameKeys) {
            if (k.strong.isNotEmpty()) keys += k.strong
            if (k.weak.isNotEmpty()) keys += k.weak
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
        val uhd = byName.uhd || byId?.uhd == true
        byId?.let { k -> pick(strong[k.strong], wanted, uhd)?.let { return it } }
        pick(strong[byName.strong], wanted, uhd)?.let { return it }
        pick(weak[byName.weak], wanted, uhd)?.let { return it }
        byId?.let { k -> pick(weak[k.weak], wanted, uhd)?.let { return it } }
        // Regionalfenster ("MDR Sachsen", "WDR Köln"): sonst das Hauptprogramm der Anstalt.
        byName.region?.let { pick(strong[it], wanted, uhd)?.let { id -> return id } }
        // Eigene Vorsätze des Anbieters ("PRIME: 3SAT", "VIP | ZDF", "Sport+ : DAZN 1"): ohne sie noch einmal.
        val bare = name.replace(ANY_PREFIX, "")
        if (bare != name && bare.isNotBlank()) {
            val k = analyse(bare, isId = false)
            pick(strong[k.strong], wanted, uhd)?.let { return it }
            pick(weak[k.weak], wanted, uhd)?.let { return it }
            k.region?.let { pick(strong[it], wanted, uhd)?.let { id -> return id } }
        }
        // Dyn mit Nummer („DE: DYN VOLLEYBALL 2“, „Dyn 5“): der Spielplan der Sportart bzw. aller Sportarten.
        DYN_NUMBERED.find(byName.strong)?.let { m ->
            val sport = m.groupValues[1]
            if (sport.isNotEmpty()) pick(strong["dyn$sport"], wanted, false)?.let { return it }
            pick(strong["dyn"], wanted, false)?.let { return it }
        }
        return null
    }

    /**
     * Passt der Sendername zum EPG-Sender [guideId]? Manche Anbieter geben einem Sender die Kennung eines
     * anderen („Sky Sport Austria 1“ mit der Kennung von „Sky Sport 1“): Dann stimmt sein Programm nicht.
     * Unbekannte Sender und Namen ohne verwertbaren Kern gelten als passend.
     */
    fun fitsName(guideId: String, name: String): Boolean {
        val keys = keysOf[guideId] ?: return true
        val k = analyse(name, isId = false)
        if (k.strong.length < 3 || k.strong in keys || k.region in keys) return true
        val bare = analyse(name.replace(ANY_PREFIX, ""), isId = false)
        if (bare.strong in keys || bare.region in keys) return true
        // Nur Füllwörter verschieden („Bibel TV“ / „Bibel“): passt.
        return k.weak in keys || bare.weak in keys
    }

    /**
     * Unter gleich lautenden EPG-Sendern zuerst den mit passender UHD-Angabe: „Sky Sport Bundesliga UHD“ bekommt
     * den eigenen UHD-Sender, „Sky Sport Bundesliga HD“ das normale Programm. Gibt es nur eine Fassung (bei „RTL UHD“
     * meist), gilt diese. Danach das gewünschte Land.
     */
    private fun pick(candidates: List<Guide>?, wanted: String, uhd: Boolean = false): String? {
        if (candidates.isNullOrEmpty()) return null
        val pool = candidates.filter { it.uhd == uhd }.ifEmpty { candidates }
        return (pool.firstOrNull { it.country == wanted }
            ?: pool.firstOrNull { it.country == null }
            ?: pool.first()).id
    }

    private fun MutableList<Guide>.addUnique(guide: Guide) {
        if (none { it.id == guide.id }) add(guide)
    }

    class Key(val strong: String, val weak: String, val country: String?, val region: String? = null, val uhd: Boolean = false)

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

        /** Anstalten mit Regionalprogrammen: "MDR Sachsen" fällt auf "MDR" zurück. */
        private val REGIONAL = setOf("mdr", "wdr", "ndr", "br", "swr", "hr", "rbb", "sr", "orf2", "srf1")

        /** Zweitnamen → ein Name (nach dem Zusammenziehen, ohne Leer- und Sonderzeichen). */
        private val ALIASES = mapOf(
            "ard" to "daserste", "erste" to "daserste", "ardhd" to "daserste", "ardalpha" to "alpha", "bralpha" to "alpha",
            "pro7" to "prosieben", "pro7maxx" to "prosiebenmaxx", "prosiebenmax" to "prosiebenmaxx",
            "rtl2" to "rtlzwei", "rtlii" to "rtlzwei", "rtlplus" to "rtlup", "rtlnitro" to "nitro",
            "kabel1" to "kabeleins", "kabel1doku" to "kabeleinsdoku", "kabeleinsclassics" to "kabeleinsclassics",
            "sat1" to "sat1", "sateins" to "sat1", "ntv" to "ntv", "n24" to "welt", "weltn24" to "welt",
            "n24doku" to "n24doku", "superrtl" to "superrtl", "toggoplus" to "toggoplus",
            "eurosport" to "eurosport1", "sportdigital" to "sportdigitalfussball",
            "skycinemapremieren" to "skycinemapremiere", "skycinemapremierenplus24" to "skycinemapremiereplus24", "swrsr" to "swr",
            "bayerischesfernsehen" to "br", "brfernsehen" to "br", "hrfernsehen" to "hr", "mdrfernsehen" to "mdr",
            "ndrfernsehen" to "ndr", "wdrfernsehen" to "wdr", "swrfernsehen" to "swr", "rbbfernsehen" to "rbb",
            "einsfestival" to "one", "ardone" to "one", "zdfneo" to "zdfneo", "zdfinfo" to "zdfinfo",
            "tele5" to "tele5", "dmax" to "dmax", "sixx" to "sixx", "kika" to "kika", "kikavonardundzdf" to "kika",
            "orfeins" to "orf1", "orfzwei" to "orf2", "orfiii" to "orf3",
            "nick" to "nickelodeon", "srfeins" to "srf1", "srfzwei" to "srf2", "anixeplus" to "anixeplus",
            "13thstreetuniversal" to "13thstreet", "uhd1by" to "uhd1", "uhd1byhd" to "uhd1", "skyatlantik" to "skyatlantic",
            "magentasport" to "magentasport", "magentatvsport" to "magentasport", "telekomsport" to "magentasport",
            "dazn1bar" to "dazn1", "dazn2bar" to "dazn2", "sportdigital1" to "sportdigital1plus",
            // Amazon: „Prime“ (epgshare01 DE), „Amazon Prime Video“ (CH), in Listen auch „Prime Video (24/7)“.
            "primevideo" to "prime", "amazonprime" to "prime", "amazonprimevideo" to "prime", "primevideolive" to "prime",
            "primevideosport" to "prime", "amazonprimevideolive" to "prime",
        )

        private val BRACKETS = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""")
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
        private val UHD_TOKENS = setOf("uhd", "4k", "2160p", "8k")
        private val GLUED_UHD = Regex("""(?:uhd|4k)$""")
        private val ANY_PREFIX = Regex("""^(?:\s*[^:|]{1,24}?\s*[:|]+\s*)+""")

        private val DYN_NUMBERED = Regex("""^dyn(?:sport|event|live)?([a-z]*?)\d{1,2}$""")
        /** „24/7“ und „24-7“ (Dauersender) gehören nicht zum Namen. */
        private val ROUND_THE_CLOCK = Regex("""\b24\s*[/-]\s*7\b""")

        private val TIME_SHIFT = Regex("""\s*\+\s*(\d{1,2})(?:\s*(?:h|std|stunden?)\b)?(?![\d.])""", RegexOption.IGNORE_CASE)

        /**
         * Zeitversatz im Namen („Sky Cinema Premieren +24“, „RTL +1 HD“): Stunden und Name ohne die Angabe; null ohne
         * Versatz. Für Sender, die es im EPG nicht eigens gibt: Sie zeigen das Programm des Hauptsenders später.
         */
        fun timeShift(name: String): Pair<Int, String>? {
            val m = TIME_SHIFT.find(name) ?: return null
            val hours = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..48 } ?: return null
            val base = name.removeRange(m.range).trim()
            return if (base.isEmpty()) null else hours to base
        }

        /** Wie verschieden zwei Namenskerne sind (Bearbeitungsabstand); gleiche zuerst, leere zuletzt. */
        fun editDistance(a: String, b: String): Int {
            if (a.isEmpty() || b.isEmpty()) return 1_000
            if (a == b) return -1
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                cur.copyInto(prev)
            }
            return prev[b.length]
        }

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
            text = text.replace(ROUND_THE_CLOCK, " ")
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
            val allTokens = text.split(TOKENS).filter { it.isNotEmpty() }
            // UHD-Fassung („Sky Sport Bundesliga UHD“, „RTL 4K“, „Sky Sport UHD“), auch angehängt („ZDF4K“).
            val uhd = allTokens.any { it in UHD_TOKENS || (it.length > 3 && GLUED_UHD.containsMatchIn(it) && !it.startsWith("uhd")) }
            val tokens = allTokens.filter { it !in NOISE }.toMutableList()
            // "DE" am Ende ("RTL DE"), aber nicht, wenn der Name nur daraus besteht
            if (tokens.size > 1 && tokens.last() in COUNTRIES && tokens.last().length >= 2) {
                country = country ?: COUNTRIES[tokens.last()]
                tokens.removeAt(tokens.lastIndex)
            }
            // Gastronomie-Fassung („DAZN 1 Bar“) zeigt dasselbe Programm wie der Sender selbst.
            if (tokens.size > 1 && tokens.last() == "bar" && tokens.first() == "dazn") tokens.removeAt(tokens.lastIndex)
            val strong = canonical(tokens.joinToString(""))
            val weakTokens = tokens.filter { it !in FILLER }
            val weak = canonical(if (weakTokens.isEmpty()) tokens.joinToString("") else weakTokens.joinToString(""))
            val first = tokens.firstOrNull()?.let { if (it == "orf" || it == "srf") it + tokens.getOrNull(1).orEmpty() else it }
            val region = first?.takeIf { it in REGIONAL && canonical(it) != strong }
            return Key(strong, weak, country, region, uhd)
        }

        /** Umbenennungen bei Sky ("Sky Bundesliga 3" heißt heute "Sky Sport Bundesliga 3"). */
        private val SKY_RENAMES = listOf(
            "skybundesliga" to "skysportbundesliga", "skysports" to "skysport",
            "skysportosterreich" to "skysportaustria", "skyaustria" to "skysportaustria",
            // „Sky Top Event“, „Sky Premier League“, „Sky F1“: ohne „Sport“ im Namen.
            "skytop" to "skysporttop", "skypremierleague" to "skysportpremierleague", "skyf1" to "skysportf1",
            "skytennis" to "skysporttennis", "skygolf" to "skysportgolf", "skymix" to "skysportmix",
        )
        /** „myTeamTV Sport 1“ heißt im EPG „Sport 1 - myTeamTV“. */
        private val MYTEAM = Regex("""^myteamtv(sport\d+)$""")

        private fun canonical(joined: String): String {
            var key = joined
            // („skysports…“ beginnt selbst mit „skysport“: deshalb nur auf den alten Anfang prüfen.)
            for ((old, new) in SKY_RENAMES) if (key.startsWith(old)) { key = new + key.removePrefix(old); break }
            MYTEAM.find(key)?.let { key = it.groupValues[1] + "myteamtv" }
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
