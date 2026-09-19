import SwiftUI

enum Route: Hashable {
    case book(Int)
    case search
}

struct LibraryView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var player: Player

    @Binding var openBookId: Int?
    @Binding var showPlayer: Bool

    @State private var path: [Route] = []
    @State private var showAdd = false
    @State private var pendingDelete: Book?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if store.books.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("Моя библиотека")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { path.append(.search) } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .accessibilityLabel("Поиск книг")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Добавить по ссылке")
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case let .book(id): BookView(bookId: id, showPlayer: $showPlayer)
                case .search:
                    SearchView(
                        api: container.api,
                        settings: container.settings,
                        openBook: { path.append(.book($0)) }
                    )
                }
            }
        }
        .sheet(isPresented: $showAdd) {
            AddByLinkSheet { bookId in
                showAdd = false
                path.append(.book(bookId))
            }
        }
        .alert("Удалить книгу?", isPresented: .constant(pendingDelete != nil)) {
            Button("Удалить", role: .destructive) {
                if let book = pendingDelete { store.deleteBook(book.id) }
                pendingDelete = nil
            }
            Button("Отмена", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("«\(pendingDelete?.title ?? "")» и скачанные главы будут удалены с устройства.")
        }
        .onChange(of: openBookId) { _, newValue in
            guard let newValue else { return }
            path.append(.book(newValue))
            openBookId = nil
        }
    }

    private var list: some View {
        List {
            if let last = store.lastPlayed, let book = store.book(last.bookId) {
                Section {
                    continueCard(book: book)
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                }
            }
            Section {
                ForEach(store.ordered) { book in
                    row(book)
                        .contentShape(Rectangle())
                        .onTapGesture { path.append(.book(book.id)) }
                        .swipeActions {
                            Button(role: .destructive) { pendingDelete = book } label: {
                                Label("Удалить", systemImage: "trash")
                            }
                        }
                }
            }
        }
        .listStyle(.plain)
    }

    private func continueCard(book: Book) -> some View {
        HStack(spacing: 16) {
            CoverImage(url: book.cover, size: 84)

            VStack(alignment: .leading, spacing: 2) {
                Text("Продолжить")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tint)
                Text(book.title)
                    .font(.headline)
                    .lineLimit(2)
                Text(subtitle(for: book))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            Button {
                player.open(bookId: book.id, play: true)
                showPlayer = true
            } label: {
                Image(systemName: "play.fill")
                    .font(.title2)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(.tint))
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Продолжить слушать")
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.accentColor.opacity(0.12)))
    }

    private func subtitle(for book: Book) -> String {
        let chapter = store.progress[book.id]
            .flatMap { store.chapter($0.chapterId, in: book.id)?.title }
        let left = Format.left(book.totalDurationMs - store.elapsedMs(book.id))
        return chapter.map { "\($0) · осталось \(left)" } ?? "осталось \(left)"
    }

    private func row(_ book: Book) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                CoverImage(url: book.cover, size: 64)

                VStack(alignment: .leading, spacing: 2) {
                    Text(book.title).font(.subheadline.weight(.medium)).lineLimit(2)
                    Text(book.authors.isEmpty ? book.readers : book.authors)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if store.downloadedCount(book.id) < book.chapterCount {
                        Text("Скачано \(store.downloadedCount(book.id)) из \(book.chapterCount)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer(minLength: 0)

                Button {
                    player.open(bookId: book.id, play: true)
                    showPlayer = true
                } label: {
                    Image(systemName: "play.fill")
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Играть")
            }

            HStack {
                Text(started(book) ? "Прослушано \(percent(book))%" : "Не начата")
                Spacer()
                Text(started(book)
                     ? "осталось \(Format.left(book.totalDurationMs - store.elapsedMs(book.id)))"
                     : Format.left(book.totalDurationMs))
            }
            .font(.caption2)
            .foregroundStyle(.secondary)

            ProgressView(value: store.fraction(book.id))
                .progressViewStyle(.linear)
        }
        .padding(.vertical, 4)
    }

    private func started(_ book: Book) -> Bool { store.progress[book.id] != nil }
    private func percent(_ book: Book) -> Int { Int((store.fraction(book.id) * 100).rounded()) }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Text("Пока пусто").font(.title2.weight(.semibold))
            Text("Найдите книгу поиском или вставьте ссылку с сайта — приложение скачает главы и будет играть их офлайн.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 16) {
                Button("Поиск") { path.append(.search) }
                Button("Вставить ссылку") { showAdd = true }
            }
            .padding(.top, 8)
        }
        .padding(32)
    }
}

/// Paste a book URL (or share one into the app) and it becomes a library entry.
struct AddByLinkSheet: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss

    let onAdded: (Int) -> Void

    @State private var link = ""
    @State private var startDownload = true
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://knigavuhe.org/book/…", text: $link, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(1...3)
                    Toggle("Сразу скачать все главы", isOn: $startDownload)
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red).font(.footnote) }
                }
            }
            .navigationTitle("Добавить книгу")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") { dismiss() }.disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button("Добавить", action: add).disabled(link.isEmpty)
                    }
                }
            }
        }
    }

    private func add() {
        busy = true
        error = nil
        Task {
            do {
                let id = try await store.importBook(link: link)
                if startDownload { await container.downloader.start(bookId: id) }
                busy = false
                onAdded(id)
            } catch {
                self.error = error.localizedDescription
                busy = false
            }
        }
    }
}
