package dev.mark.knigavuhe

import dev.mark.knigavuhe.data.db.AppDatabase
import dev.mark.knigavuhe.data.db.ChapterEntity
import dev.mark.knigavuhe.data.remote.OwnLibraryApi
import dev.mark.knigavuhe.playback.PlaylistRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class OwnLiveSyncTest {

    private val api = OwnLibraryApi(okhttp3.OkHttpClient())

    @Test
    fun parsesManifestAllWithStatuses() {
        val raw = """{"id":"a970dc9b1b94","title":"Собор","authors":["И. Измайлова"],"state":"running",
            "chapters_total":4,"chapters_done":1,"chapters_queued":2,"progress":0.31,"seconds_left":5400,
            "running":{"n":3,"title":"Глава 2","done":12,"total":30},"links_expire":1794496264,
            "chapters":[
              {"n":2,"title":"Глава 1","state":"done","done":25,"total":25,"duration":388.6,"size":3100000,
               "url":"/v1/library/files/a970dc9b1b94/2.mp3?exp=1&sig=a"},
              {"n":3,"title":"Глава 2","state":"running","done":12,"total":30,"duration":null,"size":null},
              {"n":4,"title":"Глава 3","state":"queued","done":0,"total":0},
              {"n":5,"title":"Глава 4","state":"new"}]}"""
        val m = api.parseManifest(raw)
        assertTrue(m.isVoicing)
        assertEquals(5400, m.seconds_left)
        assertEquals(2, m.chapters_queued)
        assertEquals(3, m.running!!.n)
        assertEquals(listOf("done", "running", "queued", "new"), m.chapters.map { it.state })
        assertEquals(12, m.chapters[1].done)
        assertEquals(30, m.chapters[1].total)
        assertNull(m.chapters[1].url)
        assertNull(m.chapters[3].duration)
    }

    @Test
    fun oldManifestWithoutStatesMeansReady() {
        val m = api.parseManifest("""{"id":"abc","chapters":[{"n":1,"url":"/x.mp3"}]}""")
        assertEquals("done", m.chapters[0].state)
        assertNull(m.running)
    }

    private fun ch(n: Int, remote: String?, url: String = "u$n", downloaded: Boolean = false) = ChapterEntity(
        id = -n, bookId = -1, position = n, title = "$n", url = if (remote == null || remote == "done") url else "",
        durationMs = 1000, state = if (downloaded) ChapterEntity.STATE_DONE else ChapterEntity.STATE_PENDING,
        remoteState = remote,
    )

    @Test
    fun playableRules() {
        assertTrue(ch(1, null).isPlayable)                      // knigavuhe
        assertTrue(ch(1, "done").isPlayable)
        assertFalse(ch(1, "running").isPlayable)
        assertFalse(ch(1, "queued").isPlayable)
        // пересчитывают на сервере, а файл уже скачан — играем скачанное
        assertTrue(ch(1, "running", downloaded = true).isPlayable)
    }

    @Test
    fun ownPlaylistStopsAtFirstUnreadyChapter() {
        val list = listOf(ch(1, "done"), ch(2, "done"), ch(3, "running"), ch(4, "done"), ch(5, "queued"))
        assertEquals(0..1, PlaylistRules.block(list, 0, own = true))
        assertEquals(0..1, PlaylistRules.block(list, 1, own = true))
        assertNull(PlaylistRules.block(list, 2, own = true))     // тап по неготовой — не играем
        assertEquals(3..3, PlaylistRules.block(list, 3, own = true))
        assertTrue(PlaylistRules.waitsForNext(list, 0..1))
        assertTrue(PlaylistRules.waitsForNext(list, 3..3))
        val ready = list.map { if (it.remoteState == "done") it else it.copy(remoteState = "done", url = "x") }
        assertEquals(0..4, PlaylistRules.block(ready, 2, own = true))
        assertFalse(PlaylistRules.waitsForNext(ready, 0..4))     // дослушана целиком
    }

    @Test
    fun knigavuhePlaylistIsWholeBook() {
        val list = listOf(ch(1, null), ch(2, null), ch(3, null))
        assertEquals(0..2, PlaylistRules.block(list, 1, own = false))
    }

    /** Миграция 3→4 на настоящем SQLite: схема 3 + ALTER-ы = схема 4, данные целы. */
    @Test
    fun migration3to4KeepsDataAndMatchesSchema4() {
        fun schema(v: Int) = Json.parseToJsonElement(File("schemas/dev.mark.knigavuhe.data.db.AppDatabase/$v.json").readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                schema(3).forEach { e ->
                    val name = e.jsonObject["tableName"]!!.jsonPrimitive.content
                    st.execute(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
                st.execute("INSERT INTO books(id,title,path,authors,readers,series,cover,totalDurationMs,chapterCount,addedAt," +
                    "urlsFetchedAt,speedLevels,favorite,seriesSlug,seriesIndex,seriesTotal) " +
                    "VALUES (5230,'Гарри','/book/5230/','Роулинг','Чтец',null,null,1000,1,1,1,'1',1,null,0,0)")
                st.execute("INSERT INTO chapters(id,bookId,position,title,url,durationMs,sizeBytes,downloadedBytes,state) " +
                    "VALUES (77,5230,0,'Глава','http://x',1000,10,10,2)")
                AppDatabase.MIGRATION_3_4_SQL.forEach { st.execute(it) }

                schema(4).forEach { e ->
                    val name = e.jsonObject["tableName"]!!.jsonPrimitive.content
                    val expected = e.jsonObject["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
                    val actual = mutableSetOf<String>()
                    st.executeQuery("PRAGMA table_info(`$name`)").use { rs -> while (rs.next()) actual += rs.getString("name") }
                    assertEquals("колонки $name", expected, actual)
                }
                st.executeQuery("SELECT title, favorite, autoDownload, remoteState FROM books WHERE id = 5230").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Гарри", rs.getString(1))
                    assertEquals(1, rs.getInt(2))
                    assertEquals(0, rs.getInt(3))
                    assertNull(rs.getString(4))
                }
                st.executeQuery("SELECT state, remoteDone, remoteTotal, remoteState FROM chapters WHERE id = 77").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt(1))
                    assertEquals(0, rs.getInt(2))
                    assertEquals(0, rs.getInt(3))
                    assertNull(rs.getString(4))
                }
            }
        }
    }
}
