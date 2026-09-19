package dev.mark.knigavuhe.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val path: String,
    val authors: String,
    val readers: String,
    val series: String?,
    val cover: String?,
    val totalDurationMs: Long,
    val chapterCount: Int,
    val addedAt: Long,
    /** When the chapter URLs were last pulled from book_data; they rotate roughly every 66 hours. */
    val urlsFetchedAt: Long,
    val speedLevels: String,
    /** Отложена на потом: лежит в избранном, качать необязательно. */
    val favorite: Boolean = false,
    val seriesSlug: String? = null,
    val seriesIndex: Int = 0,
    val seriesTotal: Int = 0,
)

@Entity(
    tableName = "chapters",
    indices = [Index("bookId"), Index(value = ["bookId", "position"])],
)
data class ChapterEntity(
    @PrimaryKey val id: Int,
    val bookId: Int,
    val position: Int,
    val title: String,
    val url: String,
    val durationMs: Long,
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val state: Int = STATE_PENDING,
) {
    val isDownloaded: Boolean get() = state == STATE_DONE

    companion object {
        const val STATE_PENDING = 0
        const val STATE_DOWNLOADING = 1
        const val STATE_DONE = 2
        const val STATE_FAILED = 3
    }
}

/** One row per book: every book keeps its own position, so switching back and forth is lossless. */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val bookId: Int,
    val chapterId: Int,
    val positionMs: Long,
    val speed: Float = 1f,
    val updatedAt: Long = 0,
)
