import AVFoundation
import Combine
import Foundation
import MediaPlayer
import UIKit

/// The single player for the process: one AVQueuePlayer, the lock-screen controls, the sleep
/// timer, and the per-book position bookkeeping.
@MainActor
final class Player: NSObject, ObservableObject {

    static let skipBack: Int64 = 10_000
    static let skipForward: Int64 = 30_000

    @Published private(set) var bookId: Int?
    @Published private(set) var bookTitle = ""
    @Published private(set) var authors = ""
    @Published private(set) var cover: String?
    @Published private(set) var chapterIndex = 0
    @Published private(set) var chapterCount = 0
    @Published private(set) var chapterTitle = ""
    @Published private(set) var positionMs: Int64 = 0
    @Published private(set) var durationMs: Int64 = 0
    @Published private(set) var bookElapsedMs: Int64 = 0
    @Published private(set) var bookTotalMs: Int64 = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var speed: Double = 1
    @Published private(set) var speedLevels: [Double] = Api.defaultSpeeds
    @Published private(set) var sleep: SleepState = .off
    @Published private(set) var error: String?

    private let player = AVQueuePlayer()
    private var store: LibraryStore!
    private var chapters: [Chapter] = []
    private var offsets: [Int64] = []
    private var timeObserver: Any?
    private var endObserver: AnyCancellable?
    private var sleepTimer: Timer?
    private var sleepDeadline: Date?
    private var sleepAtChapterEnd = false
    private var ticksSinceSave = 0
    private var retriedAfterRefresh = false

    func configure(store: LibraryStore) {
        self.store = store
        player.actionAtItemEnd = .advance
        configureAudioSession()
        configureRemoteCommands()
        observeTime()
        observeItemEnd()
    }

    // MARK: - Loading

    /// Loads a book, resuming exactly where it was left unless told otherwise.
    func open(bookId: Int, chapterId: Int? = nil, positionMs: Int64? = nil, play: Bool = true) {
        guard let book = store.book(bookId) else { return }
        let list = store.chapters(of: bookId)
        guard !list.isEmpty else { return }

        let saved = store.progress[bookId]
        let targetChapter = chapterId ?? saved?.chapterId ?? list[0].id
        let targetPosition = positionMs ?? saved?.positionMs ?? 0
        let index = list.firstIndex { $0.id == targetChapter } ?? 0

        chapters = list
        offsets = []
        var accumulated: Int64 = 0
        for chapter in list {
            offsets.append(accumulated)
            accumulated += chapter.durationMs
        }

        self.bookId = book.id
        bookTitle = book.title
        authors = book.authors
        cover = book.cover
        chapterCount = list.count
        bookTotalMs = book.totalDurationMs
        speedLevels = book.speedLevels.isEmpty ? Api.defaultSpeeds : book.speedLevels
        speed = saved?.speed ?? 1
        retriedAfterRefresh = false
        error = nil

        rebuildQueue(from: index, seekTo: targetPosition, play: play)
    }

    func openLastPlayed(play: Bool) {
        guard let last = store.lastPlayed else { return }
        open(bookId: last.bookId, chapterId: last.chapterId, positionMs: last.positionMs, play: play)
    }

    /// AVQueuePlayer plays a fixed queue, so jumping to a chapter means rebuilding it from there.
    private func rebuildQueue(from index: Int, seekTo positionMs: Int64, play: Bool) {
        player.removeAllItems()
        for chapter in chapters[index...] {
            player.insert(AVPlayerItem(url: source(for: chapter)), after: nil)
        }
        chapterIndex = index
        chapterTitle = chapters[index].title
        durationMs = chapters[index].durationMs

        if positionMs > 0 {
            player.seek(to: CMTime(value: positionMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        player.rate = play ? Float(speed) : 0
        isPlaying = play
        if play { activateSession() }
        updateNowPlaying()
    }

    /// A downloaded chapter plays from disk; anything else streams, which keeps a partly
    /// downloaded book usable right away.
    private func source(for chapter: Chapter) -> URL {
        let local = Storage.chapterFile(bookId: chapter.bookId, chapterId: chapter.id)
        if Storage.fileExists(local), Storage.size(of: local) > 0 { return local }
        return URL(string: chapter.url) ?? local
    }

    // MARK: - Transport

    func playPause() {
        if isPlaying {
            player.rate = 0
            isPlaying = false
            saveProgress()
        } else {
            activateSession()
            player.rate = Float(speed)
            isPlaying = true
        }
        updateNowPlaying()
    }

    func seek(toMs ms: Int64) {
        player.seek(to: CMTime(value: max(ms, 0), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        positionMs = max(ms, 0)
        updateNowPlaying()
    }

    func skip(_ deltaMs: Int64) {
        let target = positionMs + deltaMs
        if target < 0 {
            if chapterIndex > 0 {
                playChapter(chapterIndex - 1)
            } else {
                seek(toMs: 0)
            }
        } else if durationMs > 0, target > durationMs {
            nextChapter()
        } else {
            seek(toMs: target)
        }
    }

    func nextChapter() {
        guard chapterIndex + 1 < chapters.count else { return }
        playChapter(chapterIndex + 1)
    }

    func previousChapter() {
        if positionMs > 3_000 {
            seek(toMs: 0)
        } else if chapterIndex > 0 {
            playChapter(chapterIndex - 1)
        }
    }

    func playChapter(_ index: Int) {
        guard chapters.indices.contains(index) else { return }
        rebuildQueue(from: index, seekTo: 0, play: true)
        saveProgress()
    }

    func setSpeed(_ value: Double) {
        speed = value
        if isPlaying { player.rate = Float(value) }
        saveProgress()
        updateNowPlaying()
    }

    // MARK: - Sleep timer

    func setSleep(_ option: SleepOption) {
        sleepTimer?.invalidate()
        sleepTimer = nil
        sleepAtChapterEnd = false
        sleepDeadline = nil

        switch option {
        case .off:
            sleep = .off
        case .endOfChapter:
            sleepAtChapterEnd = true
            sleep = .endOfChapter
        case let .minutes(value):
            let deadline = Date().addingTimeInterval(Double(value) * 60)
            sleepDeadline = deadline
            sleep = .countdown(remainingMs: Int64(value) * 60_000)
            sleepTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tickSleep() }
            }
        }
    }

    private func tickSleep() {
        guard let deadline = sleepDeadline else { return }
        let remaining = Int64(deadline.timeIntervalSinceNow * 1000)
        if remaining <= 0 {
            fadeOutAndPause()
        } else {
            sleep = .countdown(remainingMs: remaining)
        }
    }

    /// Eases the volume down before pausing, so falling asleep is not punctuated by a hard cut.
    private func fadeOutAndPause() {
        sleepTimer?.invalidate()
        sleepTimer = nil
        sleepDeadline = nil
        sleepAtChapterEnd = false

        Task { @MainActor in
            let steps = 20
            for step in 0..<steps {
                player.volume = Float(1 - Double(step + 1) / Double(steps))
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            player.rate = 0
            player.volume = 1
            isPlaying = false
            sleep = .off
            saveProgress()
            updateNowPlaying()
        }
    }

    // MARK: - Position

    private func observeTime() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 1000),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in self?.onTick(time) }
        }
    }

