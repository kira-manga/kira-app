package me.manga.kira.platform.push

import kotlinx.coroutines.flow.Flow

/**
 * Cross-platform push notification token facade.
 *
 * Platform actuals:
 *  - Android  → wraps `FirebaseMessaging.getInstance().token` via
 *               `kotlinx-coroutines-play-services`'s `Task<T>.await()`.
 *               `observeTokens()` emits the current token immediately and then forwards token
 *               rotations published through [PushTokenBroadcaster] (registered by
 *               `MyFirebaseMessagingService.onNewToken` on the Android side).
 *  - iOS      → no-op. `getToken` returns null, `observeTokens` emits a single null. APNS
 *               integration lives in the iOS app entry point (not in `:platform`).
 *  - Desktop  → no-op. Firebase Messaging has no first-party JVM SDK.
 *
 * Relocated from legacy `:shared/.../core/push/PushTokenProvider.kt` as part of the Phase 5.y
 * SPI port. Legacy used an `expect class`; the rework convention is plain interfaces.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster145.staleKdocSweep.cascade,
 * Task #601, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixty-second sibling of the cluster57-144
 * sweep — fifth and closing file of the wave-26 :platform tier cluster145
 * 5-leaf storage-plus-net-plus-notif-plus-push batch alongside Secure-
 * Storage plus SettingsFactory plus ConnectivityObserver plus
 * NotificationPresenter; closes cluster145):
 *  (a) "Cross-platform-push-notification-token-facade + Platform-
 *  actuals + Android-wraps-FirebaseMessaging.getInstance.token-via-
 *  kotlinx-coroutines-play-services-Task-await + observeTokens-emits-
 *  the-current-token-immediately-and-then-forwards-token-rotations-
 *  published-through-PushTokenBroadcaster-registered-by-MyFirebase-
 *  MessagingService.onNewToken-on-the-Android-side + iOS-no-op-
 *  getToken-returns-null-observeTokens-emits-a-single-null + APNS-
 *  integration-lives-in-the-iOS-app-entry-point-not-in-:platform +
 *  Desktop-no-op-Firebase-Messaging-has-no-first-party-JVM-SDK" —
 *  LIVE-NOT-STALE. Verified: 3 actuals shipped at platform/src/
 *  {android,ios,desktop}Main/push/. Android delegates to Firebase-
 *  Messaging.getInstance().token + Task.await suspension; the
 *  PushTokenBroadcaster fan-in registry is defined in AndroidPush-
 *  TokenProvider.kt (companion object). iOS getToken returns null +
 *  observeTokens emits flowOf(null). Desktop similarly no-ops since
 *  no FCM JVM SDK exists. The "APNS integration lives in iOS app
 *  entry point" boundary remains honored — no APNS reach into
 *  :platform.
 *  (b) "Relocated-from-legacy-:shared-core-push-PushTokenProvider-as-
 *  part-of-the-Phase-5.y-SPI-port + Legacy-used-an-expect-class-the-
 *  rework-convention-is-plain-interfaces" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified: the legacy `:shared` Push-
 *  TokenProvider facade at shared/src/commonMain/kotlin/me/manga/
 *  yamiapk/core/push/PushTokenProvider.kt is still LIVE — wired via
 *  :shared PlatformModule.{android,ios,desktop}.kt and consumed by
 *  legacy MyApp.kt bootstrap + Firebase-push-related repositories
 *  (cross-classified at Task #422 BLOCKER on the §250 shadow-legacy-
 *  facade retire path). The interface-not-expect-class rework
 *  convention remains consistently applied — completes the cluster145
 *  5-SPI bedrock storage+net+push tier where every SPI followed the
 *  plain-interface rework pattern.
 *  Two classifications STAND on their own merits. Closes cluster145.
 *  Original Phase 5.y.2 (Task #178) :platform-relocation prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
interface PushTokenProvider {

    suspend fun getToken(): String?

    suspend fun deleteToken()

    fun observeTokens(): Flow<String?>
}
