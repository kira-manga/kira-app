package me.manga.kira.platform.review

import co.touchlab.kermit.Logger

/**
 * iOS actual for [InAppReviewClient] — no-op.
 *
 * iOS has its own `SKStoreReviewController.requestReview()` API, which can be wired in a later
 * phase from the iosApp side. For now `requestReview()` logs a debug breadcrumb and returns
 * `false` so consumer code falls through to its "review not shown" branch.
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/review/InAppReviewClient.ios.kt`.
 */
class IosInAppReviewClient : InAppReviewClient {

    private val log = Logger.withTag(TAG)

    override suspend fun requestReview(): Boolean {
        log.d { "requestReview() — no-op on iOS, returning false" }
        return false
    }

    private companion object {
        const val TAG = "InAppReviewClient.ios"
    }
}

/*
 * §253 audit-trail postscript — cluster275 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-FACADE-NOT-YET-BOUND (iOS actual).
 *
 * Cross-reference: iOS leaf of the 3-actual platform-facade fan for the
 * commonMain interface InAppReviewClient at
 * platform/src/commonMain/.../platform/review/InAppReviewClient.kt:57
 * (swept under cluster148, Task #604; its postscript at lines 37-40 there flags
 * the SKStoreReviewController forecast as UNREALIZED). FULFILLED-PORT for the
 * relocation itself — Phase 5.z.1 (Task #188) shipped Android plus Desktop plus
 * iOS (this file) — but the iOS BEHAVIOUR is a deferred no-op, not a final
 * implementation.
 *
 * LIVE evidence — nuanced. No :composeApp rework Koin module references
 * IosInAppReviewClient (repo-wide grep across composeApp returned zero call
 * sites). What is LIVE today is the LEGACY shadow SPI: the expect-class
 * InAppReviewClient bound for iOS at
 * shared/src/iosMain/.../di/PlatformModule.ios.kt:108
 * (single InAppReviewClient, no-arg). The rework interface remains a relocated-
 * but-unwired facade pending the Task #422 §250 shadow-legacy-facade retire
 * decision; LIVE-by-design (relocation complete), not orphaned.
 *
 * Delta-axes (iOS actual):
 *  1. Platform API: NONE wired yet. iOS has SKStoreReviewController.request-
 *     Review() available, but per the commonMain forecast it is to be wired
 *     from the iosApp side in a later phase; this actual deliberately does not
 *     import StoreKit and stays a no-op.
 *  2. Threading: requestReview() is suspend by contract but does no async work
 *     and never hops dispatcher; returns synchronously on the caller's coroutine.
 *  3. Error handling: no failure path — there is nothing to catch; false is the
 *     unconditional documented "review not shown" return after a Kermit debug
 *     breadcrumb.
 *  4. DI binding mechanism: zero-arg ctor (no Context, no activityProvider),
 *     matching the Desktop actual and the legacy iOS expect-actual; contrasts
 *     the Android (context, activityProvider) shape.
 *  5. Contract parity across the fan: iOS matches Desktop as a no-op false
 *     return (DEFERRED-NOT-PERMANENT — a future StoreKit slice may make it FULL,
 *     unlike Desktop where no native surface exists). All three honour the same
 *     false-means-review-not-shown consumer contract.
 *
 * Nested-comment hazard check: exactly one legitimate KDoc opener heads the
 * class (the block at lines 5-13) plus this appended block; this appended block
 * is balanced — one opener, one closer, and the prose contains no interior
 * comment delimiters (no slash-star, no star-slash, no slash-star-star).
 */
