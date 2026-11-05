package dev.mark.knigavuhe.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mark.knigavuhe.App
import dev.mark.knigavuhe.data.db.AppDatabase
import dev.mark.knigavuhe.data.repo.BookRepository
import dev.mark.knigavuhe.data.repo.Settings
import dev.mark.knigavuhe.download.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Синк «Моей библиотеки»: при старте, при открытии экранов, раз в 30 с, пока на экране книга
 * в озвучке, и раз в 15 минут фоном (WorkManager). Новые готовые главы качаются только у книг,
 * где нажато «Скачать» (autoDownload).
 */
class OwnSync(
    private val context: Context,
    private val db: AppDatabase,
    private val repo: BookRepository,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val onSynced: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var lastAt = 0L

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** force=false — не чаще раза в 10 с: экраны зовут синк наперебой. */
    suspend fun sync(force: Boolean = false) {
        if (settings.library.value == null) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastAt < MIN_GAP_MS) return
            lastAt = now
            runCatching { repo.syncOwn() }
                .onSuccess { grown ->
                    _error.value = null
                    // Догрузка: у книг со «Скачать» — всё, что ещё не скачано из готового
                    db.books().own().filter { it.autoDownload && (it.id in grown || hasPending(it.id)) }
                        .forEach { DownloadWorker.enqueue(context, it.id) }
                    onSynced()
                }
                .onFailure { _error.value = it.message ?: "Не удалось обновить библиотеку" }
        }
    }

    private suspend fun hasPending(bookId: Int): Boolean =
        db.chapters().forBook(bookId).any { it.isPlayable && !it.isDownloaded }

    fun syncSoon(force: Boolean = false) {
        scope.launch { sync(force) }
    }

    /** Пока экран открыт: синк, и раз в 30 с, если какая-то своя книга озвучивается. */
    suspend fun pollWhileVoicing() {
        while (true) {
            sync()
            val voicing = db.books().own().any { it.remoteState == "queued" || it.remoteState == "running" }
            if (!voicing || settings.library.value == null) return
            delay(POLL_MS)
        }
    }

    companion object {
        private const val MIN_GAP_MS = 10_000L
        private const val POLL_MS = 30_000L
        private const val WORK = "own-library-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OwnSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class OwnSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as App).container.ownSync.sync(force = true)
        return Result.success()
    }
}
