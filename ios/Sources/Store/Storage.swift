import Foundation

/// Where audio and metadata live on disk.
///
/// Application Support rather than Documents: the user never opens these files directly, and the
/// whole tree is excluded from iCloud backup — a single book is a few hundred megabytes.
enum Storage {

    static var root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("KnigaVUhe", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        excludeFromBackup(dir)
        return dir
    }()

    static func bookDir(_ bookId: Int) -> URL {
        let dir = root.appendingPathComponent("books/\(bookId)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func chapterFile(bookId: Int, chapterId: Int) -> URL {
        bookDir(bookId).appendingPathComponent("\(chapterId).mp3")
    }

    static func chaptersFile(_ bookId: Int) -> URL {
        bookDir(bookId).appendingPathComponent("chapters.json")
    }

    static var libraryFile: URL { root.appendingPathComponent("library.json") }
    static var progressFile: URL { root.appendingPathComponent("progress.json") }
    static var settingsFile: URL { root.appendingPathComponent("settings.json") }

    static func fileExists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    static func size(of url: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) as? Int64 ?? 0
    }

    static func bookSize(_ bookId: Int) -> Int64 {
        let dir = bookDir(bookId)
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else {
            return 0
        }
        return files.reduce(0) { $0 + size(of: $1) }
    }

    static func deleteBook(_ bookId: Int) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent("books/\(bookId)"))
    }

    static func deleteAudio(_ bookId: Int) {
        let dir = bookDir(bookId)
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for file in files where file.pathExtension == "mp3" {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private static func excludeFromBackup(_ url: URL) {
        var target = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? target.setResourceValues(values)
    }

    // MARK: - Codable helpers

    static func load<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    static func save<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? encoder.encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()
}
