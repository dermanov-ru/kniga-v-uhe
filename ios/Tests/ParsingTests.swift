import XCTest
@testable import KnigaVUhe

final class BookDataParsingTests: XCTestCase {

    private func fixture(_ name: String) throws -> String {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json"))
        return try String(contentsOf: url, encoding: .utf8)
    }

    func testParsesTheTwoElementEnvelope() throws {
        let data = try Api.parseBookData(try fixture("book_data_sample"), bookId: 5230)

        XCTAssertEqual(data.id, 5230)
        XCTAssertEqual(data.title, "Гарри Поттер и философский камень")
        XCTAssertEqual(data.authors, "Дж. К. Роулинг")
        XCTAssertEqual(data.readers, "Александр Клюквин")
        XCTAssertEqual(data.series, "Гарри Поттер")
        XCTAssertEqual(data.tracks.count, 3)
        XCTAssertEqual(data.tracks.first?.title, "01-01")
        XCTAssertTrue(data.tracks.first?.url.hasSuffix(".mp3?1") == true)
        XCTAssertEqual(Int64((data.tracks[0].durationFloat * 1000).rounded()), 302_060)
        XCTAssertTrue(data.speedLevels.contains(1.25))
        XCTAssertTrue(data.cover?.hasPrefix("https://") == true)
    }

    func testTotalsChapterDurations() throws {
        let data = try Api.parseBookData(try fixture("book_data_sample"), bookId: 5230)
        let expected = data.tracks.reduce(Int64(0)) { $0 + Int64($1.durationFloat * 1000) }
        XCTAssertEqual(data.totalDurationMs, expected)
    }

    func testRejectsBookWhoseAudioLivesOnLitres() {
        let stub = """
        [null,{"result":{"init_data":{"id":16572,
          "book":{"id":16572,"name":"Война и мир","has_litres":true},
          "playlist":[{"id":200016572,"title":"Война и мир","error":0,"duration":0,
            "duration_float":0,
            "url":"https://www.litres.ru/audiotrial/?art=23188614&lfrom=337494039"}]}}}]
        """
        XCTAssertThrowsError(try Api.parseBookData(stub, bookId: 16572)) { error in
            XCTAssertEqual(
                (error as? ApiError)?.errorDescription,
                "Книга не выложена на сайте — только на ЛитРес"
            )
        }
    }

    func testCountsOnlyAudioServedBySite() {
        XCTAssertTrue(Api.isSiteAudio("https://s8.knigavuhe.org/1/audio/5230/hash/01-01.mp3?1"))
        XCTAssertTrue(Api.isSiteAudio("https://s5.knigavuhe.org/2/audio/x.mp3"))
        XCTAssertFalse(Api.isSiteAudio("https://www.litres.ru/audiotrial/?art=1"))
        XCTAssertFalse(Api.isSiteAudio("https://knigavuhe.org.evil.com/x.mp3"))
        XCTAssertFalse(Api.isSiteAudio(""))
    }

