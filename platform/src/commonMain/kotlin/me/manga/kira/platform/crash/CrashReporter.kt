package me.manga.kira.platform.crash

/**
 * Cross-platform crash-reporting facade.
 *
 * Implementations:
 *  - Android  → wraps `FirebaseCrashlytics.getInstance()`. The `context` map is fanned out
 *               into `setCustomKey` calls before `recordException` because Crashlytics
 *               snapshots all custom keys at the time `recordException` is called, so the
 *               order matters.
 *  - iOS      → no-op. Firebase Crashlytics iOS SDK is not wired in Phase 8; real crash
 *               reporting is scheduled for Phase 12 alongside the rest of the Firebase iOS
 *               integration. `recordException` still logs the throwable's stack via Kermit
 *               at error level so the iOS console surfaces the problem during development.
 *  - Desktop  → no-op. Firebase Crashlytics has no first-party JVM/desktop SDK.
 *               `recordException` logs the throwable via Kermit at error level so desktop
 *               runs surface the problem on the console rather than swallowing it silently.
 */
interface CrashReporter {
    fun recordException(throwable: Throwable, context: Map<String, String> = emptyMap())
    fun log(message: String)
    fun setUserId(id: String?)
    fun setCustomKey(key: String, value: String)
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster148.staleKdocSweep.cascade,
 * Task #604, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventy-seventh sibling of the cluster57-147
 * sweep — fifth and closing file of the wave-26 :platform tier cluster148
 * 5-leaf telemetry-plus-monetization batch alongside InAppReviewClient plus
 * ConsentFlowClient plus AdProvider plus AnalyticsClient; closes cluster148):
 *  (a) "Cross-platform-crash-reporting-facade + Android-wraps-Firebase-
 *  Crashlytics.getInstance + The-context-map-is-fanned-out-into-set-
 *  CustomKey-calls-before-recordException-because-Crashlytics-snapshots
 *  -all-custom-keys-at-the-time-recordException-is-called-so-the-order-
 *  matters + iOS-no-op-Firebase-Crashlytics-iOS-SDK-is-not-wired-in-
 *  Phase-8-real-crash-reporting-is-scheduled-for-Phase-12-alongside-
 *  the-rest-of-the-Firebase-iOS-integration + recordException-still-
 *  logs-the-throwable-s-stack-via-Kermit-at-error-level-so-the-iOS-
 *  console-surfaces-the-problem-during-development + Desktop-no-op-
 *  Firebase-Crashlytics-has-no-first-party-JVM-desktop-SDK + record-
 *  Exception-logs-the-throwable-via-Kermit-at-error-level-so-desktop-
 *  runs-surface-the-problem-on-the-console-rather-than-swallowing-it-
 *  silently" — LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED. Verified:
 *  3 actuals shipped at platform/src/{android,ios,desktop}Main/crash/.
 *  Android delegates to FirebaseCrashlytics.getInstance() with the
 *  documented setCustomKey-before-recordException ordering (verified
 *  in AndroidCrashReporter.kt — fan-out happens before recordException
 *  call to preserve the Crashlytics snapshot semantics). The "Phase 12
 *  Firebase iOS Crashlytics" forecast remains UNREALIZED — IosCrash
 *  Reporter falls back to Kermit error-level log (the documented
 *  fallback for dev visibility). Desktop similarly delegates to Kermit
 *  to avoid silent swallowing on workstation runs.
 *  Two classifications: only one prose block (the entire KDoc above
 *  the interface) — the per-method Kermit fallback contract is the
 *  same on both iOS and Desktop, so it groups under the same (a)
 *  classification. Closes cluster148 — completes the wave-26 :platform
 *  tier telemetry+monetization 5-SPI sub-tier where every SPI
 *  followed the plain-interface rework pattern with Android-resident
 *  full implementation + iOS/Desktop no-op-with-honest-fallback.
 *  Original Phase 5.z.6 (Task #193) :platform-relocation prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
