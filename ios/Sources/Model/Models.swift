import Foundation

/// A book in the library, as stored on disk.
struct Book: Codable, Identifiable, Hashable {
    let id: Int
    var title: String
    var path: String
    var authors: String
    var readers: String
    var series: String?
    var cover: String?
    var totalDurationMs: Int64
    var chapterCount: Int
    var addedAt: Date
    /// When the chapter URLs were last fetched; the site rotates them roughly every 66 hours.
    var urlsFetchedAt: Date
    var speedLevels: [Double]
}

enum ChapterState: Int, Codable {
    case pending = 0
    case downloading = 1
    case done = 2
    case failed = 3
}

struct Chapter: Codable, Identifiable, Hashable {
    let id: Int
    var bookId: Int
    var position: Int
    var title: String
    var url: String
    var durationMs: Int64
    var sizeBytes: Int64 = 0
    var downloadedBytes: Int64 = 0
    var state: ChapterState = .pending

    var isDownloaded: Bool { state == .done }
}

/// Playback position, kept per book so switching between books loses nothing.
struct Progress: Codable, Hashable {
    var bookId: Int
    var chapterId: Int
    var positionMs: Int64
    var speed: Double
    var updatedAt: Date
}

/// A book card from a search or catalog listing.
struct BookCard: Identifiable, Hashable {
    var id: String { path }
    let bookId: Int?
    let path: String
    let title: String
    let cover: String?
    let authors: String
    let readers: String
    let genre: String
    let about: String
    let durationText: String
    /// Audio never made it onto the site: the card only leads to LitRes.
    let isLitres: Bool
}

/// A narrator from /readers/.
struct ReaderCard: Identifiable, Hashable, Codable {
    var id: String { slug }
    let slug: String
    let path: String
    let name: String
    let booksText: String
    let avatar: String?
}

struct CatalogPage {
    let items: [BookCard]
    let hasMore: Bool
}

struct ReaderPage {
    let items: [ReaderCard]
    let hasMore: Bool
}

/// Everything the app needs out of one `book_data` call.
struct BookData {
    let id: Int
    let title: String
    let path: String
    let authors: String
    let readers: String
    let series: String?
    let cover: String?
    let tracks: [Track]
    let speedLevels: [Double]

    var totalDurationMs: Int64 {
        tracks.reduce(0) { $0 + Int64($1.durationFloat * 1000) }
    }
}

struct Track {
    let id: Int
    let title: String
    let url: String
    let durationFloat: Double
}
