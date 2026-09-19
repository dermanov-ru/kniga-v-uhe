import SwiftUI

struct BookView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var downloader: Downloader
    @EnvironmentObject private var player: Player

    let bookId: Int
    @Binding var showPlayer: Bool

    @State private var importing = false
    @State private var error: String?

    private var book: Book? { store.book(bookId) }
    private var chapters: [Chapter] { store.chapters(of: bookId) }
    private var downloaded: Int { store.downloadedCount(bookId) }

    var body: some View {
        Group {
            if let book {
                content(book)
            } else if importing {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Text(error ?? "Книга не найдена")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(book?.title ?? "Книга")
        .navigationBarTitleDisplayMode(.inline)
        .task { await ensureImported() }
    }

    private func content(_ book: Book) -> some View {
        List {
            Section {
                HStack(alignment: .top, spacing: 16) {
                    CoverImage(url: book.cover, size: 120, corner: 12)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(book.title).font(.headline)
                        if !book.authors.isEmpty {
                            Text(book.authors).font(.caption).foregroundStyle(.secondary)
                        }
                        if !book.readers.isEmpty {
                            Text("Читает: \(book.readers)").font(.caption).foregroundStyle(.secondary)
                        }
                        Text("\(book.chapterCount) глав · \(Format.left(book.totalDurationMs))")
                            .font(.caption.weight(.medium))
                            .padding(.top, 2)
                        if Storage.bookSize(bookId) > 0 {
                            Text("На устройстве: \(Format.size(Storage.bookSize(bookId)))")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section {
                if downloaded < book.chapterCount {
                    VStack(alignment: .leading, spacing: 4) {
                        ProgressView(value: Double(downloaded), total: Double(max(book.chapterCount, 1)))
                        Text("Скачано \(downloaded) из \(book.chapterCount)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                HStack(spacing: 12) {
                    Button {
                        player.open(bookId: bookId, play: true)
                        showPlayer = true
                    } label: {
                        Label("Слушать", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)

                    if downloader.isDownloading(bookId) {
                        Button(role: .cancel) {
                            downloader.cancel(bookId: bookId)
                        } label: {
                            Label("Стоп", systemImage: "stop.fill")
                        }
                        .buttonStyle(.bordered)
                    } else if downloaded < book.chapterCount {
                        Button {
                            Task { await downloader.start(bookId: bookId) }
                        } label: {
                            Label("Скачать всё", systemImage: "arrow.down.circle")
                        }
                        .buttonStyle(.bordered)
                    } else {
                        Button(role: .destructive) {
                            store.deleteAudio(bookId)
                        } label: {
                            Label("Удалить файлы", systemImage: "trash")
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .buttonStyle(.bordered)
            }

            Section("Главы") {
                ForEach(Array(chapters.enumerated()), id: \.element.id) { index, chapter in
                    Button {
                        player.open(bookId: bookId, chapterId: chapter.id, positionMs: 0, play: true)
                        showPlayer = true
                    } label: {
                        HStack(spacing: 12) {
                            stateIcon(chapter)
                            Text(chapter.title).lineLimit(1)
                            Spacer()
                            Text(Format.duration(chapter.durationMs))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private func stateIcon(_ chapter: Chapter) -> some View {
        switch chapter.state {
        case .done:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint).font(.caption)
        case .downloading:
            ProgressView().controlSize(.mini)
        case .failed:
            Image(systemName: "exclamationmark.circle").foregroundStyle(.red).font(.caption)
        case .pending:
            Image(systemName: "circle").foregroundStyle(.clear).font(.caption)
        }
    }

    /// Opened from search, the book is not in the library yet.
    private func ensureImported() async {
        guard store.book(bookId) == nil else { return }
        importing = true
        do {
            try await store.importBook(id: bookId)
        } catch {
            self.error = error.localizedDescription
        }
        importing = false
    }
}
