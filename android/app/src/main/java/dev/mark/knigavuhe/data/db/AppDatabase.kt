package dev.mark.knigavuhe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class, PlaybackStateEntity::class, ListenEventEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun playback(): PlaybackDao
    abstract fun listenEvents(): ListenEventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Своя библиотека озвучки: статусы глав и книги на сервере, флаг автодогрузки. */
        val MIGRATION_3_4_SQL = listOf(
            "ALTER TABLE chapters ADD COLUMN remoteState TEXT",
            "ALTER TABLE chapters ADD COLUMN remoteDone INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE chapters ADD COLUMN remoteTotal INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE books ADD COLUMN remoteState TEXT",
            "ALTER TABLE books ADD COLUMN remoteSecondsLeft INTEGER",
            "ALTER TABLE books ADD COLUMN autoDownload INTEGER NOT NULL DEFAULT 0",
        )

        /** Очередь событий прослушивания своих книг (синхронизация между устройствами). */
        val MIGRATION_4_5_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `listen_events` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`book` TEXT NOT NULL, `n` INTEGER NOT NULL, `posMs` INTEGER NOT NULL, `speed` REAL NOT NULL, " +
                "`kind` TEXT NOT NULL, `at` INTEGER NOT NULL)",
        )

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach(db::execSQL)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN seriesSlug TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN seriesIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN seriesTotal INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "knigavuhe.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
