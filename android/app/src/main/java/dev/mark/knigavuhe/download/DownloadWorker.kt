package dev.mark.knigavuhe.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mark.knigavuhe.App
import dev.mark.knigavuhe.R
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.remote.USER_AGENT
import dev.mark.knigavuhe.data.repo.Storage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads every chapter of one book. Runs as foreground work so Android keeps it alive with the
 * screen off, resumes partial files with a Range request, and refetches the chapter URLs if the
 * site has rotated them mid-run.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as App).container
    private val done = AtomicInteger()
    private var total = 0
    private var bookTitle = ""
    private val refreshLock = Mutex()
    private var lastRefreshAt = 0L

    override suspend fun doWork(): Result {
        val bookId = inputData.getInt(KEY_BOOK_ID, 0)
        if (bookId == 0) return Result.failure()
        val repo = container.bookRepository
        val book = repo.book(bookId) ?: return Result.failure()
        bookTitle = book.title

        var chapters = repo.refreshUrlsIfStale(bookId).ifEmpty { repo.chapters(bookId) }
        chapters = chapters.filterNot { it.isDownloaded && chapterOnDisk(bookId, it) }
        total = chapters.size
        if (total == 0) return Result.success()

        setForeground(foregroundInfo(0, total))

        val semaphore = Semaphore(PARALLEL)
        val failures = AtomicInteger()
        coroutineScope {
            chapters.map { chapter ->
                async {
                    semaphore.withPermit {
                        if (isStopped) return@withPermit
                        val ok = runCatching { downloadChapter(bookId, chapter) }.getOrElse { error ->
                            if (error is InterruptedException) throw error
                            false
                        }
                        if (!ok) {
                            failures.incrementAndGet()
                            container.db.chapters().updateState(chapter.id, ChapterEntity.STATE_FAILED)
                        }
                        val progress = done.incrementAndGet()
                        runCatching { setForeground(foregroundInfo(progress, total)) }
                    }
                }
            }.awaitAll()
        }

        return when {
            isStopped -> Result.success()
            failures.get() == 0 -> Result.success()
            failures.get() < total -> Result.retry()
            else -> Result.failure()
        }
    }

    private fun chapterOnDisk(bookId: Int, chapter: ChapterEntity): Boolean =
        Storage.chapterFile(applicationContext, bookId, chapter.id).length() > 0

    private suspend fun downloadChapter(bookId: Int, chapter: ChapterEntity): Boolean {
        val target = Storage.chapterFile(applicationContext, bookId, chapter.id)
        if (target.length() > 0) {
            container.db.chapters().updateProgress(
                chapter.id, ChapterEntity.STATE_DONE, target.length(), target.length(),
            )
            return true
        }
        container.db.chapters().updateState(chapter.id, ChapterEntity.STATE_DOWNLOADING)
        val part = Storage.partFile(applicationContext, bookId, chapter.id)

        var url = chapter.url
        repeat(2) { attempt ->
            val outcome = transfer(url, part, chapter.id, bookId)
            when (outcome) {
                Outcome.Done -> {
                    if (part.renameTo(target)) {
                        container.db.chapters().updateProgress(
                            chapter.id, ChapterEntity.STATE_DONE, target.length(), target.length(),
                        )
                        return true
                    }
                    return false
                }
                Outcome.Stale -> {
                    if (attempt == 0) {
                        url = freshUrl(bookId, chapter.id) ?: return false
                    } else {
                        return false
                    }
                }
                Outcome.Failed -> return false
                Outcome.Cancelled -> return false
            }
        }
        return false
    }

    /** One refresh serves every worker coroutine that hit a rotated URL at the same moment. */
    private suspend fun freshUrl(bookId: Int, chapterId: Int): String? = refreshLock.withLock {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt > REFRESH_DEBOUNCE_MS) {
            lastRefreshAt = now
            runCatching { container.bookRepository.refreshUrls(bookId) }
        }
        container.db.chapters().get(chapterId)?.url
    }

    private suspend fun transfer(
        url: String,
        part: File,
        chapterId: Int,
        bookId: Int,
    ): Outcome = withContext(Dispatchers.IO) {
        val existing = part.length()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        try {
            container.http.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    403, 404, 410 -> return@withContext Outcome.Stale
                    200, 206 -> Unit
                    else -> return@withContext Outcome.Failed
                }
                val appending = response.code == 206 && existing > 0
                val body = response.body ?: return@withContext Outcome.Failed
                val declared = body.contentLength().takeIf { it > 0 } ?: 0
                val totalSize = if (appending) existing + declared else declared

                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, appending).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var written = if (appending) existing else 0L
                        var sinceUpdate = 0L
                        while (true) {
                            if (isStopped) return@withContext Outcome.Cancelled
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            sinceUpdate += read
                            if (sinceUpdate >= PROGRESS_STEP) {
                                sinceUpdate = 0
                                container.db.chapters().updateProgress(
                                    chapterId, ChapterEntity.STATE_DOWNLOADING, written, totalSize,
                                )
                            }
                        }
                        output.flush()
                    }
                }
                Outcome.Done
            }
        } catch (io: IOException) {
            if (isStopped) Outcome.Cancelled else Outcome.Failed
        }
    }

    private fun foregroundInfo(current: Int, total: Int): ForegroundInfo {
        val launch = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = launch?.let {
            PendingIntent.getActivity(
                applicationContext, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, Notifications.DOWNLOAD_CHANNEL)
            .setContentTitle(bookTitle.ifBlank { "Загрузка книги" })
            .setContentText("Скачано $current из $total глав")
            .setSmallIcon(R.drawable.ic_stat_book)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, false)
            .setContentIntent(pending)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(Notifications.DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }

    private enum class Outcome { Done, Stale, Failed, Cancelled }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        private const val PARALLEL = 3
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 512L * 1024
        private const val REFRESH_DEBOUNCE_MS = 30_000L

        fun workName(bookId: Int) = "download-$bookId"

        fun enqueue(context: Context, bookId: Int) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId))
                .addTag(workName(bookId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, bookId: Int) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
        }
    }
}
