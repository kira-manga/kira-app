package me.manga.kira.platform.notification

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS [DownloadNotifier] backed by `UNUserNotificationCenter`.
 *
 * Per-chapter notifications use a stable identifier (`"dl-$key"`), so each [onProgress] update
 * **replaces** the previous one in place. Progress, finalizing and deferred posts carry a passive
 * interruption level, **no sound** and the `"DOWNLOAD_PROGRESS"` category: list-only even when the
 * app is backgrounded. The foreground delegate (`iosApp/iosApp/AppDelegate.swift`) keeps its list-only
 * policy as defense in depth. [onComplete] / [onFailed] use an active interruption level, the
 * `"DOWNLOAD_DONE"` category and the default sound. System authorization/settings still govern delivery.
 *
 * Text is English-only here — the `:platform`/`:shared` layers have no compose-resources access
 * (same constraint as the Android download worker's English strings); localization is a follow-up.
 *
 * Authorization is requested during onboarding (`NotificationPermissionRequester`); if it was never
 * granted the OS drops the request silently.
 */
class IosDownloadNotifier internal constructor(
    private val deliver: (UNNotificationRequest) -> Unit,
) : DownloadNotifier {

    constructor() : this(
        deliver = { request ->
            UNUserNotificationCenter.currentNotificationCenter()
                .addNotificationRequest(request) { _ -> /* errors swallowed; permission may be absent */ }
        },
    )

    override suspend fun onProgress(key: Int, title: String, current: Int, total: Int) =
        post(key, title, body = "$current/$total pages", category = CATEGORY_PROGRESS, alerting = false)

    // All pages downloaded, CBZ still building (or waiting for a CPU window). Silent — same quiet
    // CATEGORY_PROGRESS presentation as onProgress — so the user sees "being processed", not a premature
    // "complete". Reuses the "dl-$key" id, so it replaces the progress notification in place.
    override suspend fun onFinalizing(key: Int, title: String) =
        post(key, title, body = "Finalizing chapter…", category = CATEGORY_PROGRESS, alerting = false)

    // Pages downloaded + chapter readable, but the CBZ is deferred by Low Power Mode (user opt-out). Silent,
    // in-place (reuses "dl-$key") — the copy reads as a settled "paused", NOT "Finalizing…", so a finished
    // download never looks stuck. onComplete still fires only when the CBZ is actually built (LPM ends / opt-in).
    override suspend fun onFinalizeDeferred(key: Int, title: String) =
        post(key, title, body = "Chapter ready — compression paused (Low Power Mode)", category = CATEGORY_PROGRESS, alerting = false)

    override suspend fun onComplete(key: Int, title: String) =
        post(key, title, body = "Download complete", category = CATEGORY_DONE, alerting = true)

    override suspend fun onFailed(key: Int, title: String) =
        post(key, title, body = "Download failed", category = CATEGORY_DONE, alerting = true)

    override suspend fun clear(key: Int) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val ids = listOf(identifier(key))
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    private fun post(key: Int, title: String, body: String, category: String, alerting: Boolean) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setCategoryIdentifier(category)
            // Omitting sound is not enough: the framework default is active, and willPresent only
            // controls foreground presentation. iOS 15+ content owns the background policy as well.
            setInterruptionLevel(
                if (alerting) UNNotificationInterruptionLevelActive else UNNotificationInterruptionLevelPassive,
            )
            setSound(if (alerting) UNNotificationSound.defaultSound else null)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = identifier(key),
            content = content,
            trigger = null, // deliver immediately
        )
        deliver(request)
    }

    private fun identifier(key: Int): String = "dl-$key"

    private companion object {
        const val CATEGORY_PROGRESS = "DOWNLOAD_PROGRESS"
        const val CATEGORY_DONE = "DOWNLOAD_DONE"
    }
}
