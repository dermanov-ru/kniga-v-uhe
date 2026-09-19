package dev.mark.knigavuhe.data.remote

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Turns the server-rendered listing pages into book cards. There is no JSON API for these.
 *
 * The site serves two different markups for the same listing: `div.bookkitem` on the desktop host
 * and `span.bookkitemm` on m.knigavuhe.org, which it redirects mobile clients to. They differ in
 * how authors are marked up and the mobile card drops the description, so both are handled here.
 */
object CatalogScraper {

    private val ID_IN_ATTR = Regex("""book(\d+)""")

    fun parseListing(html: String): CatalogPage {
        val doc = Jsoup.parse(html, SITE)
        val items = doc.select("div.bookkitem, span.bookkitemm").mapNotNull { card ->
            val link = card.selectFirst("a.bookkitem_name")
                ?: card.selectFirst("a.bookkitem_cover")
                ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null
            BookCard(
                bookId = KnigavuheApi.idFromUrl(href) ?: idFromAttr(card),
                path = href,
                title = link.text().trim(),
                cover = card.selectFirst("img.bookkitem_cover_img")?.absUrl("src"),
                authors = authorsOf(card),
                readers = metaBlockLinks(card, "-reader"),
                genre = card.select(".bookkitem_genre a").joinToString(", ") { it.text() },
                about = card.selectFirst(".bookkitem_about")?.text()?.trim().orEmpty(),
                durationText = card.selectFirst(".bookkitem_meta_time")?.text()?.trim().orEmpty(),
                // Такие карточки идут с иконкой ЛитРеса и без блока длительности.
                isLitres = card.selectFirst(".bookkitem_icon.-litres") != null,
            )
        }
        // Listings paginate two different ways (`?page=N` for search, `/slug/N/` for catalog
        // pages), and an out-of-range page simply comes back with no cards. Treating a non-empty
        // page as "there may be more" works for both and costs one empty request at the end.
        return CatalogPage(items, hasMore = items.isNotEmpty())
    }

    /** Parses the people cards of /readers/ — the narrator picker feeds on these. */
    fun parseReaders(html: String): ReaderPage {
        val doc = Jsoup.parse(html, SITE)
        val items = doc.select(".legacy_people_name a[href^=/reader/]").mapNotNull { link ->
            val href = link.attr("href")
            val slug = href.trim('/').removePrefix("reader/").trim('/')
            if (slug.isBlank()) return@mapNotNull null
            // The avatar and the book count are siblings of the name, so climb to the card itself.
            val card = link.parents().firstOrNull { it.selectFirst(".legacy_people_books") != null }
            ReaderCard(
                slug = slug,
                path = href,
                name = link.text().trim(),
                booksText = card?.selectFirst(".legacy_people_books")?.text()?.trim().orEmpty(),
                avatar = card?.selectFirst(".legacy_people_avatar img")?.absUrl("src"),
            )
        }.distinctBy { it.slug }
        return ReaderPage(items, hasMore = items.isNotEmpty())
    }

    /** The mobile card carries the numeric id as `id="book1127"`, even when the URL is slug-only. */
    private fun idFromAttr(card: Element): Int? =
        ID_IN_ATTR.find(card.id())?.groupValues?.get(1)?.toIntOrNull()

    /** Desktop groups authors in `.bookkitem_author`, mobile puts them in an icon-marked block. */
    private fun authorsOf(card: Element): String {
        val desktop = card.select(".bookkitem_author a").joinToString(", ") { it.text() }
        return desktop.ifBlank { metaBlockLinks(card, "-author") }
    }

    private fun metaBlockLinks(card: Element, iconModifier: String): String =
        card.select(".bookkitem_meta_block")
            .firstOrNull { it.selectFirst(".bookkitem_icon.$iconModifier") != null }
            ?.select("a")
            ?.joinToString(", ") { it.text() }
            .orEmpty()
}
