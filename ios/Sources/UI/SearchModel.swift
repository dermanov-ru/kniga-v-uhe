import Foundation

/// Search state: the global site search, a narrator filter with its own title filter, and the
/// author/genre listings opened from a card.
@MainActor
final class SearchModel: ObservableObject {

    @Published var query = ""
    @Published var readerQuery = ""
    @Published private(set) var readerSuggestions: [ReaderCard] = []
    @Published private(set) var readerLoading = false
    @Published var selectedReader: ReaderCard?
    @Published private(set) var items: [BookCard] = []
    @Published private(set) var loading = false
    @Published private(set) var loadingMore = false
    @Published private(set) var page = 0
    @Published private(set) var hasMore = false
    @Published private(set) var scannedBooks = 0
    @Published private(set) var error: String?

    private let api: Api
    private let settings: Settings
    private var readerLookup: Task<Void, Never>?

    /// One request scans a slice of a prolific narrator's catalogue; scrolling continues it.
    private let pagesPerBatch = 20
    private let minMatches = 5

    var readerMode: Bool { selectedReader != nil }

    init(api: Api, settings: Settings) {
        self.api = api
        self.settings = settings
        // The favourite narrator is the whole point of saving one: it comes preselected.
        if let favorite = settings.favoriteReader {
            selectedReader = favorite
            Task { await search() }
        }
    }

    var favoriteSlug: String? { settings.favoriteReader?.slug }

    // MARK: - Narrator picker

    func lookupReaders(_ value: String) {
        readerLookup?.cancel()
        readerLoading = true
        readerLookup = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            do {
                let page = try await api.searchReaders(query: value)
                guard !Task.isCancelled else { return }
                readerSuggestions = page.items
            } catch {
                readerSuggestions = []
            }
            readerLoading = false
        }
    }

    func select(reader: ReaderCard) async {
        selectedReader = reader
        items = []
        await search()
    }

    func clearReader() async {
        selectedReader = nil
        items = []
        page = 0
        hasMore = false
        if !query.trimmingCharacters(in: .whitespaces).isEmpty { await search() }
    }

    /// Star on the selected narrator: saved as the default filter for next time.
    func toggleFavorite() {
        guard let reader = selectedReader else { return }
        settings.setFavorite(settings.isFavorite(reader.slug) ? nil : reader)
        objectWillChange.send()
    }

    // MARK: - Book search

    func search() async {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard selectedReader != nil || !trimmed.isEmpty else { return }
        loading = true
        error = nil
        items = []
        page = 0
        scannedBooks = 0
        await loadPage(first: true)
    }

    func loadMore() async {
        guard !loading, !loadingMore, hasMore else { return }
        loadingMore = true
        await loadPage(first: false)
    }

    /// Empty result with pages left: continue the scan from where it stopped.
    func keepLooking() async {
        await loadMore()
    }

    private func loadPage(first: Bool) async {
        let startPage = first ? 1 : page + 1
        do {
            let batch: Batch
            if let reader = selectedReader {
                batch = try await readerBatch(reader: reader, startPage: startPage)
            } else {
                let result = try await api.search(query: query.trimmingCharacters(in: .whitespaces), page: startPage)
                batch = Batch(items: result.items, lastPage: startPage, hasMore: result.hasMore, scanned: 0)
            }
            let known = Set(items.map(\.path))
            let fresh = batch.items.filter { !known.contains($0.path) }
            items = first ? batch.items : items + fresh
            page = batch.lastPage
            hasMore = batch.hasMore
            scannedBooks = batch.scanned
            error = nil
        } catch {
            if first { self.error = error.localizedDescription }
            hasMore = false
        }
        loading = false
        loadingMore = false
    }

    /// The site cannot search inside one narrator's catalogue, so the title filter is applied
    /// here: walk the narrator's pages and keep the titles that match.
    private func readerBatch(reader: ReaderCard, startPage: Int) async throws -> Batch {
        let needle = query.trimmingCharacters(in: .whitespaces)
        var collected: [BookCard] = []
        var page = startPage
        var hasMore = true
        var scanned = 0
        var seen = scannedBooks

        while scanned < pagesPerBatch {
            let result = try await api.listing(path: reader.path, page: page)
            scanned += 1
            if result.items.isEmpty {
                hasMore = false
                break
            }
            seen += result.items.count
            collected += needle.isEmpty
                ? result.items
                : result.items.filter { $0.title.range(of: needle, options: .caseInsensitive) != nil }
            page += 1
            if needle.isEmpty || collected.count >= minMatches { break }
            // Nothing yet: show the counter moving instead of a frozen spinner.
            scannedBooks = seen
        }
        return Batch(items: collected, lastPage: page - 1, hasMore: hasMore, scanned: seen)
    }

    private struct Batch {
        let items: [BookCard]
        let lastPage: Int
        let hasMore: Bool
        let scanned: Int
    }
}
