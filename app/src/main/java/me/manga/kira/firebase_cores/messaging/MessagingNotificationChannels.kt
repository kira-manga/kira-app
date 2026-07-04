package me.manga.kira.firebase_cores.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * The FCM "App Messages" notification channel, centralized so it can be created EAGERLY at app start
 * (from `MyApp.onCreate`, mirroring native `NotificationHelper.init`).
 *
 * Why eager matters: background-delivered notification-messages are posted by the FCM SDK directly,
 * WITHOUT invoking [MyFirebaseMessagingService]. If the channel were created only inside
 * `onMessageReceived` (which never runs for those), a campaign delivered before the app first
 * receives a push in the foreground would land on FCM's auto-created, unbranded "Miscellaneous"
 * fallback channel at default importance (no heads-up), and the message type would be split across
 * two channels (#12). The manifest additionally points
 * `com.google.firebase.messaging.default_notification_channel_id` at [MESSAGES_CHANNEL_ID].
 */
internal object MessagingNotificationChannels {

    /** Must stay in sync with the AndroidManifest `default_notification_channel_id` meta-data value. */
    const val MESSAGES_CHANNEL_ID = "firebase_messages"
    private const val MESSAGES_CHANNEL_NAME = "App Messages"

    /**
     * Create the IMPORTANCE_HIGH message channel. Idempotent — `createNotificationChannel` is a no-op
     * when a channel with the same id already exists.
     */
    fun ensure(context: Context) {
        val notificationManager = context.getSystemService<NotificationManager>() ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                MESSAGES_CHANNEL_ID,
                MESSAGES_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
