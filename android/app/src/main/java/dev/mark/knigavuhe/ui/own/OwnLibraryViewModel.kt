package dev.mark.knigavuhe.ui.own

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.remote.OwnBook
import dev.mark.knigavuhe.data.remote.OwnConnection
import dev.mark.knigavuhe.data.remote.OwnIds
import dev.mark.knigavuhe.download.DownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OwnLibraryState(
    val loading: Boolean = false,
    val books: List<OwnBook> = emptyList(),
    val error: String? = null,
    val busy: Set<String> = emptySet(),
    val message: String? = null,
)

class OwnLibraryViewModel(private val container: AppContainer) : ViewModel() {

    val connection: StateFlow<OwnConnection?> = container.settings.library

    private val _state = MutableStateFlow(OwnLibraryState())
    val state: StateFlow<OwnLibraryState> = _state.asStateFlow()

    /** id своих книг, которые уже лежат в библиотеке приложения. */
    val added: StateFlow<Set<String>> = container.db.books().observeAll()
        .map { books -> books.mapNotNull { OwnIds.fromPath(it.path) }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        if (connection.value != null) refresh()
    }

    fun refresh() {
        if (connection.value == null) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { container.bookRepository.ownBooks() }
                .onSuccess { _state.value = _state.value.copy(loading = false, books = it) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Не удалось загрузить") }
        }
    }

    /** Вставленная строка подключения. false — строку не разобрать. */
    fun connect(input: String): Boolean {
        val parsed = OwnConnection.parse(input) ?: return false
        container.settings.setLibrary(parsed)
        _state.value = OwnLibraryState(message = "Библиотека подключена")
        refresh()
        return true
    }

    fun disconnect() {
        container.settings.setLibrary(null)
        _state.value = OwnLibraryState()
    }

    /** Кладёт книгу в библиотеку приложения (или дописывает новые главы) и сразу качает. */
    fun add(book: OwnBook, onAdded: (Int) -> Unit = {}) {
        if (book.id in _state.value.busy) return
        _state.value = _state.value.copy(busy = _state.value.busy + book.id)
        viewModelScope.launch {
            runCatching { container.bookRepository.addOwn(book.id) }
                .onSuccess { localId ->
                    DownloadWorker.enqueue(container.appContext, localId)
                    _state.value = _state.value.copy(message = "«${book.title}» в библиотеке, главы качаются")
                    onAdded(localId)
                }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: "Не удалось добавить") }
            _state.value = _state.value.copy(busy = _state.value.busy - book.id)
        }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }
}
