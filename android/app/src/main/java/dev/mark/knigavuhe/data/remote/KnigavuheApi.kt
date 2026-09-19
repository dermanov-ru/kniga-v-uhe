package dev.mark.knigavuhe.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class KnigavuheApi(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchBookData(bookId: Int): BookData = withContext(Dispatchers.IO) {
        val body = get("$SITE/ajax/book_data/$bookId/")
        parseBookData(body, bookId)
    }

    /**
     * Resolves a book page URL to its numeric id. Most URLs carry it (`/book/5230-slug/`), some
     * only have the slug — those need the page, where the id sits in the inline player bootstrap.
     */
    suspend fun resolveBookId(url: String): Int = withContext(Dispatchers.IO) {
        idFromUrl(url)?.let { return@withContext it }
        val html = get(normalizeUrl(url))
        idFromHtml(html) ?: throw IOException("Не удалось определить id книги по ссылке")
    }

    /** Слаг цикла и номер книги в нём живут только на странице книги, не в `book_data`. */
    suspend fun seriesOf(bookPath: String): SeriesInfo? = withContext(Dispatchers.IO) {
        runCatching { SeriesScraper.parse(get(SITE + bookPath)) }.getOrNull()
    }

    suspend fun search(query: String, page: Int = 1): CatalogPage = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val suffix = if (page > 1) "&page=$page" else ""
        CatalogScraper.parseListing(get("$SITE/search/?q=$encoded$suffix"))
    }

    /**
     * Author, reader, genre and series pages share the listing markup with search, but not its
     * pagination: they number pages in the path (`/reader/<slug>/2/`) rather than with `?page=`.
     */
    suspend fun listing(path: String, page: Int = 1): CatalogPage = withContext(Dispatchers.IO) {
        CatalogScraper.parseListing(get(SITE + pagedPath(path, page)))
    }

    /** Narrator lookup for the reader filter: `/readers/?q=…`, 30 people per page. */
    suspend fun searchReaders(query: String, page: Int = 1): ReaderPage = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val base = if (page > 1) "/readers/$page/" else "/readers/"
        CatalogScraper.parseReaders(get("$SITE$base?q=$encoded"))
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} на $url")
            return response.body?.string().orEmpty()
        }
    }

    fun parseBookData(raw: String, bookId: Int): BookData {
        // The endpoint answers with a two-element array: [error, payload].
        val array = json.parseToJsonElement(raw) as? JsonArray
            ?: throw IOException("Неожиданный ответ book_data")
        val error = array.getOrNull(0)
        if (error != null && error.toString() != "null") {
            throw IOException("book_data вернул ошибку: $error")
        }
        val payload = array.getOrNull(1) ?: throw IOException("book_data без данных")
        val envelope = json.decodeFromJsonElement(BookDataEnvelope.serializer(), payload.jsonObject)
        val init = envelope.result?.init_data ?: throw IOException("book_data без init_data")
        val book = init.book ?: throw IOException("book_data без книги")
        val tracks = init.playlist.filter { it.error == 0 && isSiteAudio(it.url) }
        if (tracks.isEmpty()) {
            // У книг, права на которые ушли в ЛитРес, плейлист состоит из одной заглушки
            // нулевой длины со ссылкой на litres.ru вместо mp3.
            val litres = init.playlist.any { it.url.contains("litres.ru", ignoreCase = true) }
            throw IOException(
                if (litres) "Книга не выложена на сайте — только на ЛитРес"
                else "У книги нет доступных глав"
            )
        }
        val meta = tracks.first().player_data
        return BookData(
            id = if (init.id != 0) init.id else bookId,
            title = book.name,
            path = book.url ?: "/book/$bookId/",
            authors = meta?.authors.orEmpty(),
            readers = meta?.readers.orEmpty(),
            series = meta?.series,
            cover = init.covers.firstOrNull()?.src ?: book.cover ?: meta?.cover,
            tracks = tracks,
            speedLevels = envelope.result.player_data?.speed_levels ?: DEFAULT_SPEEDS,
            urlRefreshAt = meta?.url_refresh_at ?: 0,
        )
    }

    companion object {
        val DEFAULT_SPEEDS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

        private val URL_ID = Regex("""/book/(\d+)-""")
        private val HTML_ID = Regex("""new BookPlayer\((\d+)""")
        private val HTML_ID_ALT = Regex("""cur\.book\s*=\s*\{"id":(\d+)""")

        fun idFromUrl(url: String): Int? = URL_ID.find(url)?.groupValues?.get(1)?.toIntOrNull()

        /** Настоящая глава лежит на CDN сайта; всё остальное — ссылка на чужой магазин. */
        fun isSiteAudio(url: String): Boolean =
            url.startsWith("http") && Regex("""^https?://[^/]*\bknigavuhe\.org/""").containsMatchIn(url)

        /** `/reader/kljukvin-aleksandr/` + page 3 -> `/reader/kljukvin-aleksandr/3/`. */
        fun pagedPath(path: String, page: Int): String {
            if (page <= 1) return path
            if (path.contains('?')) return path + "&page=" + page
            return path.trimEnd('/') + "/" + page + "/"
        }

        fun idFromHtml(html: String): Int? =
            (HTML_ID.find(html) ?: HTML_ID_ALT.find(html))?.groupValues?.get(1)?.toIntOrNull()

        /** Accepts m./www. hosts and bare paths, hands back an absolute desktop URL. */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim()
            val withScheme = when {
                trimmed.startsWith("http") -> trimmed
                trimmed.startsWith("/") -> SITE + trimmed
                else -> "https://$trimmed"
            }
            return withScheme
                .replace("://m.knigavuhe.org", "://knigavuhe.org")
                .replace("://www.knigavuhe.org", "://knigavuhe.org")
        }

        /** Pulls a knigavuhe book link out of arbitrary shared text. */
        fun extractLink(text: String): String? =
            Regex("""https?://(?:m\.|www\.)?knigavuhe\.org/\S+""").find(text)?.value
    }
}

data class BookData(
    val id: Int,
    val title: String,
    val path: String,
    val authors: String,
    val readers: String,
    val series: String?,
    val cover: String?,
    val tracks: List<TrackDto>,
    val speedLevels: List<Float>,
    val urlRefreshAt: Long,
) {
    val totalDurationMs: Long get() = tracks.sumOf { (it.duration_float * 1000).toLong() }
}
