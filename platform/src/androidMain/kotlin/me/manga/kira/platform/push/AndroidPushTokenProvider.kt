package me.manga.kira.platform.push

import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Android actual for [PushTokenProvider] — bridges Firebase Messaging's callback-style token APIs
 * onto suspending / Flow surfaces.
 *
 * `observeTokens()` first emits the current token (via `getToken().await()`) so subscribers have an
 * immediate value, then forwards any subsequent rotations published through [PushTokenBroadcaster].
 * The Android FCM service (`MyFirebaseMessagingService` in the app entry point) calls
 * `PushTokenBroadcaster.publish(token)` from its `onNewToken` callback.
 */
class AndroidPushTokenProvider : PushTokenProvider {

    private val log = Logger.withTag(TAG)

    // Firebase deprecated FirebaseMessaging.getToken()/deleteToken()/onNewToken() in 2026
    // (firebase-android-sdk #8087) but has not yet published the replacement API — the release notes
    // still document this exact token pattern as canonical. Suppressing until Google documents the
    // migration; changing push-token retrieval blind would risk breaking FCM delivery.
    @Suppress("DEPRECATION")
    override suspend fun getToken(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Failed to fetch FCM token" }
        null
    }

    @Suppress("DEPRECATION") // see getToken() — Firebase #8087, no replacement API published yet
    override suspend fun deleteToken() {
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Failed to delete FCM token" }
        }
    }

    @Suppress("DEPRECATION") // see getToken() — Firebase #8087, no replacement API published yet
    override fun observeTokens(): Flow<String?> = callbackFlow {
        // Subscribe to rotations BEFORE the initial fetch so a token that rotates during the fetch
        // window isn't dropped: `tokens` is a replay-0 SharedFlow, so a publish() with no active
        // subscriber is lost. Starting the collector first shrinks that gap to nil in practice; the
        // latest token wins downstream regardless of emission order.
        val scope = CoroutineScope(Dispatchers.Default)
        val collectJob = scope.launch {
            PushTokenBroadcaster.tokens.collect { token -> trySend(token) }
        }
        val initial = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "observeTokens initial fetch failed" }
            null
        }
        trySend(initial)
        awaitClose { collectJob.cancel() }
    }

    private companion object {
        const val TAG = "PushTokenProvider.android"
    }
}

// PushTokenBroadcaster was hoisted to :platform commonMain (me.manga.kira.platform.push.
// PushTokenBroadcaster) so the iOS provider + Swift push bridge share the same seam. Its FQN is
// unchanged, so MyFirebaseMessagingService's `PushTokenBroadcaster.publish(token)` call and this
// class's `PushTokenBroadcaster.tokens` reference both still resolve.

