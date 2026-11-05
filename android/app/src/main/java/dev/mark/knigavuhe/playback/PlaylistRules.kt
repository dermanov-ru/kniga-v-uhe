package dev.mark.knigavuhe.playback

import dev.mark.knigavuhe.data.db.ChapterEntity

/**
 * Что можно положить в плеер. У knigavuhe — вся книга. У своей книги часть глав ещё
 * озвучивается: играем только непрерывный кусок готовых глав вокруг выбранной, чтобы плеер
 * не перескочил через дыру и не уткнулся в главу без звука.
 */
object PlaylistRules {

    /** Диапазон индексов [first, last] в списке глав или null, если выбранная глава не готова. */
    fun block(chapters: List<ChapterEntity>, index: Int, own: Boolean): IntRange? {
        if (chapters.isEmpty() || index !in chapters.indices) return null
        if (!own) return chapters.indices
        if (!chapters[index].isPlayable) return null
        var first = index
        while (first > 0 && chapters[first - 1].isPlayable) first--
        var last = index
        while (last < chapters.lastIndex && chapters[last + 1].isPlayable) last++
        return first..last
    }

    /** Дослушан конец блока, а дальше есть глава без звука — ждём озвучку, книга не дочитана. */
    fun waitsForNext(chapters: List<ChapterEntity>, block: IntRange): Boolean =
        block.last < chapters.lastIndex && !chapters[block.last + 1].isPlayable
}
