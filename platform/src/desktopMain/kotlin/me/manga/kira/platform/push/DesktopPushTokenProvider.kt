package me.manga.kira.platform.push

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Desktop actual for [PushTokenProvider] — no-op on Desktop.
 *
 * Firebase Messaging has no first-party JVM/desktop SDK. `getToken()` returns null,
 * `deleteToken()` is a noop, and `observeTokens()` emits a single null so collectors complete
 * deterministically.
 */
class DesktopPushTokenProvider : PushTokenProvider {

    private val log = Logger.withTag(TAG)

    override suspend fun getToken(): String? {
        log.d { "getToken() — no-op on Desktop, returning null" }
        return null
    }

    override suspend fun deleteToken() {
        log.d { "deleteToken() — no-op on Desktop" }
    }

    override fun observeTokens(): Flow<String?> {
        log.d { "observeTokens() — no-op on Desktop, emitting single null" }
        return flowOf(null)
    }

    private companion object {
        const val TAG = "PushTokenProvider.desktop"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster250.staleKdocSweep.cascade, Task #706, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster250 leaf 2 of 5 — :platform desktopMain push DesktopPushTokenProvider,
 * sibling 523 of 5-LEAF-DESKTOPMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 247 leaves with this commit.
 *
 * File-shape note: 35-line file (pre-postscript) — file-level KDoc (6
 * lines) preserved verbatim. 1 top-level class (DesktopPushTokenProvider)
 * implementing PushTokenProvider with 3 overrides (getToken + deleteToken
 * + observeTokens). 3 imports (Kermit Logger + Flow + flowOf). 1
 * companion (TAG = "PushTokenProvider.desktop"). ENTIRELY-NO-OP-IMPL.
 * BYTE-FOR-BYTE-IDENTICAL-SHAPE-TO-cluster249-LEAF-2-IosPushTokenProvider
 * (only TAG suffix differs ".desktop" vs ".ios").
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - PUSHTOKENPROVIDER-DESKTOP-ACTUAL-LIVE — class implements
 *     PushTokenProvider with 3 overrides. 3-AGREE-WITH-cluster248-LEAF-2-
 *     PLUS-cluster249-LEAF-2 (same 3-method shape across triplet). 1-
 *     DIVERGES because Desktop IS ENTIRELY-NO-OP (vs Android's
 *     FirebaseMessaging.await + iOS's also-no-op-but-different-rationale).
 *     PRESERVE — load-bearing as Desktop-side of 3-actual fan.
 *
 *   - ENTIRELY-NO-OP-IMPLEMENTATION-LIVE — getToken() returns null,
 *     deleteToken() does nothing, observeTokens() returns flowOf(null).
 *     2-AGREE-WITH-cluster249-LEAF-2-IosPushTokenProvider (iOS also
 *     all-no-op). The total-noop posture IS load-bearing because
 *     Firebase Messaging HAS NO first-party JVM/desktop SDK. PRESERVE-
 *     AS-DOCUMENTED — KDoc explicitly cites "Firebase Messaging has no
 *     first-party JVM/desktop SDK".
 *
 *   - FLOWOF-NULL-NEVER-HANGS-LIVE — observeTokens() returns
 *     flowOf(null) (cold flow that emits ONE null then completes). 2-
 *     AGREE-WITH-cluster249-LEAF-2-IosPushTokenProvider. The completing-
 *     flow IS load-bearing because subscribers (push-token-sync use
 *     case) would otherwise hang waiting indefinitely for a token that
 *     NEVER arrives on Desktop. PRESERVE — defends against future "use
 *     emptyFlow() instead" refactor (which would NEVER emit and could
 *     break subscribers expecting at-least-one emission).
 *
 *   - KERMIT-DEBUG-LOG-LEVEL-LIVE — each method calls log.d { ... } at
 *     debug level. 2-AGREE-WITH-cluster249-LEAF-2-IosPushTokenProvider.
 *     The debug-level choice IS load-bearing because no-op calls SHOULD
 *     NOT spam info/warn logs. PRESERVE.
 *
 *   - COMPANION-TAG-PLATFORM-SUFFIX-LIVE — `private companion object {
 *     const val TAG = "PushTokenProvider.desktop" }`. 3-AGREE-WITH-
 *     cluster248-LEAF-2 (".android") PLUS cluster249-LEAF-2 (".ios").
 *     The platform-suffix-in-tag pattern IS load-bearing across the
 *     PushTokenProvider triplet for log-filter ergonomics (3 platforms,
 *     3 distinct tag suffixes). PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     3-AGREE-WITH-cluster248-LEAF-2 PLUS cluster249-LEAF-2 (all three
 *     PushTokenProvider impls have zero-param ctor). PRESERVE.
 *
 *   - BYTE-FOR-BYTE-SIBLING-IOS-DESKTOP-LIVE — Desktop impl IS byte-for-
 *     byte identical to iOS leaf-2 except TAG suffix. The shape-mirror IS
 *     load-bearing because both platforms share the no-Firebase-SDK
 *     posture; collapsing into a shared :common-or-similar :stub-actual
 *     would be a future-refactor consideration but currently kept
 *     separate for class-discovery ergonomics. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster250-LIVE — DesktopPushTokenProvider
 *     IS leaf 2 of 5 of cluster250. PRESERVE.
 */

