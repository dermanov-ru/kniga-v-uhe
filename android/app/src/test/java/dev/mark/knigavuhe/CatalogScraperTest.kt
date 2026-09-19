package dev.mark.knigavuhe

import dev.mark.knigavuhe.data.remote.CatalogScraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogScraperTest {

    /** Mirrors the listing markup the site renders for search, author, reader and genre pages. */
    private val listing = """
        <html><body>
        <div class="bookkitem">
          <a class="bookkitem_cover" href="/book/546-uznik/">
            <img class="bookkitem_cover_img" src="https://s5.knigavuhe.org/1/covers/546/2-1.jpg" />
          </a>
          <div class="bookkitem_right">
            <div class="bookkitem_name">
              <a href="/book/546-uznik/" class="bookkitem_name">Узник Азкабана</a>
              <span class="bookkitem_author">
                <span class="bookkitem_author_label">автор</span>
                <a href="/author/dzhoan-rouling/">Дж. К. Роулинг</a>
              </span>
            </div>
            <div class="bookkitem_genre"><a href="/genre/fantastika/">Фантастика и фэнтези</a></div>
            <div class="bookkitem_about">Третий год обучения.</div>
            <div class="bookkitem_meta">
              <div class="bookkitem_meta_block">
                <span class="bookkitem_icon -reader"></span>
                <span class="bookkitem_meta_label">Читает</span>
                <a href="/reader/kljukvin-aleksandr/">Александр Клюквин</a>
              </div>
              <div class="bookkitem_meta_block">
                <span class="bookkitem_icon -time"></span>
                <span class="bookkitem_meta_time">11 часов 22 минуты</span>
              </div>
            </div>
          </div>
        </div>
        <div class="bookkitem">
          <a class="bookkitem_cover" href="/book/metody-racionalnogo-myshlenija/"></a>
          <div class="bookkitem_name">
            <a href="/book/metody-racionalnogo-myshlenija/" class="bookkitem_name">Методы рационального мышления</a>
          </div>
        </div>
        <div class="paginator"><a href="/search/?q=x&page=2">2</a></div>
        </body></html>
    """.trimIndent()

    @Test
    fun `reads every field of a result card`() {
        val page = CatalogScraper.parseListing(listing)
        assertEquals(2, page.items.size)

        val first = page.items.first()
        assertEquals(546, first.bookId)
        assertEquals("/book/546-uznik/", first.path)
        assertEquals("Узник Азкабана", first.title)
        assertEquals("Дж. К. Роулинг", first.authors)
        assertEquals("Александр Клюквин", first.readers)
        assertEquals("Фантастика и фэнтези", first.genre)
        assertEquals("Третий год обучения.", first.about)
        assertEquals("11 часов 22 минуты", first.durationText)
        assertEquals("https://s5.knigavuhe.org/1/covers/546/2-1.jpg", first.cover)
    }

    @Test
    fun `leaves the id empty when the url has no number`() {
        val page = CatalogScraper.parseListing(listing)
        assertNull(page.items[1].bookId)
        assertEquals("Методы рационального мышления", page.items[1].title)
    }


    /** m.knigavuhe.org renders the same listing as `span.bookkitemm` with a different meta layout. */
    private val mobileListing = """
        <html><body><div class="books_list">
        <span class="bookkitemm" id="book1127">
          <a class="bookkitem_cover" href="/book/warhammer-40000-kozyr/">
            <img class="bookkitem_cover_img" src="https://s5.knigavuhe.org/1/covers/1127/2-1.jpg" alt="img" />
          </a>
          <span class="bookkitem_right">
            <div class="bookkitem_name -extend_padding">
              <a href="/book/warhammer-40000-kozyr/" class="bookkitem_name">Warhammer 40000. Козырь</a>
            </div>
            <div class="bookkitem_genre -extend_padding"><a href="/genre/fantastika/">Фантастика и фэнтези</a></div>
            <div class="bookkitem_meta_block -extend_padding">
              <span class="bookkitem_icon -author"></span>
              <a href="/author/imodium-general/">Имодиум Генерал</a>
            </div>
            <div class="bookkitem_meta_block -extend_padding">
              <span class="bookkitem_icon -reader"></span>
              <a href="/reader/ravenhan/">ravenhan</a>
            </div>
            <div class="bookkitem_meta_block -extend_padding">
              <span class="bookkitem_icon -time"></span>
              <span class="bookkitem_meta_time">3 часа 19 минут</span>
            </div>
          </span>
        </span>
        </div>
        <a href="/search/?q=warhammer&page=2">2</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `reads the mobile card markup too`() {
        val page = CatalogScraper.parseListing(mobileListing)
        assertEquals(1, page.items.size)

        val card = page.items.first()
        assertEquals("Warhammer 40000. Козырь", card.title)
        assertEquals("Имодиум Генерал", card.authors)
        assertEquals("ravenhan", card.readers)
        assertEquals("Фантастика и фэнтези", card.genre)
        assertEquals("3 часа 19 минут", card.durationText)
        // The URL has no number, but the element id does.
        assertEquals(1127, card.bookId)
    }

    @Test
    fun `flags cards that only lead to litres`() {
        val litresCard = """
            <html><body>
            <span class="bookkitemm" id="book16572">
              <div class="bookkitem_name"><a href="/book/vojjna-i-mir-2/" class="bookkitem_name">Война и мир</a></div>
              <div class="bookkitem_meta_block">
                <span class="bookkitem_icon -reader"></span><a href="/reader/kljukvin-aleksandr/">Александр Клюквин</a>
              </div>
              <div class="bookkitem_meta_block -litres">
                <span class="bookkitem_icon -litres"></span><span class="bookkitem_litres_icon"></span>
              </div>
            </span>
            </body></html>
        """.trimIndent()

        val card = CatalogScraper.parseListing(litresCard).items.single()
        assertEquals(true, card.isLitres)
        assertEquals("", card.durationText)
        assertEquals(16572, card.bookId)
    }

    @Test
    fun `leaves ordinary cards unflagged`() {
        assertEquals(false, CatalogScraper.parseListing(listing).items.first().isLitres)
    }

    @Test
    fun `reports an empty page instead of pretending there is more`() {
        val page = CatalogScraper.parseListing("<html><body>ничего</body></html>")
        assertEquals(0, page.items.size)
        assertEquals(false, page.hasMore)
    }
}
