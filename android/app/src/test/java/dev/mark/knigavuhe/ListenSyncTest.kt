package dev.mark.knigavuhe

import dev.mark.knigavuhe.data.db.AppDatabase
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.remote.ListenApi
import dev.mark.knigavuhe.data.remote.ListenEvent
import dev.mark.knigavuhe.data.remote.ListenState
import dev.mark.knigavuhe.sync.ListenRules
import dev.mark.knigavuhe.sync.PlayAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.util.Calendar

class ListenSyncTest {

    private val api = ListenApi(okhttp3.OkHttpClient())

    private fun where(advice: String, device: String = "HUAWEI P30", n: Int = 5, jump: Long = 700_000) = api.parseWhere(
        """{"state":{"book":"a970dc9b1b94","n":$n,"pos_ms":760000,"pos_now_ms":790000,"speed":1.0,
            "device":"d:abc","device_name":"$device","at":1793900000.5,"playing":true,"active":true,
            "jump_n":${n - 1},"jump_pos_ms":$jump},"advice":"$advice","step_back_ms":60000,"me":"d:me"}"""
    )

    @Test
    fun decideByAdvice() {
        assertEquals(PlayAction.Local, ListenRules.decide(null, explicit = false))           // нет сети / 2 с
        assertEquals(PlayAction.Local, ListenRules.decide(api.parseWhere("""{"state":null,"advice":"local"}"""), false))
        assertEquals(PlayAction.Local, ListenRules.decide(where("local"), false))
        // отступ 60 с перешёл через границу главы: прыгаем на jump_n, а не на n
        assertEquals(PlayAction.Jump(4, 700_000, "HUAWEI P30", 5, 760_000), ListenRules.decide(where("jump"), false))
        assertEquals(PlayAction.Ask("HUAWEI P30"), ListenRules.decide(where("ask"), false))
        // сам выбрал главу: на чужое место не прыгаем, но про играющее устройство спрашиваем
        assertEquals(PlayAction.Local, ListenRules.decide(where("jump"), explicit = true))
        assertEquals(PlayAction.Ask("HUAWEI P30"), ListenRules.decide(where("ask"), explicit = true))
        assertEquals(PlayAction.Ask("другом устройстве"), ListenRules.decide(where("ask", device = ""), false))
    }

    @Test
    fun seqNeverRepeats() {
        // пустое хранилище (очистка данных) — старт от времени, больше любых прежних номеров
        assertEquals(1_793_900_000_000, ListenRules.nextSeq(0, 1_793_900_000_000))
        // несколько событий в одну миллисекунду и часы, ушедшие назад, — всё равно растёт
        assertEquals(1_793_900_000_001, ListenRules.nextSeq(1_793_900_000_000, 1_793_900_000_000))
        assertEquals(1_793_900_000_001, ListenRules.nextSeq(1_793_900_000_000, 1_700_000_000_000))
    }

    @Test
    fun parsesReplies() {
        val reply = api.parseEventsReply(
            """{"accepted":[1,2,3],"states":{"a970dc9b1b94":{"n":2,"pos_ms":5000,"device_name":"Браузер · Linux",
               "at":1.0,"playing":true,"active":true},"b":null},"stop":["a970dc9b1b94"]}"""
        )
        assertEquals(listOf(1L, 2L, 3L), reply.accepted)
        assertEquals("Браузер · Linux", reply.states["a970dc9b1b94"]!!.device_name)
        assertNull(reply.states["b"])
        assertEquals(listOf("a970dc9b1b94"), reply.stop)
        val w = where("ask")
        assertEquals(790_000, w.state!!.pos_now_ms)
        assertTrue(w.state!!.active)
        val sent = api.encode(listOf(ListenEvent("a970dc9b1b94", 2, 1234, 1.25f, "tick", 1793900000000, 7)))
        assertTrue(sent.contains("\"pos_ms\":1234") && sent.contains("\"seq\":7") && sent.contains("\"kind\":\"tick\""))
    }

