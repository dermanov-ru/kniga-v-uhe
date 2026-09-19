package dev.mark.knigavuhe.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mark.knigavuhe.data.db.LibraryRow
import dev.mark.knigavuhe.ui.components.Cover
import dev.mark.knigavuhe.util.formatLeft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSeries: (String, String) -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LibraryRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Моя библиотека") },
                actions = {
                    IconButton(onClick = onOpenFavorites) {
                        Icon(Icons.Rounded.Star, contentDescription = "Избранное")
                    }
                    IconButton(onClick = onOpenStorage) {
                        Icon(Icons.Rounded.Storage, contentDescription = "Память")
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "Поиск книг")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Добавить по ссылке")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (books.isEmpty()) {
                EmptyLibrary(onOpenSearch = onOpenSearch, onAdd = { showAdd = true })
            } else {
                val last = books.firstOrNull { it.started }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        // Enough room for the floating button to sit over empty space.
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (last != null) {
                        item(key = "continue-${last.id}") {
                            ContinueCard(
                                row = last,
                                onPlay = {
                                    viewModel.continueLast { onOpenPlayer() }
                                },
                                onOpen = { onOpenBook(last.id) },
                            )
                        }
                    }
                    entries.forEach { entry ->
                        when (entry) {
                            is LibraryEntry.Single -> item(key = "book-${entry.row.id}") {
                                LibraryItem(
                                    row = entry.row,
                                    onOpen = { onOpenBook(entry.row.id) },
                                    onPlay = {
                                        viewModel.play(entry.row.id)
                                        onOpenPlayer()
                                    },
                                    onDelete = { pendingDelete = entry.row },
                                )
                            }

                            is LibraryEntry.Series -> {
                                val open = expanded[entry.slug] ?: false
                                item(key = "series-${entry.slug}") {
                                    SeriesHeader(
                                        entry = entry,
                                        expanded = open,
                                        onToggle = { expanded[entry.slug] = !open },
                                        onOpenSeries = { onOpenSeries(entry.slug, entry.name) },
                                    )
                                }
                                if (open) {
                                    items(entry.rows, key = { "series-book-${it.id}" }) { row ->
                                        LibraryItem(
                                            row = row,
                                            inSeries = true,
                                            onOpen = { onOpenBook(row.id) },
                                            onPlay = {
                                                viewModel.play(row.id)
                                                onOpenPlayer()
                                            },
                                            onDelete = { pendingDelete = row },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddByLinkDialog(
            importing = importing,
            error = message,
            onDismiss = {
                showAdd = false
                viewModel.clearMessage()
            },
            onSubmit = { link, download ->
                viewModel.import(link, download) { bookId ->
                    showAdd = false
                    onOpenBook(bookId)
                }
            },
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить книгу?") },
            text = { Text("«${row.title}» и скачанные главы будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(row.id)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun ContinueCard(row: LibraryRow, onPlay: () -> Unit, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(row.cover, size = 84.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Продолжить",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    row.chapterTitle?.let { "$it · осталось ${formatLeft(row.leftMs)}" }
                        ?: "осталось ${formatLeft(row.leftMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Продолжить слушать")
            }
        }
    }
}

/** Шапка цикла: одна строка вместо пачки книг, пока её не развернут. */
@Composable
private fun SeriesHeader(
    entry: LibraryEntry.Series,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenSeries: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Свернуть цикл" else "Развернуть цикл",
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${entry.rows.size} из ${entry.known} книг · ${formatLeft(entry.totalMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSeries) {
                    Icon(Icons.Rounded.Star, contentDescription = "Открыть цикл")
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { entry.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LibraryItem(
    row: LibraryRow,
    inSeries: Boolean = false,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (inSeries) 16.dp else 0.dp)
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(row.cover, size = 64.dp)
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
                    if (row.doneCount < row.chapterCount) {
                        Text(
                            "Скачано ${row.doneCount} из ${row.chapterCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Играть")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Удалить")
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (row.started) "Прослушано ${row.percent}%" else "Не начата",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (row.started) "осталось ${formatLeft(row.leftMs)}" else formatLeft(row.totalDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onOpenSearch: () -> Unit, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Пока пусто", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Найдите книгу поиском или вставьте ссылку с сайта — приложение скачает главы и будет играть их офлайн.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onOpenSearch) { Text("Поиск") }
            TextButton(onClick = onAdd) { Text("Вставить ссылку") }
        }
    }
}

@Composable
private fun AddByLinkDialog(
    importing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, Boolean) -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var download by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("Добавить книгу") },
        text = {
            Column {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Ссылка на книгу") },
                    placeholder = { Text("https://knigavuhe.org/book/…") },
                    singleLine = false,
                    maxLines = 3,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = download,
                        onCheckedChange = { download = it },
                        enabled = !importing,
                    )
                    Text("Сразу скачать все главы")
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (importing) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Читаю страницу книги…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(link, download) }, enabled = !importing && link.isNotBlank()) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) { Text("Отмена") }
        },
    )
}
