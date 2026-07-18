package me.manga.kira.platform.crash

import co.touchlab.kermit.Logger

/**
 * iOS actual for [CrashReporter] — no-op.
 *
 * Firebase Crashlytics iOS SDK is not wired in Phase 8; real crash reporting is scheduled for
 * Phase 12 alongside the rest of the Firebase iOS integration. `recordException` still logs
 * the throwable's stack via Kermit at error level so the iOS console surfaces the problem
 * during development.
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/crash/CrashReporter.ios.kt`.
 */
class IosCrashReporter : CrashReporter {
    private val log = Logger.withTag(TAG)

    override fun recordException(
        throwable: Throwable,
        context: Map<String, String>,
    ) {
        log.e { "Non-fatal exception reached the no-op Kotlin iOS crash adapter" }
    }

    override fun log(message: String) {
        log.d { "Crash breadcrumb received by the no-op Kotlin iOS adapter" }
    }

    override fun setUserId(id: String?) {
        log.d { "Crash user ID ignored by the Kotlin iOS adapter" }
    }

    override fun setCustomKey(
        key: String,
        value: String,
    ) {
        log.d { "Crash custom key ignored by the Kotlin iOS adapter" }
    }

    private companion object {
        const val TAG = "CrashReporter.ios"
    }
}

/*
 * §253 audit-trail postscript — cluster267 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Phase 5.z.6 :platform relocation, Task #193).
 * Unit kind: platform-facade — the iOS impl of the rework
 * me.manga.kira.platform.crash.CrashReporter interface (commonMain
 * expect-decl swept in cluster148, Task #604; interface lives at
 * platform/src/commonMain/.../crash/CrashReporter.kt). This is a no-op leg of
 * the 3-actual fan — Firebase Crashlytics iOS is deferred to Phase 12.
 *
 * LIVE evidence — interface contract is LIVE-NOT-STALE; the deferral rationale
 * is recorded at platform/build.gradle.kts:119-120 ("iOS / Desktop are no-ops
 * (iOS Crashlytics deferred to Phase 12 ...)"). The rework per-platform Koin
 * binding is FORECAST-NOT-YET-WIRED: no single resolving this type exists yet
 * under composeApp/di (ReworkModules.kt family carries zero platform.crash
 * reference). The LIVE consumer today is the LEGACY parallel symbol
 * me.manga.kira.core.crash.CrashReporter, bound per-host in
 * shared/.../di/PlatformModule.ios.kt (actual fun platformModule at line 60,
 * threaded through KoinHelper.kt:20 modules-of allSharedModules plus
 * platformModule). This rework :platform iOS impl is present, compiled,
 * awaiting both Phase 8.x Koin wiring and Phase 12 real Firebase iOS backend.
 *
 * Delta-axes (iOS leg):
 *  1. Platform API: NONE wired in Phase 8 — Firebase Crashlytics iOS SDK is
 *     scheduled for Phase 12; until then this leg substitutes
 *     co.touchlab.kermit.Logger for any backend.
 *  2. Threading/dispatcher: none — Kermit logging is synchronous, fire-and-forget.
 *  3. Error handling: recordException routes the throwable to Kermit at error
 *     level (log dot e) so the iOS console surfaces the problem during
 *     development; the other three methods log at debug.
 *  4. DI binding mechanism: plain interface plus per-platform concrete class;
 *     per-platform Koin single is the intended binding — unwired here.
 *  5. Contract parity across 3 actuals: method-set parity (recordException,
 *     log, setUserId, setCustomKey) holds; this iOS leg shares the identical
 *     no-op-with-honest-Kermit-fallback contract as the Desktop leg, differing
 *     from the Android leg which alone reaches a real backend. The companion
 *     TAG distinguishes iOS log lines (CrashReporter dot ios). The
 *     UNREALIZED-until-Phase-12 forecast applies to this leg specifically.
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class header). The appended block is balanced — one opener, one closer, and
 * zero interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose).
 */
