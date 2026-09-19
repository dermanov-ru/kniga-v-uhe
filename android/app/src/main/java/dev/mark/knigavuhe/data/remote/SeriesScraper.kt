package dev.mark.knigavuhe.data.remote

import org.jsoup.Jsoup

/**
 * Привязка книги к циклу. `book_data` отдаёт только название цикла, поэтому слаг и номер книги
 * берутся со страницы книги — из блока «Все книги из цикла …».
 */
data class SeriesInfo(
    val slug: String,
    val name: String,
    /** Номер книги в цикле, как его печатает сайт (с единицы). */
    val index: Int,
    /** Сколько книг показано в блоке. Полный состав всё равно берём со страницы цикла. */
    val listed: Int,
)

object SeriesScraper {

    private val SLUG = Regex("""/series/([^/]+)/""")

    fun parse(html: String): SeriesInfo? {
        val doc = Jsoup.parse(html, SITE)

        // Класс `book_serie_block` на странице встречается дважды: сначала «Другие озвучки»
        // той же книги, потом сам цикл. Нужен тот блок, у которого в заголовке ссылка на цикл.
        val block = doc.select(".book_serie_block").firstOrNull { candidate ->
            candidate.selectFirst(".book_serie_title a[href^=\"/series/\"]") != null
        } ?: return null

        val link = block.selectFirst(".book_serie_title a[href^=\"/series/\"]") ?: return null
        val slug = SLUG.find(link.attr("href"))?.groupValues?.get(1) ?: return null

        val items = block.select(".book_serie_item")
        // Текущая книга внутри блока не ссылка, а <strong> — по этому её и находим.
        val currentIndex = items.indexOfFirst { it.selectFirst("strong") != null }
        val printed = items.getOrNull(currentIndex)
            ?.selectFirst(".book_serie_item_index")
            ?.text()
            ?.trim()
            ?.removeSuffix(".")
            ?.toIntOrNull()

        return SeriesInfo(
            slug = slug,
            name = link.text().trim(),
            index = printed ?: (currentIndex + 1).coerceAtLeast(1),
            listed = items.size,
        )
    }

    fun path(slug: String): String = "/series/$slug/"
}
