package dev.mark.knigavuhe

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.mark.knigavuhe.download.Notifications
import dev.mark.knigavuhe.sync.OwnSync

class App : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        // «Моя библиотека»: синк при старте и фоном раз в 15 минут (без подключения — ничего)
        OwnSync.schedule(this)
        container.ownSync.syncSoon(force = true)
    }

    /** Covers come from the same host as the audio, so they ride the shared OkHttp client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .build()
}
