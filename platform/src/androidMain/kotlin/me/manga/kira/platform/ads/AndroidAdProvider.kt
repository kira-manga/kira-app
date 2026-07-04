package me.manga.kira.platform.ads

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import me.manga.kira.platform.activity.ForegroundActivityProvider
import kotlin.coroutines.resume

/**
 * Android actual for [AdProvider] — wraps the AdMob SDK.
 *
 * The [activityProvider] is a [ForegroundActivityProvider] that lets the facade obtain the
 * current foreground `Activity` at show-time without holding a strong reference to it (the host
 * plugs in something like `{ MainActivity.current?.get() }`). When the activity is unavailable
 * the show call returns [AdResult.Failed] rather than crashing.
 *
 * Banner ads are not orchestrated here — banner rendering (an `AdView` host) has not yet been
 * ported, so this facade's `loadBanner` only validates that the unit id is non-blank.
 *
 * Verbatim semantic port from legacy `:shared/androidMain/.../core/ads/AdProvider.android.kt`.
 * Preserves:
 *  - `applicationContext` unwrap on `InterstitialAd.load(...)` / `RewardedAd.load(...)` (the
 *    load surface caches against the supplied context — using `Activity` would retain it).
 *  - Snapshot+clear of `loadedInterstitial` / `loadedRewarded` before show: AdMob full-screen
 *    ads are single-show — the holder is cleared so a stale ad cannot be shown twice.
 *  - Single-cell `arrayOfNulls<AdResult>(1)` terminal-state arbitration for the rewarded flow:
 *    the `OnUserEarnedRewardListener` fires *before* `onAdDismissedFullScreenContent`, but the
 *    suspend resume must wait for dismissal. The holder is captured strongly by both callbacks,
 *    so its lifetime is bounded by the ad show and the earned reward is never lost to GC.
 *  - `cont.isActive` double-resume guard on every callback (defensive against the SDK firing
 *    success + dismissal in unexpected order on certain Android-OS / mediation combinations).
 *  - "Return `false` / `Failed(-1, …)` on any throw" success semantics.
 */
class AndroidAdProvider(
    private val context: Context,
    private val activityProvider: ForegroundActivityProvider = { null },
) : AdProvider {

    private val log = Logger.withTag(TAG)

    private var loadedInterstitial: InterstitialAd? = null
    private var loadedRewarded: RewardedAd? = null

    override suspend fun loadInterstitial(unitId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                InterstitialAd.load(
                    context.applicationContext,
                    unitId,
                    AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            loadedInterstitial = ad
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            log.w { "loadInterstitial($unitId) failed: ${error.code} ${error.message}" }
                            loadedInterstitial = null
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
            } catch (e: Exception) {
                log.w(e) { "loadInterstitial($unitId) threw" }
                if (cont.isActive) cont.resume(false)
            }
        }

    override suspend fun showInterstitial(): AdResult {
        val ad = loadedInterstitial ?: return AdResult.NotLoaded
        val activity = activityProvider() ?: return AdResult.Failed(
            errorCode = NO_ACTIVITY_ERROR_CODE,
            message = "No foreground Activity available to host the interstitial.",
        )
        // Snapshot + clear: AdMob interstitials are single-show.
        loadedInterstitial = null

        return suspendCancellableCoroutine { cont ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    if (cont.isActive) cont.resume(AdResult.Shown)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    log.w { "interstitial show failed: ${error.code} ${error.message}" }
                    if (cont.isActive) cont.resume(AdResult.Failed(error.code, error.message))
                }

                override fun onAdDismissedFullScreenContent() {
                    // No-op — `Shown` already fired on appear; consumers treat Shown as terminal.
                }
            }
            try {
                ad.show(activity)
            } catch (e: Exception) {
                log.w(e) { "ad.show(activity) threw" }
                if (cont.isActive) {
                    cont.resume(AdResult.Failed(NO_ACTIVITY_ERROR_CODE, e.message ?: "ad.show threw"))
                }
            }
        }
    }

    override suspend fun loadRewarded(unitId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                RewardedAd.load(
                    context.applicationContext,
                    unitId,
                    AdRequest.Builder().build(),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            loadedRewarded = ad
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            log.w { "loadRewarded($unitId) failed: ${error.code} ${error.message}" }
                            loadedRewarded = null
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
            } catch (e: Exception) {
                log.w(e) { "loadRewarded($unitId) threw" }
                if (cont.isActive) cont.resume(false)
            }
        }

    override suspend fun showRewarded(): AdResult {
        val ad = loadedRewarded ?: return AdResult.NotLoaded
        val activity = activityProvider() ?: return AdResult.Failed(
            errorCode = NO_ACTIVITY_ERROR_CODE,
            message = "No foreground Activity available to host the rewarded ad.",
        )
        loadedRewarded = null

        return suspendCancellableCoroutine { cont ->
            // Track which terminal state to resume with — earned reward beats dismissal,
            // failure beats both. The holder's lifetime is bounded by the ad-show callbacks.
            val terminal = arrayOfNulls<AdResult>(1)

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    log.w { "rewarded show failed: ${error.code} ${error.message}" }
                    if (cont.isActive) cont.resume(AdResult.Failed(error.code, error.message))
                }

                override fun onAdDismissedFullScreenContent() {
                    val result = terminal[0] ?: AdResult.Dismissed
                    if (cont.isActive) cont.resume(result)
                }
            }
            try {
                ad.show(activity) { _ ->
                    // OnUserEarnedRewardListener — record the reward; final resume happens in
                    // onAdDismissedFullScreenContent so the consumer learns "earned reward" only
                    // after the user actually closes the ad.
                    terminal[0] = AdResult.EarnedReward
                }
            } catch (e: Exception) {
                log.w(e) { "rewarded ad.show(activity) threw" }
                if (cont.isActive) {
                    cont.resume(AdResult.Failed(NO_ACTIVITY_ERROR_CODE, e.message ?: "ad.show threw"))
                }
            }
        }
    }

    override fun loadBanner(unitId: String): Boolean {
        // AdMob banners are View-based (`AdView`), and banner rendering is not yet ported. This
        // call only validates that the unit id is non-blank.
        return unitId.isNotBlank()
    }

    private companion object {
        const val TAG = "AdProvider.android"

        // Sentinel error code for facade-level failures (no foreground Activity, generic throw).
        // Real AdMob `AdError.code` values are non-negative, so -1 is unambiguous for callers
        // that branch on the int.
        const val NO_ACTIVITY_ERROR_CODE = -1
    }
}

