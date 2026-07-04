package me.manga.kira.platform.notification

/**
 * Cross-platform notification surface.
 *
 * Channels are an Android concept (API 26+); on iOS and Desktop [ensureChannel] is a noop.
 * `importance` mirrors `NotificationManager.IMPORTANCE_*` (DEFAULT == 3).
 *
 * All operations are suspending so platform implementations can offload to the appropriate
 * dispatcher (Android tray ops are cheap and main-thread-safe, but Desktop SystemTray and iOS
 * permission-request paths may block briefly).
 *
 * Relocated from legacy `:shared/.../core/notification/NotificationPresenter.kt` as part of the
 * Phase 5.y SPI port. Legacy used an `expect class`; the rework convention is plain interfaces
 * (matched by Phase 5.x ConnectivityObserver, 5.v SecureStorage, etc).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster145.staleKdocSweep.cascade,
 * Task #601, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixty-first sibling of the cluster57-144
 * sweep — fourth file of the wave-26 :platform tier cluster145 5-leaf
 * storage-plus-net-plus-notif-plus-push batch alongside SecureStorage
 * plus SettingsFactory plus ConnectivityObserver plus PushTokenProvider):
 *  (a) "Cross-platform-notification-surface + Channels-are-an-Android-
 *  concept-API-26-plus-on-iOS-and-Desktop-ensureChannel-is-a-noop +
 *  importance-mirrors-NotificationManager.IMPORTANCE-DEFAULT-equals-3 +
 *  All-operations-are-suspending-so-platform-implementations-can-
 *  offload-to-the-appropriate-dispatcher + Android-tray-ops-are-cheap-
 *  and-main-thread-safe-but-Desktop-SystemTray-and-iOS-permission-
 *  request-paths-may-block-briefly" — LIVE-NOT-STALE. Verified: 3
 *  actuals shipped at platform/src/{android,ios,desktop}Main/
 *  notification/. The 4-method SPI (show + cancel + cancelAll +
 *  ensureChannel) wired with documented platform-specific routing:
 *  Android uses NotificationManagerCompat + NotificationChannel
 *  creation, iOS routes through UNUserNotificationCenter (with
 *  ensureChannel as no-op), Desktop uses AWT SystemTray TrayIcon
 *  displayMessage (with ensureChannel as no-op). The DEFAULT_IMPORTANCE
 *  = 3 const matches NotificationManager.IMPORTANCE_DEFAULT without
 *  androidx import bleed into commonMain.
 *  (b) "Relocated-from-legacy-:shared-core-notification-Notification-
 *  Presenter-as-part-of-the-Phase-5.y-SPI-port + Legacy-used-an-expect-
 *  class-the-rework-convention-is-plain-interfaces-matched-by-Phase-
 *  5.x-ConnectivityObserver-5.v-SecureStorage-etc" — LIVE-NOT-STALE
 *  plus PARTIALLY-FULFILLED-FORECAST. Verified: the legacy `:shared`
 *  NotificationPresenter facade is still LIVE — wired via :shared
 *  PlatformModule.{android,ios,desktop}.kt and consumed by legacy
 *  worker/background-job notification paths (cross-classified at Task
 *  #422 BLOCKER on the §250 shadow-legacy-facade retire path). The
 *  "interface not expect class" rework convention is consistently
 *  honored across all 5 cluster145 SPIs (SecureStorage + Settings-
 *  Factory + ConnectivityObserver + NotificationPresenter +
 *  PushTokenProvider), validating the cross-reference to the Phase
 *  5.x + 5.v sibling SPIs as a coherent convention pattern.
 *  Two classifications STAND on their own merits. Original Phase 5.y.1
 *  (Task #177) :platform-relocation prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
interface NotificationPresenter {

    suspend fun show(id: Int, title: String, body: String, channelId: String = DEFAULT_CHANNEL_ID)

    suspend fun cancel(id: Int)

    suspend fun cancelAll()

    suspend fun ensureChannel(
        channelId: String,
        channelName: String,
        importance: Int = DEFAULT_IMPORTANCE,
    )

    companion object {
        const val DEFAULT_CHANNEL_ID: String = "default"

        /** Mirrors `NotificationManager.IMPORTANCE_DEFAULT` (== 3) without depending on Android. */
        const val DEFAULT_IMPORTANCE: Int = 3
    }
}
