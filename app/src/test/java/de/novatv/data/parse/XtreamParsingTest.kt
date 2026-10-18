package de.novatv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamParsingTest {

    @Test
    fun toleratesMixedTypesAndNulls() {
        val items = mutableListOf<XtreamLive>()
        XtreamParsing.liveStreams(
            """[
              {"num":1,"name":"ARD","stream_id":101,"stream_icon":"","epg_channel_id":null,"category_id":"5","tv_archive":1,"tv_archive_duration":"7"},
              {"num":"2","name":"ZDF","stream_id":"102","epg_channel_id":"zdf.de","category_id":5},
              {"name":"ohne id"}
            ]""".byteInputStream()
        ) { items += it }

        assertEquals(2, items.size)
        assertEquals("101", items[0].streamId)
        assertNull(items[0].icon)
        assertNull(items[0].epgId)
        assertEquals(7, items[0].archiveDays)
        assertEquals(2, items[1].number)
        assertEquals("5", items[1].categoryId)
    }

    @Test
    fun readsCategoriesAndAccount() {
        val categories = XtreamParsing.categories("""[{"category_id":"1","category_name":"Sport"}]""".byteInputStream())
        assertEquals(mapOf("1" to "Sport"), categories)

        val account = XtreamParsing.account("""{"user_info":{"auth":1,"status":"Active","exp_date":"1700000000","max_connections":"2"}}""".byteInputStream())
        assertEquals(1_700_000_000_000L, account?.expiresAt)
        assertEquals(2, account?.maxConnections)
        assertNull(XtreamParsing.account("""{"user_info":{"auth":0}}""".byteInputStream()))
    }

    @Test
    fun readsEpisodesInBothShapes() {
        val asObject = """{"episodes":{"2":[{"id":"9","episode_num":1,"season":2,"container_extension":"mkv","info":{"duration_secs":"1500"}}],
                           "1":[{"id":"8","episode_num":"3","season":"1","title":"Pilot"}]}}"""
        val episodes = XtreamParsing.episodes(asObject.byteInputStream())
        assertEquals(listOf("8", "9"), episodes.map { it.id })
        assertEquals(1500L, episodes[1].durationSeconds)
        assertEquals("mkv", episodes[1].extension)

        val asArray = """{"episodes":[[{"id":"1","episode_num":1,"season":1}]]}"""
        assertEquals(1, XtreamParsing.episodes(asArray.byteInputStream()).size)
    }

    @Test
    fun nonArrayResponseYieldsNothing() {
        var count = 0
        XtreamParsing.vodStreams("""{"user_info":{"auth":0}}""".byteInputStream()) { count++ }
        assertEquals(0, count)
    }
}
