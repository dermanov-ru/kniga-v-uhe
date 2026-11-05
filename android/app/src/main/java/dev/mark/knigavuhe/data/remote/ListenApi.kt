package dev.mark.knigavuhe.data.remote

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Синхронизация прослушивания своих книг между устройствами (`/v1/listen/…`).
 * Книга — серверный строковый id, глава — её номер на сервере (= position главы у нас).
 */
@Serializable
data class ListenEvent(
    val book: String,
    val n: Int,
    val pos_ms: Long,
    val speed: Float,
    val kind: String,
    val at: Long,
    val seq: Long,
)

@Serializable
data class ListenState(
    val book: String? = null,
    val n: Int = 1,
    val pos_ms: Long = 0,
    val pos_now_ms: Long = 0,
    val speed: Float = 1f,
    val device: String = "",
    val device_name: String = "",
    /** секунды по часам сервера */
    val at: Double = 0.0,
    val playing: Boolean = false,
    val active: Boolean = false,
    /** Куда прыгать при advice=jump: отступ 60 с назад, через границу главы. */
    val jump_n: Int? = null,
    val jump_pos_ms: Long = 0,
)

@Serializable
data class ListenWhere(
    val state: ListenState? = null,
    /** local | jump | ask */
    val advice: String = "local",
    val step_back_ms: Long = 60_000,
    /** id этого устройства на сервере: «(это устройство)» в строке «Последний раз». */
    val me: String = "",
)

@Serializable
data class ListenEventsReply(
    val accepted: List<Long> = emptyList(),
    val states: Map<String, ListenState?> = emptyMap(),
    val stop: List<String> = emptyList(),
)

@Serializable
data class ClaimBody(val n: Int? = null, val pos_ms: Long? = null, val speed: Float = 1f)

@Serializable
data class ClaimReply(val n: Int = 1, val pos_ms: Long = 0)

@Serializable
data class ControlReply(val stop: Boolean = false, val book: String = "", val by: String = "")

class ListenApi(base: OkHttpClient = Http.client) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    /** «Играть» не должен ждать сеть дольше двух секунд. */
    private val quick = base.newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
    private val normal = base.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    /** long-poll канала стопа: сервер держит до 50 с. */
    private val longPoll = base.newBuilder().readTimeout(70, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()

    private val deviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }
        .joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { "Телефон" }

    suspend fun send(conn: OwnConnection, events: List<ListenEvent>): ListenEventsReply = withContext(Dispatchers.IO) {
        val body = json.encodeToString(events).toRequestBody(jsonType)
        val request = base(conn, "/v1/listen/events")
            .header("X-Client-Time", System.currentTimeMillis().toString())
            .post(body).build()
        parseEventsReply(call(normal, request) ?: "{}")
    }

    /** null — нет сети, таймаут или ошибка: играем со своего места. */
    suspend fun where(conn: OwnConnection, book: String): ListenWhere? = withContext(Dispatchers.IO) {
        runCatching { call(quick, base(conn, "/v1/listen/$book").get().build())?.let(::parseWhere) }.getOrNull()
    }

    suspend fun claim(conn: OwnConnection, book: String, body: ClaimBody): ClaimReply = withContext(Dispatchers.IO) {
        val request = base(conn, "/v1/listen/$book/claim").post(json.encodeToString(body).toRequestBody(jsonType)).build()
        json.decodeFromString(call(normal, request) ?: "{}")
    }

    /** Висит до 50 с. null — стопа не было (204). */
    suspend fun waitControl(conn: OwnConnection): ControlReply? = withContext(Dispatchers.IO) {
        call(longPoll, base(conn, "/v1/listen/control/wait?wait=50").get().build())?.let { json.decodeFromString(it) }
    }

    suspend fun last(conn: OwnConnection, book: String): ListenWhere? = withContext(Dispatchers.IO) {
        runCatching { call(normal, base(conn, "/v1/listen/$book").get().build())?.let(::parseWhere) }.getOrNull()
    }

    fun parseWhere(raw: String): ListenWhere = json.decodeFromString(raw)
    fun parseEventsReply(raw: String): ListenEventsReply = json.decodeFromString(raw)
    fun encode(events: List<ListenEvent>): String = json.encodeToString(events)

    private fun base(conn: OwnConnection, path: String) = Request.Builder()
        .url(conn.baseUrl + path)
        .header("Authorization", "Bearer ${conn.key}")
        .header("X-Device-Name", deviceName)
        .header("Accept", "application/json")

    /** Тело ответа; null для 204. Ошибки HTTP — исключение. */
    private fun call(client: OkHttpClient, request: Request): String? =
        client.newCall(request).execute().use { response ->
            when {
                response.code == 204 -> null
                response.isSuccessful -> response.body?.string()
                else -> throw OwnLibraryException(response.code, "Сервер ответил ${response.code}")
            }
        }
}
