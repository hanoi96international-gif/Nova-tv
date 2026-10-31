package de.ayutv.data.parse

import de.ayutv.data.parse.KeyRescue.Ref
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRescueTest {
    @Test
    fun newStreamAddressKeepsGroupEntries() {
        // M3U: Token in der Adresse hat sich geändert.
        val old = listOf(Ref("1:c:rtl:http://a/1.ts?t=old", "DE: RTL HD", "rtl.de", "DE | Voll"), Ref("1:c:zdf:http://a/2.ts?t=old", "ZDF", "zdf.de", "DE | Voll"))
        val fresh = listOf(Ref("1:c:rtl:http://a/1.ts?t=new", "DE: RTL HD", "rtl.de", "DE | Voll"), Ref("1:c:zdf:http://a/2.ts?t=new", "ZDF", "zdf.de", "DE | Voll"))
        assertEquals(mapOf("1:c:rtl:http://a/1.ts?t=old" to "1:c:rtl:http://a/1.ts?t=new", "1:c:zdf:http://a/2.ts?t=old" to "1:c:zdf:http://a/2.ts?t=new"), KeyRescue.remap(old, fresh))
    }

    @Test
    fun xtreamRenumberedAndRenamedSlightly() {
        val old = listOf(Ref("1:c:100", "DAZN 1 HD", "dazn1.de", "DE | Sport"), Ref("1:c:101", "|DE| SKY SPORT BUNDESLIGA 1 FHD", null, "DE | Sky"))
        val fresh = listOf(
            Ref("1:c:500", "DAZN 1 HD", "dazn1.de", "DE | Sport"),
            Ref("1:c:501", "DE: SKY SPORT BUNDESLIGA 1 FHD", null, "DE | Sky"),
            Ref("1:c:502", "DE: SKY SPORT BUNDESLIGA 1 4K", null, "DE | Sky"),
        )
        assertEquals(mapOf("1:c:100" to "1:c:500", "1:c:101" to "1:c:501"), KeyRescue.remap(old, fresh))
    }

    @Test
    fun ambiguousOrMissingIsNotGuessed() {
        val old = listOf(Ref("1:c:1", "Sport 1", null, "A"), Ref("1:c:2", "Weg", null, "A"))
        val fresh = listOf(Ref("1:c:7", "Sport 1", null, "B"), Ref("1:c:8", "Sport 1", null, "C"))
        assertEquals(emptyMap<String, String>(), KeyRescue.remap(old, fresh))
        // Unverändert: nichts zu tun.
        assertEquals(emptyMap<String, String>(), KeyRescue.remap(listOf(Ref("1:c:1", "X", null, "A")), listOf(Ref("1:c:1", "X", null, "A"))))
    }
}
