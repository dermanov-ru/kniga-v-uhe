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
import androidx.media3.common.ForwardingPlayer
import dev.mark.knigavuhe.data.remote.ClaimBody
import dev.mark.knigavuhe.sync.ListenRules
import dev.mark.knigavuhe.sync.ListenSync
import dev.mark.knigavuhe.sync.PlayAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.remote.OwnIds
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

    /** Синк своей библиотеки (OwnSync.sync(force=true)), ставит AppContainer: плеер ждёт неготовую главу. */
    var requestOwnSync: (suspend () -> Unit)? = null
    private var waitJob: kotlinx.coroutines.Job? = null

    /** Ждём, пока следующая глава озвучится: синк раз в 20 с, пока висит «ещё озвучивается». */
    private fun waitForNext() {
        val bookId = _state.value.bookId ?: return
        if (waitJob?.isActive == true) return
        waitJob = scope.launch {
            while (_state.value.bookId == bookId && _state.value.notice == NEXT_NOT_READY) {
                runCatching { requestOwnSync?.invoke() }
                if (_state.value.notice != NEXT_NOT_READY) break
                kotlinx.coroutines.delay(WAIT_SYNC_MS)
            }
        }
    }
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    // ---------- синхронизация прослушивания своих книг ----------

    private var listen: ListenSync? = null
    /** Серверный id своей книги в плеере; у knigavuhe — null. */
    private var serverBook: String? = null
    private var sinceTick = 0

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Короткие сообщения для тоста: «Продолжаю с места на …». */
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val _ask = MutableStateFlow<AskClaim?>(null)
    /** Своя книга играет на другом устройстве — спросить, перехватывать ли. */
    val ask: StateFlow<AskClaim?> = _ask.asStateFlow()

    fun attachListen(sync: ListenSync) {
        listen = sync
        sync.onStop = { book, by -> stoppedElsewhere(book, by) }
    }

    /** Событие прослушивания своей книги — в очередь (и на сервер, когда получится). */
    private fun emit(kind: String) {
        val sync = listen ?: return
        val book = serverBook ?: return
        val chapter = chapters.getOrNull(player.currentMediaItemIndex) ?: return
        sync.record(book, chapter.position, player.currentPosition, player.playbackParameters.speed, kind)
    }

    /** Другое устройство забрало книгу: пауза и подсказка в плеере. */
    private suspend fun stoppedElsewhere(book: String, by: String) {
        withContext(Dispatchers.Main) {
            if (serverBook != book) return@withContext
            if (player.isPlaying) player.pause()
            _state.value = _state.value.copy(notice = "Слушание продолжилось на $by")
        }
    }

    /**
     * «Играть» с проверкой места на сервере (только свои книги, ответ не дольше 2 с).
     * chapterId != null — человек выбрал главу сам (explicit): на чужое место не прыгаем.
     */
    suspend fun startPlay(bookId: Int, chapterId: Int? = null, positionMs: Long? = null) {
        val sync = listen
        val book = if (OwnIds.isOwn(bookId)) repo.book(bookId)?.path?.let(OwnIds::fromPath) else null
        if (sync == null || book == null || !sync.connected) return playLocal(bookId, chapterId, positionMs)
        val explicit = chapterId != null
        when (val action = ListenRules.decide(sync.where(book), explicit)) {
            PlayAction.Local -> playLocal(bookId, chapterId, positionMs)
            is PlayAction.Jump -> jumpTo(bookId, action.n, action.posMs,
                "Продолжаю с места на ${action.device} (глава ${action.fromN}, ${ListenRules.clock(action.fromPosMs)}) — на минуту раньше")
            is PlayAction.Ask -> _ask.value = AskClaim(bookId, book, action.device, chapterId, positionMs)
        }
    }

    /** Ответ на «Сейчас играет на X. Остановить там и продолжить здесь?». «Нет» — ничего не делаем. */
    fun answerAsk(yes: Boolean) {
        val ask = _ask.value ?: return
        _ask.value = null
        if (!yes) return
        scope.launch {
            val explicit = ask.chapterId != null
            val body = if (explicit) {
                val ch = repo.chapters(ask.bookId).firstOrNull { it.id == ask.chapterId }
                ClaimBody(n = ch?.position, pos_ms = ask.positionMs ?: 0L, speed = _state.value.speed)
            } else ClaimBody(speed = _state.value.speed)
            val reply = listen?.claim(ask.book, body)
            when {
                reply == null -> {
                    _toasts.tryEmit("Не удалось связаться с сервером — играю со своего места")
                    playLocal(ask.bookId, ask.chapterId, ask.positionMs)
                }
                explicit -> playLocal(ask.bookId, ask.chapterId, ask.positionMs)
                else -> jumpTo(ask.bookId, reply.n, reply.pos_ms, null)
            }
        }
    }

    private suspend fun playLocal(bookId: Int, chapterId: Int?, positionMs: Long?) {
        val resume = chapterId == null && positionMs == null && _state.value.bookId == bookId &&
            withContext(Dispatchers.Main) { player.mediaItemCount > 0 }
        if (!resume) return open(bookId, chapterId, positionMs, play = true)
        withContext(Dispatchers.Main) {
            if (player.playbackState == Player.STATE_ENDED && player.hasNextMediaItem()) player.seekToNextMediaItem()
            _state.value = _state.value.copy(notice = null)
            startSessionHost()
            player.play()
        }
    }

    /** Прыжок на главу n (номер на сервере). Глава у нас не готова — синк, потом ближайшая готовая назад. */
    private suspend fun jumpTo(bookId: Int, n: Int, posMs: Long, toast: String?) {
        var target = ListenRules.resolve(repo.chapters(bookId), n)
        if (target == null || target.second) {
            runCatching { requestOwnSync?.invoke() }
            target = ListenRules.resolve(repo.chapters(bookId), n) ?: return playLocal(bookId, null, null)
        }
        val (chapter, fallback) = target
        if (fallback) {
            _toasts.tryEmit("Глава $n у вас ещё не готова — продолжаю с главы ${chapter.position}")
            open(bookId, chapter.id, 0L, play = true)
        } else {
            toast?.let { _toasts.tryEmit(it) }
            open(bookId, chapter.id, posMs, play = true)
        }
    }

    /**
     * Плеер для MediaSession: «Играть» с гарнитуры и из уведомления идёт через ту же проверку.
     * Если нужно спросить, а экрана нет — звук не включается, вопрос ждёт в приложении.
     */
    val sessionPlayer: Player by lazy {
        object : ForwardingPlayer(player) {
            override fun play() {
                val bookId = _state.value.bookId
                if (bookId == null || !OwnIds.isOwn(bookId) || listen?.connected != true) {
                    super.play()
                } else {
                    scope.launch { startPlay(bookId) }
                }
            }
        }
    }

    /** Главы в плеере (у своей книги — только непрерывный блок готовых). */
    private var chapters: List<ChapterEntity> = emptyList()
    /** Все главы книги и где в них начинается блок плеера: для номера главы и времени от начала книги. */
    private var allChapters: List<ChapterEntity> = emptyList()
    private var blockStart = 0
    private var chapterOffsets: LongArray = LongArray(0)
    private var sleepJob: Job? = null
    private var sleepUntil: Long = 0
    private var sleepEndOfChapter = false
    private var ticker: Job? = null
    private var retriedAfterRefresh = false

    /**
     * Пока книга подменяется, состояние рассогласовано: плеер уже переключён, а `bookId`
     * в состоянии ещё прежний. Сохранения в это окно писали бы чужую главу в строку
     * старой книги и обнуляли её позицию.
     */
    private var switching = false

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
            if (!switching) emit(if (isPlaying) "play" else "pause")
            val book = serverBook
            if (isPlaying && book != null) listen?.startControl(book) else listen?.stopControl()
            if (isPlaying) startTicker() else saveProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!switching && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) emit("chapter")
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
                val bookId = _state.value.bookId
                val block = blockStart until blockStart + chapters.size
                if (bookId != null && OwnIds.isOwn(bookId) && PlaylistRules.waitsForNext(allChapters, block)) {
                    // Своя книга: дальше глава ещё не озвучена — пауза в конце, книга не дочитана
                    _state.value = _state.value.copy(notice = NEXT_NOT_READY)
                    waitForNext()
                } else {
                    // Плейлист — это вся книга, поэтому STATE_ENDED означает, что она дослушана.
                    _state.value = _state.value.copy(finishedBookId = bookId)
                }
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
        val fullIndex = loaded.indexOfFirst { it.id == targetChapterId }.coerceAtLeast(0)
        val block = PlaylistRules.block(loaded, fullIndex, OwnIds.isOwn(bookId))
        if (block == null) {
            _state.value = _state.value.copy(notice = CHAPTER_NOT_READY)
            return
        }
        val index = fullIndex - block.first

        allChapters = loaded
        serverBook = if (OwnIds.isOwn(bookId)) OwnIds.fromPath(book.path) else null
        blockStart = block.first
        chapters = loaded.subList(block.first, block.last + 1).toList()
        chapterOffsets = LongArray(loaded.size)
        var acc = 0L
        loaded.forEachIndexed { i, chapter ->
            chapterOffsets[i] = acc
            acc += chapter.durationMs
        }

        // Дописываем позицию прошлой книги, пока состояние ещё согласовано.
        if (_state.value.bookId != null && _state.value.bookId != bookId) {
            flushProgress()
        }

        val items = chapters.map { chapter -> mediaItem(book.id, book.title, book.authors, book.cover, chapter) }
        withContext(Dispatchers.Main) {
            switching = true
            retriedAfterRefresh = false
            _state.value = _state.value.copy(finishedBookId = null, notice = null)
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
            switching = false
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
        if (!player.isPlaying && _state.value.notice == NEXT_NOT_READY && !player.hasNextMediaItem()) {
            // Ждали неготовую главу: спросить сервер сейчас, а не через 20 с
            scope.launch {
                runCatching { requestOwnSync?.invoke() }
                withContext(Dispatchers.Main) {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        startSessionHost()
                        player.play()
                    }
                    pushState()
                }
            }
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else if (serverBook != null && listen?.connected == true) {
            // своя книга: перед «Играть» — где её слушали последний раз и не играет ли она где-то ещё
            val bookId = _state.value.bookId ?: return
            scope.launch { startPlay(bookId) }
        } else {
            // Своя книга дослушана до неготовой главы, а синк уже принёс её — продолжаем с неё
            if (player.playbackState == Player.STATE_ENDED && player.hasNextMediaItem()) player.seekToNextMediaItem()
            startSessionHost()
            player.play()
        }
    }

    /**
     * Синк принёс новые готовые главы. Если играет своя книга, дописываем готовые главы
     * в конец плейлиста, не прерывая звук; сообщение «ещё озвучивается» снимаем.
     */
    suspend fun ownChaptersChanged() {
        val bookId = _state.value.bookId ?: return
        if (!OwnIds.isOwn(bookId)) return
        val book = repo.book(bookId) ?: return
        val loaded = repo.chapters(bookId)
        withContext(Dispatchers.Main) {
            if (_state.value.bookId != bookId || chapters.isEmpty()) return@withContext
            val current = chapters.last()
            val at = loaded.indexOfFirst { it.id == current.id }
            if (at < 0) return@withContext
            val fresh = mutableListOf<ChapterEntity>()
            var i = at + 1
            while (i < loaded.size && loaded[i].isPlayable) fresh += loaded[i++]
            allChapters = loaded
            chapterOffsets = offsets(loaded)
            if (fresh.isEmpty()) return@withContext
            player.addMediaItems(fresh.map { mediaItem(book.id, book.title, book.authors, book.cover, it) })
            chapters = chapters + fresh
            _state.value = _state.value.copy(notice = null, chapterCount = loaded.size)
            pushState()
        }
    }

    private fun offsets(list: List<ChapterEntity>): LongArray {
        val out = LongArray(list.size)
        var acc = 0L
        list.forEachIndexed { i, chapter -> out[i] = acc; acc += chapter.durationMs }
        return out
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        emit("seek")
        pushState()
    }

    fun skip(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        if (target > player.duration && player.duration > 0) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(player.duration)
        } else {
            player.seekTo(target)
        }
        emit("seek")
        pushState()
    }

    fun nextChapter() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            val bookId = _state.value.bookId
            val block = blockStart until blockStart + chapters.size
            if (bookId != null && OwnIds.isOwn(bookId) && PlaylistRules.waitsForNext(allChapters, block)) {
                _state.value = _state.value.copy(notice = NEXT_NOT_READY)
                waitForNext()
            }
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun previousChapter() {
        if (player.currentPosition > 3_000) player.seekTo(0) else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    /** index — номер главы в книге (список глав на экране плеера — вся книга). */
    fun playChapter(index: Int) {
        val local = index - blockStart
        if (local in chapters.indices) {
            player.seekTo(local, 0)
            startSessionHost()
            player.play()
            pushState()
            return
        }
        val chapter = allChapters.getOrNull(index) ?: return
        val bookId = _state.value.bookId ?: return
        if (!chapter.isPlayable) {
            _state.value = _state.value.copy(notice = CHAPTER_NOT_READY)
            return
        }
        // своя книга: готовая глава за дырой — открываем её блок заново
        scope.launch { open(bookId, chapter.id, 0L, play = true) }
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
                // своя книга: раз в минуту место уходит на сервер
                if (++sinceTick >= TICK_SECONDS) {
                    sinceTick = 0
                    withContext(Dispatchers.Main) { if (player.isPlaying) emit("tick") }
                }
                delay(1_000)
                if (!withContext(Dispatchers.Main) { player.isPlaying }) break
            }
            saveProgress()
        }
    }

    fun saveProgress() {
        if (switching) return
        scope.launch { flushProgress() }
    }

    /**
     * Пишет позицию и ждёт записи. Книга, её список глав и показания плеера снимаются вместе:
     * глава обязана принадлежать той самой книге, иначе сохранение пропускается.
     */
    private suspend fun flushProgress() {
        val bookId = _state.value.bookId ?: return
        val known = chapters
        val snapshot = withContext(Dispatchers.Main) {
            Triple(
                player.currentMediaItem?.mediaId?.toIntOrNull() ?: 0,
                player.currentPosition,
                player.playbackParameters.speed,
            )
        }
        val (chapterId, position, speed) = snapshot
        if (belongsToBook(chapterId, bookId, known)) {
            repo.saveProgress(bookId, chapterId, position, speed)
        }
    }

    private fun pushState() {
        val index = player.currentMediaItemIndex
        val chapter = chapters.getOrNull(index)
        val position = player.currentPosition.coerceAtLeast(0)
        // номер главы и время — от начала книги, даже если в плеере только блок готовых глав
        val fullIndex = blockStart + index
        val elapsed = (chapterOffsets.getOrNull(fullIndex) ?: 0L) + position
        _state.value = _state.value.copy(
            chapterId = chapter?.id ?: _state.value.chapterId,
            chapterIndex = fullIndex,
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
        emit("stop")
        listen?.stopControl()
        ticker?.cancel()
        sleepJob?.cancel()
        player.release()
    }

    companion object {
        const val SKIP_BACK_MS = 10_000L
        const val SKIP_FORWARD_MS = 30_000L
        const val NEXT_NOT_READY = "Следующая глава ещё озвучивается"
        const val TICK_SECONDS = 60
        private const val WAIT_SYNC_MS = 20_000L
        const val CHAPTER_NOT_READY = "Глава ещё озвучивается"

        /**
         * Защита от записи чужой главы: при подмене книги плеер успевает переключиться раньше,
         * чем обновится состояние, и позиция уходила не в ту строку.
         */
        fun belongsToBook(chapterId: Int, bookId: Int, chapters: List<ChapterEntity>): Boolean =
            chapterId != 0 && chapters.any { it.id == chapterId && it.bookId == bookId }
    }
}
