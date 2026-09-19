package dev.mark.knigavuhe

import android.content.Context
import dev.mark.knigavuhe.data.db.AppDatabase
import dev.mark.knigavuhe.data.remote.Http
import dev.mark.knigavuhe.data.remote.KnigavuheApi
import dev.mark.knigavuhe.data.repo.BookRepository
import dev.mark.knigavuhe.data.repo.Settings
import dev.mark.knigavuhe.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Hand-rolled dependency graph; the app is small enough that a DI framework would be overhead. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob())
    val http: OkHttpClient = Http.client
    val db: AppDatabase = AppDatabase.get(appContext)
    val api = KnigavuheApi(http)
    val settings = Settings(appContext)
    val bookRepository = BookRepository(appContext, db, api)
    val playback = PlaybackController(appContext, bookRepository, appScope)
}
