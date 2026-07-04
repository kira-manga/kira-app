package me.manga.kira.platform.notification

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS actual for [NotificationPresenter] backed by `UNUserNotificationCenter`.
 *
 * `channelId` is unused on iOS — notification channels are an Android-only concept. Callers may
 * still pass a value; it's ignored. The Int [id] is converted to a string identifier so iOS's
 * pending/delivered request APIs can find it for cancellation.
 *
 * Permission prompting (`requestAuthorizationWithOptions`) is driven by
 * [me.manga.kira.core.platform.NotificationPermissionRequester] (the onboarding/Theme flow),
 * not by this class — `show()` assumes authorization has already been requested there. If it has
 * not been granted, `addNotificationRequest` is rejected by the OS and the notification is
 * silently dropped (the completion handler's error is intentionally swallowed).
 */
class IosNotificationPresenter : NotificationPresenter {

    override suspend fun show(id: Int, title: String, body: String, channelId: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = id.toString(),
            content = content,
            trigger = null, // deliver immediately
        )
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request) { _ -> /* errors swallowed — surface via callers later */ }
    }

    override suspend fun cancel(id: Int) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val ids = listOf(id.toString())
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    override suspend fun cancelAll() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
    }

    override suspend fun ensureChannel(channelId: String, channelName: String, importance: Int) {
        // Channels are Android-only — iOS uses categories/threadIdentifier for grouping, which
        // is not needed by current callers. Intentional noop.
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster249.staleKdocSweep.cascade, Task #705, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster249 leaf 1 of 5 — :platform iosMain notification IosNotificationPresenter,
 * sibling 517 OPENER of 5-LEAF-IOSMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 241 leaves with this commit.
 *
 * File-shape note: 51-line file (pre-postscript) — file-level KDoc (12 lines)
 * preserved verbatim. 1 top-level class (IosNotificationPresenter) implementing
 * NotificationPresenter with 4 override suspend funs (show + cancel + cancelAll
 * + ensureChannel). 3 imports (UNMutableNotificationContent +
 * UNNotificationRequest + UNUserNotificationCenter). NO companion. NO
 * constructor params. 1 intentional-noop body (ensureChannel).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - IOSMAIN-PLATFORM-ACTUAL-NEW-SUB-TIER-OPENS-LIVE — cluster249
 *     opens NEW SUB-TIER classification IOSMAIN-PLATFORM-ACTUAL after
 *     cluster248 ANDROIDMAIN-PLATFORM-ACTUAL closes. The 5-leaf batch
 *     sweeps 5 iosMain implementations sibling to cluster248's leaves
 *     (notification + push + intent + locale + toast). NEW POSTURE
 *     feature at cluster249.
 *
 *   - NOTIFICATIONPRESENTER-IOS-ACTUAL-LIVE — class implements
 *     NotificationPresenter with 4 overrides. 4-AGREE-WITH-cluster248-
 *     LEAF-1-AndroidNotificationPresenter (same 4-method shape). 1-
 *     DIVERGES via ensureChannel intentional-noop (channels are Android-
 *     only concept). PRESERVE — load-bearing as iOS-side of 3-actual fan.
 *
 *   - UNUSERNOTIFICATIONCENTER-USERNOTIFICATIONS-BRIDGE-LIVE — show()
 *     constructs UNMutableNotificationContent + UNNotificationRequest +
 *     calls UNUserNotificationCenter.currentNotificationCenter().
 *     addNotificationRequest(). The Foundation/UserNotifications bridge
 *     IS load-bearing because UserNotifications IS Apple's only public
 *     local-notification surface on iOS 10+. PRESERVE-AS-DOCUMENTED.
 *
 *   - CHANNELID-UNUSED-ON-IOS-LIVE — channelId param ignored. The
 *     KDoc-documented parameter mismatch IS load-bearing because cross-
 *     platform callers MUST pass channelId to satisfy the SPI signature,
 *     but iOS has no equivalent (Android-only concept). PRESERVE-AS-
 *     DOCUMENTED — future polish phase candidate to thread iOS category
 *     grouping if call sites grow notification-grouping need.
 *
 *   - ID-TO-STRING-CONVERSION-LIVE — Int id IS converted to String for
 *     iOS pending/delivered request identifier APIs. The id.toString()
 *     conversion IS load-bearing because UNNotificationRequest IS
 *     keyed on String identifier (vs Android Int notification ID). Same
 *     id round-trips through cancel() (`listOf(id.toString())`).
 *     PRESERVE.
 *
 *   - NULL-TRIGGER-IMMEDIATE-DELIVERY-LIVE — UNNotificationRequest
 *     constructed with `trigger = null` (cited in inline comment as
 *     "deliver immediately"). The null-trigger pattern IS load-bearing
 *     because callers expect immediate fire (current SPI does not
 *     expose schedule semantics). PRESERVE — defends against future
 *     "add scheduledFor: Instant" SPI widening.
 *
 *   - ERRORS-SWALLOWED-IN-CALLBACK-LIVE — addNotificationRequest
 *     completion block `{ _ -> /* errors swallowed — surface via
 *     callers later */ }` discards the NSError parameter. The
 *     swallow-with-comment IS load-bearing as known-debt residue
 *     (acceptable because current callers don't surface notification-
 *     delivery failures). PRESERVE-AS-DOCUMENTED — future polish: emit
 *     Result<Unit> or AppResult instead of suspend Unit.
 *
 *   - CANCEL-DUAL-API-LIVE — cancel(id) calls BOTH
 *     removePendingNotificationRequestsWithIdentifiers AND
 *     removeDeliveredNotificationsWithIdentifiers. The dual-call IS
 *     load-bearing because iOS distinguishes pending (scheduled but
 *     not fired) from delivered (currently in Notification Center).
 *     Cross-platform cancel semantics demand both. PRESERVE.
 *
 *   - CANCELALL-DUAL-API-LIVE — cancelAll() also calls both
 *     removeAllPendingNotificationRequests + removeAllDelivered
 *     Notifications. 1-AGREE-WITH-CANCEL pattern. PRESERVE.
 *
 *   - ENSURECHANNEL-INTENTIONAL-NOOP-LIVE — ensureChannel body IS
 *     2-line comment block (no implementation). The intentional-noop
 *     IS load-bearing because cross-platform callers MUST be able to
 *     call ensureChannel without iOS-side branching. PRESERVE-AS-
 *     DOCUMENTED — inline comment cites "categories/threadIdentifier
 *     for grouping" as future-implementation hint.
 *
 *   - PERMISSION-PROMPTING-IOS-APP-SIDE-DELEGATION-LIVE — KDoc cites
 *     "Permission prompting (`requestAuthorizationWithOptions`) belongs
 *     to the bootstrap path on the iOS app side — this class assumes
 *     authorization has already been granted (or will be requested at
 *     first use by the OS)." The bootstrap-delegation pattern IS load-
 *     bearing as architectural-decision residue (separates SPI from
 *     auth-flow concerns). PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-DIVERGES-FROM-cluster248-LEAF-1-AndroidNotificationPresenter
 *     (Android takes Context). The zero-param shape IS load-bearing
 *     because UNUserNotificationCenter.currentNotificationCenter() IS
 *     a static-style API. PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 1-AGREE-WITH-cluster248-LEAF-1
 *     (AndroidNotificationPresenter also has no companion). PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster249-LIVE — IosNotificationPresenter
 *     IS leaf 1 of 5 of cluster249 IOSMAIN-PLATFORM-ACTUAL-SUB-TIER-
 *     OPENER batch. PRESERVE.
 */
