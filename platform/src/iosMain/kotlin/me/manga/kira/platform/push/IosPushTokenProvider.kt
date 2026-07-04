package me.manga.kira.platform.push

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow

/**
 * iOS actual for [PushTokenProvider].
 *
 * The Firebase Messaging iOS SDK lives in the Swift host (linked via SPM), not in this Kotlin
 * framework, so the FCM token arrives from Swift: `MessagingDelegate.messaging(_:didReceiveRegistration
 * Token:)` calls `IosPushBridgeKt.onPushToken(token)`, which publishes into the shared
 * [PushTokenBroadcaster]. This provider is a thin reader over that broadcaster:
 *  - [getToken] returns the last token the host published (or null before APNs registration lands).
 *  - [observeTokens] returns the broadcaster's conflated latest-flow, so a subscriber gets the
 *    current token immediately (if any) and every subsequent rotation.
 *
 * [deleteToken] is a no-op here — FCM token deletion on iOS is a `Messaging.messaging().deleteToken`
 * call owned by the Swift host; there is no delete need wired yet.
 */
class IosPushTokenProvider : PushTokenProvider {

    private val log = Logger.withTag(TAG)

    override suspend fun getToken(): String? {
        val token = PushTokenBroadcaster.lastToken
        log.d { "getToken() — ${if (token != null) "cached token" else "no token yet"}" }
        return token
    }

    override suspend fun deleteToken() {
        log.d { "deleteToken() — no-op on iOS (host owns Messaging.deleteToken)" }
    }

    override fun observeTokens(): Flow<String?> = PushTokenBroadcaster.latestFlow

    private companion object {
        const val TAG = "PushTokenProvider.ios"
    }
}
