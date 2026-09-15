package me.manga.kira.locale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Parcel
import org.junit.Assert.assertEquals

/** Seeds user-owned state before an owner builds; Parcel prevents the shadow's reference aliasing. */
internal fun NotificationManager.seedUserChannel(id: String, importance: Int): NotificationChannel {
    createNotificationChannelGroup(NotificationChannelGroup("owner_group", "Owner group"))
    val channel = NotificationChannel(id, "Owner label", importance).apply {
        description = "Owner description"
        group = "owner_group"
        setSound(
            Uri.parse("content://locale-test/user-sound"),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
        )
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 130, 270)
        enableLights(true)
        lightColor = 0xFF336699.toInt()
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
    }
    createNotificationChannel(channel)
    val parcel = Parcel.obtain()
    return try {
        checkNotNull(getNotificationChannel(id)).writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        NotificationChannel.CREATOR.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}

/** Checks every channel equality field, not just importance; this is still only a shadow contract. */
internal fun NotificationManager.assertOnlyChannelLabelsChanged(
    before: NotificationChannel,
    name: String,
    description: String,
) {
    before.name = name
    before.description = description
    assertEquals(before, getNotificationChannel(before.id))
}
