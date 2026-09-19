package dev.mark.knigavuhe.data.db

/** Flat projection for the library list: book, download progress and saved position in one query. */
data class LibraryRow(
    val id: Int,
    val title: String,
    val authors: String,
    val readers: String,
    val cover: String?,
    val totalDurationMs: Long,
    val chapterCount: Int,
    val addedAt: Long,
    val doneCount: Int,
    val chapterId: Int?,
    val chapterTitle: String?,
    val positionMs: Long?,
    val updatedAt: Long?,
    val elapsedBeforeMs: Long?,
    val favorite: Boolean = false,
    val seriesSlug: String? = null,
    val seriesName: String? = null,
    val seriesIndex: Int = 0,
    val seriesTotal: Int = 0,
) {
    val elapsedMs: Long get() = (elapsedBeforeMs ?: 0L) + (positionMs ?: 0L)
    val leftMs: Long get() = (totalDurationMs - elapsedMs).coerceAtLeast(0)
    val started: Boolean get() = updatedAt != null

    /** Share of the book already listened to, 0..1. */
    val progress: Float
        get() = if (totalDurationMs > 0) (elapsedMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f

    val percent: Int get() = kotlin.math.round(progress * 100).toInt()
}
