package dev.mark.knigavuhe.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

object Notifications {
    const val DOWNLOAD_CHANNEL = "downloads"
    const val DOWNLOAD_NOTIFICATION_ID = 4201

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL,
            "Загрузка книг",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Прогресс скачивания глав" }
        manager.createNotificationChannel(channel)
    }
}
