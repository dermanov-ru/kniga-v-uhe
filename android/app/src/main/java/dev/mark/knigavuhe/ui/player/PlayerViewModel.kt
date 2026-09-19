package dev.mark.knigavuhe.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mark.knigavuhe.AppContainer
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.playback.PlaybackController
import dev.mark.knigavuhe.playback.PlaybackUiState
import dev.mark.knigavuhe.playback.SleepOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<PlaybackUiState> = container.playback.state

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val chapters: StateFlow<List<ChapterEntity>> = container.playback.state
        .map { it.bookId }
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else container.bookRepository.observeChapters(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sleepSheet = MutableStateFlow(false)
    val sleepSheet: StateFlow<Boolean> = _sleepSheet.asStateFlow()

    fun clearFinished() = container.playback.clearFinished()

    fun playPause() = container.playback.playPause()
    fun skipBack() = container.playback.skip(-PlaybackController.SKIP_BACK_MS)
    fun skipForward() = container.playback.skip(PlaybackController.SKIP_FORWARD_MS)
    fun next() = container.playback.nextChapter()
    fun previous() = container.playback.previousChapter()
    fun seek(ms: Long) = container.playback.seekTo(ms)
    fun setSpeed(speed: Float) = container.playback.setSpeed(speed)
    fun setSleep(option: SleepOption) = container.playback.setSleep(option)
    fun playChapter(index: Int) = container.playback.playChapter(index)
    fun showSleepSheet(show: Boolean) { _sleepSheet.value = show }

    /** Restores the last book into the player on a cold start, paused and ready. */
    fun restoreIfEmpty() {
        if (state.value.bookId != null) return
        viewModelScope.launch { container.playback.openLastPlayed(play = false) }
    }
}
