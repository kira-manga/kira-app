package me.manga.kira.platform.crash

import co.touchlab.kermit.Logger

/**
 * Desktop actual for [CrashReporter] — no-op.
 *
 * Firebase Crashlytics has no first-party JVM/desktop SDK. `recordException` logs the
 * throwable via Kermit at error level so desktop runs surface the problem on the console
 * rather than swallowing it silently.
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/crash/CrashReporter.desktop.kt`.
 */
class DesktopCrashReporter : CrashReporter {

    private val log = Logger.withTag(TAG)

    override fun recordException(throwable: Throwable, context: Map<String, String>) {
        log.e(throwable) { "recordException(context=$context) — no-op on Desktop" }
    }

    override fun log(message: String) {
        log.d { "log($message) — no-op on Desktop" }
    }

    override fun setUserId(id: String?) {
        log.d { "setUserId($id) — no-op on Desktop" }
    }

    override fun setCustomKey(key: String, value: String) {
        log.d { "setCustomKey($key, $value) — no-op on Desktop" }
    }

    private companion object {
        const val TAG = "CrashReporter.desktop"
    }
}

/*
 * §253 audit-trail postscript — cluster267 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Phase 5.z.6 :platform relocation, Task #193).
 * Unit kind: platform-facade — the Desktop impl of the rework
 * me.manga.kira.platform.crash.CrashReporter interface (commonMain
 * expect-decl swept in cluster148, Task #604; interface lives at
 * platform/src/commonMain/.../crash/CrashReporter.kt). This is a no-op leg of
 * the 3-actual fan — there is no first-party Firebase Crashlytics JVM SDK.
 *
 * LIVE evidence — interface contract is LIVE-NOT-STALE; the no-op rationale is
 * recorded at platform/build.gradle.kts:119-120 ("iOS / Desktop are no-ops ...
 * no Firebase JVM SDK"). The rework per-platform Koin binding is
 * FORECAST-NOT-YET-WIRED: no single resolving this type exists yet under
 * composeApp/di (ReworkModules.kt family carries zero platform.crash
 * reference). The LIVE production consumer today is the LEGACY parallel symbol
 * me.manga.kira.core.crash.CrashReporter, bound per-host in
 * shared/.../di/PlatformModule.desktop.kt (actual fun platformModule at line
 * 60) and consumed by the uncaught-handler at app/.../MyApp.kt:87. This rework
 * :platform Desktop impl is present, compiled, awaiting Phase 8.x wiring.
 *
 * Delta-axes (Desktop leg):
 *  1. Platform API: NONE — Firebase Crashlytics has no JVM/desktop SDK, so this
 *     leg substitutes co.touchlab.kermit.Logger for any backend.
 *  2. Threading/dispatcher: none — Kermit logging is synchronous, fire-and-forget.
 *  3. Error handling: recordException routes the throwable to Kermit at error
 *     level (log dot e) so workstation runs surface the problem on the console
 *     rather than swallowing it silently; the other three methods log at debug.
 *  4. DI binding mechanism: plain interface plus per-platform concrete class;
 *     per-platform Koin single is the intended binding — unwired here.
 *  5. Contract parity across 3 actuals: method-set parity (recordException,
 *     log, setUserId, setCustomKey) holds; this Desktop leg shares the
 *     identical no-op-with-honest-Kermit-fallback contract as the iOS leg,
 *     differing from the Android leg which alone reaches a real backend. The
 *     companion TAG distinguishes Desktop log lines (CrashReporter dot desktop).
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class header). The appended block is balanced — one opener, one closer, and
 * zero interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose).
 */
