import Foundation

/// Downloads a book's chapters with a background URLSession, so the transfer survives the app
/// being backgrounded or the screen locking — iOS is much stricter about this than Android.
@MainActor
final class Downloader: NSObject, ObservableObject {

    /// Books with a download in flight, and how many chapters are still pending for each.
    @Published private(set) var active: [Int: Int] = [:]

    private var store: LibraryStore!
    private var session: URLSession!
    /// Chapters already retried after a URL refresh, so a dead link cannot loop forever.
    private var retried = Set<Int>()
    private var refreshing = Set<Int>()

    /// Set by the app delegate when iOS wakes us to finish background transfers.
    var backgroundCompletion: (() -> Void)?

    func configure(store: LibraryStore) {
        self.store = store
        let config = URLSessionConfiguration.background(withIdentifier: "dev.mark.knigavuhe.downloads")
        config.httpMaximumConnectionsPerHost = 3
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        config.httpAdditionalHeaders = ["User-Agent": Api.userAgent]
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    func isDownloading(_ bookId: Int) -> Bool { (active[bookId] ?? 0) > 0 }

    /// Queues every chapter that is not on disk yet. The session decides the actual ordering.
    func start(bookId: Int) async {
        guard store != nil else { return }
        _ = try? await store.refreshUrlsIfStale(bookId)

        let pending = store.chapters(of: bookId).filter { chapter in
            !Storage.fileExists(Storage.chapterFile(bookId: bookId, chapterId: chapter.id))
        }
        guard !pending.isEmpty else {
            active[bookId] = 0
            return
        }
        active[bookId] = pending.count

        for chapter in pending {
            enqueue(chapter)
        }
    }

    func cancel(bookId: Int) {
        session.getAllTasks { tasks in
            for task in tasks where task.taskDescription?.hasPrefix("\(bookId):") == true {
                task.cancel()
            }
        }
        active[bookId] = 0
        for chapter in store.chapters(of: bookId) where chapter.state == .downloading {
            var copy = chapter
            copy.state = .pending
            store.update(chapter: copy)
        }
    }

    private func enqueue(_ chapter: Chapter) {
        guard let url = URL(string: chapter.url) else { return }
        var updated = chapter
        updated.state = .downloading
        store.update(chapter: updated)

        let task = session.downloadTask(with: url)
        task.taskDescription = "\(chapter.bookId):\(chapter.id)"
        task.resume()
    }

    private func finish(bookId: Int, chapterId: Int, success: Bool) {
        guard var chapter = store.chapter(chapterId, in: bookId) else { return }
        if success {
            let file = Storage.chapterFile(bookId: bookId, chapterId: chapterId)
            chapter.state = .done
            chapter.downloadedBytes = Storage.size(of: file)
            chapter.sizeBytes = chapter.downloadedBytes
        } else {
            chapter.state = .failed
        }
        store.update(chapter: chapter)
        active[bookId] = max((active[bookId] ?? 1) - 1, 0)
    }

    /// A rotated URL comes back as 403/404/410. One refresh serves every chapter that hit it.
    private func retryAfterRefresh(bookId: Int, chapterId: Int) async {
        guard !retried.contains(chapterId) else {
            finish(bookId: bookId, chapterId: chapterId, success: false)
            return
        }
        retried.insert(chapterId)

        if !refreshing.contains(bookId) {
            refreshing.insert(bookId)
            _ = try? await store.refreshUrls(bookId)
            refreshing.remove(bookId)
        }
        guard let fresh = store.chapter(chapterId, in: bookId) else { return }
        enqueue(fresh)
    }
}

extension Downloader: URLSessionDownloadDelegate {

    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let parts = downloadTask.taskDescription?.split(separator: ":"),
              parts.count == 2,
              let bookId = Int(parts[0]),
              let chapterId = Int(parts[1])
        else { return }

        let code = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        let target = Storage.chapterFile(bookId: bookId, chapterId: chapterId)

        if [403, 404, 410].contains(code) {
            Task { @MainActor in await self.retryAfterRefresh(bookId: bookId, chapterId: chapterId) }
            return
        }
        guard (200..<300).contains(code) else {
            Task { @MainActor in self.finish(bookId: bookId, chapterId: chapterId, success: false) }
            return
        }

        // The temporary file is gone as soon as this callback returns, so move it right here.
        try? FileManager.default.removeItem(at: target)
        let moved = (try? FileManager.default.moveItem(at: location, to: target)) != nil
        Task { @MainActor in self.finish(bookId: bookId, chapterId: chapterId, success: moved) }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let parts = downloadTask.taskDescription?.split(separator: ":"),
              parts.count == 2,
              let bookId = Int(parts[0]),
              let chapterId = Int(parts[1])
        else { return }

        Task { @MainActor in
            guard var chapter = self.store.chapter(chapterId, in: bookId) else { return }
            chapter.downloadedBytes = totalBytesWritten
            chapter.sizeBytes = max(totalBytesExpectedToWrite, 0)
            self.store.update(chapter: chapter)
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error,
              (error as NSError).code != NSURLErrorCancelled,
              let parts = task.taskDescription?.split(separator: ":"),
              parts.count == 2,
              let bookId = Int(parts[0]),
              let chapterId = Int(parts[1])
        else { return }

        Task { @MainActor in self.finish(bookId: bookId, chapterId: chapterId, success: false) }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }
}
