package dev.mark.knigavuhe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Int): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Int): BookEntity?

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Int, favorite: Boolean)

    @Query("SELECT * FROM books WHERE seriesSlug = :slug ORDER BY seriesIndex")
    suspend fun inSeries(slug: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE seriesSlug = :slug AND seriesIndex = :index LIMIT 1")
    suspend fun inSeriesAt(slug: String, index: Int): BookEntity?

    @Query(
        """
        SELECT b.id AS id, b.title AS title, b.authors AS authors, b.readers AS readers,
               b.cover AS cover, b.totalDurationMs AS totalDurationMs, b.chapterCount AS chapterCount,
               b.addedAt AS addedAt, b.favorite AS favorite,
               b.seriesSlug AS seriesSlug, b.series AS seriesName,
               b.seriesIndex AS seriesIndex, b.seriesTotal AS seriesTotal,
               (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id AND c.state = 2) AS doneCount,
               p.chapterId AS chapterId,
               (SELECT c4.title FROM chapters c4 WHERE c4.id = p.chapterId) AS chapterTitle,
               p.positionMs AS positionMs,
               p.updatedAt AS updatedAt,
               (SELECT COALESCE(SUM(c2.durationMs), 0) FROM chapters c2
                 WHERE c2.bookId = b.id
                   AND c2.position < (SELECT c3.position FROM chapters c3 WHERE c3.id = p.chapterId)
               ) AS elapsedBeforeMs
        FROM books b
        LEFT JOIN playback_state p ON p.bookId = b.id
        ORDER BY COALESCE(p.updatedAt, b.addedAt) DESC
        """
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    fun observeForBook(bookId: Int): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    suspend fun forBook(bookId: Int): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Int): ChapterEntity?

    @Query("UPDATE chapters SET url = :url WHERE id = :id")
    suspend fun updateUrl(id: Int, url: String)

    @Query("UPDATE chapters SET state = :state, downloadedBytes = :downloaded, sizeBytes = :size WHERE id = :id")
    suspend fun updateProgress(id: Int, state: Int, downloaded: Long, size: Long)

    @Query("UPDATE chapters SET state = :state WHERE id = :id")
    suspend fun updateState(id: Int, state: Int)

    @Query("UPDATE chapters SET state = :to WHERE bookId = :bookId AND state = :from")
    suspend fun retagState(bookId: Int, from: Int, to: Int)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND state = 2")
    fun observeDownloadedCount(bookId: Int): Flow<Int>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Int)

    @Transaction
    suspend fun replaceUrls(fresh: Map<Int, String>) {
        fresh.forEach { (id, url) -> updateUrl(id, url) }
    }
}

@Dao
interface PlaybackDao {
    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)

    @Query("SELECT * FROM playback_state WHERE bookId = :bookId")
    suspend fun get(bookId: Int): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state WHERE bookId = :bookId")
    fun observe(bookId: Int): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state ORDER BY updatedAt DESC LIMIT 1")
    fun observeLast(): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state ORDER BY updatedAt DESC LIMIT 1")
    suspend fun last(): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state")
    fun observeAll(): Flow<List<PlaybackStateEntity>>

    @Query("DELETE FROM playback_state WHERE bookId = :bookId")
    suspend fun delete(bookId: Int)
}
