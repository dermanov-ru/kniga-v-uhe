package dev.mark.knigavuhe.ui.favorites

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mark.knigavuhe.data.db.LibraryRow
import dev.mark.knigavuhe.ui.components.Cover
import dev.mark.knigavuhe.util.formatLeft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onBack: () -> Unit,
    onOpenBook: (Int) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Избранное") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.pending.isEmpty() && state.downloaded.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(),
                Alignment.Center,
            ) {
                Text(
                    "Отмечайте книги звездой в поиске — они лягут сюда, и можно будет качать не сразу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.pending.isNotEmpty()) {
                    item { SectionTitle("Ещё не скачаны") }
                    items(state.pending, key = { "pending-${it.id}" }) { row ->
                        FavoriteRow(
                            row = row,
                            downloaded = false,
                            onOpen = { onOpenBook(row.id) },
                            onAction = { viewModel.download(row.id) },
                            onRemove = { viewModel.remove(row.id) },
                        )
                    }
                }
                if (state.downloaded.isNotEmpty()) {
                    item { SectionTitle("Скачаны целиком") }
                    items(state.downloaded, key = { "done-${it.id}" }) { row ->
                        FavoriteRow(
                            row = row,
                            downloaded = true,
                            onOpen = { onOpenBook(row.id) },
                            onAction = {
                                viewModel.play(row.id)
                                onOpenPlayer()
                            },
                            onRemove = { viewModel.remove(row.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun FavoriteRow(
    row: LibraryRow,
    downloaded: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(row.cover, size = 56.dp)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.authors.ifBlank { row.readers },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatLeft(row.totalDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!downloaded && row.doneCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (row.chapterCount > 0) row.doneCount.toFloat() / row.chapterCount else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Скачано ${row.doneCount} из ${row.chapterCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(onClick = onAction) {
                Icon(
                    if (downloaded) Icons.Rounded.PlayArrow else Icons.Rounded.Download,
                    contentDescription = if (downloaded) "Играть" else "Скачать",
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.StarBorder, contentDescription = "Убрать из избранного")
            }
        }
    }
}