    private func onTick(_ time: CMTime) {
        positionMs = Int64(max(time.seconds, 0) * 1000)
        if let itemDuration = player.currentItem?.duration.seconds, itemDuration.isFinite, itemDuration > 0 {
            durationMs = Int64(itemDuration * 1000)
        }
        bookElapsedMs = (offsets.indices.contains(chapterIndex) ? offsets[chapterIndex] : 0) + positionMs
        isPlaying = player.rate != 0

        ticksSinceSave += 1
        if ticksSinceSave >= 5 {
            ticksSinceSave = 0
            saveProgress()
            updateNowPlaying()
        }
    }

    private func observeItemEnd() {
        endObserver = NotificationCenter.default
            .publisher(for: AVPlayerItem.didPlayToEndTimeNotification)
            .sink { [weak self] _ in
                Task { @MainActor in self?.onChapterFinished() }
            }
    }

    private func onChapterFinished() {
        guard chapterIndex + 1 < chapters.count else {
            isPlaying = false
            saveProgress()
            return
        }
        chapterIndex += 1
        chapterTitle = chapters[chapterIndex].title
        durationMs = chapters[chapterIndex].durationMs
        positionMs = 0
        saveProgress()
        updateNowPlaying()

        if sleepAtChapterEnd {
            fadeOutAndPause()
        }
    }

    func saveProgress() {
        guard let bookId, chapters.indices.contains(chapterIndex) else { return }
        store.saveProgress(
            bookId: bookId,
            chapterId: chapters[chapterIndex].id,
            positionMs: positionMs,
            speed: speed
        )
    }

    // MARK: - System integration

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio, options: [])
    }

    private func activateSession() {
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in if self?.isPlaying == false { self?.playPause() } }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in if self?.isPlaying == true { self?.playPause() } }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.playPause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.nextChapter() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previousChapter() }
            return .success
        }

        center.skipBackwardCommand.preferredIntervals = [NSNumber(value: Player.skipBack / 1000)]
        center.skipBackwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skip(-Player.skipBack) }
            return .success
        }
        center.skipForwardCommand.preferredIntervals = [NSNumber(value: Player.skipForward / 1000)]
        center.skipForwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skip(Player.skipForward) }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(toMs: Int64(event.positionTime * 1000)) }
            return .success
        }
    }

    private func updateNowPlaying() {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: chapterTitle,
            MPMediaItemPropertyArtist: authors.isEmpty ? bookTitle : authors,
            MPMediaItemPropertyAlbumTitle: bookTitle,
            MPMediaItemPropertyPlaybackDuration: Double(durationMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? speed : 0,
        ]
        if let artwork = artworkCache {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        loadArtworkIfNeeded()
    }

    private var artworkCache: MPMediaItemArtwork?
    private var artworkURL: String?

    /// The lock screen wants a UIImage, so the cover is fetched once per book and cached.
    private func loadArtworkIfNeeded() {
        guard let cover, cover != artworkURL, let url = URL(string: cover) else { return }
        artworkURL = cover
        Task.detached { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  let image = UIImage(data: data) else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            await MainActor.run {
                self?.artworkCache = artwork
                self?.updateNowPlaying()
            }
        }
    }
}

