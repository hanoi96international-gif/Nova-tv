package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sendernamen aus echten IPTV-Listen gegen typische EPG-Kennungen. */
class EpgMatcherTest {
    private val matcher = EpgMatcher().apply {
        addGuideChannel("RTL.de", listOf("RTL"))
        addGuideChannel("RTL.nl", listOf("RTL 4"))
        addGuideChannel("RTLZWEI.de", listOf("RTLZWEI"))
        addGuideChannel("RTLup.de", listOf("RTLup"))
        addGuideChannel("ProSieben.de", listOf("ProSieben"))
        addGuideChannel("ProSiebenMaxx.de", listOf("ProSieben MAXX"))
        addGuideChannel("SAT1.de", listOf("SAT.1"))
        addGuideChannel("DasErste.de", listOf("Das Erste"))
        addGuideChannel("ZDF.de", listOf("ZDF"))
        addGuideChannel("ZDFneo.de", listOf("ZDFneo"))
        addGuideChannel("kabeleins.de", listOf("kabel eins"))
        addGuideChannel("ntv.de", listOf("n-tv"))
        addGuideChannel("VOX.de", listOf("VOX"))
        addGuideChannel("VOX.at", listOf("VOX Austria"))
        addGuideChannel("Nitro.de", listOf("NITRO"))
        addGuideChannel("ORF1.at", listOf("ORF eins"))
        addGuideChannel("SkyCinemaAction.de", listOf("Sky Cinema Action"))
        addGuideChannel("SkyCinemaPremieren.de", listOf("Sky Cinema Premieren"))
        addGuideChannel("SkyCinemaPremieren24.de", listOf("Sky Cinema Premieren +24"))
        addGuideChannel("BibelTV.de", listOf("Bibel TV"))
        addGuideChannel("hrfernsehen.de", listOf("hr-fernsehen"))
        addGuideChannel("AlJazeeraEnglish.qa", listOf("Al Jazeera English"))
    }

    private fun check(expected: String?, name: String, epgId: String? = null) =
        assertEquals("„$name“ (Kennung $epgId)", expected, matcher.match(epgId, name))

    @Test
    fun qualityAndCountryDecorationsAreIgnored() {
        check("RTL.de", "RTL 4K")
        check("RTL.de", "RTL HD")
        check("RTL.de", "RTL FHD")
        check("RTL.de", "RTL UHD")
        check("RTL.de", "DE: RTL HD")
        check("RTL.de", "|DE| RTL 4K")
        check("RTL.de", "DE | RTL HD+")
        check("RTL.de", "[DE] RTL (1080p)")
        check("RTL.de", "DE - RTL HEVC")
        check("RTL.de", "DE ▎ RTL ᵁᴴᴰ")
        check("RTL.de", "RTLᴴᴰ")
        check("RTL.de", "RTL4K")
        check("RTL.de", "VIP | DE | RTL 50FPS")
        check("RTL.de", "RTL (Backup)")
        check("RTL.de", "RTL Television")
        check("RTL.de", "DE RTL")
        check("RTL.de", "RTL DE")
    }

    @Test
    fun differentChannelsStayApart() {
        check("RTL.nl", "RTL 4")
        check("RTLZWEI.de", "RTL 2 HD")
        check("RTLZWEI.de", "RTL II")
        check("RTLup.de", "RTL+")
        check("SkyCinemaPremieren.de", "Sky Cinema Premieren HD")
        check("SkyCinemaPremieren24.de", "Sky Cinema Premieren +24 HD")
        check(null, "Sky Cinema Family")
        check(null, "RTL Crime")
        check("AlJazeeraEnglish.qa", "Al Jazeera English")
        check("hrfernsehen.de", "HR Fernsehen HD")
    }

    @Test
    fun secondNamesAndSpellings() {
        check("ProSieben.de", "Pro7 HD")
        check("ProSiebenMaxx.de", "DE: Pro 7 MAXX")
        check("SAT1.de", "Sat 1 FHD")
        check("DasErste.de", "ARD HD")
        check("DasErste.de", "Das Erste 4K")
        check("kabeleins.de", "Kabel 1 HD")
        check("ntv.de", "NTV HD")
        check("Nitro.de", "RTL Nitro")
        check("ORF1.at", "ORF 1 HD")
        check("BibelTV.de", "Bibel HD")
        check("ZDFneo.de", "ZDF neo")
    }

