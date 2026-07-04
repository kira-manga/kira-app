package me.manga.kira.platform.update

import co.touchlab.kermit.Logger

/**
 * iOS actual for [AppUpdateClient] — no-op.
 *
 * App Store handles updates outside the app on iOS; there is no equivalent in-app flow to drive.
 * Every method logs a breadcrumb and returns the "nothing happened" value so consumer code falls
 * through to its hidden state.
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/update/AppUpdateClient.ios.kt`.
 */
class IosAppUpdateClient : AppUpdateClient {

    private val log = Logger.withTag(TAG)

    override suspend fun checkForUpdate(): AppUpdateInfo? {
        log.d { "checkForUpdate() — no-op on iOS, returning null" }
        return null
    }

    override suspend fun startFlexibleUpdate(): Boolean {
        log.d { "startFlexibleUpdate() — no-op on iOS, returning false" }
        return false
    }

    override suspend fun completeUpdate(): Boolean {
        log.d { "completeUpdate() — no-op on iOS, returning false" }
        return false
    }

    override fun registerUpdateListener(onDownloaded: () -> Unit) {
        log.d { "registerUpdateListener() — no-op on iOS" }
    }

    override fun unregisterUpdateListener() {
        log.d { "unregisterUpdateListener() — no-op on iOS" }
    }

    override suspend fun resumeIfDownloaded(): Boolean {
        log.d { "resumeIfDownloaded() — no-op on iOS, returning false" }
        return false
    }

    private companion object {
        const val TAG = "AppUpdateClient.ios"
    }
}

/*
 * §253 audit-trail postscript — cluster276 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-INTERFACE-CONTRACT (iOS no-op actual, binding pending).
 *
 * iOS leaf of the 3-actual AppUpdateClient platform-facade fan (Phase 5.z.2
 * relocation, Task #189). The shared commonMain interface
 * me.manga.kira.platform.update.AppUpdateClient plus its AppUpdateInfo data
 * class were swept in cluster149 (Task #605); that file's appended audit-trail
 * block (lines 43-86) records the 3 actuals at platform src android-ios-desktop
 * Main update and the deliberate iOS no-op stance (App Store handles updates
 * outside the app).
 *
 * LIVE evidence: the AppUpdateClient SPI is LIVE. The legacy :shared twin
 * me.manga.kira.core.update.AppUpdateClient is bound at
 * PlatformModule.ios.kt:107 "single { AppUpdateClient() }" (peers:
 * PlatformModule.android.kt:123, PlatformModule.desktop.kt:107) and eager-init'd
 * at app/.../MyApp.kt:115. The interface this iOS class implements is consumed
 * end-to-end in the legacy graph; the rework :platform class itself is NOT yet
 * bound by any composeApp rework Koin module (grep for IosAppUpdateClient /
 * platform.update.AppUpdateClient returns zero hits) — awaiting host wiring.
 * Classified FULFILLED-PORT (relocated + SOLID-audited, SOLID_AUDIT.md File 3 of
 * 4 lines 2875-2884), not STALE, because the implemented interface is LIVE.
 *
 * Delta-axes (this iOS actual's distinct approach):
 *  1. Platform API — NONE. iOS updates are driven by the App Store outside the
 *     app; there is no in-app StoreKit equivalent to Play Core's update flow, so
 *     this actual integrates with no platform update API.
 *  2. Threading — trivial: suspend functions return synchronously; no dispatcher
 *     hop, no async work, no I/O. Each call is a logged constant return.
 *  3. Error handling — no try-catch needed; the methods cannot throw. They log a
 *     Kermit debug breadcrumb then return the SPI safe default (null / false).
 *  4. DI binding mechanism — zero-arg constructor; no Context, no
 *     ForegroundActivityProvider, mirroring DesktopAppUpdateClient and contrasting
 *     with the Android actual's two-arg constructor.
 *  5. Behavioural-contract parity across the fan — byte-for-byte identical to
 *     DesktopAppUpdateClient (same three returns, same tag-suffix pattern). Honours
 *     the "nothing available, nothing started" contract so caller UI stays hidden;
 *     only the Android actual performs a real Play Core update flow.
 *
 * Nested-comment hazard check: this file has exactly 1 pre-existing legitimate
 * comment opener — the class-level KDoc block (lines 5-13) — plus this appended
 * block, for 2 openers total. This appended block is balanced: one opener, one
 * closer, and zero forbidden interior delimiter sequences in the prose.
 */
