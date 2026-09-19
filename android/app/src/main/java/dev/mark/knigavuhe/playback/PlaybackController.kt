package dev.mark.knigavuhe.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.repo.BookRepository
import dev.mark.knigavuhe.data.repo.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the single ExoPlayer instance for the process. The UI talks to it directly and
 * [PlaybackService] wraps the same player in a MediaSession so the notification, lock screen and
 * headset buttons work.
 */
class PlaybackController(
    private val context: Context,
    private val repo: BookRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var chapters: List<ChapterEntity> = emptyList()
    private var chapterOffsets: LongArray = LongArray(0)
    private var sleepJob: Job? = null
    private var sleepUntil: Long = 0
    private var sleepEndOfChapter = false
    private var ticker: Job? = null
    private var retriedAfterRefresh = false

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SKIP_BACK_MS)
            .setSeekForwardIncrementMs(SKIP_FORWARD_MS)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            pushState()
            if (isPlaying) startTicker() else saveProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveProgress()
            pushState()
            if (sleepEndOfChapter && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                fadeOutAndPause()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            pushState()
            if (playbackState == Player.STATE_ENDED) {
                saveProgress()
                // Плейлист — это вся книга, поэтому STATE_ENDED означает, что она дослушана.
                _state.value = _state.value.copy(finishedBookId = _state.value.bookId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A chapter URL that rotated on the server shows up here as a source error.
            val bookId = _state.value.bookId ?: return
            if (retriedAfterRefresh) {
                _state.value = _state.value.copy(error = "Не удалось воспроизвести главу")
                return
            }
            retriedAfterRefresh = true
            val chapterId = _state.value.chapterId
            val position = player.currentPosition
            scope.launch {
                runCatching { repo.refreshUrls(bookId) }
                open(bookId, chapterId, position, play = true)
            }
        }
    }

    suspend fun openLastPlayed(play: Boolean) {
        val last = repo.lastPlayed() ?: return
        open(last.bookId, last.chapterId, last.positionMs, play)
    }

    /** Loads a book into the player, optionally resuming exactly where it was left. */
    suspend fun open(bookId: Int, chapterId: Int? = null, positionMs: Long? = null, play: Boolean = true) {
        val book = repo.book(bookId) ?: return
        val loaded = repo.chapters(bookId)
        if (loaded.isEmpty()) return
        val saved = repo.progress(bookId)
        val targetChapterId = chapterId ?: saved?.chapterId ?: loaded.first().id
        val targetPosition = positionMs ?: saved?.positionMs ?: 0L
        val speed = saved?.speed ?: 1f
        val index = loaded.indexOfFirst { it.id == targetChapterId }.coerceAtLeast(0)

        chapters = loaded
        chapterOffsets = LongArray(loaded.size)
        var acc = 0L
        loaded.forEachIndexed { i, chapter ->
            chapterOffsets[i] = acc
            acc += chapter.durationMs
        }

        val items = loaded.map { chapter -> mediaItem(book.id, book.title, book.authors, book.cover, chapter) }
        withContext(Dispatchers.Main) {
            retriedAfterRefresh = false
            _state.value = _state.value.copy(finishedBookId = null)
            player.setMediaItems(items, index, targetPosition)
            player.playbackParameters = PlaybackParameters(speed)
            player.prepare()
            if (play) {
                startSessionHost()
                player.play()
            }
            _state.value = _state.value.copy(
                bookId = book.id,
                bookTitle = book.title,
                authors = book.authors,
                cover = book.cover,
                chapterCount = loaded.size,
                bookTotalMs = book.totalDurationMs,
                speed = speed,
                speedLevels = book.speedLevels.split(",").mapNotNull { it.toFloatOrNull() },
            )
            pushState()
            startTicker()
        }
    }

    private fun mediaItem(
        bookId: Int,
        bookTitle: String,
        authors: String,
        cover: String?,
        chapter: ChapterEntity,
    ): MediaItem {
        val local = Storage.chapterFile(context, bookId, chapter.id)
        val uri = if (local.length() > 0) Uri.fromFile(local) else Uri.parse(chapter.url)
        return MediaItem.Builder()
            .setMediaId(chapter.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(chapter.title)
                    .setArtist(authors.ifBlank { bookTitle })
                    .setAlbumTitle(bookTitle)
                    .setArtworkUri(cover?.let(Uri::parse))
                    .build()
            )
            .build()
    }

    /** Media3 posts the playback notification from the service, so it has to be running first. */
    private fun startSessionHost() {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
    }

    fun playPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            startSessionHost()
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        pushState()
    }

    fun skip(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        if (target > player.duration && player.duration > 0) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(player.duration)
        } else {
            player.seekTo(target)
        }
        pushState()
    }

    fun nextChapter() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previousChapter() {
        if (player.currentPosition > 3_000) player.seekTo(0) else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    fun playChapter(index: Int) {
        if (index !in chapters.indices) return
        player.seekTo(index, 0)
        startSessionHost()
        player.play()
        pushState()
    }

    fun clearFinished() {
        _state.value = _state.value.copy(finishedBookId = null)
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
        saveProgress()
    }

    fun setSleep(option: SleepOption) {
        sleepJob?.cancel()
        sleepEndOfChapter = false
        when (option) {
            is SleepOption.Off -> {
                sleepUntil = 0
                _state.value = _state.value.copy(sleep = SleepState.Off)
            }
            is SleepOption.EndOfChapter -> {
                sleepEndOfChapter = true
                _state.value = _state.value.copy(sleep = SleepState.EndOfChapter)
            }
            is SleepOption.Minutes -> {
                sleepUntil = System.currentTimeMillis() + option.value * 60_000L
                _state.value = _state.value.copy(sleep = SleepState.Countdown(option.value * 60_000L))
                sleepJob = scope.launch {
                    while (true) {
                        val left = sleepUntil - System.currentTimeMillis()
                        if (left <= 0) break
                        _state.value = _state.value.copy(sleep = SleepState.Countdown(left))
                        delay(1_000)
                    }
                    withContext(Dispatchers.Main) { fadeOutAndPause() }
                }
            }
        }
    }

    /** Eases the volume down before pausing, so falling asleep is not punctuated by a hard cut. */
    private fun fadeOutAndPause() {
        sleepEndOfChapter = false
        sleepUntil = 0
        scope.launch(Dispatchers.Main) {
            val steps = 20
            repeat(steps) { step ->
                player.volume = 1f - (step + 1) / steps.toFloat()
                delay(250)
            }
            player.pause()
            player.volume = 1f
            _state.value = _state.value.copy(sleep = SleepState.Off)
            saveProgress()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceSave = 0
            while (true) {
                withContext(Dispatchers.Main) { pushState() }
                if (++sinceSave >= 5) {
                    sinceSave = 0
                    saveProgress()
                }
                delay(1_000)
                if (!withContext(Dispatchers.Main) { player.isPlaying }) break
            }
            saveProgress()
        }
    }

    fun saveProgress() {
        val bookId = _state.value.bookId ?: return
        scope.launch {
            val snapshot = withContext(Dispatchers.Main) {
                Triple(
                    player.currentMediaItem?.mediaId?.toIntOrNull() ?: 0,
                    player.currentPosition,
                    player.playbackParameters.speed,
                )
            }
            val (chapterId, position, speed) = snapshot
            if (chapterId != 0) repo.saveProgress(bookId, chapterId, position, speed)
        }
    }

    private fun pushState() {
        val index = player.currentMediaItemIndex
        val chapter = chapters.getOrNull(index)
        val position = player.currentPosition.coerceAtLeast(0)
        val elapsed = (chapterOffsets.getOrNull(index) ?: 0L) + position
        _state.value = _state.value.copy(
            chapterId = chapter?.id ?: _state.value.chapterId,
            chapterIndex = index,
            chapterTitle = chapter?.title.orEmpty(),
            positionMs = position,
            durationMs = player.duration.takeIf { it > 0 } ?: chapter?.durationMs ?: 0,
            bookElapsedMs = elapsed,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            speed = player.playbackParameters.speed,
        )
    }

    fun release() {
        ticker?.cancel()
        sleepJob?.cancel()
        player.release()
    }

    companion object {
        const val SKIP_BACK_MS = 10_000L
        const val SKIP_FORWARD_MS = 30_000L
    }
}
