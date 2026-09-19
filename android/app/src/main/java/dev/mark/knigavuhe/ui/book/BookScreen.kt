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

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
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
                val complete = state.downloadedCount == book.chapterCount && book.chapterCount > 0
                Column {
                    if (!complete) {
                        LinearProgressIndicator(
                            progress = {
                                if (book.chapterCount > 0) {
                                    state.downloadedCount.toFloat() / book.chapterCount
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Скачано ${state.downloadedCount} из ${book.chapterCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
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
                        } else if (!complete) {
                            OutlinedButton(onClick = viewModel::download) {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Скачать всё")
                            }
                        } else {
                            OutlinedButton(onClick = viewModel::deleteDownloads) {
                                Icon(Icons.Rounded.DownloadDone, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Удалить файлы")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Главы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }

            itemsIndexed(state.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                ChapterRow(
                    chapter = chapter,
                    onClick = {
                        viewModel.play(index)
                        onOpenPlayer()
                    },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterEntity, onClick: () -> Unit) {
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
        Text(
            chapter.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatDuration(chapter.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
