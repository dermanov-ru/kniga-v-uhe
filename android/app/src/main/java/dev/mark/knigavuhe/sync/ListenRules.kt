package dev.mark.knigavuhe.sync

import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.remote.ListenState
import dev.mark.knigavuhe.data.remote.ListenWhere
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Что делать по «Играть» для своей книги — по ответу сервера `GET /v1/listen/{book}`. */
sealed interface PlayAction {
    /** Играть со своего места. */
    data object Local : PlayAction

    /** Последнее место — с другого устройства: прыгнуть туда (сервер уже отнял 60 с). */
    data class Jump(val n: Int, val posMs: Long, val device: String) : PlayAction

    /** Сейчас играет на другом устройстве — спросить, перехватывать ли. */
    data class Ask(val device: String) : PlayAction
}

object ListenRules {

    /**
     * where == null — нет сети или сервер молчал 2 с: играем со своего места.
     * explicit — человек сам выбрал главу: прыгать на чужое место не надо, но спросить
     * про играющее другое устройство всё равно надо, иначе сервер тут же остановит нас.
     */
    fun decide(where: ListenWhere?, explicit: Boolean): PlayAction {
        val state = where?.state ?: return PlayAction.Local
        return when (where.advice) {
            "ask" -> PlayAction.Ask(state.device_name.ifBlank { "другом устройстве" })
            "jump" -> if (explicit) PlayAction.Local
                else PlayAction.Jump(state.jump_n ?: state.n, state.jump_pos_ms, state.device_name)
            else -> PlayAction.Local
        }
    }

    /**
     * Глава n (у своей книги position = номер на сервере). Не готова у нас — ближайшая готовая
     * назад; second = true, если пришлось отступить.
     */
    fun resolve(chapters: List<ChapterEntity>, n: Int): Pair<ChapterEntity, Boolean>? {
        chapters.firstOrNull { it.position == n && it.isPlayable }?.let { return it to false }
        val back = chapters.filter { it.position < n && it.isPlayable }.maxByOrNull { it.position }
            ?: chapters.firstOrNull { it.isPlayable }
            ?: return null
        return back to true
    }

    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** «Последний раз: Huawei P30 · глава 5, 12:40 · сегодня 21:10». */
    fun lastLine(state: ListenState, me: String = "", now: Long = System.currentTimeMillis()): String {
        val whenText = whenText((state.at * 1000).toLong(), now)
        val playing = if (state.active) " · играет сейчас" else ""
        val mine = if (me.isNotEmpty() && state.device == me) " (это устройство)" else ""
        return "Последний раз: ${state.device_name}$mine · глава ${state.n}, ${clock(state.pos_ms)} · $whenText$playing"
    }

    /**
     * Номер события: уникален для устройства навсегда — сервер молча пропускает повтор
     * (устройство, seq). Растёт от текущего времени в мс, поэтому после очистки данных или
     * переустановки не повторит старые номера.
     */
    fun nextSeq(lastSeq: Long, nowMs: Long): Long = maxOf(nowMs, lastSeq + 1)

    fun whenText(atMs: Long, now: Long): String {
        val at = Calendar.getInstance().apply { timeInMillis = atMs }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val time = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(atMs))
        val days = dayNumber(today) - dayNumber(at)
        return when (days) {
            0L -> "сегодня $time"
            1L -> "вчера $time"
            else -> SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(atMs))
        }
    }

    private fun dayNumber(c: Calendar): Long =
        (c.timeInMillis + c.timeZone.getOffset(c.timeInMillis)) / 86_400_000L
}
