package me.manga.kira.platform.review

import co.touchlab.kermit.Logger

/**
 * Desktop actual for [InAppReviewClient] — no-op.
 *
 * Desktop builds have no native in-app review surface. The facade returns `false` so consumer
 * code falls through to its "review not shown" branch. A future enhancement could open the
 * platform store page (Microsoft Store / Mac App Store) but the rework defers that scope.
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/review/InAppReviewClient.desktop.kt`.
 */
class DesktopInAppReviewClient : InAppReviewClient {

    private val log = Logger.withTag(TAG)

    override suspend fun requestReview(): Boolean {
        log.d { "requestReview() — no-op on Desktop, returning false" }
        return false
    }

    private companion object {
        const val TAG = "InAppReviewClient.desktop"
    }
}

/*
 * §253 audit-trail postscript — cluster275 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-FACADE-NOT-YET-BOUND (Desktop actual).
 *
 * Cross-reference: Desktop leaf of the 3-actual platform-facade fan for the
 * commonMain interface InAppReviewClient at
 * platform/src/commonMain/.../platform/review/InAppReviewClient.kt:57
 * (swept under cluster148, Task #604). FULFILLED-PORT: the Phase 5.z.1
 * (Task #188) relocation landed Android plus Desktop (this file) plus iOS, all
 * implementing the single suspend fun requestReview() Boolean.
 *
 * LIVE evidence — nuanced. No :composeApp rework Koin module references
 * DesktopInAppReviewClient (repo-wide grep across composeApp returned zero call
 * sites). What is LIVE today is the LEGACY shadow SPI: the expect-class
 * InAppReviewClient bound for Desktop at
 * shared/src/desktopMain/.../di/PlatformModule.desktop.kt:108
 * (single InAppReviewClient, no-arg). The rework interface remains a relocated-
 * but-unwired facade pending the Task #422 §250 shadow-legacy-facade retire
 * decision; LIVE-by-design (relocation complete), not orphaned.
 *
 * Delta-axes (Desktop actual):
 *  1. Platform API: NONE — Desktop has no native in-app review surface. The
 *     entire body is a Kermit debug breadcrumb plus return false.
 *  2. Threading: requestReview() is suspend by contract but performs zero
 *     async work and never switches dispatcher; it returns synchronously on the
 *     caller's coroutine.
 *  3. Error handling: no failure path exists — there is nothing to catch; false
 *     is the unconditional, documented "review not shown" return.
 *  4. DI binding mechanism: zero-arg ctor (no Context, no activityProvider),
 *     contrasting the Android actual's (context, activityProvider) shape. The
 *     legacy Desktop expect-actual was likewise parameterless.
 *  5. Contract parity across the fan: Desktop matches iOS as a no-op false
 *     return; only Android is FULL. All three honour the same consumer contract
 *     where false means review-not-shown and the caller falls through.
 *
 * Nested-comment hazard check: exactly one legitimate KDoc opener heads the
 * class (the block at lines 5-13) plus this appended block; this appended block
 * is balanced — one opener, one closer, and the prose contains no interior
 * comment delimiters (no slash-star, no star-slash, no slash-star-star).
 */
