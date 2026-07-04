package me.manga.kira.platform.analytics

/**
 * Cross-platform analytics facade.
 *
 * Implementations:
 *  - Android  → wraps `FirebaseAnalytics.getInstance(context)`. The `Map<String, Any?>`
 *               parameter map is translated to `android.os.Bundle` per Firebase Analytics' type
 *               conventions (String / Int / Long / Float / Double / Boolean; `null` skipped;
 *               unknown types coerced via `toString()`).
 *  - iOS      → no-op. Firebase iOS SDK is not wired in Phase 8; APNS / Firebase iOS lives in
 *               Phase 12 once the CocoaPods / cinterop story is finalized.
 *  - Desktop  → no-op. Firebase has no first-party JVM/desktop SDK; the REST-based Measurement
 *               Protocol is out of scope.
 *
 * `params` values may be String, Int, Long, Float, Double, Boolean, or `null`. Other types are
 * coerced via `toString()` on Android — same convention upstream used implicitly via
 * `Bundle.put(...)`.
 */
interface AnalyticsClient {
    fun logEvent(name: String, params: Map<String, Any?> = emptyMap())
    fun setUserProperty(key: String, value: String?)
    fun setUserId(id: String?)
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster148.staleKdocSweep.cascade,
 * Task #604, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventy-sixth sibling of the cluster57-147
 * sweep — fourth file of the wave-26 :platform tier cluster148 5-leaf
 * telemetry-plus-monetization batch alongside InAppReviewClient plus
 * ConsentFlowClient plus AdProvider plus CrashReporter):
 *  (a) "Cross-platform-analytics-facade + Android-wraps-FirebaseAnalytics.
 *  getInstance-context + The-Map-String-Any-parameter-map-is-translated-
 *  to-android.os.Bundle-per-Firebase-Analytics-type-conventions-String-
 *  Int-Long-Float-Double-Boolean-null-skipped-unknown-types-coerced-via
 *  -toString + iOS-no-op-Firebase-iOS-SDK-is-not-wired-in-Phase-8-APNS-
 *  Firebase-iOS-lives-in-Phase-12-once-the-CocoaPods-cinterop-story-is-
 *  finalized + Desktop-no-op-Firebase-has-no-first-party-JVM-desktop-
 *  SDK-the-REST-based-Measurement-Protocol-is-out-of-scope" —
 *  LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED. Verified: 3 actuals
 *  shipped at platform/src/{android,ios,desktop}Main/analytics/.
 *  Android delegates to FirebaseAnalytics.getInstance(context) with
 *  Map → Bundle translation honoring documented type conventions
 *  (verified in AndroidAnalyticsClient.kt). The "Phase 12 iOS Firebase
 *  via CocoaPods/cinterop" forecast remains UNREALIZED — IosAnalytics
 *  Client is a no-op. Desktop no-op as documented (no first-party
 *  JVM SDK; REST Measurement Protocol intentionally out of scope —
 *  the rework hasn't and won't bring it into scope per project
 *  posture: telemetry is Android-centric).
 *  (b) "params-values-may-be-String-Int-Long-Float-Double-Boolean-or-
 *  null + Other-types-are-coerced-via-toString-on-Android-same-
 *  convention-upstream-used-implicitly-via-Bundle.put" — LIVE-NOT-
 *  STALE. Verified: the type-coercion contract is honored by the
 *  Android actual's Bundle-translation helper; legacy upstream
 *  Bundle.put implicit coercion behavior is mirrored explicitly so
 *  the rework caller doesn't observe a regression on enum/long/etc
 *  param values.
 *  Two classifications STAND on their own merits. Original Phase
 *  5.z.5 (Task #192) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
