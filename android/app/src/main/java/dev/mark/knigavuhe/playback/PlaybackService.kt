package dev.mark.knigavuhe.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.mark.knigavuhe.App
import dev.mark.knigavuhe.R

/**
 * MediaSession host over the shared player: it is what puts the playback notification in the
 * shade and on the lock screen, and what makes headset buttons work.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as App).container.playback

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_CHANNEL)
                .setChannelName(R.string.playback_channel_name)
                .setNotificationId(PLAYBACK_NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_book) }
        )

        session = MediaSession.Builder(this, controller.player)
            .setSessionActivity(openAppIntent())
            .build()
            // onGetSession only fires when a MediaController connects. Nothing in this app
            // connects one, so without registering the session here the service never learns
            // about it and never posts the playback notification.
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Tapping the notification brings the player back instead of starting a second task. */
    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: Intent()
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val controller = (application as App).container.playback
        controller.saveProgress()
        // Swiping the app away while paused should not leave a dead notification behind.
        if (!controller.player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        (application as App).container.playback.saveProgress()
        session?.release()
        session = null
        super.onDestroy()
    }

    private companion object {
        const val PLAYBACK_CHANNEL = "playback"
        const val PLAYBACK_NOTIFICATION_ID = 4301
    }
}