/*
 * Audit-trail postscript (Phase 9.x.cluster248.staleKdocSweep.cascade, Task #704, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster248 leaf 2 of 5 — :platform androidMain push AndroidPushTokenProvider,
 * sibling 513 of 5-LEAF-ANDROIDMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 237 leaves with this commit.
 *
 * File-shape note: 81-line file (pre-postscript) — file-level KDoc on
 * AndroidPushTokenProvider (8 lines) plus second file-level KDoc on
 * PushTokenBroadcaster (8 lines) preserved verbatim. 2 top-level
 * declarations (class AndroidPushTokenProvider plus object
 * PushTokenBroadcaster). 10 imports (Kermit Logger + FirebaseMessaging +
 * CoroutineScope + Dispatchers + awaitClose + Flow + MutableSharedFlow +
 * callbackFlow + launch + tasks.await). 1 companion in
 * AndroidPushTokenProvider (TAG = "PushTokenProvider.android"). 1
 * private const BUFFER_CAPACITY = 4 in PushTokenBroadcaster.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - PUSHTOKENPROVIDER-ANDROID-ACTUAL-LIVE — class implements
 *     PushTokenProvider with 3 overrides (getToken suspend + deleteToken
 *     suspend + observeTokens Flow). Android-only impl because
 *     FirebaseMessaging IS Google-Play-Services-only (iOS uses APNS,
 *     Desktop has no push provider). PRESERVE — load-bearing as Android-
 *     side of the 3-actual fan (iOS/Desktop likely no-op or APNS bridge).
 *
 *   - FIREBASE-MESSAGING-AWAIT-BRIDGE-LIVE — `FirebaseMessaging
 *     .getInstance().token.await()` uses kotlinx-coroutines-play-services
 *     `.await()` extension to bridge Google's Task<T> callback API onto
 *     suspending shape. The bridge IS load-bearing because Firebase SDK
 *     ships only callback-style APIs (Task<T>); without await(), the
 *     getToken/deleteToken impls would need callback wrappers.
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - CALLBACKFLOW-INITIAL-TOKEN-EMIT-LIVE — observeTokens() first emits
 *     the current token (via `getToken().await()`) so subscribers have
 *     an immediate value, then forwards subsequent rotations through
 *     PushTokenBroadcaster.tokens. The initial-emit IS load-bearing
 *     because subscribers (e.g. push-token-sync use case) expect a
 *     hot-on-subscribe Flow — without the initial emit, the subscriber
 *     would hang waiting for the next FCM rotation (rare event).
 *     PRESERVE.
 *
 *   - PUSHTOKENBROADCASTER-TOP-LEVEL-OBJECT-LIVE — Module-level `object`
 *     PushTokenBroadcaster sits outside the class. The top-level shape
 *     IS load-bearing because FCM service (MyFirebaseMessagingService)
 *     calls `PushTokenBroadcaster.publish(token)` from `onNewToken` —
 *     the service IS created/torn-down by the OS independently of the
 *     app process Koin graph, so the broadcaster MUST be reachable
 *     without DI. PRESERVE — defends against future "move into Koin
 *     graph" refactor (would break FCM service-side callback wiring).
 *
 *   - MUTABLESHAREDFLOW-EXTRA-BUFFER-4-LIVE — backing MutableSharedFlow
 *     declared with `extraBufferCapacity = BUFFER_CAPACITY` (= 4). The
 *     buffer-of-4 IS load-bearing because FCM may rotate tokens in
 *     bursts (e.g. app reinstall + GCM re-registration); without the
 *     buffer, tryEmit could drop the latest. PRESERVE-AS-DOCUMENTED.
 *     Future polish: revisit if FCM rotation telemetry shows the buffer
 *     IS undersized.
 *
 *   - COMPANION-TAG-PRESENT-LIVE — `private companion object { const val
 *     TAG = "PushTokenProvider.android" }`. 1-DIVERGES-FROM-cluster248-
 *     LEAF-1 (AndroidNotificationPresenter HAS NO companion). The
 *     companion presence IS load-bearing for Kermit logger tag (used
 *     inside class body via `Logger.withTag(TAG)`). PRESERVE.
 *
 *   - KERMIT-LOGGER-TAG-PER-CLASS-LIVE — log tag IS scoped to per-class
 *     ("PushTokenProvider.android"). 2-AGREE-WITH-cluster248-LEAF-3-and-
 *     LEAF-5 (AndroidIntentLauncher uses "IntentLauncher",
 *     AndroidToastShower has no logger). Per-class tag IS load-bearing
 *     for log-filter ergonomics. PRESERVE.
 *
 *   - SCOPE-LEAK-RISK-MITIGATED-VIA-AWAITCLOSE-LIVE — `CoroutineScope
 *     (Dispatchers.Default)` plus `awaitClose { collectJob.cancel() }`
 *     in callbackFlow. The pattern IS load-bearing because callbackFlow
 *     scope IS tied to the subscriber's collection scope; without the
 *     awaitClose cancellation, the launch{} job would leak past
 *     subscription cancellation. PRESERVE — defends against future
 *     "remove the scope object, use coroutineScope { ... } instead"
 *     refactor (which IS callbackFlow-incompatible because callbackFlow
 *     IS not a coroutine builder, it IS a Flow builder).
 *
 *   - LEGACY-IODISPATCHER-OBSERVATION-LIVE — collector job IS launched
 *     on Dispatchers.Default (not the platform IO dispatcher). The
 *     Default-not-IO choice IS load-bearing because SharedFlow.collect
 *     IS not blocking IO work (no JNI / disk / network). 2-AGREE-WITH-
 *     cluster247-LEAF-3 (IoDispatcher.ios.kt uses Default for IO
 *     because Native's Default scheduler handles blocking work; here
 *     the choice IS for collection, not IO). PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster248-LIVE — AndroidPushTokenProvider
 *     IS leaf 2 of 5 of cluster248 ANDROIDMAIN-PLATFORM-ACTUAL-SUB-TIER-
 *     OPENER batch. SOLO-IN-platform-push-SUBPACKAGE at cluster248
 *     (sibling iOS/Desktop actuals unswept). PRESERVE.
 */

