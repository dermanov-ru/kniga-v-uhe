package dev.mark.knigavuhe.playback

data class PlaybackUiState(
    val bookId: Int? = null,
    val bookTitle: String = "",
    val authors: String = "",
    val cover: String? = null,
    val chapterId: Int = 0,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bookElapsedMs: Long = 0,
    val bookTotalMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val speed: Float = 1f,
    val speedLevels: List<Float> = emptyList(),
    val sleep: SleepState = SleepState.Off,
    val error: String? = null,
    /** Книга доиграна до конца — повод предложить следующую в цикле. */
    val finishedBookId: Int? = null,
)

sealed interface SleepState {
    data object Off : SleepState
    data class Countdown(val remainingMs: Long) : SleepState
    data object EndOfChapter : SleepState
}

sealed interface SleepOption {
    data class Minutes(val value: Int) : SleepOption
    data object EndOfChapter : SleepOption
    data object Off : SleepOption

    companion object {
        val presets = listOf(Minutes(15), Minutes(30), Minutes(45), Minutes(60), EndOfChapter)
    }
}
