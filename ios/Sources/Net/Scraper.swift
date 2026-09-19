import Foundation
import SwiftSoup

/// Turns the server-rendered pages into cards. There is no JSON API for search or listings.
///
/// The site serves two markups for the same listing: `div.bookkitem` on the desktop host and
/// `span.bookkitemm` on m.knigavuhe.org, which it redirects mobile clients to. They differ in how
/// authors are marked up and the mobile card drops the description, so both are handled here.
enum Scraper {

    static func listing(_ html: String) throws -> CatalogPage {
        let doc = try SwiftSoup.parse(html, Api.site)
        let cards = try doc.select("div.bookkitem, span.bookkitemm")

        var items: [BookCard] = []
        for card in cards.array() {
            guard let link = try card.select("a.bookkitem_name").first()
                ?? card.select("a.bookkitem_cover").first() else { continue }
            let href = try link.attr("href")
            if href.isEmpty { continue }
            items.append(
                BookCard(
                    bookId: Api.idFromUrl(href) ?? idFromElementId(try card.id()),
                    path: href,
                    title: try link.text().trimmingCharacters(in: .whitespaces),
                    cover: try? card.select("img.bookkitem_cover_img").first()?.absUrl("src"),
                    authors: try authors(of: card),
                    readers: try metaBlockLinks(card, icon: "-reader"),
                    genre: try card.select(".bookkitem_genre a").array()
                        .map { try $0.text() }.joined(separator: ", "),
                    about: (try? card.select(".bookkitem_about").first()?.text())?
                        .trimmingCharacters(in: .whitespaces) ?? "",
                    durationText: (try? card.select(".bookkitem_meta_time").first()?.text())?
                        .trimmingCharacters(in: .whitespaces) ?? "",
                    // Such cards come with a LitRes icon and no duration block.
                    isLitres: ((try? card.select(".bookkitem_icon.-litres").first()) ?? nil) != nil
                )
            )
        }
        // Listings paginate two different ways and an out-of-range page simply comes back empty,
        // so a non-empty page means "there may be more" — one wasted request at the very end.
        return CatalogPage(items: items, hasMore: !items.isEmpty)
    }

    /// Parses the people cards of /readers/ — the narrator picker feeds on these.
    static func readers(_ html: String) throws -> ReaderPage {
        let doc = try SwiftSoup.parse(html, Api.site)
        var seen = Set<String>()
        var items: [ReaderCard] = []

        for link in try doc.select(".legacy_people_name a[href^=/reader/]").array() {
            let href = try link.attr("href")
            let slug = href.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                .replacingOccurrences(of: "reader/", with: "")
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if slug.isEmpty || seen.contains(slug) { continue }
            seen.insert(slug)

            // The avatar and the book count are siblings of the name, so climb to the card itself.
            let card = link.parents().array().first { parent in
                ((try? parent.select(".legacy_people_books").first()) ?? nil) != nil
            }
            items.append(
                ReaderCard(
                    slug: slug,
                    path: href,
                    name: try link.text().trimmingCharacters(in: .whitespaces),
                    booksText: (try? card?.select(".legacy_people_books").first()?.text())?
                        .trimmingCharacters(in: .whitespaces) ?? "",
                    avatar: try? card?.select(".legacy_people_avatar img").first()?.absUrl("src")
                )
            )
        }
        return ReaderPage(items: items, hasMore: !items.isEmpty)
    }

    /// The mobile card carries the numeric id as `id="book1127"`, even when the URL is slug-only.
    private static func idFromElementId(_ elementId: String) -> Int? {
        guard elementId.hasPrefix("book") else { return nil }
        return Int(elementId.dropFirst(4))
    }

    /// Desktop groups authors in `.bookkitem_author`, mobile puts them in an icon-marked block.
    private static func authors(of card: Element) throws -> String {
        let desktop = try card.select(".bookkitem_author a").array()
            .map { try $0.text() }.joined(separator: ", ")
        return desktop.isEmpty ? try metaBlockLinks(card, icon: "-author") : desktop
    }

    private static func metaBlockLinks(_ card: Element, icon: String) throws -> String {
        for block in try card.select(".bookkitem_meta_block").array() {
            // `bookkitem_icon -reader` is two classes, so the dot between them matters.
            if try block.select(".bookkitem_icon.\(icon)").first() != nil {
                return try block.select("a").array().map { try $0.text() }.joined(separator: ", ")
            }
        }
        return ""
    }
}