    /** Ausschnitt aus dem echten freien EPG (epgshare01 DE1/AT1, Oktober 2026). */
    private val real = EpgMatcher().apply {
        addGuideChannel("DAZN.de", listOf("DAZN"))
        addGuideChannel("DAZN.1.de", listOf("DAZN 1"))
        addGuideChannel("DAZN.2.de", listOf("DAZN 2"))
        addGuideChannel("Sky.Sport.Bundesliga.de", listOf("Sky Sport Bundesliga"))
        for (n in 1..10) addGuideChannel("Sky.Sport.Bundesliga.$n.de", listOf("Sky Sport Bundesliga $n"))
        addGuideChannel("Sky.Sport.News.de", listOf("Sky Sport News"))
        addGuideChannel("Sky.Sport.Top.Event.de", listOf("Sky Sport Top Event"))
        addGuideChannel("Sky.Cinema.Premiere.HD.de", listOf("Sky Cinema Premiere HD"))
        addGuideChannel("SKY.CINEMA.PREMIEREN.+24.at", listOf("SKY CINEMA PREMIEREN +24"))
        addGuideChannel("MDR.de", listOf("MDR"))
        addGuideChannel("SWR/SR.de", listOf("SWR/SR"))
        addGuideChannel("ORF.2.at", listOf("ORF 2"))
    }

    @Test
    fun skyAndDaznFromProviderLists() {
        fun real(expected: String?, name: String) = assertEquals("„$name“", expected, real.match(null, name))
        real("Sky.Sport.Bundesliga.1.de", "DE: SKY BUNDESLIGA 1 HD")
        real("Sky.Sport.Bundesliga.10.de", "DE: Sky Bundesliga 10 FHD")
        real("Sky.Sport.Bundesliga.3.de", "|DE| SKY SPORT BUNDESLIGA 3 UHD")
        real("Sky.Sport.Bundesliga.de", "DE: SKY BUNDESLIGA HD")
        real("Sky.Sport.Top.Event.de", "DE: SKY SPORT TOP EVENT")
        real("Sky.Sport.News.de", "DE: SKY SPORT NEWS FHD")
        // DAZN und DAZN 1 sind verschiedene Sender
        real("DAZN.de", "DE: DAZN")
        real("DAZN.1.de", "DE: DAZN 1 HD")
        real("DAZN.2.de", "DAZN 2 4K")
        // Gastronomie-Fassung zeigt dasselbe Programm
        real("DAZN.1.de", "DE: DAZN 1 BAR")
        real("DAZN.de", "DAZN Bar HD")
        real("Sky.Cinema.Premiere.HD.de", "DE: SKY CINEMA PREMIEREN")
        real("SKY.CINEMA.PREMIEREN.+24.at", "DE: SKY CINEMA PREMIEREN +24")
        // Regionalfenster fallen auf das Hauptprogramm zurück
        real("MDR.de", "DE: MDR SACHSEN HD")
        real("SWR/SR.de", "DE: SWR BW")
        real("ORF.2.at", "AT: ORF 2 Wien")
    }

    @Test
    fun skySportAustria() {
        val m = EpgMatcher().apply {
            addGuideChannel("Sky.Sport.Austria.1.HD.de", listOf("Sky Sport Austria 1 HD"))
            addGuideChannel("Sky.Sport.Austria.2.HD.de", listOf("Sky Sport Austria 2 HD"))
            addGuideChannel("Sky.Sport.Austria.de", listOf("Sky Sport Austria"))
            addGuideChannel("Sky.Sport.1.de", listOf("Sky Sport 1"))
        }
        assertEquals("Sky.Sport.Austria.1.HD.de", m.match(null, "AT: SKY SPORT AUSTRIA 1 HD"))
        assertEquals("Sky.Sport.Austria.1.HD.de", m.match(null, "AT| SKY SPORTS AUSTRIA 1 HD"))
        assertEquals("Sky.Sport.Austria.1.HD.de", m.match(null, "AT: SKY SPORT ÖSTERREICH 1"))
        assertEquals("Sky.Sport.Austria.2.HD.de", m.match(null, "AT: Sky Austria 2"))
        assertEquals("Sky.Sport.1.de", m.match(null, "DE: SKY SPORT 1 HD"))
        // Kennung des Anbieters gehört zu einem anderen Sender: als unpassend erkennen
        assertEquals("Sky.Sport.1.de", m.match("Sky.Sport.1.de", "AT: SKY SPORT AUSTRIA 1"))
        assertEquals(false, m.fitsName("Sky.Sport.1.de", "AT: SKY SPORT AUSTRIA 1"))
        assertEquals(true, m.fitsName("Sky.Sport.1.de", "DE: SKY SPORT 1 FHD"))
        assertEquals(true, m.fitsName("Sky.Sport.Austria.1.HD.de", "|AT| Sky Sport Austria 1 ᴴᴰ"))
        assertEquals(true, matcher.fitsName("BibelTV.de", "DE: Bibel"))
        assertEquals(true, matcher.fitsName("DasErste.de", "DE: Das Erste HD"))
        assertEquals(true, matcher.fitsName("unbekannt.de", "Irgendwas"))
    }