    private fun ch(n: Int, ready: Boolean) = ChapterEntity(
        id = -n, bookId = -1, position = n, title = "$n", url = if (ready) "u" else "", durationMs = 1,
        remoteState = if (ready) "done" else "queued",
    )

    @Test
    fun resolveChapterFallsBackToNearestReady() {
        val list = listOf(ch(2, true), ch(3, true), ch(4, false), ch(5, false))
        assertEquals(3 to false, ListenRules.resolve(list, 3)!!.let { it.first.position to it.second })
        assertEquals(3 to true, ListenRules.resolve(list, 5)!!.let { it.first.position to it.second })
        assertEquals(2 to true, ListenRules.resolve(list, 1)!!.let { it.first.position to it.second })
        assertNull(ListenRules.resolve(listOf(ch(2, false)), 2))
    }

    @Test
    fun formatsLastLine() {
        assertEquals("12:40", ListenRules.clock(760_000))
        assertEquals("1:02:03", ListenRules.clock(3_723_000))
        val now = Calendar.getInstance().apply { set(2026, 10, 5, 22, 0) }.timeInMillis
        val at = Calendar.getInstance().apply { set(2026, 10, 5, 21, 10) }.timeInMillis
        val line = ListenRules.lastLine(ListenState(n = 5, pos_ms = 760_000, device_name = "HUAWEI P30", at = at / 1000.0), now = now)
        assertEquals("Последний раз: HUAWEI P30 · глава 5, 12:40 · сегодня 21:10", line)
        val mine = ListenRules.lastLine(ListenState(n = 5, pos_ms = 760_000, device = "d:me", device_name = "Pixel",
            at = at / 1000.0), me = "d:me", now = now)
        assertEquals("Последний раз: Pixel (это устройство) · глава 5, 12:40 · сегодня 21:10", mine)
        val yesterday = Calendar.getInstance().apply { set(2026, 10, 4, 9, 5) }.timeInMillis
        assertEquals("вчера 09:05", ListenRules.whenText(yesterday, now))
    }

    /** Миграция 4→5 и очередь: seq растёт, подтверждённые удаляются, старые данные целы. */
    @Test
    fun migration4to5AndQueue() {
        fun entities(v: Int) = Json.parseToJsonElement(File("schemas/dev.mark.knigavuhe.data.db.AppDatabase/$v.json").readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                entities(4).forEach { e ->
                    val name = e.jsonObject["tableName"]!!.jsonPrimitive.content
                    st.execute(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
                st.execute("INSERT INTO playback_state(bookId,chapterId,positionMs,speed,updatedAt) VALUES (-5,-5001,42,1.0,1)")
                AppDatabase.MIGRATION_4_5_SQL.forEach { st.execute(it) }
                entities(5).forEach { e ->
                    val name = e.jsonObject["tableName"]!!.jsonPrimitive.content
                    val expected = e.jsonObject["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
                    val actual = mutableSetOf<String>()
                    st.executeQuery("PRAGMA table_info(`$name`)").use { rs -> while (rs.next()) actual += rs.getString("name") }
                    assertEquals("колонки $name", expected, actual)
                }
                repeat(3) { i ->
                    st.execute("INSERT INTO listen_events(book,n,posMs,speed,kind,at) VALUES ('a970dc9b1b94',2,${i * 1000},1.0,'tick',$i)")
                }
                st.execute("DELETE FROM listen_events WHERE seq IN (1,2)")
                st.execute("INSERT INTO listen_events(book,n,posMs,speed,kind,at) VALUES ('a970dc9b1b94',3,0,1.0,'chapter',9)")
                val seqs = mutableListOf<Long>()
                st.executeQuery("SELECT seq FROM listen_events ORDER BY seq").use { rs -> while (rs.next()) seqs += rs.getLong(1) }
                assertEquals(listOf(3L, 4L), seqs)   // номера не переиспользуются: сервер отсеивает повторы по seq
                st.executeQuery("SELECT positionMs FROM playback_state WHERE bookId = -5").use { rs ->
                    assertTrue(rs.next()); assertEquals(42, rs.getInt(1))
                }
            }
        }
    }
}
