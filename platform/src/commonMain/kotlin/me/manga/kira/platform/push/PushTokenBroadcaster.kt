package me.manga.kira.platform.push

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Module-level broadcaster bridging platform FCM token callbacks into the
 * [PushTokenProvider.observeTokens] Flow. Hoisted to commonMain (strangler-era it lived in
 * `AndroidPushTokenProvider`) so both platform providers share one seam:
 *  - Android: `MyFirebaseMessagingService.onNewToken` calls [publish].
 *  - iOS: the Swift host's `MessagingDelegate.didReceiveRegistrationToken` calls [publish] through
 *    the `IosPushBridge`.
 *
 * Kept as a top-level `object` (not a Koin binding) so it is reachable from the OS-instantiated
 * Android FCM service, which lives outside the Koin graph.
 */
object PushTokenBroadcaster {

    private val tokenChannel = MutableSharedFlow<String?>(extraBufferCapacity = BUFFER_CAPACITY)
    private val _latest = MutableStateFlow<String?>(null)

    /**
     * Hot rotation stream (replay 0). The Android provider seeds an initial value itself via a
     * synchronous `FirebaseMessaging.getInstance().token` fetch, then forwards subsequent rotations
     * published here — so this must NOT replay, or the initial value would double-emit.
     */
    val tokens: Flow<String?> = tokenChannel

    /**
     * Conflated latest-token holder that replays the current token to late subscribers. Used by iOS,
     * which has no synchronous FCM fetch: its `observeTokens()` returns this and its `getToken()`
     * reads [lastToken].
     */
    val latestFlow: StateFlow<String?> = _latest.asStateFlow()

    /** The most recently published token, or `null` if none has arrived yet. */
    val lastToken: String? get() = _latest.value

    fun publish(token: String?) {
        _latest.value = token
        tokenChannel.tryEmit(token)
    }

    private const val BUFFER_CAPACITY = 4
}
