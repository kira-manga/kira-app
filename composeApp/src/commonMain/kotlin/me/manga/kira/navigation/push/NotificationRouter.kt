package me.manga.kira.navigation.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A pending deep link plus a monotonically increasing sequence number.
 *
 * The `seq` makes every [NotificationRouter.submit] produce a value that is NOT equal to the
 * previous one, even when the same destination is submitted twice. Without it, re-submitting an
 * equal destination in the narrow window between [NotificationRouter.consume] and the host's
 * collector observing the intervening `null` would be conflated away by `StateFlow` (which only
 * re-emits distinct consecutive values) — leaving the router permanently holding an un-consumed
 * value and the collecting `LaunchedEffect` never restarting (its key never changes). Tab
 * destinations ([PushDestination.Updates]/[PushDestination.Home]) are `data object`s and so are
 * always equal, making that jam trivial to hit without the seq.
 */
data class PendingDeepLink(val seq: Long, val destination: PushDestination)

/**
 * App-scoped bus that carries a pending deep-link from a platform notification/message tap to the
 * navigation host, decoupling the platform edges (Android `MainActivity`, iOS `AppDelegate` bridge)
 * from the `NavController`.
 *
 * Modeled as a [StateFlow] `pending` holder (carrying a seq-tagged [PendingDeepLink]) rather than a
 * `Channel`/`SharedFlow` for two reasons that matter here:
 *  - **Cold-start safe**: a [submit] made before the host starts observing (e.g. from
 *    `MainActivity.onCreate` on a notification cold-launch) is retained as the current value and
 *    delivered as soon as the host collects.
 *  - **Re-collection & config-change safe**: `StateFlow` may be collected repeatedly (Android
 *    activity recreation re-runs the composition), and once the host has navigated it calls
 *    [consume] to clear the value, so the deep link is acted on exactly once and never replays on
 *    rotation.
 *
 * Bound as a Koin `single`; the same instance is resolved by the platform tap handlers and by the
 * navigation host. [submit] is only ever called from the main thread (both the Android and iOS
 * notification-tap handlers dispatch there), so the plain `seq` counter needs no synchronization.
 */
class NotificationRouter {

    private val _pending = MutableStateFlow<PendingDeepLink?>(null)

    /** The deep link awaiting navigation, or `null` when there is nothing pending. */
    val pending: StateFlow<PendingDeepLink?> = _pending.asStateFlow()

    private var seq = 0L

    /** Record a deep link to navigate to. The latest submission wins; each submit is distinct (seq). */
    fun submit(destination: PushDestination) {
        _pending.value = PendingDeepLink(seq++, destination)
    }

    /** Clear the pending deep link after the host has navigated, so it is not acted on again. */
    fun consume() {
        _pending.value = null
    }
}
