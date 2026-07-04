package me.manga.kira.platform.crash

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Android actual for [CrashReporter] — delegates to the singleton
 * `FirebaseCrashlytics.getInstance()`.
 *
 * For [recordException], custom keys from the [context] map are written before the exception
 * is recorded; Crashlytics snapshots all custom keys at the time `recordException` is called,
 * so the order matters. Using an empty-string fallback for `""` or null keys would silently
 * lose info, so the entries are written verbatim.
 *
 * Verbatim semantic port from legacy
 * `:shared/androidMain/.../core/crash/CrashReporter.android.kt`. Preserves:
 *  - Zero-arg `FirebaseCrashlytics.getInstance()` (no Context needed; the SDK reads its
 *    configuration from the embedded `google-services.json` at process start).
 *  - Fan-out of `context` Map entries into `setCustomKey(key, value)` calls *before*
 *    `recordException`, because Crashlytics' key-snapshot happens at record time.
 *  - `setUserId(id ?: "")` — Crashlytics' `setUserId` expects a non-null `String`; passing
 *    the empty string is the documented clear-the-id idiom.
 */
class AndroidCrashReporter : CrashReporter {

    private val crashlytics: FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

    override fun recordException(throwable: Throwable, context: Map<String, String>) {
        for ((key, value) in context) {
            crashlytics.setCustomKey(key, value)
        }
        crashlytics.recordException(throwable)
    }

    override fun log(message: String) {
        crashlytics.log(message)
    }

    override fun setUserId(id: String?) {
        // FirebaseCrashlytics.setUserId expects non-null; pass empty string to clear.
        crashlytics.setUserId(id ?: "")
    }

    override fun setCustomKey(key: String, value: String) {
        crashlytics.setCustomKey(key, value)
    }
}

/*
 * §253 audit-trail postscript — cluster267 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Phase 5.z.6 :platform relocation, Task #193).
 * Unit kind: platform-facade — the Android impl of the rework
 * me.manga.kira.platform.crash.CrashReporter interface (commonMain
 * expect-decl swept in cluster148, Task #604; interface lives at
 * platform/src/commonMain/.../crash/CrashReporter.kt). This is the heavy
 * leg of the 3-actual fan: the only one with a real backend.
 *
 * LIVE evidence — interface contract is LIVE-NOT-STALE; this concrete impl is
 * the rework Android binding, declared as the dependency rationale at
 * platform/build.gradle.kts:116-121 ("Firebase Crashlytics — required by
 * AndroidCrashReporter (Phase 5.z.6 CrashReporter) ... iOS / Desktop are
 * no-ops"). The rework per-platform Koin binding is FORECAST-NOT-YET-WIRED:
 * no single resolving this type exists yet under composeApp/di (the
 * ReworkModules.kt family carries zero platform.crash reference). The
 * production-LIVE consumer today is the LEGACY parallel symbol: a
 * me.manga.kira.core.crash.CrashReporter is bound at
 * shared/.../di/PlatformModule.android.kt:115 (single { CrashReporter() })
 * and consumed by the uncaught-handler at app/.../MyApp.kt:87 via
 * get-of-CrashReporter dot recordException. The rework :platform impl is the
 * forward-target awaiting Phase 8.x platformModule wiring — present, compiled,
 * not yet bound; consistent with the cluster148 FORECAST classification.
 *
 * Delta-axes (Android leg):
 *  1. Platform API: Firebase Crashlytics SDK — FirebaseCrashlytics.getInstance()
 *     (zero-arg; SDK reads config from embedded google-services.json at process
 *     start, no Context needed).
 *  2. Threading/dispatcher: none — Crashlytics buffers internally and flushes on
 *     its own worker; all four methods are fire-and-forget synchronous calls.
 *  3. Error handling: none thrown; recordException is itself the error sink.
 *     setUserId coerces null to empty-string (the documented clear-the-id idiom).
 *  4. DI binding mechanism: plain interface plus per-platform concrete class
 *     (NOT expect-actual); per-platform Koin single is the intended binding —
 *     unwired here as noted above.
 *  5. Contract parity across 3 actuals: this is the ONLY actual that emits to a
 *     real crash backend; iOS and Desktop legs are honest Kermit-logging no-ops.
 *     Method-set parity (recordException, log, setUserId, setCustomKey) holds.
 *  6. Ordering contract: context map fanned out into setCustomKey BEFORE
 *     recordException, because Crashlytics snapshots custom keys at record time.
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class header). The appended block is balanced — one opener, one closer, and
 * zero interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose).
 */
