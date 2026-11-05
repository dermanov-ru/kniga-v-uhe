package dev.mark.knigavuhe.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mark.knigavuhe.App
import dev.mark.knigavuhe.data.db.AppDatabase
import dev.mark.knigavuhe.data.db.ListenEventEntity
import dev.mark.knigavuhe.data.remote.ClaimBody
import dev.mark.knigavuhe.data.remote.ClaimReply
import dev.mark.knigavuhe.data.remote.ListenApi
import dev.mark.knigavuhe.data.remote.ListenEvent
import dev.mark.knigavuhe.data.remote.ListenState
import dev.mark.knigavuhe.data.remote.ListenWhere
import dev.mark.knigavuhe.data.repo.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Синхронизация прослушивания своих книг. Каждое событие сначала ложится в Room и только потом
 * уходит на сервер пачкой: без сети ничего не теряется и не мешает слушать. Пока играет своя
 * книга, держим long-poll «стоп»: другое устройство перехватило книгу — плеер ставит паузу.
 */
class ListenSync(
    private val context: Context,
    private val db: AppDatabase,
    private val api: ListenApi,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    /** Книгу перехватили: (серверный id книги, имя устройства). Ставит PlaybackController. */
    var onStop: (suspend (String, String) -> Unit)? = null

    private val sending = Mutex()
    private var control: Job? = null

    val connected: Boolean get() = settings.library.value != null

    private val prefs = context.getSharedPreferences("listen", Context.MODE_PRIVATE)
    private val seqLock = Any()

    private fun nextSeq(now: Long): Long = synchronized(seqLock) {
        val seq = ListenRules.nextSeq(prefs.getLong(KEY_SEQ, 0L), now)
        prefs.edit().putLong(KEY_SEQ, seq).commit()
        seq
    }

    fun record(book: String, n: Int, posMs: Long, speed: Float, kind: String) {
        val now = System.currentTimeMillis()
        val seq = nextSeq(now)
        scope.launch {
            db.listenEvents().insert(
                ListenEventEntity(seq = seq, book = book, n = n, posMs = posMs.coerceAtLeast(0), speed = speed, kind = kind,
                    at = now)
            )
            flush()
        }
    }

    /** Отправить очередь. Не вышло — WorkManager повторит, когда появится сеть. */
    suspend fun flush(): Boolean {
        val conn = settings.library.value ?: return true
        return sending.withLock {
            runCatching {
                repeat(MAX_ROUNDS) {
                    val batch = db.listenEvents().pending(BATCH)
                    if (batch.isEmpty()) return@runCatching
                    val reply = api.send(conn, batch.map {
                        ListenEvent(it.book, it.n, it.posMs, it.speed, it.kind, it.at, it.seq)
                    })
                    if (reply.accepted.isNotEmpty()) db.listenEvents().delete(reply.accepted)
                    reply.stop.forEach { book ->
                        onStop?.invoke(book, reply.states[book]?.device_name?.ifBlank { null } ?: "другом устройстве")
                    }
                    if (reply.accepted.isEmpty()) return@runCatching
                }
            }.onFailure { ListenFlushWorker.schedule(context) }.isSuccess
        }
    }

    suspend fun where(book: String): ListenWhere? {
        val conn = settings.library.value ?: return null
        return api.where(conn, book)
    }

    suspend fun claim(book: String, body: ClaimBody): ClaimReply? {
        val conn = settings.library.value ?: return null
        return runCatching { api.claim(conn, book, body) }.getOrNull()
    }

    suspend fun last(book: String): ListenWhere? {
        val conn = settings.library.value ?: return null
        return api.last(conn, book)
    }

    /** Пока играет своя книга: висим на канале стопа, переподключаемся. */
    fun startControl(book: String) {
        if (control?.isActive == true || !connected) return
        control = scope.launch {
            while (isActive) {
                val conn = settings.library.value ?: break
                val reply = runCatching { api.waitControl(conn) }
                val stop = reply.getOrNull()
                if (stop?.stop == true) onStop?.invoke(stop.book, stop.by.ifBlank { "другом устройстве" })
                if (reply.isFailure) delay(RETRY_MS)
            }
        }
    }

    fun stopControl() {
        control?.cancel()
        control = null
    }

    companion object {
        private const val BATCH = 200
        private const val MAX_ROUNDS = 10
        private const val RETRY_MS = 5_000L
        private const val KEY_SEQ = "last_seq"
    }
}

/** Повтор отправки очереди событий, когда появится сеть. */
class ListenFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = (applicationContext as App).container.listen.flush()
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK = "listen-flush"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ListenFlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
