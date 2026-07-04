package me.manga.kira.platform.update

import co.touchlab.kermit.Logger

/**
 * Desktop actual for [AppUpdateClient] — no-op.
 *
 * Desktop builds have no Play-Store-style in-app update flow; updates are delivered through the
 * installer for the distribution channel (Squirrel.Windows, .pkg, .deb, etc.). Every method logs
 * a breadcrumb and returns the "nothing happened" value.
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/update/AppUpdateClient.desktop.kt`.
 */
class DesktopAppUpdateClient : AppUpdateClient {

    private val log = Logger.withTag(TAG)

    override suspend fun checkForUpdate(): AppUpdateInfo? {
        log.d { "checkForUpdate() — no-op on Desktop, returning null" }
        return null
    }

    override suspend fun startFlexibleUpdate(): Boolean {
        log.d { "startFlexibleUpdate() — no-op on Desktop, returning false" }
        return false
    }

    override suspend fun completeUpdate(): Boolean {
        log.d { "completeUpdate() — no-op on Desktop, returning false" }
        return false
    }

    override fun registerUpdateListener(onDownloaded: () -> Unit) {
        log.d { "registerUpdateListener() — no-op on Desktop" }
    }

    override fun unregisterUpdateListener() {
        log.d { "unregisterUpdateListener() — no-op on Desktop" }
    }

    override suspend fun resumeIfDownloaded(): Boolean {
        log.d { "resumeIfDownloaded() — no-op on Desktop, returning false" }
        return false
    }

    private companion object {
        const val TAG = "AppUpdateClient.desktop"
    }
}

/*
 * §253 audit-trail postscript — cluster276 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-INTERFACE-CONTRACT (Desktop no-op actual, binding pending).
 *
 * Desktop leaf of the 3-actual AppUpdateClient platform-facade fan (Phase 5.z.2
 * relocation, Task #189). The shared commonMain interface
 * me.manga.kira.platform.update.AppUpdateClient plus its AppUpdateInfo data
 * class were swept in cluster149 (Task #605); see that file's appended audit-trail
 * block (lines 43-86) which records the 3 actuals at platform src android-ios-
 * desktop Main update and the deliberate Desktop no-op stance.
 *
 * LIVE evidence: the AppUpdateClient SPI is LIVE. The legacy :shared twin
 * me.manga.kira.core.update.AppUpdateClient is bound at
 * PlatformModule.desktop.kt:107 "single { AppUpdateClient() }" (peers:
 * PlatformModule.android.kt:123, PlatformModule.ios.kt:107) and eager-init'd at
 * app/.../MyApp.kt:115. The interface this Desktop class implements is consumed
 * end-to-end in the legacy graph; the rework :platform class itself is NOT yet
 * bound by any composeApp rework Koin module (grep for DesktopAppUpdateClient /
 * platform.update.AppUpdateClient returns zero hits) — awaiting host wiring.
 * Classified FULFILLED-PORT (relocated + SOLID-audited, SOLID_AUDIT.md File 4 of
 * 4 lines 2886-2895), not STALE, because the implemented interface is LIVE.
 *
 * Delta-axes (this Desktop actual's distinct approach):
 *  1. Platform API — NONE. No Play-Store-equivalent in-app update on Desktop;
 *     updates ship through the distribution channel installer (Squirrel.Windows,
 *     .pkg, .deb). This actual integrates with no platform update API at all.
 *  2. Threading — trivial: suspend functions return synchronously; no dispatcher,
 *     no Task await, no I/O. Each call is a logged constant return.
 *  3. Error handling — no try-catch needed; the methods cannot throw. They log a
 *     Kermit debug breadcrumb then return the SPI safe default (null / false).
 *  4. DI binding mechanism — zero-arg constructor; no Context, no
 *     ForegroundActivityProvider (Desktop has no Android Activity to host a dialog),
 *     unlike the Android actual's two-arg constructor.
 *  5. Behavioural-contract parity across the fan — identical to IosAppUpdateClient:
 *     checkForUpdate returns null, startFlexibleUpdate / completeUpdate return false.
 *     Honours the "nothing available, nothing started" contract so caller UI stays
 *     in its hidden state; only the Android actual does real work.
 *
 * Nested-comment hazard check: this file has exactly 1 pre-existing legitimate
 * comment opener — the class-level KDoc block (lines 5-13) — plus this appended
 * block, for 2 openers total. This appended block is balanced: one opener, one
 * closer, and zero forbidden interior delimiter sequences in the prose.
 */
