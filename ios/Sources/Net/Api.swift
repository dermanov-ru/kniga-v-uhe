import Foundation

enum ApiError: LocalizedError {
    case http(Int, String)
    case badPayload(String)

    var errorDescription: String? {
        switch self {
        case let .http(code, url): return "HTTP \(code) на \(url)"
        case let .badPayload(reason): return reason
        }
    }
}

/// Client for knigavuhe.org. Everything it needs is public: no auth, no cookies, no tokens.
actor Api {

    static let site = "https://knigavuhe.org"
    /// A browser UA keeps ddos-guard happy; it also decides which markup the site serves.
    static let userAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.default
        config.httpAdditionalHeaders = [
            "User-Agent": Api.userAgent,
            "Accept-Language": "ru-RU,ru;q=0.9",
        ]
        config.timeoutIntervalForRequest = 30
        config.httpCookieAcceptPolicy = .always
        session = URLSession(configuration: config)
    }

    // MARK: - Book

    func bookData(id: Int) async throws -> BookData {
        let raw = try await get("\(Api.site)/ajax/book_data/\(id)/")
        return try Api.parseBookData(raw, bookId: id)
    }

    /// Most book URLs carry the id (`/book/5230-slug/`); some only have the slug, and then it
    /// has to be read out of the inline player bootstrap on the page itself.
    func resolveBookId(url: String) async throws -> Int {
        if let known = Api.idFromUrl(url) { return known }
        let html = try await get(Api.normalize(url))
        guard let id = Api.idFromHtml(html) else {
            throw ApiError.badPayload("Не удалось определить id книги по ссылке")
        }
        return id
    }

    // MARK: - Catalog

    func search(query: String, page: Int = 1) async throws -> CatalogPage {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? query
        let suffix = page > 1 ? "&page=\(page)" : ""
        return try Scraper.listing(try await get("\(Api.site)/search/?q=\(encoded)\(suffix)"))
    }

    /// Author, reader and genre pages share the listing markup but number pages in the path.
    func listing(path: String, page: Int = 1) async throws -> CatalogPage {
        try Scraper.listing(try await get(Api.site + Api.pagedPath(path, page: page)))
    }

    /// Narrator lookup for the reader filter: 30 people per page.
    func searchReaders(query: String, page: Int = 1) async throws -> ReaderPage {
        let encoded = query.trimmingCharacters(in: .whitespaces)
            .addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        let base = page > 1 ? "/readers/\(page)/" : "/readers/"
        return try Scraper.readers(try await get("\(Api.site)\(base)?q=\(encoded)"))
    }

    // MARK: - Transport

    private func get(_ url: String) async throws -> String {
        guard let target = URL(string: url) else { throw ApiError.badPayload("Плохая ссылка: \(url)") }
        let (data, response) = try await session.data(from: target)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw ApiError.http(code, url) }
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: - Parsing helpers (static so they can be unit-tested without the network)

    /// The endpoint answers with a two-element array: `[error, payload]`, HTTP 200 either way.
    static func parseBookData(_ raw: String, bookId: Int) throws -> BookData {
        guard let data = raw.data(using: .utf8),
              let array = try JSONSerialization.jsonObject(with: data) as? [Any],
              array.count == 2
        else { throw ApiError.badPayload("Неожиданный ответ book_data") }

        if !(array[0] is NSNull) {
            throw ApiError.badPayload("book_data вернул ошибку: \(array[0])")
        }
        guard let payload = array[1] as? [String: Any],
              let result = payload["result"] as? [String: Any],
              let initData = result["init_data"] as? [String: Any],
              let book = initData["book"] as? [String: Any],
              let playlist = initData["playlist"] as? [[String: Any]]
        else { throw ApiError.badPayload("book_data без init_data") }

        let tracks: [Track] = playlist.compactMap { item in
            guard (item["error"] as? Int ?? 0) == 0,
                  let id = item["id"] as? Int,
                  let url = item["url"] as? String, isSiteAudio(url)
            else { return nil }
            return Track(
                id: id,
                title: item["title"] as? String ?? "",
                url: url,
                durationFloat: item["duration_float"] as? Double
                    ?? Double(item["duration"] as? Int ?? 0)
            )
        }
        guard !tracks.isEmpty else {
            // Books whose rights moved to LitRes carry a single zero-length stub pointing at
            // their shop instead of an mp3.
            let litres = playlist.contains { item in
                ((item["url"] as? String) ?? "").localizedCaseInsensitiveContains("litres.ru")
            }
            throw ApiError.badPayload(
                litres ? "Книга не выложена на сайте — только на ЛитРес" : "У книги нет доступных глав"
            )
        }

        // `authors` / `readers` come back as an object normally but as an empty array for a book
        // with none, so the human-readable names are taken from the track metadata instead.
        let meta = playlist.first?["player_data"] as? [String: Any]
        let covers = initData["covers"] as? [[String: Any]]
        let cover = (covers?.first?["src"] as? String)
            ?? (book["cover"] as? String)
            ?? (meta?["cover"] as? String)

        let speeds = (result["player_data"] as? [String: Any])?["speed_levels"] as? [Double]

        return BookData(
            id: initData["id"] as? Int ?? bookId,
            title: book["name"] as? String ?? "Без названия",
            path: book["url"] as? String ?? "/book/\(bookId)/",
            authors: meta?["authors"] as? String ?? "",
            readers: meta?["readers"] as? String ?? "",
            series: meta?["series"] as? String,
            cover: cover,
            tracks: tracks,
            speedLevels: speeds ?? defaultSpeeds
        )
    }

    static let defaultSpeeds: [Double] = [0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0]

    /// A real chapter sits on the site's own CDN; anything else is a link to someone's shop.
    static func isSiteAudio(_ url: String) -> Bool {
        guard url.hasPrefix("http"),
              let host = URL(string: url)?.host
        else { return false }
        return host == "knigavuhe.org" || host.hasSuffix(".knigavuhe.org")
    }

    static func idFromUrl(_ url: String) -> Int? {
        firstMatch(#"/book/(\d+)-"#, in: url)
    }

    static func idFromHtml(_ html: String) -> Int? {
        firstMatch(#"new BookPlayer\((\d+)"#, in: html)
            ?? firstMatch(#"cur\.book\s*=\s*\{"id":(\d+)"#, in: html)
    }

    /// `/reader/kljukvin-aleksandr/` + page 3 -> `/reader/kljukvin-aleksandr/3/`
    static func pagedPath(_ path: String, page: Int) -> String {
        guard page > 1 else { return path }
        if path.contains("?") { return path + "&page=\(page)" }
        return path.hasSuffix("/") ? path + "\(page)/" : path + "/\(page)/"
    }

    /// Accepts m./www. hosts and bare paths, hands back an absolute desktop URL.
    static func normalize(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let withScheme: String
        if trimmed.hasPrefix("http") {
            withScheme = trimmed
        } else if trimmed.hasPrefix("/") {
            withScheme = site + trimmed
        } else {
            withScheme = "https://" + trimmed
        }
        return withScheme
            .replacingOccurrences(of: "://m.knigavuhe.org", with: "://knigavuhe.org")
            .replacingOccurrences(of: "://www.knigavuhe.org", with: "://knigavuhe.org")
    }

    /// Pulls a knigavuhe book link out of arbitrary shared text.
    static func extractLink(_ text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: #"https?://(?:m\.|www\.)?knigavuhe\.org/\S+"#),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(match.range, in: text)
        else { return nil }
        return String(text[range])
    }

    private static func firstMatch(_ pattern: String, in text: String) -> Int? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: text)
        else { return nil }
        return Int(text[range])
    }
}
