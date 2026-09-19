import Foundation

/// The library: books, their chapters, and where playback stopped in each.
///
/// Everything is small enough to keep in memory and mirror to JSON files — a few dozen books with
/// a few hundred chapters each. No database dependency to carry.
@MainActor
final class LibraryStore: ObservableObject {

    @Published private(set) var books: [Book] = []
    @Published private(set) var chapters: [Int: [Chapter]] = [:]
    @Published private(set) var progress: [Int: Progress] = [:]

    private let api: Api

    init(api: Api) {
        self.api = api
        books = Storage.load([Book].self, from: Storage.libraryFile) ?? []
        progress = Dictionary(
            uniqueKeysWithValues: (Storage.load([Progress].self, from: Storage.progressFile) ?? [])
                .map { ($0.bookId, $0) }
        )
        for book in books {
            chapters[book.id] = Storage.load([Chapter].self, from: Storage.chaptersFile(book.id)) ?? []
        }
    }

    // MARK: - Reading

    func book(_ id: Int) -> Book? { books.first { $0.id == id } }
    func chapters(of bookId: Int) -> [Chapter] { chapters[bookId] ?? [] }
    func chapter(_ id: Int, in bookId: Int) -> Chapter? { chapters(of: bookId).first { $0.id == id } }

    /// Books ordered the way the library shows them: most recently touched first.
    var ordered: [Book] {
        books.sorted { lhs, rhs in
            let l = progress[lhs.id]?.updatedAt ?? lhs.addedAt
            let r = progress[rhs.id]?.updatedAt ?? rhs.addedAt
            return l > r
        }
    }

    var lastPlayed: Progress? {
        progress.values.max { $0.updatedAt < $1.updatedAt }
    }

    func downloadedCount(_ bookId: Int) -> Int {
        chapters(of: bookId).filter(\.isDownloaded).count
    }

    /// How far into the whole book the saved position is.
    func elapsedMs(_ bookId: Int) -> Int64 {
        guard let saved = progress[bookId] else { return 0 }
        let list = chapters(of: bookId)
        guard let index = list.firstIndex(where: { $0.id == saved.chapterId }) else { return saved.positionMs }
        let before = list.prefix(index).reduce(Int64(0)) { $0 + $1.durationMs }
        return before + saved.positionMs
    }

    func fraction(_ bookId: Int) -> Double {
        guard let book = book(bookId), book.totalDurationMs > 0 else { return 0 }
        return min(max(Double(elapsedMs(bookId)) / Double(book.totalDurationMs), 0), 1)
    }

    // MARK: - Importing

    @discardableResult
    func importBook(link: String) async throws -> Int {
        let id: Int
        if let direct = Int(link.trimmingCharacters(in: .whitespaces)) {
            id = direct
        } else {
            id = try await api.resolveBookId(url: Api.normalize(Api.extractLink(link) ?? link))
        }
        try await importBook(id: id)
        return id
    }

    func importBook(id: Int) async throws {
        let data = try await api.bookData(id: id)
        store(data)
    }

    private func store(_ data: BookData) {
        let existing = book(data.id)
        let book = Book(
            id: data.id,
            title: data.title,
            path: data.path,
            authors: data.authors,
            readers: data.readers,
            series: data.series,
            cover: data.cover,
            totalDurationMs: data.totalDurationMs,
            chapterCount: data.tracks.count,
            addedAt: existing?.addedAt ?? Date(),
            urlsFetchedAt: Date(),
            speedLevels: data.speedLevels
        )
        if let index = books.firstIndex(where: { $0.id == book.id }) {
            books[index] = book
        } else {
            books.append(book)
        }

        // Keep the download state of chapters we already have; only the URLs go stale.
        let previous = Dictionary(uniqueKeysWithValues: chapters(of: data.id).map { ($0.id, $0) })
        chapters[data.id] = data.tracks.enumerated().map { index, track in
            var chapter = previous[track.id] ?? Chapter(
                id: track.id,
                bookId: data.id,
                position: index,
                title: track.title,
                url: track.url,
                durationMs: Int64(track.durationFloat * 1000)
            )
            chapter.url = track.url
            chapter.position = index
            chapter.title = track.title
            chapter.durationMs = Int64(track.durationFloat * 1000)
            return chapter
        }
        persistBooks()
        persistChapters(data.id)
    }

    /// Chapter URLs carry a hash the site rotates every ~66 hours; anything older is refetched.
    @discardableResult
    func refreshUrls(_ bookId: Int) async throws -> [Chapter] {
        let data = try await api.bookData(id: bookId)
        store(data)
        return chapters(of: bookId)
    }

    func refreshUrlsIfStale(_ bookId: Int) async throws -> [Chapter] {
        guard let book = book(bookId) else { return [] }
        if Date().timeIntervalSince(book.urlsFetchedAt) > 24 * 60 * 60 {
            return try await refreshUrls(bookId)
        }
        return chapters(of: bookId)
    }

    // MARK: - Mutating

    func update(chapter: Chapter) {
        guard var list = chapters[chapter.bookId],
              let index = list.firstIndex(where: { $0.id == chapter.id }) else { return }
        list[index] = chapter
        chapters[chapter.bookId] = list
        persistChapters(chapter.bookId)
    }

    func saveProgress(bookId: Int, chapterId: Int, positionMs: Int64, speed: Double) {
        progress[bookId] = Progress(
            bookId: bookId,
            chapterId: chapterId,
            positionMs: positionMs,
            speed: speed,
            updatedAt: Date()
        )
        Storage.save(Array(progress.values), to: Storage.progressFile)
    }

    func deleteBook(_ bookId: Int) {
        Storage.deleteBook(bookId)
        books.removeAll { $0.id == bookId }
        chapters[bookId] = nil
        progress[bookId] = nil
        persistBooks()
        Storage.save(Array(progress.values), to: Storage.progressFile)
    }

    /// Frees the audio but keeps the book and its position in the library.
    func deleteAudio(_ bookId: Int) {
        Storage.deleteAudio(bookId)
        chapters[bookId] = chapters(of: bookId).map { chapter in
            var copy = chapter
            copy.state = .pending
            copy.downloadedBytes = 0
            return copy
        }
        persistChapters(bookId)
    }

    private func persistBooks() {
        Storage.save(books, to: Storage.libraryFile)
    }

    func persistChapters(_ bookId: Int) {
        Storage.save(chapters(of: bookId), to: Storage.chaptersFile(bookId))
    }
}
