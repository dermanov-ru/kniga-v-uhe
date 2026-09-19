package dev.mark.knigavuhe.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.db.LibraryRow
import dev.mark.knigavuhe.data.repo.Storage
import dev.mark.knigavuhe.download.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StorageSort(val label: String) {
    Size("По размеру"),
    Duration("По длительности"),
}

data class StorageEntry(
    val row: LibraryRow,
    val bytes: Long,
)

data class StorageUiState(
    val entries: List<StorageEntry> = emptyList(),
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val sort: StorageSort = StorageSort.Size,
    val loading: Boolean = true,
)

class StorageViewModel(private val container: AppContainer) : ViewModel() {

    private val sort = MutableStateFlow(StorageSort.Size)
    private val sizes = MutableStateFlow<Map<Int, Long>>(emptyMap())
    private val totals = MutableStateFlow(0L to 0L)
    private var knownIds: List<Int> = emptyList()

    val state: StateFlow<StorageUiState> = combine(
        container.db.books().observeLibrary(),
        sizes,
        sort,
        totals,
    ) { rows, bytes, order, (total, free) ->
        val entries = rows.map { StorageEntry(it, bytes[it.id] ?: 0L) }
        StorageUiState(
            entries = entries.sortedWith(
                when (order) {
                    // Книги без файлов уходят вниз: в разделе про место они не интересны.
                    StorageSort.Size -> compareByDescending<StorageEntry> { it.bytes }
                        .thenByDescending { it.row.totalDurationMs }
                    StorageSort.Duration -> compareByDescending<StorageEntry> { it.row.totalDurationMs }
                        .thenByDescending { it.bytes }
                }
            ),
            totalBytes = total,
            freeBytes = free,
            sort = order,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    init {
        // Размеры на диске меняются на ходу, пока идёт закачка, поэтому меряем при каждом
        // изменении состава библиотеки и по запросу.
        viewModelScope.launch {
            container.db.books().observeLibrary().collect { rows ->
                knownIds = rows.map { it.id }
                measure()
            }
        }
    }

    fun setSort(value: StorageSort) {
        sort.value = value
    }

    fun refresh() {
        viewModelScope.launch { measure() }
    }

    private suspend fun measure() {
        val context = container.appContext
        val ids = knownIds
        val measured = withContext(Dispatchers.IO) {
            val perBook = ids.associateWith { Storage.bookSizeBytes(context, it) }
            perBook to (Storage.totalBytes(context) to Storage.freeBytes(context))
        }
        sizes.value = measured.first
        totals.value = measured.second
    }

    /** Удаляет только аудио: книга, позиция и избранное остаются. */
    fun freeSpace(bookId: Int) {
        viewModelScope.launch {
            DownloadWorker.cancel(container.appContext, bookId)
            container.bookRepository.deleteDownloads(bookId)
            measure()
        }
    }

    fun deleteBook(bookId: Int) {
        viewModelScope.launch {
            DownloadWorker.cancel(container.appContext, bookId)
            container.bookRepository.deleteBook(bookId)
            measure()
        }
    }
}
