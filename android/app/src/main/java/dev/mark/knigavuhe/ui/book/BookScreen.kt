package dev.mark.knigavuhe.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.ui.components.Cover
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import dev.mark.knigavuhe.data.db.BookEntity
import dev.mark.knigavuhe.util.formatDuration
import dev.mark.knigavuhe.util.formatLeft
import dev.mark.knigavuhe.util.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    viewModel: BookViewModel,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSeries: (String, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.ensureImported() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.book?.title ?: "Книга",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    val favorite = state.book?.favorite == true
                    IconButton(onClick = viewModel::toggleFavorite, enabled = state.book != null) {
                        Icon(
                            if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
                            tint = if (favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        val book = state.book
        if (book == null) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                when {
                    busy || (state.loading && error == null) -> CircularProgressIndicator()
                    error != null -> Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(32.dp),
                    )
                    else -> Text("Книга не найдена")
                }
            }
            return@Scaffold
        }

        val own = viewModel.isOwn
        val context = LocalContext.current
        LaunchedEffect(own) { viewModel.pollOwn() }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            if (own) {
                ownStatus(book, state.chapters)?.let { status ->
                    item {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Cover(book.cover, size = 120.dp, corner = 12.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                        if (book.authors.isNotBlank()) {
                            Text(
                                book.authors,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (book.readers.isNotBlank()) {
                            Text(
                                "Читает: ${book.readers}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${book.chapterCount} глав · ${formatLeft(book.totalDurationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        val slug = book.seriesSlug
                        if (slug != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    append("Цикл «${book.series ?: slug}»")
                                    if (book.seriesIndex > 0 && book.seriesTotal > 0) {
                                        append(" · книга ${book.seriesIndex} из ${book.seriesTotal}")
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onOpenSeries(slug, book.series ?: "") }
                                    .padding(vertical = 2.dp),
                            )
                        }
                        if (state.sizeOnDisk > 0) {
                            Text(
                                "На устройстве: ${formatSize(state.sizeOnDisk)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                val downloading = state.downloadingCount > 0
                // у своей книги считаем только главы со звуком: остальные ещё озвучиваются
                val available = if (own) state.chapters.count { it.isPlayable } else book.chapterCount
                val complete = state.downloadedCount == available && available > 0
                val playable = state.chapters.any { it.isPlayable }
                Column {
                    if (!complete) {
                        LinearProgressIndicator(
                            progress = {
                                if (available > 0) state.downloadedCount.toFloat() / available else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (own && !book.autoDownload) {
                                "Не скачана — слушать можно по сети, готово глав: $available"
                            } else {
                                "Скачано ${state.downloadedCount} из $available"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = playable,
                            onClick = {
                                viewModel.play()
                                onOpenPlayer()
                            },
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Слушать")
                        }
                        if (downloading) {
                            OutlinedButton(onClick = viewModel::cancelDownload) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Стоп")
                            }
                        } else if (own && !book.autoDownload) {
                            OutlinedButton(onClick = viewModel::download, enabled = available > 0) {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Скачать")
                            }
                        } else if (!own && !complete) {
                            OutlinedButton(onClick = viewModel::download) {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Скачать всё")
                            }
                        } else if (!own || state.downloadedCount > 0 || book.autoDownload) {
                            OutlinedButton(onClick = viewModel::deleteDownloads) {
                                Icon(Icons.Rounded.DownloadDone, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Удалить файлы")
                            }
                        }
                    }
                    if (own && book.autoDownload && book.remoteState in setOf("queued", "running")) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Новые главы догружаются сами, как только озвучатся",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Главы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }

            itemsIndexed(state.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                ChapterRow(
                    chapter = chapter,
                    onClick = {
                        if (chapter.isPlayable) {
                            viewModel.play(index)
                            onOpenPlayer()
                        } else {
                            Toast.makeText(context, "Глава ещё озвучивается", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

/** Строка статуса своей книги под шапкой; у готовой книги — ничего. */
private fun ownStatus(book: BookEntity, chapters: List<ChapterEntity>): String? {
    val state = book.remoteState ?: return null
    val ready = chapters.count { it.remoteState == ChapterEntity.REMOTE_DONE }
    val total = chapters.size
    return when (state) {
        "done" -> null
        "gone" -> "Книги больше нет в библиотеке озвучки — новые главы не появятся"
        "paused" -> "Озвучка на паузе · готово $ready из $total"
        "error" -> "Озвучка остановилась с ошибкой · готово $ready из $total"
        else -> buildString {
            val running = chapters.firstOrNull { it.remoteState == ChapterEntity.REMOTE_RUNNING }
            if (running != null) {
                append("Озвучивается: глава ${running.position}")
                if (running.remoteTotal > 0) append(" — ${running.remoteDone * 100 / running.remoteTotal} %")
            } else {
                append("В очереди на озвучку")
            }
            val queued = chapters.count { it.remoteState == "queued" || it.remoteState == "new" }
            if (queued > 0) append(", в очереди $queued")
            append(", готово $ready из $total")
            book.remoteSecondsLeft?.takeIf { it > 0 }?.let { append(", осталось около ${formatLeft(it * 1000L)}") }
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterEntity, onClick: () -> Unit) {
    val waiting = !chapter.isPlayable
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (chapter.state) {
            ChapterEntity.STATE_DONE -> Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Скачана",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            ChapterEntity.STATE_DOWNLOADING -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            ChapterEntity.STATE_FAILED -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = "Ошибка",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            else -> Spacer(Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (waiting) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (waiting && chapter.remoteState == ChapterEntity.REMOTE_RUNNING) {
                Text(
                    if (chapter.remoteTotal > 0) "озвучивается ${chapter.remoteDone}/${chapter.remoteTotal}" else "озвучивается",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (chapter.remoteTotal > 0) {
                    LinearProgressIndicator(
                        progress = { chapter.remoteDone.toFloat() / chapter.remoteTotal },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            } else if (waiting) {
                Text(
                    if (chapter.remoteState == "error") "ошибка озвучки" else "в очереди",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        if (!waiting) {
            Text(
                formatDuration(chapter.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
