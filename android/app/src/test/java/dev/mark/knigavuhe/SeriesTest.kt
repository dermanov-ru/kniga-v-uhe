package dev.mark.knigavuhe

import dev.mark.knigavuhe.data.db.LibraryRow
import dev.mark.knigavuhe.data.remote.SeriesScraper
import dev.mark.knigavuhe.ui.library.LibraryEntry
import dev.mark.knigavuhe.ui.library.LibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesScraperTest {

    /** Блок «Все книги из цикла …» со страницы книги: текущая книга не ссылка, а <strong>. */
    private val page = """
        <html><body>
        <div class="info_block book_serie_block">
          <div class="book_serie_title">
            Все книги из цикла <a href="/series/garri-potter/">Гарри Поттер</a>:
          </div>
          <div class="book_serie_item">
            <span class="book_serie_item_index">1.</span>
            <a href="/book/5230-garri-potter-i-filosofskijj-kamen/">Философский камень</a>
          </div>
          <div class="book_serie_item">
            <span class="book_serie_item_index">2.</span>
            <strong>Тайная комната</strong>
          </div>
          <div class="book_serie_item">
            <span class="book_serie_item_index">3.</span>
            <a href="/book/5246-uznik/">Узник Азкабана</a>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `reads the real page where two serie blocks sit side by side`() {
        // На странице книги сначала идёт блок «Другие озвучки» с такими же классами, и его
        // пункты нельзя считать книгами цикла.
        val info = SeriesScraper.parse(fixture("book_series_block.html"))!!
        assertEquals("garri-potter", info.slug)
        assertEquals("Гарри Поттер", info.name)
        assertEquals(1, info.index)
        assertEquals(7, info.listed)
    }

    @Test
    fun `reads slug name and the current book number`() {
        val info = SeriesScraper.parse(page)!!
        assertEquals("garri-potter", info.slug)
        assertEquals("Гарри Поттер", info.name)
        assertEquals(2, info.index)
        assertEquals(3, info.listed)
    }

    @Test
    fun `returns nothing for a book outside any cycle`() {
        assertNull(SeriesScraper.parse("<html><body><div>Просто книга</div></body></html>"))
    }

    @Test
    fun `builds the series path`() {
        assertEquals("/series/garri-potter/", SeriesScraper.path("garri-potter"))
    }
}

class LibraryGroupingTest {

    private fun row(id: Int, slug: String? = null, index: Int = 0, total: Int = 0, title: String = "Книга $id") =
        LibraryRow(
            id = id,
            title = title,
            authors = "",
            readers = "",
            cover = null,
            totalDurationMs = 3_600_000,
            chapterCount = 10,
            addedAt = 0,
            doneCount = 0,
            chapterId = null,
            chapterTitle = null,
            positionMs = null,
            updatedAt = null,
            elapsedBeforeMs = null,
            seriesSlug = slug,
            seriesName = slug?.let { "Цикл $it" },
            seriesIndex = index,
            seriesTotal = total,
        )

    @Test
    fun `collects two or more books of a cycle into one block`() {
        val entries = LibraryViewModel.groupBySeries(
            listOf(
                row(2, "hp", index = 2, total = 7),
                row(99),
                row(1, "hp", index = 1, total = 7),
            )
        )

        assertEquals(2, entries.size)
        val series = entries.first() as LibraryEntry.Series
        assertEquals("hp", series.slug)
        // Внутри блока книги идут по номеру в цикле, а не по времени добавления.
        assertEquals(listOf(1, 2), series.rows.map { it.id })
        assertEquals(7, series.known)
        assertTrue(entries[1] is LibraryEntry.Single)
    }

    @Test
    fun `keeps a lone book of a cycle as a plain row`() {
        val entries = LibraryViewModel.groupBySeries(listOf(row(1, "hp", index = 3, total = 7)))
        assertTrue(entries.single() is LibraryEntry.Single)
    }

    @Test
    fun `puts the cycle where its freshest book stands`() {
        val entries = LibraryViewModel.groupBySeries(
            listOf(
                row(50),
                row(2, "hp", index = 2),
                row(1, "hp", index = 1),
                row(51),
            )
        )
        assertTrue(entries[0] is LibraryEntry.Single)
        assertTrue(entries[1] is LibraryEntry.Series)
        assertTrue(entries[2] is LibraryEntry.Single)
        assertEquals(3, entries.size)
    }

    @Test
    fun `sums progress across the cycle`() {
        val series = LibraryViewModel.groupBySeries(
            listOf(
                row(1, "hp", index = 1).copy(positionMs = 1_800_000, updatedAt = 1),
                row(2, "hp", index = 2),
            )
        ).first() as LibraryEntry.Series

        assertEquals(7_200_000, series.totalMs)
        assertEquals(1_800_000, series.elapsedMs)
        assertEquals(0.25f, series.fraction, 0.001f)
    }
}

class NextInSeriesTest {

    private fun card(title: String, id: Int) = dev.mark.knigavuhe.data.remote.BookCard(
        bookId = id,
        path = "/book/$id-x/",
        title = title,
        cover = null,
        authors = "",
        readers = "",
        genre = "",
        about = "",
        durationText = "",
    )

    private val cycle = listOf(
        card("1. Философский камень", 1),
        card("2. Тайная комната", 2),
        card("3. Узник Азкабана", 3),
    )

    @Test
    fun `picks the book whose title carries the next number`() {
        val next = dev.mark.knigavuhe.data.repo.BookRepository.pickNext(cycle, currentIndex = 1)
        assertEquals(2, next?.bookId)
    }

    @Test
    fun `falls back to list order when titles carry no numbers`() {
        val plain = cycle.map { card(it.title.substringAfter(". "), it.bookId ?: 0) }
        val next = dev.mark.knigavuhe.data.repo.BookRepository.pickNext(plain, currentIndex = 1)
        assertEquals(2, next?.bookId)
    }

    @Test
    fun `returns nothing past the last book`() {
        assertNull(dev.mark.knigavuhe.data.repo.BookRepository.pickNext(cycle, currentIndex = 3))
    }

    @Test
    fun `returns nothing when the book has no place in a cycle`() {
        assertNull(dev.mark.knigavuhe.data.repo.BookRepository.pickNext(cycle, currentIndex = 0))
    }
}
