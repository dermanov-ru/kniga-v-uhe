package dev.mark.knigavuhe.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.db.LibraryRow
import kotlinx.coroutines.flow.map
import dev.mark.knigavuhe.download.DownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    val books: StateFlow<List<LibraryRow>> = container.db.books().observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Библиотека списком, где книги одного цикла собраны в блок. Одиночная книга цикла остаётся
     * обычной строкой: сворачивать группу из одного элемента незачем.
     */
    val entries: StateFlow<List<LibraryEntry>> = container.db.books().observeLibrary()
        .map { rows -> groupBySeries(rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Takes a pasted link, a shared text containing one, or a bare book id. */
    fun import(input: String, thenDownload: Boolean, onDone: (Int) -> Unit = {}) {
        if (input.isBlank() || _importing.value) return
        _importing.value = true
        viewModelScope.launch {
            val link = dev.mark.knigavuhe.data.remote.KnigavuheApi.extractLink(input) ?: input
            runCatching { container.bookRepository.import(link) }
                .onSuccess { bookId ->
                    if (thenDownload) DownloadWorker.enqueue(container.appContext, bookId)
                    _message.value = null
                    onDone(bookId)
                }
                .onFailure { _message.value = it.message ?: "Не удалось добавить книгу" }
            _importing.value = false
        }
    }

    fun continueLast(onOpen: (Int) -> Unit) {
        viewModelScope.launch {
            val last = container.bookRepository.lastPlayed() ?: return@launch
            container.playback.open(last.bookId, last.chapterId, last.positionMs, play = true)
            onOpen(last.bookId)
        }
    }

    fun play(bookId: Int) {
        viewModelScope.launch { container.playback.open(bookId, play = true) }
    }

    fun delete(bookId: Int) {
        viewModelScope.launch {
            DownloadWorker.cancel(container.appContext, bookId)
            container.bookRepository.deleteBook(bookId)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        fun groupBySeries(rows: List<LibraryRow>): List<LibraryEntry> {
            val counts = rows.mapNotNull { it.seriesSlug }.groupingBy { it }.eachCount()
            val emitted = mutableSetOf<String>()
            val result = mutableListOf<LibraryEntry>()

            // Порядок задают сами книги: цикл встаёт туда, где стоит его самая свежая книга.
            for (row in rows) {
                val slug = row.seriesSlug
                if (slug == null || (counts[slug] ?: 0) < 2) {
                    result += LibraryEntry.Single(row)
                    continue
                }
                if (!emitted.add(slug)) continue
                val members = rows.filter { it.seriesSlug == slug }.sortedBy { it.seriesIndex }
                result += LibraryEntry.Series(
                    slug = slug,
                    name = members.firstNotNullOfOrNull { it.seriesName } ?: slug,
                    rows = members,
                )
            }
            return result
        }
    }
}

sealed interface LibraryEntry {
    data class Single(val row: LibraryRow) : LibraryEntry

    data class Series(
        val slug: String,
        val name: String,
        val rows: List<LibraryRow>,
    ) : LibraryEntry {
        val totalMs: Long get() = rows.sumOf { it.totalDurationMs }
        val elapsedMs: Long get() = rows.sumOf { it.elapsedMs }
        val fraction: Float
            get() = if (totalMs > 0) (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
        val downloaded: Int get() = rows.count { it.chapterCount > 0 && it.doneCount >= it.chapterCount }
        /** Сколько книг цикла вообще существует, по данным любой из уже добавленных. */
        val known: Int get() = rows.maxOf { it.seriesTotal }.coerceAtLeast(rows.size)
    }
}
