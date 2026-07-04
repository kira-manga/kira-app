package me.manga.yamiapk.ad_mob.rewarded

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.yamiapk.ad_mob.AdConfig
import me.manga.yamiapk.ad_mob.AdLoadResult
import me.manga.yamiapk.ad_mob.AdShowResult
import me.manga.yamiapk.ad_mob.AdState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton manager for Rewarded Ads.
 *
 * Features:
 * - Preloads ad on app start
 * - Automatically preloads next ad after impression/dismiss
 * - Thread-safe state management
 * - Exponential backoff retry on failures
 * - FIX: Added timeout for stuck "showing" state
 */
@Singleton
class RewardedAdManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    @Volatile
    private var rewardedAd: RewardedAd? = null

    private val _state = MutableStateFlow<AdState>(AdState.Idle)
    val state: StateFlow<AdState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryCount = AtomicInteger(0)

    private val isLoadingFlag = AtomicBoolean(false)
    private val isShowingFlag = AtomicBoolean(false)

    @Volatile
    private var currentAdUnitId: String = AdConfig.REWARDED_AD_UNIT_ID

    // FIX: Runnable for showing timeout reset
    private var showingTimeoutRunnable: Runnable? = null

    fun initialize(context: Context) {
        currentAdUnitId = AdConfig.REWARDED_AD_UNIT_ID
        Log.i("testprodactioncurrentAdUnitId",currentAdUnitId.toString())

        preload()
    }

    fun preload() {
        if (isLoadingFlag.get() || rewardedAd != null) {
            Log.d(TAG, "Preload skipped - already loading or ready")
            return
        }
        load(currentAdUnitId, null)
    }

    fun load(
        adUnitId: String = AdConfig.REWARDED_AD_UNIT_ID,
        onComplete: ((AdLoadResult) -> Unit)?
    ) {

        Log.i("testprodaction",adUnitId.toString())
        if (!isLoadingFlag.compareAndSet(false, true)) {
            Log.d(TAG, "Load request ignored - already loading")
            onComplete?.invoke(AdLoadResult.Failed("Already loading"))
            return
        }

        if (rewardedAd != null) {
            Log.d(TAG, "Load request ignored - ad already ready")
            isLoadingFlag.set(false)
            onComplete?.invoke(AdLoadResult.Success)
            return
        }

        if (isShowingFlag.get()) {
            Log.d(TAG, "Load request ignored - ad currently showing")
            isLoadingFlag.set(false)
            onComplete?.invoke(AdLoadResult.Failed("Ad currently showing"))
            return
        }

        _state.value = AdState.Loading
        currentAdUnitId = adUnitId
        Log.d(TAG, "Starting ad load")

        val adRequest = AdRequest.Builder().build()

        RewardedAd.load(appContext, adUnitId, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.e(TAG, "Ad failed to load: ${error.message} (code: ${error.code})")

                val currentRetry = retryCount.incrementAndGet()
                if (currentRetry <= AdConfig.MAX_RETRY_COUNT) {
                    val delay = AdConfig.INITIAL_RETRY_DELAY_MS * (1L shl (currentRetry - 1))
                    Log.w(TAG, "Retrying (attempt $currentRetry/${AdConfig.MAX_RETRY_COUNT}) after ${delay}ms")

                    _state.value = AdState.Idle
                    isLoadingFlag.set(false)

                    mainHandler.postDelayed({ load(adUnitId, onComplete) }, delay)
                } else {
                    Log.e(TAG, "Failed after ${AdConfig.MAX_RETRY_COUNT} attempts")
                    _state.value = AdState.Error(error.message, error.code)
                    retryCount.set(0)
                    isLoadingFlag.set(false)
                    onComplete?.invoke(AdLoadResult.Failed(error.message, error.code))
                }
            }

            override fun onAdLoaded(ad: RewardedAd) {
                Log.d(TAG, "Ad loaded successfully")
                rewardedAd = ad
                _state.value = AdState.Ready
                retryCount.set(0)
                isLoadingFlag.set(false)
                onComplete?.invoke(AdLoadResult.Success)
            }
        })
    }

    /**
     * Show the rewarded ad.
     *
     * @param context Activity context required for showing fullscreen ad
     * @param onResult Callback with the result of showing the ad
     * @return true if show was initiated, false otherwise
     */
    fun show(context: Context, onResult: (AdShowResult) -> Unit): Boolean {
        if (!isShowingFlag.compareAndSet(false, true)) {
            Log.w(TAG, "Cannot show - already showing an ad")
            onResult(AdShowResult.Failed("Already showing an ad"))
            return false
        }

        val ad = rewardedAd
        if (ad == null) {
            Log.w(TAG, "Cannot show - ad not ready")
            isShowingFlag.set(false)
            _state.value = AdState.Idle
            onResult(AdShowResult.Failed("Ad not ready"))
            return false
        }

        val activity = context.findActivity()
        if (activity == null) {
            Log.e(TAG, "Cannot show - activity not found")
            isShowingFlag.set(false)
            onResult(AdShowResult.Failed("Activity not found"))
            return false
        }

        if (activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "Cannot show - activity invalid")
            isShowingFlag.set(false)
            onResult(AdShowResult.Failed("Activity invalid"))
            return false
        }

        _state.value = AdState.Showing

        // FIX: Set up timeout to reset stuck showing state
        setupShowingTimeout(onResult)

        var rewardEarned = false
        var resultDelivered = false

        val showRunnable = Runnable {
            if (activity.isFinishing || activity.isDestroyed) {
                Log.e(TAG, "Activity became invalid before showing")
                cleanupAfterShow()
                if (!resultDelivered) {
                    resultDelivered = true
                    onResult(AdShowResult.Failed("Activity no longer valid"))
                }
                schedulePreload()
                return@Runnable
            }

            val currentAd = rewardedAd
            if (currentAd == null) {
                Log.e(TAG, "Ad became null before showing")
                isShowingFlag.set(false)
                _state.value = AdState.Idle
                cancelShowingTimeout()
                if (!resultDelivered) {
                    resultDelivered = true
                    onResult(AdShowResult.Failed("Ad no longer available"))
                }
                schedulePreload()
                return@Runnable
            }

            currentAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    Log.d(TAG, "Ad showed fullscreen")
                    rewardedAd = null
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    Log.e(TAG, "Failed to show: ${error.message}")
                    cleanupAfterShow()
                    if (!resultDelivered) {
                        resultDelivered = true
                        onResult(AdShowResult.Failed(error.message))
                    }
                    schedulePreload()
                }

                override fun onAdDismissedFullScreenContent() {
                    Log.d(TAG, "Ad dismissed (rewardEarned: $rewardEarned)")
                    cleanupAfterShow()

                    if (!rewardEarned && !resultDelivered) {
                        resultDelivered = true
                        onResult(AdShowResult.Dismissed)
                    }
                    schedulePreload()
                }

                override fun onAdImpression() {
                    Log.d(TAG, "Ad impression recorded")
                }
            }

            try {
                currentAd.show(activity) { reward ->
                    Log.d(TAG, "Reward earned: ${reward.type} x${reward.amount}")
                    rewardEarned = true
                    if (!resultDelivered) {
                        resultDelivered = true
                        onResult(AdShowResult.RewardEarned(reward.type, reward.amount))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception showing ad", e)
                cleanupAfterShow()
                if (!resultDelivered) {
                    resultDelivered = true
                    onResult(AdShowResult.Failed("Exception: ${e.message}"))
                }
                schedulePreload()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            showRunnable.run()
        } else {
            mainHandler.post(showRunnable)
        }

        return true
    }

    // FIX: Setup timeout to prevent permanently stuck showing state
    private fun setupShowingTimeout(onResult: (AdShowResult) -> Unit) {
        cancelShowingTimeout()
        showingTimeoutRunnable = Runnable {
            if (isShowingFlag.get()) {
                Log.e(TAG, "Showing timeout reached - force resetting state")
                cleanupAfterShow()
                onResult(AdShowResult.Failed("Ad showing timed out"))
                schedulePreload()
            }
        }
        mainHandler.postDelayed(showingTimeoutRunnable!!, AdConfig.SHOWING_TIMEOUT_MS)
    }

    // FIX: Cancel timeout when ad completes normally
    private fun cancelShowingTimeout() {
        showingTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            showingTimeoutRunnable = null
        }
    }

    private fun cleanupAfterShow() {
        cancelShowingTimeout()
        rewardedAd = null
        isShowingFlag.set(false)
        _state.value = AdState.Idle
    }

    private fun schedulePreload() {
        mainHandler.postDelayed({ preload() }, AdConfig.PRELOAD_DELAY_AFTER_DISMISS_MS)
    }

    fun isReady(): Boolean = rewardedAd != null && !isShowingFlag.get()
    fun isLoading(): Boolean = isLoadingFlag.get()
    fun isShowing(): Boolean = isShowingFlag.get()

    fun destroy() {
        Log.d(TAG, "Destroying RewardedAdManager")
        cancelShowingTimeout()
        mainHandler.removeCallbacksAndMessages(null)
        rewardedAd?.fullScreenContentCallback = null
        rewardedAd = null
        _state.value = AdState.Idle
        retryCount.set(0)
        isLoadingFlag.set(false)
        isShowingFlag.set(false)
    }

    fun getStateDescription(): String =
        "State: ${_state.value}, Ready: ${isReady()}, Loading: ${isLoading()}, Showing: ${isShowing()}"

    private fun Context.findActivity(): Activity? {
        var ctx: Context = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    companion object {
        private const val TAG = "RewardedAdManager"
    }
}