package me.manga.kira.firebase_cores.messaging

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import me.manga.kira.MainActivity
import me.manga.kira.R
import me.manga.kira.platform.push.PushTokenBroadcaster

/**
 * Android FCM entry point. The manifest routes `com.google.firebase.MESSAGING_EVENT` here, so the
 * Firebase SDK dispatches [onMessageReceived] / [onNewToken] to this class.
 *
 * Display parity with the native app's `MyFirebaseMessagingService` + `NotificationHelper`:
 *  - title/body resolution and fallback strings match native verbatim
 *    (`notification.title ?: data["title"] ?: "Hello"`, likewise for body).
 *  - the notification is posted to the IMPORTANCE_HIGH `firebase_messages` / "App Messages" channel
 *    with the branded `ic_message` small icon and `setAutoCancel(true)`, exactly as native's
 *    `NotificationHelper.showNotification(...)`.
 *
 * The notification is built with `NotificationCompat` directly (mirroring native's helper) rather
 * than via the shared `NotificationPresenter` facade: the Android `NotificationPresenter` actual
 * hardcodes a placeholder small icon (`android.R.drawable.ic_dialog_info`, pending the Phase 10
 * resource migration), so routing FCM through it would lose the branded `ic_message` icon that the
 * manifest's `default_notification_icon` meta-data and native parity both require. Building here in
 * `:app` keeps access to the app `R` class and matches native's pixel-for-pixel notification.
 *
 * This service is instantiated by the OS, not by Koin, so it does not resolve dependencies through
 * the graph. `onNewToken` republishes rotated tokens through [PushTokenBroadcaster] — the documented
 * bridge into the `:platform` `PushTokenProvider.observeTokens()` Flow (native's `onNewToken` left a
 * `// e.g., ApiClient.registerFcmToken(token)` placeholder for the same "feed the token onward"
 * intent; in this codebase that sink is the broadcaster).
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushTokenBroadcaster.publish(token)
    }

    override fun onMessageReceived(remote: RemoteMessage) {
        super.onMessageReceived(remote)

        val title = remote.notification?.title ?: remote.data["title"] ?: "Hello"
        val body = remote.notification?.body ?: remote.data["body"] ?: "You’ve got a message"

        val notificationManager = getSystemService<NotificationManager>() ?: return

        // The channel is created eagerly in MyApp.onCreate (so background-delivered messages get the
        // branded HIGH channel, #12); ensure it here too, idempotently, for the data-message path.
        MessagingNotificationChannels.ensure(this)

        // Tapping the notification opens the app; the data payload is carried as intent extras so
        // MainActivity can parse a deep link (PushPayloadParser) and route via NotificationRouter.
        // For notification-messages tapped from the tray, FCM already delivers `data` as launch-intent
        // extras; for the data-only messages this service builds, we copy them here so both paths
        // converge on MainActivity's extras reader. Without a contentIntent a tap does nothing and
        // setAutoCancel(true) is inert. FLAG_IMMUTABLE is mandatory on API 26+ for a PendingIntent we
        // don't mutate at send time; FLAG_UPDATE_CURRENT refreshes the extras when a newer push reuses
        // this same (requestCode 0) PendingIntent.
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            remote.data.forEach { (key, value) -> putExtra(key, value) }
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, MessagingNotificationChannels.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(pushNotificationId(remote), notification)
    }

    /**
     * Notification id for a push. Push ids live in the NEGATIVE Int space so they can't collide with
     * the download engine's positive `100 + chapterId` band (unbounded Room row ids that cross
     * native's fixed 1000) — there a download progress tick would replace the push, and the worker's
     * `cancel(100 + chapterId)` would dismiss an untapped push (#11). The id varies per FCM message so
     * stacked campaigns don't overwrite each other, falling back to a fixed negative id when the
     * message has no id.
     */
    private fun pushNotificationId(remote: RemoteMessage): Int {
        val raw = remote.messageId?.hashCode() ?: return NOTIF_ID_MESSAGE_FALLBACK
        return -(raw and Int.MAX_VALUE) // mask sign bit → [0, MAX], negate → [-MAX, 0]; disjoint from downloads
    }

    private companion object {
        const val NOTIF_ID_MESSAGE_FALLBACK = -1000
    }
}
