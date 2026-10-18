package com.gpo.pilipala.feature.player

import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.asObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** view_points as /x/player/wbi/v2 returns them (BV1JBng6SEqF, trimmed). */
class ChaptersTest {
    private val sample = """
        {"view_points":[
          {"type":2,"from":0,"to":92,"content":"开场","imgUrl":"http://i0.hdslb.com/bfs/vchapter/42830335179_0.jpg","logoUrl":""},
          {"type":2,"from":92,"to":293,"content":"初代战神","imgUrl":"http://i0.hdslb.com/bfs/vchapter/42830335179_92.jpg","logoUrl":""},
          {"type":1,"from":100,"to":101,"content":"高能","imgUrl":""},
          {"type":2,"from":293,"to":892,"content":"SKYLINE GTR","imgUrl":"http://i0.hdslb.com/bfs/vchapter/42830335179_293.jpg","logoUrl":""}
        ]}
    """.trimIndent()

    @Test
    fun parsesTypeTwoChapters() {
        val chapters = parseChapters(BiliJson.parseToJsonElement(sample).asObj)
        assertEquals(listOf(0L, 92_000L, 293_000L), chapters.map { it.fromMs })
        assertEquals("初代战神", chapters[1].title)
        assertEquals("http://i0.hdslb.com/bfs/vchapter/42830335179_92.jpg", chapters[1].imgUrl)
        assertEquals("SKYLINE GTR", chapters.at(400_000L)?.title)
        assertNull(emptyList<Chapter>().at(0L))
    }

    @Test
    fun emptyWithoutViewPoints() {
        assertEquals(0, parseChapters(BiliJson.parseToJsonElement("""{"view_points":[]}""").asObj).size)
        assertEquals(0, parseChapters(null).size)
    }
}
