package de.ayutv.data.parse

import de.ayutv.data.parse.SportKinds.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SportKindsTest {
    private fun k(title: String, sub: String? = null, cat: String? = null, desc: String? = null, channel: String = "", match: Boolean = false) =
        SportKinds.kind(title, sub, cat, desc, channel, match)

    @Test
    fun footballOnlyWhenClear() {
        assertEquals(Kind.FOOTBALL, k("Fußball: Bundesliga", "Borussia Dortmund – Eintracht Frankfurt"))
        assertEquals(Kind.FOOTBALL, k("Live: 2. BL", channel = "DE: SKY SPORT BUNDESLIGA 2 HD"))
        assertEquals(Kind.FOOTBALL, k("FC St. Pauli vs. 1. FC Nürnberg", channel = "DE: SOCCER PPV 21"))
        assertEquals(Kind.FOOTBALL, k("Live: DFB-Pokal"))
        assertEquals(Kind.FOOTBALL, k("Hertha BSC – 1. FC Köln", match = true))
        // Bundesliga anderer Sportarten ist kein Fußball
        assertEquals(Kind.HANDBALL, k("Handball-Bundesliga", "THW Kiel – SG Flensburg-Handewitt"))
        assertEquals(Kind.VOLLEYBALL, k("Volleyball Bundesliga: VCO Dresden – Nawaro Straubing"))
        assertEquals(Kind.BASKETBALL, k("BBL: Rostock Seawolves – FC Bayern München Basketball"))
        assertEquals(Kind.ICE_HOCKEY, k("DEL: Eisbären Berlin – Adler Mannheim"))
    }

    @Test
    fun eventNamesFromTheUsersPlaylist() {
        // Dyn-Events ohne Sportart im Namen: keine Fußball-Vermutung wegen „SV“/„HC“
        assertEquals(Kind.HOCKEY, k("MÜNCHNER SC (D) - DER CLUB AN DER ALSTER (D)", channel = "DE: DYN PPV 2"))
        assertEquals(Kind.HANDBALL, k("HSG WETZLAR - MT MELSUNGEN", channel = "DE: DYN PPV 19"))
        assertEquals(Kind.OTHER, k("SCU EMLICHHEIM - SV LOHHOF", channel = "DE: DYN PPV 21"))
        assertEquals(Kind.US_SPORTS, k("WEEK 8 – RAVENS AT BILLS", channel = "DE: RTL+ PPV 3"))
        assertEquals(Kind.SNOOKER, k("Snooker: International Championship, 1. Runde"))
        assertEquals(Kind.TENNIS, k("2026 Erste Bank Open Tennis"))
        assertEquals(Kind.MOTORSPORT, k("Live", channel = "4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals(Kind.WINTER, k("SKATE CANADA – FRAUEN – KÜR"))
        assertEquals(Kind.DARTS, k("PDC World Series of Darts"))
    }
}
