package me.manga.kira.platform.ads

import co.touchlab.kermit.Logger

/**
 * iOS actual for [AdProvider] — no-op.
 *
 * Google Mobile Ads iOS SDK is not wired in Phase 8; rewarded / interstitial flows on iOS will
 * be added in a later phase (likely Phase 12 alongside the Firebase iOS integration). All
 * load/show calls return as if no ad were available, so consumer code paths fall back to their
 * "no-ad" branches.
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/ads/AdProvider.ios.kt`.
 */
class IosAdProvider : AdProvider {

    private val log = Logger.withTag(TAG)

    override suspend fun loadInterstitial(unitId: String): Boolean {
        log.d { "loadInterstitial($unitId) — no-op on iOS, returning false" }
        return false
    }

    override suspend fun showInterstitial(): AdResult {
        log.d { "showInterstitial() — no-op on iOS, returning NotLoaded" }
        return AdResult.NotLoaded
    }

    override suspend fun loadRewarded(unitId: String): Boolean {
        log.d { "loadRewarded($unitId) — no-op on iOS, returning false" }
        return false
    }

    override suspend fun showRewarded(): AdResult {
        log.d { "showRewarded() — no-op on iOS, returning NotLoaded" }
        return AdResult.NotLoaded
    }

    override fun loadBanner(unitId: String): Boolean {
        log.d { "loadBanner($unitId) — no-op on iOS, returning false" }
        return false
    }

    private companion object {
        const val TAG = "AdProvider.ios"
    }
}

/*
 * §253 audit-trail postscript — cluster263 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE (iOS no-op leaf of the AdProvider 3-actual fan).
 *
 * LIVE evidence:
 *  - The iOS runtime binding for the ads facade is still the LEGACY :shared no-arg actual:
 *    shared/src/iosMain/.../di/PlatformModule.ios.kt:106 declares single { AdProvider() } against
 *    me.manga.kira.core.ads.AdProvider. Sibling leaves bind at PlatformModule.android.kt:122
 *    and PlatformModule.desktop.kt:106.
 *  - The relocated :platform interface lives at platform/src/commonMain/.../platform/ads/
 *    AdProvider.kt:19 (swept in cluster148, Task #604). No rework Koin module nor :composeApp
 *    consumer references me.manga.kira.platform.ads.* yet; this IosAdProvider is the
 *    awaiting-cutover destination half of the strangler-fig relocation — not orphaned.
 *
 * FULFILLED-PORT status: Phase 5.z.4 (Task #191) per-platform relocation of the no-op iOS impl
 *  out of legacy :shared/iosMain core/ads/AdProvider.ios.kt into :platform. Verbatim semantic
 *  port — every member returns the "no ad available" branch with a Kermit debug log.
 *
 * NOTE on the deferred-integration forecast: the class doc above predicts Google Mobile Ads iOS
 *  SDK wiring "in a later phase (likely Phase 12 alongside Firebase iOS integration)". As of this
 *  sweep that forecast remains UNREALIZED — no rework iOS slice has integrated the iOS ad SDK; the
 *  parent interface's cluster148 postscript already recorded this as deferred indefinitely (the
 *  iOS Free tier serves no ads). The forecast is documented, not yet fulfilled.
 *
 * Delta-axes (iOS leaf — distinct approach: pure no-op pending deferred SDK wiring):
 *  1. Platform API: none today. Unlike the Android leaf (play-services-ads), this leaf wires no
 *     ad SDK; the eventual Google Mobile Ads iOS SDK would arrive via a CocoaPods/cinterop bridge.
 *  2. Threading/dispatcher: trivially main-safe; suspend functions return synchronously with no
 *     coroutine suspension and no callback bridging.
 *  3. Error handling: no failure surface — load* returns false and show* returns
 *     AdResult.NotLoaded unconditionally; no try/catch, no sentinel error code.
 *  4. DI binding mechanism: Koin single, no-arg constructor (legacy :shared module today). No
 *     Context, no ForegroundActivityProvider — iOS hosting differs structurally from Android.
 *  5. Contract parity vs Android + Desktop: identical five-member interface surface; iOS, like
 *     Desktop, can return only the no-ad branch (false / NotLoaded), so consumers fall through to
 *     their no-ad path identically across all three actuals — parity confirmed.
 *
 * Nested-comment hazard check: this file has exactly one legitimate KDoc opener (the class-level
 *  doc above line 15). The appended block is balanced — one opener, one closer, and the prose
 *  contains no interior comment delimiters (no slash-star, no star-slash, no slash-star-star).
 */
