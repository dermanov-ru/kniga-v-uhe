package dev.mark.knigavuhe.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.db.LibraryRow
import dev.mark.knigavuhe.data.remote.BookCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SeriesItem(
    val card: BookCard,
    val row: LibraryRow?,
) {
    val inLibrary: Boolean get() = row != null
    val downloaded: Boolean get() = row != null && row.chapterCount > 0 && row.doneCount >= row.chapterCount
    val percent: Int get() = row?.percent ?: 0
}

data class SeriesUiState(
    val name: String = "",
    val items: List<SeriesItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val favoriteProgress: Pair<Int, Int>? = null,
)

class SeriesViewModel(
    private val container: AppContainer,
    private val slug: String,
    private val title: String,
) : ViewModel() {

    private val cards = MutableStateFlow<List<BookCard>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<String?>(null)
    private val favoriteProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    private val _state = MutableStateFlow(SeriesUiState(name = title))
    val state: StateFlow<SeriesUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            combine(
                cards,
                container.db.books().observeLibrary(),
                loading,
                error,
                favoriteProgress,
            ) { list, rows, isLoading, failure, progress ->
                val byId = rows.associateBy { it.id }
                SeriesUiState(
                    name = title,
                    items = list.map { SeriesItem(card = it, row = it.bookId?.let(byId::get)) },
                    loading = isLoading,
                    error = failure,
                    favoriteProgress = progress,
                )
            }.collect { _state.value = it }
        }
    }

    fun load() {
        loading.value = true
        error.value = null
        viewModelScope.launch {
            runCatching { container.bookRepository.seriesBooks(slug) }
                .onSuccess { cards.value = it }
                .onFailure { error.value = it.message ?: "Не удалось открыть цикл" }
            loading.value = false
        }
    }

    /** Добавляет весь цикл в избранное: метаданные тянутся, файлы — нет. */
    fun favoriteAll() {
        if (favoriteProgress.value != null) return
        viewModelScope.launch {
            runCatching {
                container.bookRepository.favoriteSeries(slug) { done, total ->
                    favoriteProgress.value = done to total
                }
            }.onFailure { error.value = it.message ?: "Не удалось добавить цикл" }
            favoriteProgress.value = null
        }
    }

    /** У части карточек id нет в адресе — тогда достаём его со страницы книги. */
    fun openBook(card: BookCard, onResolved: (Int) -> Unit) {
        card.bookId?.let { onResolved(it); return }
        viewModelScope.launch {
            runCatching {
                container.api.resolveBookId(
                    dev.mark.knigavuhe.data.remote.KnigavuheApi.normalizeUrl(card.path)
                )
            }.onSuccess(onResolved)
        }
    }
}
