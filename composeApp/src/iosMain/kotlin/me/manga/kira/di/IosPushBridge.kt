package me.manga.kira.di

import me.manga.kira.navigation.push.NotificationRouter
import me.manga.kira.navigation.push.PushPayloadParser
import me.manga.kira.platform.push.PushTokenBroadcaster
import org.koin.mp.KoinPlatform

/**
 * Swift-callable bridge for Firebase push on iOS. Lives in `:composeApp/iosMain` — the same
 * Swift-facing seam as `IosBackgroundBridge` / `IosKoin` — so it can resolve the Koin graph the host
 * bootstrapped via `bootstrapIosKoin()`.
 *
 * APNs + the Firebase Messaging iOS SDK live in the Swift host (`AppDelegate`); this bridge is the
 * inbound seam for the two events the shared layer cares about:
 *  - a rotated FCM registration token → [PushTokenBroadcaster] (feeds `PushTokenProvider`),
 *  - a tapped notification's data payload → parse → [NotificationRouter] (feeds nav deep-linking).
 *
 * Swift call sites:
 * ```swift
 * // MessagingDelegate
 * func messaging(_ m: Messaging, didReceiveRegistrationToken token: String?) {
 *     IosPushBridgeKt.onPushToken(token: token)
 * }
 * // UNUserNotificationCenterDelegate — notification tap
 * func userNotificationCenter(_ c: UNUserNotificationCenter,
 *                             didReceive response: UNNotificationResponse,
 *                             withCompletionHandler completion: @escaping () -> Void) {
 *     IosPushBridgeKt.onNotificationTap(userInfo: response.notification.request.content.userInfo)
 *     completion()
 * }
 * ```
 */

/** Publish a rotated FCM registration token from the Swift `MessagingDelegate`. */
fun onPushToken(token: String?) {
    PushTokenBroadcaster.publish(token)
}

/**
 * Handle a tapped notification's `userInfo`. Coerces the ObjC dictionary to `Map<String, String>`
 * (nested values like the `aps` dictionary are dropped — only top-level string values, which is
 * exactly the FCM data payload, survive), parses a deep link, and submits it to the router that the
 * nav host drains. A payload with no valid deep link is ignored, so the app just opens normally.
 */
fun onNotificationTap(userInfo: Map<Any?, *>) {
    val data = userInfo.entries.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        val value = v as? String ?: return@mapNotNull null
        key to value
    }.toMap()
    val destination = PushPayloadParser.parse(data) ?: return
    KoinPlatform.getKoin().get<NotificationRouter>().submit(destination)
}
