package dev.mark.knigavuhe.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder

/**
 * «Моя библиотека» — личная библиотека озвучки на своём API (`/v1/library/…`).
 *
 * Запросы к API идут с ключом устройства в `Authorization`. Звук и обложки отдаются по
 * подписанным ссылкам (подпись в query, ~7 дней), поэтому загрузчик и плеер качают их как
 * обычные файлы, без заголовков.
 */
@Serializable
data class OwnChapter(
    val n: Int,
    val title: String = "",
    /** done|running|queued|new|error; в manifest без all=1 поля нет — там только готовые. */
    val state: String = "done",
    val done: Int = 0,
    val total: Int = 0,
    val duration: Double? = null,
    val size: Long? = null,
    val url: String? = null,
)

@Serializable
data class OwnRunning(val n: Int, val title: String = "", val done: Int = 0, val total: Int = 0)

@Serializable
data class OwnBook(
    val id: String,
    val title: String = "",
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val voice_title: String = "",
    val state: String = "",
    val chapters_total: Int = 0,
    val chapters_done: Int = 0,
    val chapters_queued: Int = 0,
    val running: OwnRunning? = null,
    val seconds_left: Int? = null,
    val progress: Double = 0.0,
    val duration: Double = 0.0,
    val size: Long = 0,
    val cover: String? = null,
    val chapters: List<OwnChapter> = emptyList(),
    val links_expire: Long = 0,
) {
    val isDone: Boolean get() = state == "done"
    val isVoicing: Boolean get() = state == "queued" || state == "running"
    val authorsText: String get() = authors.joinToString(", ")
}

/** Адрес библиотеки и ключ устройства. */
data class OwnConnection(val baseUrl: String, val key: String) {
    fun absolute(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + path

    companion object {
        /**
         * Строка с QR на странице библиотеки: `knigavuhe://library?url=<base>&key=<k_…>`.
         * `url` приходит неэкранированным (`https://…`), поэтому разбираем вручную, а не Uri.
         * Принимает и строку, вставленную с пробелами по краям.
         */
        fun parse(input: String): OwnConnection? {
            val text = input.trim()
            val start = text.indexOf("knigavuhe://library")
            if (start < 0) return null
            val query = text.substring(start).substringAfter('?', "").substringBefore(' ').substringBefore('\n')
            val params = mutableMapOf<String, String>()
            // key=… всегда последний и без '&'; url может содержать '&' только экранированным
            for (part in query.split('&')) {
                val name = part.substringBefore('=', "")
                if (name.isEmpty()) continue
                params[name] = URLDecoder.decode(part.substringAfter('='), "UTF-8")
            }
            val base = params["url"]?.trim()?.trimEnd('/') ?: return null
            val key = params["key"]?.trim() ?: return null
            if (!(base.startsWith("https://") || base.startsWith("http://")) || key.isEmpty()) return null
            return OwnConnection(base, key)
        }
    }
}

class OwnLibraryException(val code: Int, message: String) : IOException(message)

@kotlinx.serialization.Serializable
data class OwnMe(val title: String? = null)

class OwnLibraryApi(private val client: OkHttpClient = Http.client) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // Сервер отдаёт обложку относительной ссылкой: картинку грузит Coil, которому нужен полный адрес.
    suspend fun books(conn: OwnConnection): List<OwnBook> = withContext(Dispatchers.IO) {
        parseBooks(get(conn, "/v1/library/books")).map { it.copy(cover = it.cover?.let(conn::absolute)) }
    }

    /** all=true — все не снятые главы со статусами, ссылки только у готовых. */
    suspend fun manifest(conn: OwnConnection, bookId: String, all: Boolean = true): OwnBook = withContext(Dispatchers.IO) {
        parseManifest(get(conn, "/v1/library/books/$bookId/manifest" + if (all) "?all=1" else "")).let { it.copy(cover = it.cover?.let(conn::absolute)) }
    }

    /** Название библиотеки от сервера («Озвучки Марка»); старый сервер без поля — null. */
    suspend fun title(conn: OwnConnection): String? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<OwnMe>(get(conn, "/v1/library/me")).title }.getOrNull()
    }

    fun parseBooks(raw: String): List<OwnBook> = json.decodeFromString(raw)

    fun parseManifest(raw: String): OwnBook = json.decodeFromString(raw)

    private fun get(conn: OwnConnection, path: String): String {
        val request = Request.Builder()
            .url(conn.baseUrl + path)
            .header("Authorization", "Bearer ${conn.key}")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when (response.code) {
                200 -> return body
                401 -> throw OwnLibraryException(401, "Доступ к библиотеке закрыт или телефон отключён. Скачанное осталось на телефоне.")
                403 -> throw OwnLibraryException(403, "Доступ к библиотеке закрыт. Скачанное осталось на телефоне.")
                404 -> throw OwnLibraryException(404, "Этот ключ не открывает библиотеку")
                else -> throw OwnLibraryException(response.code, "Библиотека ответила ${response.code}")
            }
        }
    }
}

/**
 * Свои книги живут в тех же таблицах, что и книги knigavuhe.org, где id — положительные Int.
 * Своим даём отрицательные: книга `-key`, глава `-(key * 1000 + n)`, где key — стабильное число
 * из строкового id книги (12 hex). key < 2 000 000, поэтому глава укладывается в Int.
 */
object OwnIds {
    const val PATH_PREFIX = "own:"
    private const val SPACE = 2_000_000L
    const val MAX_CHAPTERS = 999

    fun key(bookId: String): Int {
        require(bookId.isNotEmpty() && bookId.all { it.isLetterOrDigit() }) { "плохой id книги: $bookId" }
        var hash = 1125899906842597L
        for (c in bookId) hash = 31 * hash + c.code
        return (Math.floorMod(hash, SPACE - 1) + 1).toInt()
    }

    fun book(bookId: String): Int = -key(bookId)

    fun chapter(bookId: String, n: Int): Int {
        require(n in 1..MAX_CHAPTERS) { "глава $n вне 1..$MAX_CHAPTERS" }
        return -(key(bookId) * 1000 + n)
    }

    fun isOwn(bookId: Int): Boolean = bookId < 0

    fun path(bookId: String): String = PATH_PREFIX + bookId

    fun fromPath(path: String): String? = path.removePrefix(PATH_PREFIX).takeIf { path.startsWith(PATH_PREFIX) }
}
