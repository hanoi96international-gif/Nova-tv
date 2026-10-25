package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountriesTest {
    private fun code(group: String) = Countries.of(group)?.code

    @Test
    fun recognisesCommonGroupNames() {
        assertEquals("DE", code("DE | Sport"))
        assertEquals("DE", code("|DE| NACHRICHTEN"))
        assertEquals("DE", code("[DE] Kinder"))
        assertEquals("DE", code("GERMANY HD"))
        assertEquals("DE", code("DE: Sky Cinema"))
        assertEquals("DE", code("DE Sport"))
        assertEquals("UK", code("UK: Sports"))
        assertEquals("TR", code("TR ULUSAL"))
        assertEquals("EXYU", code("EX-YU | Filmovi"))
        assertEquals("AT", code("★ AT ★ Österreich"))
        assertEquals("Deutschland", Countries.of("DE | Sport")?.label)
    }

    @Test
    fun otherGroupsHaveNoCountry() {
        assertNull(code("Sport"))
        assertNull(code("PRIME"))
        assertNull(code("Filme | Neu"))
        assertNull(code("TV Spielfilm"))
        assertNull(code("Ohne Gruppe"))
    }
}
