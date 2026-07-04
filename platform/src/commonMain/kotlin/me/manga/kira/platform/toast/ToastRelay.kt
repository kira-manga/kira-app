package me.manga.kira.platform.toast

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A toast request for platforms with no native toast (iOS/Desktop), surfaced by a UI host. */
data class ToastEvent(val message: String, val long: Boolean)

/**
 * Process-wide relay that lets the no-native-toast platforms route [ToastShower] calls to a
 * visible Compose surface (a `SnackbarHost` at the app root). Android uses `android.widget.Toast`
 * directly and never posts here, so the root host stays inert there (no double toast).
 *
 * [post] is non-blocking ([MutableSharedFlow.tryEmit] with a small buffer that drops the oldest
 * under a burst); `replay = 0` so a freshly-subscribed host never re-shows a stale message.
 */
object ToastRelay {
    private val _events = MutableSharedFlow<ToastEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ToastEvent> = _events

    fun post(message: String, long: Boolean) {
        _events.tryEmit(ToastEvent(message, long))
    }
}
