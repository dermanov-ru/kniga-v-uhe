package dev.mark.knigavuhe.ui.own

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.mark.knigavuhe.data.remote.OwnBook
import dev.mark.knigavuhe.data.remote.OwnIds
import dev.mark.knigavuhe.ui.components.Cover
import dev.mark.knigavuhe.util.formatDuration

/**
 * «Мои озвучки»: книги, озвученные на своём сервере. Не подключено — экран подключения
 * (QR со страницы библиотеки открывает приложение сам, строку можно и вставить руками).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnLibraryScreen(
    viewModel: OwnLibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: (Int) -> Unit,
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val added by viewModel.added.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDisconnect by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мои озвучки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (connection != null) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
                        }
                        IconButton(onClick = { confirmDisconnect = true }) {
                            Icon(Icons.Rounded.LinkOff, contentDescription = "Отключить библиотеку")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (connection == null) {
            ConnectPane(Modifier.padding(padding), onConnect = viewModel::connect)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    connection?.baseUrl.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loading && state.books.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                }
            }
            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(state.books, key = { it.id }) { book ->
                OwnBookRow(
                    book = book,
                    inLibrary = book.id in added,
                    busy = book.id in state.busy,
                    onAdd = { viewModel.add(book) },
                    onOpen = { onOpenBook(OwnIds.book(book.id)) },
                )
            }
            if (!state.loading && state.error == null && state.books.isEmpty()) {
                item {
                    Text(
                        "На сервере пока нет книг. Загрузите FB2 на странице библиотеки.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Отключить библиотеку?") },
            text = { Text("Скачанные книги останутся на телефоне. Новые главы подтягиваться не будут, пока не подключите снова.") },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; viewModel.disconnect() }) { Text("Отключить") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ConnectPane(modifier: Modifier, onConnect: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Библиотека не подключена", style = MaterialTheme.typography.titleMedium)
        Text(
            "Откройте страницу библиотеки, нажмите «Подключить телефон» и наведите камеру на QR — " +
                "приложение откроется само. Или вставьте строку подключения сюда.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; wrong = false },
            label = { Text("knigavuhe://library?url=…&key=…") },
            isError = wrong,
            supportingText = { if (wrong) Text("Не похоже на строку подключения") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(onClick = { wrong = !onConnect(text) }, enabled = text.isNotBlank()) { Text("Подключить") }
    }
}

@Composable
private fun OwnBookRow(book: OwnBook, inLibrary: Boolean, busy: Boolean, onAdd: () -> Unit, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = inLibrary, onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(url = book.cover, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.authorsText.isNotBlank()) {
                    Text(book.authorsText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    buildString {
                        append("глав ${book.chapters_done} из ${book.chapters_total}")
                        if (book.duration > 0) append(" · ").append(formatDuration((book.duration * 1000).toLong()))
                        stateLabel(book.state)?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!book.isDone && book.chapters_total > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { book.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                when {
                    busy -> CircularProgressIndicator(Modifier.padding(4.dp))
                    book.chapters_done == 0 -> Text(
                        "Готовых глав ещё нет",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    inLibrary -> OutlinedButton(onClick = onAdd) { Text("Подтянуть новые главы") }
                    else -> Button(onClick = onAdd) { Text("Добавить в библиотеку") }
                }
            }
        }
    }
}

private fun stateLabel(state: String): String? = when (state) {
    "running", "queued" -> "озвучивается"
    "paused" -> "на паузе"
    "draft" -> "черновик"
    "error" -> "ошибка озвучки"
    else -> null
}
