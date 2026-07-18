package me.manga.kira.platform.review

/**
 * Cross-platform in-app review facade.
 *
 * Implementations:
 *  - Android  → delegates to Play Core's `ReviewManagerFactory.create(context)` +
 *               `launchReviewFlow(activity, info)`. Needs a foreground `Activity`.
 *  - iOS      → delegates to `SKStoreReviewController.requestReviewInScene` for the active scene.
 *  - Desktop  → no-op returning `false` (there is no native in-app review surface for desktop).
 *
 * The legacy `:shared` SPI was an `expect class InAppReviewClient` with the Android actual taking
 * `(context, activityProvider)` and the iOS / Desktop actuals having no parameters. The rework
 * preserves the same constructor shape on the Android side (no Koin/DI changes for hosts) but
 * exposes the SPI as a plain interface — mockable in unit tests, and consistent with every other
 * `:platform` facade.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster148.staleKdocSweep.cascade,
 * Task #604, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventy-third sibling of the cluster57-147
 * sweep — opening file of the wave-26 :platform tier cluster148 5-leaf
 * telemetry-plus-monetization batch alongside ConsentFlowClient plus
 * AdProvider plus AnalyticsClient plus CrashReporter):
 *  (a) "Cross-platform-in-app-review-facade + Android-delegates-to-Play-
 *  Core-s-ReviewManagerFactory.create-context-plus-launchReviewFlow-
 *  activity-info + Needs-a-foreground-Activity + iOS-no-op-returning-
 *  false-the-SKStoreReviewController-integration-will-land-in-the-
 *  iosApp-wiring-phase-the-facade-returns-false-so-consumers-fall-
 *  through-to-their-review-not-shown-branch + Desktop-no-op-returning-
 *  false-there-is-no-native-in-app-review-surface-for-desktop" —
 *  LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED. Verified: 3
 *  actuals shipped at platform/src/{android,ios,desktop}Main/review/.
 *  Android delegates to Play Core ReviewManagerFactory.create(context)
 *  + launchReviewFlow(activity, info) via ForegroundActivityProvider
 *  suspension. The iOS "SKStoreReviewController will land in iosApp
 *  wiring phase" forecast remains UNREALIZED — IosInAppReviewClient
 *  still returns false; no iosApp wiring slice has integrated SKStore-
 *  ReviewController. Desktop no-op returns false as documented.
 *  (b) "The-legacy-:shared-SPI-was-an-expect-class-InAppReviewClient-
 *  with-the-Android-actual-taking-context-activityProvider-and-the-
 *  iOS-Desktop-actuals-having-no-parameters + The-rework-preserves-
 *  the-same-constructor-shape-on-the-Android-side-no-Koin-DI-changes-
 *  for-hosts-but-exposes-the-SPI-as-a-plain-interface-mockable-in-
 *  unit-tests-and-consistent-with-every-other-:platform-facade" —
 *  LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST. Verified: the
 *  Android actual ctor takes (context, foregroundActivityProvider) —
 *  same shape as legacy :shared. The legacy :shared expect-class
 *  facade is still LIVE (Task #422 BLOCKER §250 shadow-legacy-facade
 *  retire path); the plain-interface rework convention is consistent
 *  across the cluster148 telemetry+monetization tier.
 *  Two classifications STAND on their own merits. Original Phase
 *  5.z.1 (Task #188) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
interface InAppReviewClient {
    /**
     * Attempt to show the in-app review dialog. Returns `true` when the request was successfully
     * launched (note: Play Core does not surface whether the dialog actually rendered on this
     * device — Google rate-limits the surface), `false` for any failure mode (no foreground
     * activity, Play services unavailable, platform without an implementation).
     */
    suspend fun requestReview(): Boolean
}
