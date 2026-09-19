package dev.mark.knigavuhe.ui.search

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import dev.mark.knigavuhe.data.remote.BookCard
import dev.mark.knigavuhe.data.remote.ReaderCard
import dev.mark.knigavuhe.ui.components.Cover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val favoriteBusy by viewModel.favoriteBusy.collectAsStateWithLifecycle()

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.items.size - 3
        }
    }
    LaunchedEffect(nearEnd, state.items.size) {
        if (nearEnd && state.items.isNotEmpty()) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.listingTitle.ifBlank { "Поиск книг" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.listingPath == null) {
                ReaderFilterRow(
                    reader = state.selectedReader,
                    isFavorite = state.selectedReader?.slug != null &&
                        state.selectedReader?.slug == state.favoriteSlug,
                    onPick = viewModel::openPicker,
                    onClear = viewModel::clearReader,
                    onToggleFavorite = viewModel::toggleFavorite,
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = {
                        Text(
                            if (state.readerMode) "Название книги у этого чтеца"
                            else "Название, автор или чтец"
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        viewModel.search()
                    }),
                    trailingIcon = {
                        IconButton(onClick = {
                            keyboard?.hide()
                            viewModel.search()
                        }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Искать")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        if (state.readerMode && state.query.isNotBlank() && state.scannedBooks > 0) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Просмотрено ${state.scannedBooks} книг",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                }
                state.items.isEmpty() && state.page > 0 -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            when {
                                state.readerMode && state.scannedBooks > 0 ->
                                    "Среди ${state.scannedBooks} книг этого чтеца ничего не нашлось"
                                state.readerMode -> "У этого чтеца ничего не нашлось"
                                else -> "Ничего не нашлось"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        // A prolific narrator is scanned in slices; the rest is one tap away.
                        if (state.hasMore) {
                            Spacer(Modifier.height(12.dp))
                            if (state.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = viewModel::keepLooking) { Text("Искать дальше") }
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.path }) { card ->
                        SearchResult(
                            card = card,
                            favorite = card.bookId != null && card.bookId in favoriteIds,
                            busy = card.path in favoriteBusy,
                            onFavorite = { viewModel.toggleFavorite(card) },
                            onClick = {
                                if (card.isLitres) {
                                    scope.launch {
                                        snackbar.showSnackbar("Аудио на сайт не выложено — книга продаётся на ЛитРес")
                                    }
                                } else {
                                    onOpenBook(card.path)
                                }
                            },
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.pickerOpen) {
        ReaderPickerSheet(
            query = state.readerQuery,
            suggestions = state.readerSuggestions,
            loading = state.readerLoading,
            favoriteSlug = state.favoriteSlug,
            onQueryChange = viewModel::onReaderQueryChange,
            onPick = viewModel::selectReader,
            onDismiss = viewModel::closePicker,
        )
    }
}

@Composable
private fun ReaderFilterRow(
    reader: ReaderCard?,
    isFavorite: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(
            onClick = onPick,
            label = {
                Text(
                    reader?.name ?: "Чтец: любой",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.RecordVoiceOver,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.weight(1f, fill = false),
        )
        if (reader != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (isFavorite) "Убрать из любимых" else "Сделать любимым чтецом",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = "Сбросить чтеца")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPickerSheet(
    query: String,
    suggestions: List<ReaderCard>,
    loading: Boolean,
    favoriteSlug: String?,
    onQueryChange: (String) -> Unit,
    onPick: (ReaderCard) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Имя чтеца") },
                singleLine = true,
                trailingIcon = {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(suggestions, key = { it.slug }) { reader ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(reader) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(reader.avatar, size = 40.dp, corner = 20.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                reader.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (reader.booksText.isNotBlank()) {
                                Text(
                                    reader.booksText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (reader.slug == favoriteSlug) {
                            Icon(
                                Icons.Rounded.Star,
                                contentDescription = "Любимый чтец",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (suggestions.isEmpty() && !loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            Text("Никого не нашлось", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResult(
    card: BookCard,
    favorite: Boolean,
    busy: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(card.cover, size = 72.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.authors.isNotBlank()) {
                    Text(
                        card.authors,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (card.readers.isNotBlank()) {
                    Text(
                        "Читает: ${card.readers}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (card.isLitres) {
                    Text(
                        "Только на ЛитРес",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (card.durationText.isNotBlank()) {
                    Text(card.durationText, style = MaterialTheme.typography.labelSmall)
                }
            }

            // У книг, которых на сайте нет, откладывать нечего.
            if (!card.isLitres) {
                IconButton(onClick = onFavorite, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
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
                }
            }
        }
    }
}
