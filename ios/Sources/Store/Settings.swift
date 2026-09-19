import Foundation

/// Small preferences bag. Right now it only remembers the narrator listened to most.
@MainActor
final class Settings: ObservableObject {

    @Published private(set) var favoriteReader: ReaderCard?

    init() {
        favoriteReader = Storage.load(ReaderCard.self, from: Storage.settingsFile)
    }

    func setFavorite(_ reader: ReaderCard?) {
        favoriteReader = reader
        if let reader {
            Storage.save(reader, to: Storage.settingsFile)
        } else {
            try? FileManager.default.removeItem(at: Storage.settingsFile)
        }
    }

    func isFavorite(_ slug: String) -> Bool { favoriteReader?.slug == slug }
}
