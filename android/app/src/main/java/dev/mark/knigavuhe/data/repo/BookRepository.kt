package dev.mark.knigavuhe.data.repo

import android.content.Context
import dev.mark.knigavuhe.data.db.AppDatabase
import dev.mark.knigavuhe.data.db.BookEntity
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.db.PlaybackStateEntity
import dev.mark.knigavuhe.data.remote.BookCard
import dev.mark.knigavuhe.data.remote.BookData
import dev.mark.knigavuhe.data.remote.KnigavuheApi
import dev.mark.knigavuhe.data.remote.SeriesScraper
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val api: KnigavuheApi,
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
        val data = api.fetchBookData(bookId)
        db.chapters().replaceUrls(data.tracks.associate { it.id to it.url })
        db.books().get(bookId)?.let { db.books().upsert(it.copy(urlsFetchedAt = System.currentTimeMillis())) }
        return db.chapters().forBook(bookId)
    }

    suspend fun refreshUrlsIfStale(bookId: Int): List<ChapterEntity> {
        val book = db.books().get(bookId) ?: return emptyList()
        val age = System.currentTimeMillis() - book.urlsFetchedAt
        return if (age > URL_TTL_MS) refreshUrls(bookId) else db.chapters().forBook(bookId)
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
        Storage.deleteBook(context, bookId)
        db.chapters().forBook(bookId).forEach {
            db.chapters().updateProgress(it.id, ChapterEntity.STATE_PENDING, 0, 0)
        }
    }

    companion object {
        private const val URL_TTL_MS = 24L * 60 * 60 * 1000

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
