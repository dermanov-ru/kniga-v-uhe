package dev.mark.knigavuhe

import dev.mark.knigavuhe.data.remote.OwnConnection
import dev.mark.knigavuhe.data.remote.OwnIds
import dev.mark.knigavuhe.data.remote.OwnLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OwnLibraryTest {

    private val api = OwnLibraryApi(okhttp3.OkHttpClient())

    @Test
    fun parsesBookList() {
        val raw = """[{"id":"a970dc9b1b94","title":"Туман над рекой","authors":["Тест Тестов"],
            "series":"Проверочная серия, 1","voice":"alex","voice_title":"Алекс","state":"running",
            "created":1.7e9,"source_name":"t.fb2","chapters_total":3,"chapters_done":2,"progress":0.66,
            "seconds_left":120,"duration":41.3,"size":331674,"flagged":0,
            "cover":"/v1/library/files/a970dc9b1b94/cover.png?exp=1&sig=ab"}]"""
        val books = api.parseBooks(raw)
        assertEquals(1, books.size)
        val book = books[0]
        assertEquals("a970dc9b1b94", book.id)
        assertEquals("Тест Тестов", book.authorsText)
        assertEquals(2, book.chapters_done)
        assertFalse(book.isDone)
        assertTrue(book.chapters.isEmpty())
    }

    @Test
    fun parsesManifestWithReadyChaptersOnly() {
        val raw = """{"id":"a970dc9b1b94","title":"Туман","authors":[],"state":"done","chapters_total":2,
            "chapters_done":2,"cover":null,"links_expire":1794496264,"chapters":[
            {"n":2,"title":"Глава первая","chars":260,"words":40,"state":"done","duration":25.59,"size":205000,
             "done":3,"total":3,"retries":0,"flagged":0,"error":null,"skip":false,
             "url":"/v1/library/files/a970dc9b1b94/2.mp3?exp=1794496264&sig=99"},
            {"n":3,"title":"Глава вторая","duration":15.74,"size":126000,
             "url":"/v1/library/files/a970dc9b1b94/3.mp3?exp=1794496264&sig=98"}]}"""
        val m = api.parseManifest(raw)
        assertTrue(m.isDone)
        assertEquals(1794496264L, m.links_expire)
        assertEquals(listOf(2, 3), m.chapters.map { it.n })
        assertNull(m.cover)
        val conn = OwnConnection("https://api.dermanov.ru", "k_x")
        assertEquals("https://api.dermanov.ru/v1/library/files/a970dc9b1b94/2.mp3?exp=1794496264&sig=99",
            conn.absolute(m.chapters[0].url!!))
    }

    @Test
    fun parsesConnectLink() {
        val c = OwnConnection.parse("knigavuhe://library?url=https://api.dermanov.ru&key=k_AbC-12_x")!!
        assertEquals("https://api.dermanov.ru", c.baseUrl)
        assertEquals("k_AbC-12_x", c.key)
        // с пробелами, слешем на конце и в тексте вокруг
        val d = OwnConnection.parse("  строка: knigavuhe://library?url=http://100.64.0.1:8795/&key=k_1\n")!!
        assertEquals("http://100.64.0.1:8795", d.baseUrl)
        assertEquals("k_1", d.key)
        // экранированный url тоже годится
        val e = OwnConnection.parse("knigavuhe://library?url=https%3A%2F%2Fapi.dermanov.ru&key=k_2")!!
        assertEquals("https://api.dermanov.ru", e.baseUrl)
    }

    @Test
    fun rejectsBadConnectLinks() {
        assertNull(OwnConnection.parse("https://knigavuhe.org/book/123"))
        assertNull(OwnConnection.parse("knigavuhe://library?url=https://api.dermanov.ru"))
        assertNull(OwnConnection.parse("knigavuhe://library?key=k_1"))
        assertNull(OwnConnection.parse("knigavuhe://library?url=ftp://x&key=k_1"))
    }

    @Test
    fun ownIdsAreNegativeStableAndFitInt() {
        val id = "a970dc9b1b94"
        assertEquals(OwnIds.book(id), OwnIds.book(id))
        assertTrue(OwnIds.book(id) < 0)
        assertTrue(OwnIds.chapter(id, 999) < 0)
        assertTrue(OwnIds.chapter(id, 1) > Int.MIN_VALUE)
        assertTrue(OwnIds.isOwn(OwnIds.book(id)))
        assertFalse(OwnIds.isOwn(5230))
        assertEquals(id, OwnIds.fromPath(OwnIds.path(id)))
        assertNull(OwnIds.fromPath("/book/5230-garri/"))
    }

    @Test
    fun ownIdsDoNotCollideAcrossBooksAndChapters() {
        val rnd = Random(42)
        val ids = List(2000) { (1..12).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("") }.distinct()
        assertEquals(ids.size, ids.map { OwnIds.book(it) }.distinct().size)
        val chapters = ids.take(200).flatMap { book -> (1..120).map { OwnIds.chapter(book, it) } }
        assertEquals(chapters.size, chapters.distinct().size)
    }
}
