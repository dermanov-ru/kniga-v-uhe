package dev.mark.knigavuhe.data.repo

import android.content.Context
import dev.mark.knigavuhe.data.db.AppDatabase
import dev.mark.knigavuhe.data.db.BookEntity
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.db.PlaybackStateEntity
import dev.mark.knigavuhe.data.remote.BookCard
import dev.mark.knigavuhe.data.remote.BookData
import dev.mark.knigavuhe.data.remote.KnigavuheApi
import dev.mark.knigavuhe.data.remote.OwnBook
import dev.mark.knigavuhe.data.remote.OwnConnection
import dev.mark.knigavuhe.data.remote.OwnIds
import dev.mark.knigavuhe.data.remote.OwnLibraryApi
import dev.mark.knigavuhe.data.remote.OwnLibraryException
import dev.mark.knigavuhe.data.remote.SeriesScraper
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val api: KnigavuheApi,
    private val ownApi: OwnLibraryApi,
    private val settings: Settings,
) {
    fun observeBooks(): Flow<List<BookEntity>> = db.books().observeAll()
    fun observeBook(id: Int): Flow<BookEntity?> = db.books().observe(id)
    fun observeChapters(bookId: Int): Flow<List<ChapterEntity>> = db.chapters().observeForBook(bookId)
    fun observeDownloadedCount(bookId: Int): Flow<Int> = db.chapters().observeDownloadedCount(bookId)
    fun observeLastPlayed(): Flow<PlaybackStateEntity?> = db.playback().observeLast()
    fun observeAllProgress(): Flow<List<PlaybackStateEntity>> = db.playback().observeAll()

    suspend fun book(id: Int): BookEntity? = db.books().get(id)
    suspend fun chapters(bookId: Int): List<ChapterEntity> = db.chapters().forBook(bookId)
    suspend fun progress(bookId: Int): PlaybackStateEntity? = db.playback().get(bookId)
    suspend fun lastPlayed(): PlaybackStateEntity? = db.playback().last()

    /** Resolves any book link or id, pulls book_data, and stores the book with its chapters. */
    suspend fun import(input: String): Int {
        val bookId = input.trim().toIntOrNull()
            ?: api.resolveBookId(KnigavuheApi.normalizeUrl(input))
        val data = api.fetchBookData(bookId)
        store(data)
        return bookId
    }

    suspend fun importById(bookId: Int): Int {
        if (OwnIds.isOwn(bookId)) {
            refreshOwn(bookId)
            return bookId
        }
        val data = api.fetchBookData(bookId)
        store(data)
        attachSeries(bookId, data.path)
        return bookId
    }

    /**
     * Подтягивает цикл со страницы книги — один лишний запрос на импорт. Не получилось, книга
     * просто остаётся без цикла: ради этого ломать импорт незачем.
     */
    /** Для книг, добавленных до появления циклов: подтянуть привязку при первом открытии. */
    suspend fun ensureSeries(bookId: Int) {
        if (OwnIds.isOwn(bookId)) return
        val book = db.books().get(bookId) ?: return
        if (book.seriesSlug != null) return
        attachSeries(bookId, book.path)
    }

    private suspend fun attachSeries(bookId: Int, path: String) {
        val book = db.books().get(bookId) ?: return
        if (book.seriesSlug != null) return
        val info = api.seriesOf(path) ?: return
        db.books().upsert(
            book.copy(
                series = book.series ?: info.name,
                seriesSlug = info.slug,
                seriesIndex = info.index,
                seriesTotal = info.listed,
            )
        )
    }

    /** Полный состав цикла берём со страницы цикла: блок на странице книги бывает урезан. */
    suspend fun seriesBooks(slug: String): List<BookCard> =
        api.listing(SeriesScraper.path(slug)).items

    /**
     * Следующая книга цикла. Сначала смотрим в библиотеку, потом — на страницу цикла, где книги
     * идут уже по порядку и пронумерованы прямо в названии.
     */
    suspend fun nextInSeries(bookId: Int): BookCard? {
        if (OwnIds.isOwn(bookId)) return null
        val book = db.books().get(bookId) ?: return null
        val slug = book.seriesSlug ?: return null
        if (book.seriesIndex <= 0) return null

        val items = runCatching { seriesBooks(slug) }.getOrNull().orEmpty()
        return pickNext(items, book.seriesIndex)
    }

    suspend fun booksInSeries(slug: String) = db.books().inSeries(slug)

    private suspend fun store(data: BookData) {
        val existing = db.books().get(data.id)
        db.books().upsert(
            BookEntity(
                id = data.id,
                title = data.title,
                path = data.path,
                authors = data.authors,
                readers = data.readers,
                series = data.series,
                cover = data.cover,
                totalDurationMs = data.totalDurationMs,
                chapterCount = data.tracks.size,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                urlsFetchedAt = System.currentTimeMillis(),
                speedLevels = data.speedLevels.joinToString(","),
            )
        )
        db.chapters().insertAll(
            data.tracks.mapIndexed { index, track ->
                ChapterEntity(
                    id = track.id,
                    bookId = data.id,
                    position = index,
                    title = track.title,
                    url = track.url,
                    durationMs = (track.duration_float * 1000).toLong(),
                )
            }
        )
        // Chapters already in the table keep their download state, but their URLs may be stale.
        db.chapters().replaceUrls(data.tracks.associate { it.id to it.url })
    }

    /**
     * Chapter URLs carry a hash that the site rotates every ~66 hours. Anything cached longer than
     * a day is refetched before we hand links to the downloader or the player.
     */
    suspend fun refreshUrls(bookId: Int): List<ChapterEntity> {
        if (OwnIds.isOwn(bookId)) return refreshOwn(bookId)
        val data = api.fetchBookData(bookId)
        db.chapters().replaceUrls(data.tracks.associate { it.id to it.url })
        db.books().get(bookId)?.let { db.books().upsert(it.copy(urlsFetchedAt = System.currentTimeMillis())) }
        return db.chapters().forBook(bookId)
    }

    suspend fun refreshUrlsIfStale(bookId: Int): List<ChapterEntity> {
        val book = db.books().get(bookId) ?: return emptyList()
        val age = System.currentTimeMillis() - book.urlsFetchedAt
        val ttl = if (OwnIds.isOwn(bookId)) OWN_URL_TTL_MS else URL_TTL_MS
        return if (age > ttl) refreshUrls(bookId) else db.chapters().forBook(bookId)
    }

    // ---------- «Моя библиотека» ----------

    private fun connection(): OwnConnection =
        settings.library.value ?: throw OwnLibraryException(0, "Библиотека не подключена")

    /** Список книг на сервере библиотеки. */
    suspend fun ownBooks(): List<OwnBook> = ownApi.books(connection())

    /** Заводит или обновляет свою книгу со всеми главами и статусами. Файлы не качает. */
    suspend fun addOwn(remoteId: String): Int {
        storeOwn(ownApi.manifest(connection(), remoteId))
        return OwnIds.book(remoteId)
    }

    private suspend fun refreshOwn(bookId: Int): List<ChapterEntity> {
        val book = db.books().get(bookId) ?: return emptyList()
        val remoteId = OwnIds.fromPath(book.path) ?: return db.chapters().forBook(bookId)
        storeOwn(ownApi.manifest(connection(), remoteId))
        return db.chapters().forBook(bookId)
    }

    /**
     * Синк своей библиотеки: книги со state != draft заводятся сами (только метаданные и статусы),
     * manifest тянется для новых, озвучиваемых и тех, где число готовых глав разошлось.
     * Книга пропала с сервера — локально остаётся, помечается gone. Возвращает id книг, где
     * появились новые готовые главы: у них с autoDownload надо поставить загрузку.
     */
    suspend fun syncOwn(): List<Int> {
        val remote = ownBooks().filter { it.state != "draft" }
        val local = db.books().own().associateBy { it.id }
        val grown = mutableListOf<Int>()
        for (book in remote) {
            val id = OwnIds.book(book.id)
            val known = local[id]
            val readyLocal = if (known == null) -1 else db.chapters().forBook(id).count { it.remoteState == ChapterEntity.REMOTE_DONE }
            val stale = known == null || book.isVoicing || known.remoteState != book.state || readyLocal != book.chapters_done
            if (!stale) continue
            val before = readyLocal.coerceAtLeast(0)
            storeOwn(ownApi.manifest(connection(), book.id))
            val after = db.chapters().forBook(id).count { it.remoteState == ChapterEntity.REMOTE_DONE }
            if (after > before) grown += id
        }
        val alive = remote.map { OwnIds.book(it.id) }.toSet()
        local.values.filter { it.id !in alive && it.remoteState != "gone" }.forEach { db.books().setRemoteState(it.id, "gone") }
        return grown
    }

    private suspend fun storeOwn(m: OwnBook) {
        val conn = connection()
        val id = OwnIds.book(m.id)
        val existing = db.books().get(id)
        if (existing != null && existing.path != OwnIds.path(m.id)) {
            throw OwnLibraryException(0, "Совпал номер с другой книгой: «${existing.title}»")
        }
        val all = m.chapters.filter { it.n in 1..OwnIds.MAX_CHAPTERS }.sortedBy { it.n }
        db.books().upsert(
            BookEntity(
                id = id,
                title = m.title,
                path = OwnIds.path(m.id),
                authors = m.authorsText,
                readers = m.voice_title,
                series = m.series,
                cover = m.cover?.let(conn::absolute),
                totalDurationMs = all.filter { it.state == ChapterEntity.REMOTE_DONE }
                    .sumOf { ((it.duration ?: 0.0) * 1000).toLong() },
                chapterCount = all.size,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                urlsFetchedAt = System.currentTimeMillis(),
                speedLevels = existing?.speedLevels.orEmpty(),
                favorite = existing?.favorite ?: false,
                remoteState = m.state,
                remoteSecondsLeft = m.seconds_left,
                autoDownload = existing?.autoDownload ?: false,
            )
        )
        // Позиция — номер главы: глава, готовая позже соседних, всё равно встанет на своё место
        val chapters = all.map { ch ->
            ChapterEntity(
                id = OwnIds.chapter(m.id, ch.n),
                bookId = id,
                position = ch.n,
                title = ch.title,
                url = ch.url?.let(conn::absolute).orEmpty(),
                durationMs = ((ch.duration ?: 0.0) * 1000).toLong(),
                sizeBytes = ch.size ?: 0,
                remoteState = ch.state,
                remoteDone = ch.done,
                remoteTotal = ch.total,
            )
        }
        db.chapters().insertAll(chapters)
        // У существующих: статус, ссылка, длительность; скачанный файл и его состояние не трогаем
        chapters.forEach {
            db.chapters().updateRemote(it.id, it.url, it.title, it.durationMs, it.remoteState,
                it.remoteDone, it.remoteTotal, it.position)
        }
    }

    /** «Скачать» у своей книги: качать готовое сейчас и догружать новое при синке. */
    suspend fun setAutoDownload(bookId: Int, on: Boolean) {
        if (OwnIds.isOwn(bookId)) db.books().setAutoDownload(bookId, on)
    }

    /** Звезда на весь цикл: книги импортируются, файлы не качаются. */
    suspend fun favoriteSeries(slug: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val items = seriesBooks(slug)
        items.forEachIndexed { done, card ->
            onProgress(done, items.size)
            if (card.isLitres) return@forEachIndexed
            val id = card.bookId
                ?: runCatching { api.resolveBookId(KnigavuheApi.normalizeUrl(card.path)) }.getOrNull()
                ?: return@forEachIndexed
            runCatching { addToFavorites(id) }
        }
        onProgress(items.size, items.size)
    }

    suspend fun setFavorite(bookId: Int, favorite: Boolean) {
        db.books().setFavorite(bookId, favorite)
    }

    /**
     * Кладёт книгу в избранное. Метаданные и список глав тянутся сразу, файлы — нет: смысл
     * избранного в том, чтобы отложить книгу и решить про скачивание потом.
     */
    suspend fun addToFavorites(bookId: Int) {
        if (db.books().get(bookId) == null) importById(bookId)
        db.books().setFavorite(bookId, true)
    }

    suspend fun saveProgress(bookId: Int, chapterId: Int, positionMs: Long, speed: Float) {
        db.playback().upsert(
            PlaybackStateEntity(
                bookId = bookId,
                chapterId = chapterId,
                positionMs = positionMs,
                speed = speed,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteBook(bookId: Int) {
        Storage.deleteBook(context, bookId)
        db.chapters().deleteForBook(bookId)
        db.playback().delete(bookId)
        db.books().delete(bookId)
    }

    /** Frees the audio but keeps the book and the saved position in the library. */
    suspend fun deleteDownloads(bookId: Int) {
        setAutoDownload(bookId, false)
        Storage.deleteBook(context, bookId)
        db.chapters().forBook(bookId).forEach {
            db.chapters().updateProgress(it.id, ChapterEntity.STATE_PENDING, 0, 0)
        }
    }

    companion object {
        private const val URL_TTL_MS = 24L * 60 * 60 * 1000

        /** Подписанные ссылки своей библиотеки живут 7 дней; обновляем за сутки до конца. */
        private const val OWN_URL_TTL_MS = 6L * 24 * 60 * 60 * 1000

        /**
         * Сайт печатает номер прямо в названии («2. Тайная комната»), и книги на странице цикла
         * уже идут по порядку. Сначала верим номеру, потом — позиции в списке.
         */
        fun pickNext(items: List<BookCard>, currentIndex: Int): BookCard? {
            if (items.isEmpty() || currentIndex <= 0) return null
            val wanted = currentIndex + 1
            return items.firstOrNull { it.title.trimStart().startsWith("$wanted.") }
                ?: items.getOrNull(currentIndex)
        }
    }
}
