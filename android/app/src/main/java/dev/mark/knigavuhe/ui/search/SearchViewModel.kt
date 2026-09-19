package dev.mark.knigavuhe.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.remote.BookCard
import dev.mark.knigavuhe.data.remote.ReaderCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    /** Book title. With a narrator selected it filters that narrator's catalogue. */
    val query: String = "",
    val readerQuery: String = "",
    val readerSuggestions: List<ReaderCard> = emptyList(),
    val readerLoading: Boolean = false,
    val pickerOpen: Boolean = false,
    val selectedReader: ReaderCard? = null,
    val favoriteSlug: String? = null,
    /** Set when an author or genre page was opened from a card instead of a search. */
    val listingPath: String? = null,
    val listingTitle: String = "",
    val items: List<BookCard> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val page: Int = 0,
    val hasMore: Boolean = false,
    /** How many of the narrator's books the title filter has looked through so far. */
    val scannedBooks: Int = 0,
    val error: String? = null,
) {
    val readerMode: Boolean get() = selectedReader != null && listingPath == null
}

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var readerLookupJob: Job? = null

    /** Идентификаторы книг, уже отмеченных звездой, чтобы карточка показывала состояние. */
    val favoriteIds: StateFlow<Set<Int>> = container.db.books().observeLibrary()
        .map { rows -> rows.filter { it.favorite }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _favoriteBusy = MutableStateFlow<Set<String>>(emptySet())
    val favoriteBusy: StateFlow<Set<String>> = _favoriteBusy.asStateFlow()

    init {
        // The favourite narrator is the whole point of saving one: it comes preselected.
        val favorite = container.settings.favoriteReader.value
        _state.value = _state.value.copy(
            selectedReader = favorite?.asCard(),
            favoriteSlug = favorite?.slug,
        )
        if (favorite != null) search()
    }

    /**
     * Звезда в выдаче: метаданные и список глав подтягиваются сразу, файлы — нет. У ссылок без
     * числа в адресе id приходится сначала достать со страницы книги.
     */
    fun toggleFavorite(card: BookCard) {
        if (card.isLitres || card.path in _favoriteBusy.value) return
        _favoriteBusy.value = _favoriteBusy.value + card.path
        viewModelScope.launch {
            runCatching {
                val id = card.bookId
                    ?: container.api.resolveBookId(dev.mark.knigavuhe.data.remote.KnigavuheApi.normalizeUrl(card.path))
                if (id in favoriteIds.value) {
                    container.bookRepository.setFavorite(id, false)
                } else {
                    container.bookRepository.addToFavorites(id)
                }
            }
            _favoriteBusy.value = _favoriteBusy.value - card.path
        }
    }

    fun onQueryChange(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    // --- narrator picker -------------------------------------------------------------------

    fun openPicker() {
        _state.value = _state.value.copy(pickerOpen = true)
        if (_state.value.readerQuery.isBlank() && _state.value.readerSuggestions.isEmpty()) {
            lookupReaders("")
        }
    }

    fun closePicker() {
        readerLookupJob?.cancel()
        _state.value = _state.value.copy(pickerOpen = false, readerLoading = false)
    }

    fun onReaderQueryChange(value: String) {
        _state.value = _state.value.copy(readerQuery = value)
        lookupReaders(value)
    }

    private fun lookupReaders(value: String) {
        readerLookupJob?.cancel()
        _state.value = _state.value.copy(readerLoading = true)
        readerLookupJob = viewModelScope.launch {
            delay(READER_DEBOUNCE_MS)
            runCatching { container.api.searchReaders(value) }
                .onSuccess {
                    _state.value = _state.value.copy(readerSuggestions = it.items, readerLoading = false)
                }
                .onFailure {
                    _state.value = _state.value.copy(readerSuggestions = emptyList(), readerLoading = false)
                }
        }
    }

    fun selectReader(reader: ReaderCard) {
        _state.value = _state.value.copy(
            selectedReader = reader,
            pickerOpen = false,
            listingPath = null,
            listingTitle = "",
            items = emptyList(),
        )
        search()
    }

    fun clearReader() {
        _state.value = _state.value.copy(selectedReader = null, items = emptyList(), page = 0, hasMore = false)
        if (_state.value.query.isNotBlank()) search()
    }

    /** Star on the selected narrator: saved as the default filter for next time. */
    fun toggleFavorite() {
        val reader = _state.value.selectedReader ?: return
        val nowFavorite = container.settings.isFavorite(reader.slug)
        container.settings.setFavorite(if (nowFavorite) null else reader)
        _state.value = _state.value.copy(favoriteSlug = container.settings.favoriteReader.value?.slug)
    }

    // --- book search -----------------------------------------------------------------------

    fun search() {
        val current = _state.value
        val reader = current.selectedReader
        if (reader == null && current.query.isBlank()) return
        _state.value = current.copy(
            loading = true,
            error = null,
            items = emptyList(),
            page = 0,
            scannedBooks = 0,
        )
        viewModelScope.launch { loadPage(first = true) }
    }

    /** Author, reader and genre pages reuse the same listing markup. */
    fun openListing(path: String, title: String) {
        _state.value = SearchUiState(
            listingPath = path,
            listingTitle = title,
            favoriteSlug = _state.value.favoriteSlug,
            loading = true,
        )
        viewModelScope.launch { loadPage(first = true) }
    }

    /** Empty result with pages left: continue the scan from where it stopped. */
    fun keepLooking() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch { loadPage(first = false) }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch { loadPage(first = false) }
    }

    private suspend fun loadPage(first: Boolean) {
        val current = _state.value
        val startPage = if (first) 1 else current.page + 1

        val outcome = runCatching {
            when {
                current.listingPath != null -> {
                    val page = container.api.listing(current.listingPath, startPage)
                    Batch(page.items, startPage, page.hasMore)
                }
                current.selectedReader != null -> readerBatch(current, startPage)
                else -> {
                    val page = container.api.search(current.query.trim(), startPage)
                    Batch(page.items, startPage, page.hasMore)
                }
            }
        }

        outcome
            .onSuccess { batch ->
                val known = _state.value.items.mapTo(mutableSetOf()) { it.path }
                val fresh = batch.items.filter { it.path !in known }
                _state.value = _state.value.copy(
                    items = if (first) batch.items else _state.value.items + fresh,
                    page = batch.lastPage,
                    hasMore = batch.hasMore,
                    scannedBooks = batch.scannedBooks,
                    loading = false,
                    loadingMore = false,
                    error = null,
                )
            }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = if (first) error.message ?: "Не удалось загрузить" else null,
                    hasMore = false,
                )
            }
    }

    /**
     * The site cannot search inside one narrator's catalogue, so the title filter is applied here:
     * walk the narrator's pages and keep the titles that match. A prolific narrator has hundreds
     * of books, so one request scans a slice of them and reports how far it got; scrolling (or the
     * "keep looking" button on an empty result) picks up from the next page.
     */
    private suspend fun readerBatch(current: SearchUiState, startPage: Int): Batch {
        val reader = current.selectedReader!!
        val needle = current.query.trim()
        val collected = mutableListOf<BookCard>()
        var page = startPage
        var hasMore = true
        var scanned = 0
        var seen = current.scannedBooks

        while (scanned < MAX_PAGES_PER_BATCH) {
            val result = container.api.listing(reader.path, page)
            scanned++
            if (result.items.isEmpty()) {
                hasMore = false
                break
            }
            seen += result.items.size
            collected += if (needle.isBlank()) {
                result.items
            } else {
                result.items.filter { it.title.contains(needle, ignoreCase = true) }
            }
            page++
            if (needle.isBlank() || collected.size >= MIN_MATCHES) break
            // Nothing yet: show the counter moving instead of a frozen spinner.
            _state.value = _state.value.copy(scannedBooks = seen)
        }

        return Batch(collected, page - 1, hasMore, seen)
    }

    private data class Batch(
        val items: List<BookCard>,
        val lastPage: Int,
        val hasMore: Boolean,
        val scannedBooks: Int = 0,
    )

    private companion object {
        const val READER_DEBOUNCE_MS = 350L
        const val MAX_PAGES_PER_BATCH = 20
        const val MIN_MATCHES = 5
    }
}
