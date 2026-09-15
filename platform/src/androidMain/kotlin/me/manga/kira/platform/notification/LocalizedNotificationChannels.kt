package me.manga.kira.platform.notification

import android.app.NotificationChannel
import android.app.NotificationManager

/** Creates an absent channel, or updates only its labels without resetting existing user choices. */
fun NotificationManager.ensureLocalizedChannel(channel: NotificationChannel) {
    val existing = getNotificationChannel(channel.id)
    if (existing == null) {
        createNotificationChannel(channel)
    } else if (existing.name.toString() != channel.name.toString() || existing.description != channel.description) {
        existing.name = channel.name
        existing.description = channel.description
        createNotificationChannel(existing)
    }
}
