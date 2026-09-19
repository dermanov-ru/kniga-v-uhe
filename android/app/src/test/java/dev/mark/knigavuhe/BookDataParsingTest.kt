package dev.mark.knigavuhe

import dev.mark.knigavuhe.data.remote.KnigavuheApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BookDataParsingTest {

    private val api = KnigavuheApi()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `parses the two-element book_data envelope`() {
        val data = api.parseBookData(fixture("book_data_sample.json"), bookId = 5230)

        assertEquals(5230, data.id)
        assertEquals("Гарри Поттер и философский камень", data.title)
        assertEquals("Дж. К. Роулинг", data.authors)
        assertEquals("Александр Клюквин", data.readers)
        assertEquals("Гарри Поттер", data.series)
        assertEquals(3, data.tracks.size)
        assertEquals("01-01", data.tracks.first().title)
        assertTrue(data.tracks.first().url.endsWith(".mp3?1"))
        assertEquals(302_060L, (data.tracks.first().duration_float * 1000).toLong())
        assertTrue(data.speedLevels.contains(1.25f))
        assertTrue(data.cover!!.startsWith("https://"))
    }

    @Test
    fun `totals the chapter durations`() {
        val data = api.parseBookData(fixture("book_data_sample.json"), bookId = 5230)
        val expected = data.tracks.sumOf { (it.duration_float * 1000).toLong() }
        assertEquals(expected, data.totalDurationMs)
    }

    @Test
    fun `rejects a book whose audio lives on litres`() {
        // Rights that moved to LitRes leave a single zero-length stub pointing at their shop.
        val stub = """
            [null,{"result":{"init_data":{"id":16572,
              "book":{"id":16572,"name":"Война и мир","has_litres":true},
              "playlist":[{"id":200016572,"title":"Война и мир","error":0,"duration":0,
                "duration_float":0,
                "url":"https://www.litres.ru/audiotrial/?art=23188614&lfrom=337494039"}]}}}]
        """.trimIndent()

        val error = runCatching { api.parseBookData(stub, bookId = 16572) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("Книга не выложена на сайте — только на ЛитРес", error?.message)
    }

    @Test
    fun `counts only audio served by the site itself`() {
        assertTrue(KnigavuheApi.isSiteAudio("https://s8.knigavuhe.org/1/audio/5230/hash/01-01.mp3?1"))
        assertTrue(KnigavuheApi.isSiteAudio("https://s5.knigavuhe.org/2/audio/x.mp3"))
        assertFalse(KnigavuheApi.isSiteAudio("https://www.litres.ru/audiotrial/?art=1"))
        assertFalse(KnigavuheApi.isSiteAudio("https://knigavuhe.org.evil.com/x.mp3"))
        assertFalse(KnigavuheApi.isSiteAudio(""))
    }

    @Test(expected = IOException::class)
    fun `rejects the route-not-found answer`() {
        api.parseBookData("""[{"code":404,"message":"Route not found"},null]""", bookId = 1)
    }

    @Test
    fun `reads the book id straight from a numbered url`() {
        assertEquals(
            5230,
            KnigavuheApi.idFromUrl("https://m.knigavuhe.org/book/5230-garri-potter-i-filosofskijj-kamen/"),
        )
        assertEquals(546, KnigavuheApi.idFromUrl("/book/546-garri-potter-i-uznik-azkabana/"))
    }

    @Test
    fun `falls back to the page for slug-only urls`() {
        assertNull(KnigavuheApi.idFromUrl("/book/garri-potter-i-metody-racionalnogo-myshlenija/"))
        assertEquals(11675, KnigavuheApi.idFromHtml("""  var player = new BookPlayer(11675, [{"id":1}"""))
        assertEquals(11675, KnigavuheApi.idFromHtml("""cur.book = {"id":11675,"name":"…"}"""))
    }

    @Test
    fun `normalises mobile and bare links`() {
        assertEquals(
            "https://knigavuhe.org/book/5230-x/",
            KnigavuheApi.normalizeUrl("https://m.knigavuhe.org/book/5230-x/"),
        )
        assertEquals(
            "https://knigavuhe.org/book/5230-x/",
            KnigavuheApi.normalizeUrl("/book/5230-x/"),
        )
        assertEquals(
            "https://knigavuhe.org/book/5230-x/",
            KnigavuheApi.normalizeUrl(" knigavuhe.org/book/5230-x/ "),
        )
    }

    @Test
    fun `picks the link out of shared text`() {
        val shared = "Слушай: https://m.knigavuhe.org/book/5230-garri-potter/ — норм начитано"
        assertEquals(
            "https://m.knigavuhe.org/book/5230-garri-potter/",
            KnigavuheApi.extractLink(shared),
        )
    }
}
