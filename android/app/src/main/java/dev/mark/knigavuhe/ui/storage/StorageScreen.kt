package dev.mark.knigavuhe.ui.storage

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mark.knigavuhe.ui.components.Cover
import dev.mark.knigavuhe.util.formatLeft
import dev.mark.knigavuhe.util.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    viewModel: StorageViewModel,
    onBack: () -> Unit,
    onOpenBook: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<StorageEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Память") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SummaryCard(state) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StorageSort.entries.forEach { option ->
                        FilterChip(
                            selected = state.sort == option,
                            onClick = { viewModel.setSort(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            items(state.entries, key = { it.row.id }) { entry ->
                StorageRow(
                    entry = entry,
                    onOpen = { onOpenBook(entry.row.id) },
                    onFree = { viewModel.freeSpace(entry.row.id) },
                    onDelete = { pendingDelete = entry },
                )
            }

            if (state.entries.isEmpty()) {
                item {
                    Text(
                        "Книг пока нет — и места они не занимают.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить книгу целиком?") },
            text = {
                Text("«${entry.row.title}» исчезнет из библиотеки вместе с файлами и позицией.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(entry.row.id)
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
private fun SummaryCard(state: StorageUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                formatSize(state.totalBytes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "занято книгами · свободно ${formatSize(state.freeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val withFiles = state.entries.count { it.bytes > 0 }
            if (withFiles > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        val whole = (state.totalBytes + state.freeBytes).coerceAtLeast(1)
                        (state.totalBytes.toFloat() / whole).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Скачано книг: $withFiles из ${state.entries.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StorageRow(
    entry: StorageEntry,
    onOpen: () -> Unit,
    onFree: () -> Unit,
    onDelete: () -> Unit,
) {
    val row = entry.row
    Card(modifier = Modifier.fillMaxWidth()) {
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
                    if (entry.bytes > 0) formatSize(entry.bytes) else "не скачана",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.bytes > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (entry.bytes > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "${row.doneCount} из ${row.chapterCount} глав · ${formatLeft(row.totalDurationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.bytes > 0) {
                IconButton(onClick = onFree) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = "Освободить место",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Удалить книгу")
            }
        }
    }
}
