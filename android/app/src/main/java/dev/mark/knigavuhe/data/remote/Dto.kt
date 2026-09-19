package dev.mark.knigavuhe.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class BookDataEnvelope(val result: BookDataResult? = null)

@Serializable
data class BookDataResult(
    val init_data: InitData? = null,
    val player_data: PlayerMeta? = null,
)

@Serializable
data class InitData(
    val id: Int = 0,
    val book: BookDto? = null,
    val files_host: String? = null,
    val playlist: List<TrackDto> = emptyList(),
    val merged_playlist: List<TrackDto> = emptyList(),
    val covers: List<CoverDto> = emptyList(),
)

/**
 * Only the scalar fields are declared here on purpose: `authors` / `readers` come back as a JSON
 * object normally but as an empty array when a book has none, which no single Kotlin type models
 * cleanly. The human-readable names live in [TrackPlayerData] anyway.
 */
@Serializable
data class BookDto(
    val id: Int,
    val name: String,
    val url: String? = null,
    val cover: String? = null,
    val cover_square: String? = null,
    val downloadable: Boolean = false,
    val offline_allowed: Boolean = false,
    val blocked: Boolean = false,
)

@Serializable
data class TrackDto(
    val id: Int,
    val title: String = "",
    val url: String = "",
    val duration: Int = 0,
    val duration_float: Double = 0.0,
    val error: Int = 0,
    val player_data: TrackPlayerData? = null,
)

@Serializable
data class TrackPlayerData(
    val title: String? = null,
    val cover: String? = null,
    val authors: String? = null,
    val readers: String? = null,
    val series: String? = null,
    val url_refresh: Boolean = false,
    val url_refresh_at: Long = 0,
    val url_refresh_in: Long = 0,
)

@Serializable
data class CoverDto(val src: String = "", val w: Int = 0, val h: Int = 0, val msrc: String? = null)

@Serializable
data class PlayerMeta(
    val speed_levels: List<Float> = emptyList(),
    val default_speed: Float = 1f,
)

/** A book card as it appears on search / author / reader / genre listings. */
data class BookCard(
    val bookId: Int?,
    val path: String,
    val title: String,
    val cover: String?,
    val authors: String,
    val readers: String,
    val genre: String,
    val about: String,
    val durationText: String,
    /** Аудио на сайт не выложено: карточка ведёт на ЛитРес, слушать и качать нечего. */
    val isLitres: Boolean = false,
)

data class CatalogPage(
    val items: List<BookCard>,
    val hasMore: Boolean,
)

/** A reader (narrator) as listed on /readers/. */
data class ReaderCard(
    val slug: String,
    val path: String,
    val name: String,
    val booksText: String,
    val avatar: String?,
)

data class ReaderPage(
    val items: List<ReaderCard>,
    val hasMore: Boolean,
)