    @Test
    fun countryDecidesBetweenSameNames() {
        check("VOX.de", "VOX HD")
        check("VOX.at", "AT: VOX Austria")
    }

    @Test
    fun idsFromProvidersAreMatchedToo() {
        // Kennung der Playlist existiert nicht im EPG, sieht aber ähnlich aus
        check("RTL.de", "Sender 17", epgId = "rtl.de")
        check("RTL.de", "Sender 17", epgId = "RTL.4K.de")
        check("RTL.de", "Sender 17", epgId = "RTLHD.de@HD")
        check("ZDF.de", "Sender 18", epgId = "zdf hd")
        // Kennung genau vorhanden: gewinnt immer
        check("SAT1.de", "RTL", epgId = "SAT1.de")
        // Kennung unbekannt, Name hilft
        check("RTL.de", "RTL 4K", epgId = "rtl4k.provider")
    }

    @Test
    fun keyAsCheckedOnTheDevice() {
        val key = EpgMatcher.analyse("|DE| SKY BUNDESLIGA 1 ᴴᴰ [Backup]", isId = false)
        assertEquals("skysportbundesliga1", key.strong)
        assertEquals("de", key.country)
    }

    @Test
    fun providerLabelsInFront() {
        val m = EpgMatcher().apply {
            addGuideChannel("3sat.de", listOf("3sat"))
            addGuideChannel("ZDF.de", listOf("ZDF"))
            addGuideChannel("DAZN.1.de", listOf("DAZN 1"))
        }
        assertEquals("3sat.de", m.match(null, "PRIME: 3SAT ᴿᴬᵂ"))
        assertEquals("ZDF.de", m.match(null, "VIP | DE | ZDF HD"))
        assertEquals("ZDF.de", m.match(null, "PRIME: DE: ZDF FHD"))
        assertEquals("DAZN.1.de", m.match(null, "SPORT+ : DAZN 1 HD"))
        assertNull(m.match(null, "PRIME: ARTE"))
    }

    /** Echte Kennungen aus epgshare01 DE1 (Stand Oktober 2026) und MagentaTV. */
    @Test
    fun skyUhdChannelsKeepTheirOwnProgramme() {
        val m = EpgMatcher().apply {
            addGuideChannel("Sky.Sport.Bundesliga.de", listOf("Sky Sport Bundesliga"))
            addGuideChannel("Sky.Sport.Bundesliga.UHD.de", listOf("Sky Sport Bundesliga UHD"))
            addGuideChannel("Sky.Sport.Bundesliga.2.de", listOf("Sky Sport Bundesliga 2"))
            addGuideChannel("Sky.Sport.UHD.de", listOf("Sky Sport UHD"))
            addGuideChannel("Sky.Sport.Top.Event.de", listOf("Sky Sport Top Event"))
            addGuideChannel("RTL.de", listOf("RTL"))
            addGuideChannel("UHD1.de", listOf("UHD1"))
            addGuideChannel("13th.Street.Universal.de", listOf("13th Street Universal"))
            addGuideChannel("Sport.1.-.myTeamTV.de", listOf("Sport 1 - myTeamTV"))
            addGuideChannel("MagentaSport.de@MagentaTV", listOf("MagentaSport"))
        }
        assertEquals("Sky.Sport.Bundesliga.UHD.de", m.match(null, "DE: SKY SPORT BUNDESLIGA UHD"))
        assertEquals("Sky.Sport.Bundesliga.UHD.de", m.match(null, "DE: SKY BUNDESLIGA 4K"))
        assertEquals("Sky.Sport.Bundesliga.de", m.match(null, "DE: SKY SPORT BUNDESLIGA HD"))
        // Nummerierte Sender in 4K sind dasselbe Programm.
        assertEquals("Sky.Sport.Bundesliga.2.de", m.match(null, "DE: SKY SPORT BUNDESLIGA 2 UHD"))
        assertEquals("Sky.Sport.UHD.de", m.match(null, "DE: SKY SPORT 4K"))
        assertEquals("Sky.Sport.Top.Event.de", m.match(null, "DE: SKY TOP EVENT"))
        // Ohne eigenen UHD-Sender im EPG: das normale Programm.
        assertEquals("RTL.de", m.match(null, "DE: RTL UHD"))
        assertEquals("UHD1.de", m.match(null, "DE: UHD1 BY HD+"))
        assertEquals("13th.Street.Universal.de", m.match(null, "DE: 13TH STREET HD"))
        assertEquals("Sport.1.-.myTeamTV.de", m.match(null, "DE: MYTEAMTV SPORT 1"))
        assertEquals("MagentaSport.de@MagentaTV", m.match(null, "DE: MAGENTA SPORT HD"))
    }
}