    func testRejectsRouteNotFound() {
        XCTAssertThrowsError(
            try Api.parseBookData(#"[{"code":404,"message":"Route not found"},null]"#, bookId: 1)
        )
    }

    func testReadsBookIdFromNumberedUrl() {
        XCTAssertEqual(Api.idFromUrl("https://m.knigavuhe.org/book/5230-garri-potter/"), 5230)
        XCTAssertEqual(Api.idFromUrl("/book/546-uznik-azkabana/"), 546)
    }

    func testFallsBackToThePageForSlugOnlyUrls() {
        XCTAssertNil(Api.idFromUrl("/book/metody-racionalnogo-myshlenija/"))
        XCTAssertEqual(Api.idFromHtml(#"  var player = new BookPlayer(11675, [{"id":1}"#), 11675)
        XCTAssertEqual(Api.idFromHtml(#"cur.book = {"id":11675,"name":"x"}"#), 11675)
    }

    func testNormalisesMobileAndBareLinks() {
        XCTAssertEqual(Api.normalize("https://m.knigavuhe.org/book/5230-x/"), "https://knigavuhe.org/book/5230-x/")
        XCTAssertEqual(Api.normalize("/book/5230-x/"), "https://knigavuhe.org/book/5230-x/")
        XCTAssertEqual(Api.normalize(" knigavuhe.org/book/5230-x/ "), "https://knigavuhe.org/book/5230-x/")
    }

    func testPicksTheLinkOutOfSharedText() {
        let shared = "Слушай: https://m.knigavuhe.org/book/5230-garri-potter/ — норм начитано"
        XCTAssertEqual(Api.extractLink(shared), "https://m.knigavuhe.org/book/5230-garri-potter/")
    }

    func testPathPagination() {
        XCTAssertEqual(Api.pagedPath("/reader/kljukvin-aleksandr/", page: 3), "/reader/kljukvin-aleksandr/3/")
        XCTAssertEqual(Api.pagedPath("/reader/kljukvin-aleksandr/", page: 1), "/reader/kljukvin-aleksandr/")
        XCTAssertEqual(Api.pagedPath("/search/?q=x", page: 2), "/search/?q=x&page=2")
    }
}

final class ScraperTests: XCTestCase {

    /// Mirrors the listing markup the desktop host renders.
    private let desktopListing = """
    <html><body>
    <div class="bookkitem">
      <a class="bookkitem_cover" href="/book/546-uznik/">
        <img class="bookkitem_cover_img" src="https://s5.knigavuhe.org/1/covers/546/2-1.jpg" />
      </a>
      <div class="bookkitem_right">
        <div class="bookkitem_name">
          <a href="/book/546-uznik/" class="bookkitem_name">Узник Азкабана</a>
          <span class="bookkitem_author"><a href="/author/dzhoan-rouling/">Дж. К. Роулинг</a></span>
        </div>
        <div class="bookkitem_genre"><a href="/genre/fantastika/">Фантастика и фэнтези</a></div>
        <div class="bookkitem_about">Третий год обучения.</div>
        <div class="bookkitem_meta">
          <div class="bookkitem_meta_block">
            <span class="bookkitem_icon -reader"></span>
            <a href="/reader/kljukvin-aleksandr/">Александр Клюквин</a>
          </div>
          <div class="bookkitem_meta_block">
            <span class="bookkitem_icon -time"></span>
            <span class="bookkitem_meta_time">11 часов 22 минуты</span>
          </div>
        </div>
      </div>
    </div>
    </body></html>
    """

    /// m.knigavuhe.org renders the same listing as `span.bookkitemm` with a different meta layout.
    private let mobileListing = """
    <html><body><div class="books_list">
    <span class="bookkitemm" id="book1127">
      <a class="bookkitem_cover" href="/book/warhammer-40000-kozyr/">
        <img class="bookkitem_cover_img" src="https://s5.knigavuhe.org/1/covers/1127/2-1.jpg" />
      </a>
      <span class="bookkitem_right">
        <div class="bookkitem_name"><a href="/book/warhammer-40000-kozyr/" class="bookkitem_name">Warhammer 40000. Козырь</a></div>
        <div class="bookkitem_genre"><a href="/genre/fantastika/">Фантастика и фэнтези</a></div>
        <div class="bookkitem_meta_block">
          <span class="bookkitem_icon -author"></span>
          <a href="/author/imodium-general/">Имодиум Генерал</a>
        </div>
        <div class="bookkitem_meta_block">
          <span class="bookkitem_icon -reader"></span>
          <a href="/reader/ravenhan/">ravenhan</a>
        </div>
        <div class="bookkitem_meta_block">
          <span class="bookkitem_icon -time"></span>
          <span class="bookkitem_meta_time">3 часа 19 минут</span>
        </div>
      </span>
    </span>
    </div></body></html>
    """

    private let peopleListing = """
    <html><body>
    <div class="legacy_people_row">
      <a href="/reader/buldakov-oleg/" class="legacy_people_avatar">
        <img src="https://s5.knigavuhe.org/1/avatars/1/a_100.jpg" />
      </a>
      <div class="legacy_people_identity_body">
        <div class="legacy_people_name"><a href="/reader/buldakov-oleg/">Олег <span>Булдаков</span></a></div>
        <div class="legacy_people_meta"><span class="legacy_people_books">1 479 книг</span></div>
      </div>
    </div>
    </body></html>
    """

    func testReadsDesktopCard() throws {
        let page = try Scraper.listing(desktopListing)
        let card = try XCTUnwrap(page.items.first)
        XCTAssertEqual(card.bookId, 546)
        XCTAssertEqual(card.title, "Узник Азкабана")
        XCTAssertEqual(card.authors, "Дж. К. Роулинг")
        XCTAssertEqual(card.readers, "Александр Клюквин")
        XCTAssertEqual(card.genre, "Фантастика и фэнтези")
        XCTAssertEqual(card.about, "Третий год обучения.")
        XCTAssertEqual(card.durationText, "11 часов 22 минуты")
    }

    func testReadsMobileCard() throws {
        let page = try Scraper.listing(mobileListing)
        let card = try XCTUnwrap(page.items.first)
        XCTAssertEqual(card.title, "Warhammer 40000. Козырь")
        XCTAssertEqual(card.authors, "Имодиум Генерал")
        XCTAssertEqual(card.readers, "ravenhan")
        XCTAssertEqual(card.durationText, "3 часа 19 минут")
        // The URL has no number, but the element id does.
        XCTAssertEqual(card.bookId, 1127)
    }

    func testFlagsCardsThatOnlyLeadToLitres() throws {
        let litresCard = """
        <html><body>
        <span class="bookkitemm" id="book16572">
          <div class="bookkitem_name"><a href="/book/vojjna-i-mir-2/" class="bookkitem_name">Война и мир</a></div>
          <div class="bookkitem_meta_block">
            <span class="bookkitem_icon -reader"></span><a href="/reader/kljukvin-aleksandr/">Александр Клюквин</a>
          </div>
          <div class="bookkitem_meta_block -litres">
            <span class="bookkitem_icon -litres"></span>
          </div>
        </span>
        </body></html>
        """
        let card = try XCTUnwrap(try Scraper.listing(litresCard).items.first)
        XCTAssertTrue(card.isLitres)
        XCTAssertEqual(card.durationText, "")
        XCTAssertEqual(card.bookId, 16572)
    }

    func testLeavesOrdinaryCardsUnflagged() throws {
        XCTAssertFalse(try XCTUnwrap(try Scraper.listing(desktopListing).items.first).isLitres)
    }

    func testEmptyPageReportsNoMore() throws {
        let page = try Scraper.listing("<html><body>ничего</body></html>")
        XCTAssertTrue(page.items.isEmpty)
        XCTAssertFalse(page.hasMore)
    }

    func testReadsNarratorCard() throws {
        let page = try Scraper.readers(peopleListing)
        let reader = try XCTUnwrap(page.items.first)
        XCTAssertEqual(reader.slug, "buldakov-oleg")
        XCTAssertEqual(reader.name, "Олег Булдаков")
        XCTAssertEqual(reader.booksText, "1 479 книг")
        XCTAssertEqual(reader.path, "/reader/buldakov-oleg/")
    }
}
