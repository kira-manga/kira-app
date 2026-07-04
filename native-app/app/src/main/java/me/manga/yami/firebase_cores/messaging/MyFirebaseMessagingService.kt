package me.manga.yamiapk.firebase_cores.messaging

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import me.manga.yamiapk.core.util.notification.NotificationHelper
import javax.inject.Inject

@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var helper: NotificationHelper

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // e.g., ApiClient.registerFcmToken(token)
    }

    override fun onMessageReceived(remote: RemoteMessage) {
        super.onMessageReceived(remote)

        val title = remote.notification?.title ?: remote.data["title"] ?: "Hello"
        val body  = remote.notification?.body  ?: remote.data["body"]  ?: "You’ve got a message"


        helper.showNotification(this, title, body)
    }
}