package me.manga.kira.platform.ads

import co.touchlab.kermit.Logger

/**
 * Desktop actual for [AdProvider] — no-op.
 *
 * Google Mobile Ads has no first-party JVM/desktop SDK; the desktop build never serves ads.
 * All load/show calls return as if no ad were available, so consumer code paths fall back to
 * their "no-ad" branches.
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/ads/AdProvider.desktop.kt`.
 */
class DesktopAdProvider : AdProvider {

    private val log = Logger.withTag(TAG)

    override suspend fun loadInterstitial(unitId: String): Boolean {
        log.d { "loadInterstitial($unitId) — no-op on Desktop, returning false" }
        return false
    }

    override suspend fun showInterstitial(): AdResult {
        log.d { "showInterstitial() — no-op on Desktop, returning NotLoaded" }
        return AdResult.NotLoaded
    }

    override suspend fun loadRewarded(unitId: String): Boolean {
        log.d { "loadRewarded($unitId) — no-op on Desktop, returning false" }
        return false
    }

    override suspend fun showRewarded(): AdResult {
        log.d { "showRewarded() — no-op on Desktop, returning NotLoaded" }
        return AdResult.NotLoaded
    }

    override fun loadBanner(unitId: String): Boolean {
        log.d { "loadBanner($unitId) — no-op on Desktop, returning false" }
        return false
    }

    private companion object {
        const val TAG = "AdProvider.desktop"
    }
}

/*
 * §253 audit-trail postscript — cluster263 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE (Desktop no-op leaf of the AdProvider 3-actual fan).
 *
 * LIVE evidence:
 *  - The Desktop runtime binding for the ads facade is still the LEGACY :shared no-arg actual:
 *    shared/src/desktopMain/.../di/PlatformModule.desktop.kt:106 declares single { AdProvider() }
 *    against me.manga.kira.core.ads.AdProvider. The sibling leaves bind at
 *    PlatformModule.android.kt:122 and PlatformModule.ios.kt:106.
 *  - The relocated :platform interface lives at platform/src/commonMain/.../platform/ads/
 *    AdProvider.kt:19 (swept in cluster148, Task #604). No rework Koin module nor :composeApp
 *    consumer references me.manga.kira.platform.ads.* yet, so this DesktopAdProvider is the
 *    awaiting-cutover destination half of a strangler-fig relocation — not orphaned.
 *
 * FULFILLED-PORT status: Phase 5.z.4 (Task #191) per-platform relocation of the no-op desktop
 *  impl out of legacy :shared/desktopMain core/ads/AdProvider.desktop.kt into :platform. Verbatim
 *  semantic port — every member returns the "no ad available" branch with a Kermit debug log.
 *
 * Delta-axes (Desktop leaf — distinct approach: pure no-op, no ad SDK):
 *  1. Platform API: none. Google Mobile Ads has no first-party JVM/desktop SDK, so this leaf
 *     carries zero AdMob dependency — the antithesis of the Android leaf's play-services-ads.
 *  2. Threading/dispatcher: trivially main-safe; the suspend functions return synchronously with
 *     no coroutine suspension, no callback bridging, no dispatcher concern at all.
 *  3. Error handling: there is no failure surface — load* returns false and show* returns
 *     AdResult.NotLoaded unconditionally; no try/catch, no sentinel error code is ever produced.
 *  4. DI binding mechanism: Koin single, no-arg constructor (legacy :shared module today). No
 *     Context, no ForegroundActivityProvider — Desktop has neither concept.
 *  5. Contract parity vs Android: identical five-member interface surface and signatures; the
 *     observable difference is that Desktop can never return Shown / EarnedReward / Dismissed,
 *     only the no-ad branch (false / NotLoaded). Consumers therefore fall through to their
 *     no-ad path on Desktop exactly as on iOS — parity confirmed across all three actuals.
 *
 * Nested-comment hazard check: this file has exactly one legitimate KDoc opener (the class-level
 *  doc above line 14). The appended block is balanced — one opener, one closer, and the prose
 *  contains no interior comment delimiters (no slash-star, no star-slash, no slash-star-star).
 */
