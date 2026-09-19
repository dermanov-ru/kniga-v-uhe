package dev.mark.knigavuhe.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.db.BookEntity
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.repo.Storage
import dev.mark.knigavuhe.download.DownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val downloadedCount: Int = 0,
    val downloadingCount: Int = 0,
    val sizeOnDisk: Long = 0,
    val loading: Boolean = true,
)

class BookViewModel(
    private val container: AppContainer,
    private val bookId: Int,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val state: StateFlow<BookUiState> = combine(
        container.bookRepository.observeBook(bookId),
        container.bookRepository.observeChapters(bookId),
    ) { book, chapters ->
        BookUiState(
            book = book,
            chapters = chapters,
            downloadedCount = chapters.count { it.state == ChapterEntity.STATE_DONE },
            downloadingCount = chapters.count { it.state == ChapterEntity.STATE_DOWNLOADING },
            sizeOnDisk = Storage.bookSizeBytes(container.appContext, bookId),
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState())

    /** Called when the screen is opened for a book that is not in the library yet. */
    fun ensureImported() {
        viewModelScope.launch {
            if (container.bookRepository.book(bookId) == null) {
                _busy.value = true
                runCatching { container.bookRepository.importById(bookId) }
                    .onFailure { _error.value = it.message ?: "Не удалось открыть книгу" }
                _busy.value = false
            } else {
                runCatching { container.bookRepository.ensureSeries(bookId) }
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val current = container.bookRepository.book(bookId)?.favorite ?: false
            container.bookRepository.setFavorite(bookId, !current)
        }
    }

    fun download() = DownloadWorker.enqueue(container.appContext, bookId)

    fun cancelDownload() {
        DownloadWorker.cancel(container.appContext, bookId)
        viewModelScope.launch {
            container.db.chapters().retagState(
                bookId, ChapterEntity.STATE_DOWNLOADING, ChapterEntity.STATE_PENDING,
            )
        }
    }

    fun deleteDownloads() {
        DownloadWorker.cancel(container.appContext, bookId)
        viewModelScope.launch { container.bookRepository.deleteDownloads(bookId) }
    }

    fun play(chapterIndex: Int? = null) {
        viewModelScope.launch {
            val chapterId = chapterIndex?.let { state.value.chapters.getOrNull(it)?.id }
            container.playback.open(
                bookId = bookId,
                chapterId = chapterId,
                positionMs = if (chapterIndex != null) 0L else null,
                play = true,
            )
        }
    }
}
