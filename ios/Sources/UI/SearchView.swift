import SwiftUI

struct SearchView: View {
    @EnvironmentObject private var container: AppContainer
    @StateObject private var search: SearchModel

    private let openBook: (Int) -> Void

    @State private var showPicker = false
    @State private var resolving = false
    @State private var notice: String?

    init(api: Api, settings: Settings, openBook: @escaping (Int) -> Void) {
        _search = StateObject(wrappedValue: SearchModel(api: api, settings: settings))
        self.openBook = openBook
    }

    var body: some View {
        VStack(spacing: 0) {
            filterRow
            titleField
            results
        }
        .navigationTitle("Поиск книг")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPicker) { picker }
        .alert("Книги нет на сайте", isPresented: .constant(notice != nil)) {
            Button("Понятно") { notice = nil }
        } message: {
            Text(notice ?? "")
        }
    }

    // MARK: - Filter

    private var filterRow: some View {
        HStack(spacing: 4) {
            Button {
                showPicker = true
                search.lookupReaders(search.readerQuery)
            } label: {
                Label(search.selectedReader?.name ?? "Чтец: любой", systemImage: "person.wave.2")
                    .lineLimit(1)
            }
            .buttonStyle(.bordered)

            if let reader = search.selectedReader {
                Button {
                    search.toggleFavorite()
                } label: {
                    Image(systemName: search.favoriteSlug == reader.slug ? "star.fill" : "star")
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    search.favoriteSlug == reader.slug ? "Убрать из любимых" : "Сделать любимым чтецом"
                )

                Button {
                    Task { await search.clearReader() }
                } label: {
                    Image(systemName: "xmark")
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Сбросить чтеца")
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private var titleField: some View {
        HStack {
            TextField(
                search.readerMode ? "Название книги у этого чтеца" : "Название, автор или чтец",
                text: $search.query
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.search)
            .onSubmit { Task { await search.search() } }

            Button {
                Task { await search.search() }
            } label: {
                Image(systemName: "magnifyingglass")
            }
            .accessibilityLabel("Искать")
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.4)))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    // MARK: - Results

    @ViewBuilder
    private var results: some View {
        Group {
            if search.loading {
                VStack(spacing: 12) {
                    ProgressView()
                    if search.readerMode, !search.query.isEmpty, search.scannedBooks > 0 {
                        Text("Просмотрено \(search.scannedBooks) книг")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = search.error {
                Text(error)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if search.items.isEmpty, search.page > 0 {
                emptyState(search)
            } else {
                List {
                    ForEach(search.items) { card in
                        Button { open(card) } label: { resultRow(card) }
                            .buttonStyle(.plain)
                            .onAppear {
                                if card.id == search.items.last?.id {
                                    Task { await search.loadMore() }
                                }
                            }
                    }
                    if search.loadingMore {
                        HStack { Spacer(); ProgressView(); Spacer() }
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    private func emptyState(_ search: SearchModel) -> some View {
        VStack(spacing: 12) {
            Text(emptyText(search))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            // A prolific narrator is scanned in slices; the rest is one tap away.
            if search.hasMore {
                if search.loadingMore {
                    ProgressView()
                } else {
                    Button("Искать дальше") { Task { await search.keepLooking() } }
                }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func emptyText(_ search: SearchModel) -> String {
        if search.readerMode, search.scannedBooks > 0 {
            return "Среди \(search.scannedBooks) книг этого чтеца ничего не нашлось"
        }
        return search.readerMode ? "У этого чтеца ничего не нашлось" : "Ничего не нашлось"
    }

    private func resultRow(_ card: BookCard) -> some View {
        HStack(spacing: 12) {
            CoverImage(url: card.cover, size: 72)
            VStack(alignment: .leading, spacing: 2) {
                Text(card.title).font(.subheadline.weight(.medium)).lineLimit(2)
                if !card.authors.isEmpty {
                    Text(card.authors).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                if !card.readers.isEmpty {
                    Text("Читает: \(card.readers)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                if card.isLitres {
                    Text("Только на ЛитРес")
                        .font(.caption2)
                        .foregroundStyle(.red)
                        .padding(.top, 2)
                } else if !card.durationText.isEmpty {
                    Text(card.durationText).font(.caption2).padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    /// Slug-only links carry no id; it lives in the page itself.
    private func open(_ card: BookCard) {
        if card.isLitres {
            notice = "Аудио на сайт не выложено — книга продаётся на ЛитРес"
            return
        }
        if let id = card.bookId {
            openBook(id)
            return
        }
        guard !resolving else { return }
        resolving = true
        Task {
            if let id = try? await container.api.resolveBookId(url: Api.normalize(card.path)) {
                openBook(id)
            }
            resolving = false
        }
    }

    // MARK: - Narrator picker

    private var picker: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField(
                    "Имя чтеца",
                    text: Binding(
                        get: { search.readerQuery },
                        set: { value in
                            search.readerQuery = value
                            search.lookupReaders(value)
                        }
                    )
                )
                .textFieldStyle(.roundedBorder)
                .padding(16)

                if search.readerLoading {
                    ProgressView().padding()
                }

                List(search.readerSuggestions) { reader in
                    Button {
                        showPicker = false
                        Task { await search.select(reader: reader) }
                    } label: {
                        HStack(spacing: 12) {
                            CoverImage(url: reader.avatar, size: 40, corner: 20)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(reader.name).lineLimit(1)
                                if !reader.booksText.isEmpty {
                                    Text(reader.booksText).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if reader.slug == search.favoriteSlug {
                                Image(systemName: "star.fill").foregroundStyle(.tint)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
            }
            .navigationTitle("Чтец")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Готово") { showPicker = false }
                }
            }
        }
    }
}