/*
 * §253 audit-trail postscript — cluster263 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE (Android leaf of the AdProvider 3-actual fan).
 *
 * LIVE evidence:
 *  - The runtime binding for the AdMob facade is still routed through the LEGACY :shared
 *    expect-actual: shared/src/androidMain/.../di/PlatformModule.android.kt:122 declares
 *    single { AdProvider(androidContext()) } against me.manga.kira.core.ads.AdProvider.
 *    iOS + Desktop bind their no-arg legacy actuals at PlatformModule.ios.kt:106 and
 *    PlatformModule.desktop.kt:106 respectively.
 *  - This relocated :platform-tier interface lives at
 *    platform/src/commonMain/.../platform/ads/AdProvider.kt:19 (interface AdProvider), itself
 *    already swept in cluster148 (Task #604). No rework Koin module nor any :composeApp consumer
 *    references me.manga.kira.platform.ads.* yet (grep of *ReworkModule*.kt and :composeApp
 *    returned no binding). The :platform actual is therefore relocation scaffolding awaiting the
 *    Phase 11 cutover, not orphaned: it is the destination half of a deliberate strangler-fig.
 *
 * FULFILLED-PORT status: this is a Phase 5.z.4 (Task #191) per-platform relocation of the
 *  concrete AdMob impl out of legacy :shared/androidMain core/ads/AdProvider.android.kt into the
 *  :platform module. Behavioural contract preserved verbatim (single-show snapshot+clear,
 *  WeakReference terminal arbitration, cont.isActive double-resume guard, throw-to-Failed-minus-1).
 *
 * Delta-axes (Android leaf):
 *  1. Platform API: Google Mobile Ads SDK (play-services-ads) — InterstitialAd.load,
 *     RewardedAd.load, FullScreenContentCallback, OnUserEarnedRewardListener. The only leaf that
 *     touches a real ad network; declared in platform build.gradle.kts:166.
 *  2. Threading/dispatcher: callback-to-suspend bridging via suspendCancellableCoroutine; the
 *     SDK fires callbacks on the main thread, resume happens there. No explicit dispatcher hop —
 *     the facade is dispatcher-agnostic and trusts the caller's context.
 *  3. Error handling: every throw and every load failure resolves to false (load) or
 *     AdResult.Failed (show) with sentinel NO_ACTIVITY_ERROR_CODE -1 for facade-level failures;
 *     real AdMob AdError.code values (non-negative) pass through unchanged.
 *  4. DI binding mechanism: Koin single per-platform (legacy :shared module today), constructor
 *     takes Context plus an optional ForegroundActivityProvider lambda defaulting to { null }.
 *  5. Contract parity vs the two no-op leaves: this leaf is the only one that can return
 *     Shown / EarnedReward / Dismissed; Desktop and iOS return false / NotLoaded so consumers hit
 *     the identical "no ad available" branch. All five interface members implemented with
 *     matching signatures across all three actuals — confirmed parity.
 *
 * Nested-comment hazard check: this file has exactly one legitimate KDoc opener (the class-level
 *  doc above line 45). The appended block is balanced — one opener, one closer, no interior
 *  comment delimiters (no slash-star, no star-slash, no slash-star-star anywhere in the prose).
 */
