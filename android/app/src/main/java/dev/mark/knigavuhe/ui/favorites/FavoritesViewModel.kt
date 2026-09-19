package dev.mark.knigavuhe.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.db.LibraryRow
import dev.mark.knigavuhe.download.DownloadWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val pending: List<LibraryRow> = emptyList(),
    val downloaded: List<LibraryRow> = emptyList(),
    val loading: Boolean = true,
)

class FavoritesViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<FavoritesUiState> = container.db.books().observeLibrary()
        .map { rows ->
            val favorites = rows.filter { it.favorite }
            FavoritesUiState(
                // Смысл списка — «что послушать дальше», поэтому нескачанное идёт первым.
                pending = favorites.filter { it.doneCount < it.chapterCount },
                downloaded = favorites.filter { it.doneCount >= it.chapterCount && it.chapterCount > 0 },
                loading = false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    fun download(bookId: Int) = DownloadWorker.enqueue(container.appContext, bookId)

    fun remove(bookId: Int) {
        viewModelScope.launch { container.bookRepository.setFavorite(bookId, false) }
    }

    fun play(bookId: Int) {
        viewModelScope.launch { container.playback.open(bookId, play = true) }
    }
}
