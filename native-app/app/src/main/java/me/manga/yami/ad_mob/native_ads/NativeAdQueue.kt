package me.manga.yamiapk.ad_mob.native_ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.yamiapk.ad_mob.AdConfig
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Ad Queue with SDK-based impression tracking.
 *
 * CRITICAL FIX: Now uses serial loading by default to prevent:
 * - Burst loading that could cause ANR
 * - Rate limiting from AdMob
 * - Memory pressure from simultaneous ad loads
 *
 * Key improvement: Uses AdMob SDK's onAdImpression() callback which is the
 * ONLY policy-compliant way to track impressions.
 *
 * How it works:
 * 1. When ad loads, we store it with a unique adId
 * 2. When assigned to a position, we map position -> adId
 * 3. When onAdImpression fires (SDK confirmed visible), we find the position by adId
 * 4. Our internal state syncs with what AdMob dashboard will show
 */
@Singleton
class NativeAdQueue @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var contextRef: WeakReference<Context>? = null

    // Unique ID generator for ads
    private val adIdGenerator = AtomicLong(0)
    private val lastNoFillTime = AtomicLong(0)
    private val NO_FILL_BACKOFF_MS = 30_000L // Wait 60s after no-fill before trying again

    // ═══════════════════════════════════════════════════════════════════════════
    // CRITICAL FIX: Serial loading configuration
    // ═══════════════════════════════════════════════════════════════════════════
    private val SERIAL_LOAD_DELAY_MS = 2000L // 2 seconds between loads
    private val useSerialLoading = true // Set to false to revert to parallel loading

    /**
     * Wrapper to hold ad + its unique ID for tracking
     */
    data class AdWithCallback(
        val ad: NativeAd,
        val adId: Long,
        val onImpression: () -> Unit
    )

    // Available ads pool (not yet assigned to any position)
    private val availableAds = ConcurrentLinkedQueue<AdWithCallback>()

    // Ads assigned to positions but NOT YET impressed
    private val pendingImpressionAds = ConcurrentHashMap<Int, AdWithCallback>()

    // Ads that have received impressions (can be destroyed on dispose)
    private val impressedAds = ConcurrentHashMap<Int, NativeAd>()

    // Track which positions have received SDK impressions
    private val sdkImpressionReceived = ConcurrentHashMap<Int, Boolean>()

    // Map adId -> position for reverse lookup when SDK fires onAdImpression
    private val adIdToPosition = ConcurrentHashMap<Long, Int>()

    private val currentlyLoading = AtomicInteger(0)
    private val totalLoaded = AtomicInteger(0)
    private val totalFailed = AtomicInteger(0)
    private val isPreloadInProgress = AtomicBoolean(false)

    // Backoff tracking
    private val consecutiveFailures = AtomicInteger(0)
    private val isInCooldown = AtomicBoolean(false)

    @Volatile
    private var cooldownEndTime = 0L  // FIX: Added @Volatile for thread safety

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _availableCount = MutableStateFlow(0)
    val availableCount: StateFlow<Int> = _availableCount.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _isInBackoff = MutableStateFlow(false)
    val isInBackoff: StateFlow<Boolean> = _isInBackoff.asStateFlow()

    @Volatile
    private var adUnitId: String = AdConfig.NATIVE_AD_UNIT_ID

    fun initialize(context: Context, adUnitId: String? = null) {
        contextRef = WeakReference(context.applicationContext)
        this.adUnitId = adUnitId ?: AdConfig.getNativeAdUnitId()

        Log.i("testprodactionadUnitId",this.adUnitId.toString())

        Log.d(TAG, "NativeAdQueue initialized with unit ID: ${this.adUnitId.take(20)}...")
    }

    /**
     * Preload native ads.
     *
     * CRITICAL FIX: Now uses serial loading by default to prevent burst loading.
     */
    fun preloadAds(
        context: Context,
        count: Int = DEFAULT_POOL_SIZE,
        onComplete: ((loaded: Int, failed: Int) -> Unit)? = null
    ) {
        // Check cooldown
        if (isInCooldown.get()) {
            val remainingCooldown = cooldownEndTime - System.currentTimeMillis()
            if (remainingCooldown > 0) {
                Log.w(TAG, "In cooldown period. ${remainingCooldown / 1000}s remaining.")
                onComplete?.invoke(0, 0)
                return
            } else {
                isInCooldown.set(false)
                _isInBackoff.value = false
                consecutiveFailures.set(0)
            }
        }

        val timeSinceNoFill = System.currentTimeMillis() - lastNoFillTime.get()
        if (timeSinceNoFill < NO_FILL_BACKOFF_MS && lastNoFillTime.get() > 0) {
            Log.d(TAG, "Skipping preload - no-fill backoff (${(NO_FILL_BACKOFF_MS - timeSinceNoFill) / 1000}s remaining)")
            onComplete?.invoke(0, 0)
            return
        }

        if (!isPreloadInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Preload already in progress, skipping")
            onComplete?.invoke(0, 0)
            return
        }

        val loadCount = count.coerceIn(1, MAX_POOL_SIZE)
        val appContextLocal = context.applicationContext
        contextRef = WeakReference(appContextLocal)

        val currentTotal = availableAds.size + pendingImpressionAds.size + impressedAds.size
        if (currentTotal >= loadCount) {
            Log.d(TAG, "Already have $currentTotal ads, skipping preload")
            isPreloadInProgress.set(false)
            onComplete?.invoke(currentTotal, 0)
            return
        }

        val toLoad = loadCount - currentTotal
        Log.d(TAG, "Preloading $toLoad native ads (serial mode: $useSerialLoading)")

        _isLoading.value = true
        totalLoaded.set(0)
        totalFailed.set(0)
        currentlyLoading.set(toLoad)

        if (useSerialLoading) {
            // ═══════════════════════════════════════════════════════════════════════════
            // CRITICAL FIX: Use serial loading to prevent burst loading
            // ═══════════════════════════════════════════════════════════════════════════
            preloadSerially(appContextLocal, toLoad, onComplete)
        } else {
            // Legacy parallel loading (kept for reference, but not recommended)
            preloadParallel(appContextLocal, toLoad, onComplete)
        }
    }

    /**
     * CRITICAL FIX: Serial loading implementation.
     *
     * Loads ads one at a time with a delay between each load.
     * This prevents:
     * - ANR from burst loading
     * - Rate limiting from AdMob
     * - Memory pressure
     */
    private fun preloadSerially(
        context: Context,
        remaining: Int,
        onComplete: ((loaded: Int, failed: Int) -> Unit)?
    ) {
        if (remaining <= 0) {
            // All ads loaded or attempted
            _isLoading.value = false
            isPreloadInProgress.set(false)
            updateCounts()
            onComplete?.invoke(totalLoaded.get(), totalFailed.get())
            Log.d(TAG, "Serial preload complete: ${totalLoaded.get()} loaded, ${totalFailed.get()} failed")
            return
        }

        val index = currentlyLoading.get() - remaining + 1

        loadSingleAd(context, index) { success ->
            val nextRemaining = remaining - 1

            if (nextRemaining > 0) {
                // Calculate delay - use backoff if we've had failures
                val delay = if (consecutiveFailures.get() > 0) {
                    // Longer delay after failures
                    SERIAL_LOAD_DELAY_MS + calculateBackoffDelay(consecutiveFailures.get())
                } else {
                    SERIAL_LOAD_DELAY_MS
                }

                Log.d(TAG, "Scheduling next ad load in ${delay}ms (remaining: $nextRemaining)")
                mainHandler.postDelayed({
                    preloadSerially(context, nextRemaining, onComplete)
                }, delay)
            } else {
                // Last ad, finish up
                _isLoading.value = false
                isPreloadInProgress.set(false)
                updateCounts()
                onComplete?.invoke(totalLoaded.get(), totalFailed.get())
                Log.d(TAG, "Serial preload complete: ${totalLoaded.get()} loaded, ${totalFailed.get()} failed")
            }
        }
    }

    /**
     * Legacy parallel loading (not recommended).
     * Kept for comparison and potential A/B testing.
     */
    private fun preloadParallel(
        context: Context,
        toLoad: Int,
        onComplete: ((loaded: Int, failed: Int) -> Unit)?
    ) {
        repeat(toLoad) { index ->
            val delay = if (consecutiveFailures.get() > 0) {
                calculateBackoffDelay(consecutiveFailures.get()) * index / 2
            } else {
                index * 100L
            }

            mainHandler.postDelayed({
                loadSingleAd(context, index) {
                    val remaining = currentlyLoading.decrementAndGet()
                    if (remaining <= 0) {
                        _isLoading.value = false
                        isPreloadInProgress.set(false)
                        updateCounts()
                        onComplete?.invoke(totalLoaded.get(), totalFailed.get())
                        Log.d(TAG, "Parallel preload complete: ${totalLoaded.get()} loaded, ${totalFailed.get()} failed")
                    }
                }
            }, delay)
        }
    }

    /**
     * Load a single ad.
     *
     * @param context Application context
     * @param index Index for logging
     * @param onDone Callback with success status
     */
    private fun loadSingleAd(context: Context, index: Int, onDone: (success: Boolean) -> Unit) {
        val adId = adIdGenerator.incrementAndGet()

        Log.i("testprodactionloadSingleAd",adUnitId.toString())

        val adLoader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { ad ->
                Log.d(TAG, "Ad #$index loaded successfully (adId: $adId)")

                val adWithCallback = AdWithCallback(
                    ad = ad,
                    adId = adId,
                    onImpression = {
                        Log.d(TAG, "SDK onAdImpression fired for adId: $adId")
                        handleSdkImpression(adId)
                    }
                )

                availableAds.offer(adWithCallback)
                totalLoaded.incrementAndGet()
                consecutiveFailures.set(0)
                _isInBackoff.value = false
                updateCounts()

                ad.setOnPaidEventListener { adValue ->
                    Log.d(TAG, "Paid event: ${adValue.valueMicros} ${adValue.currencyCode}")
                }
                onDone(true)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e(TAG, "Ad #$index failed: ${error.message} (code: ${error.code})")
                    totalFailed.incrementAndGet()
                    handleLoadFailure(error)
                    onDone(false)
                }

                override fun onAdImpression() {
                    Log.d(TAG, "✅ SDK onAdImpression fired for adId: $adId")
                    markSdkImpression(adId)
                }

                override fun onAdClicked() {
                    Log.d(TAG, "Ad adId:$adId clicked")
                }

                override fun onAdOpened() {
                    Log.d(TAG, "Ad adId:$adId opened")
                }

                override fun onAdClosed() {
                    Log.d(TAG, "Ad adId:$adId closed")
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_LEFT)
                    .build()
            )
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    /**
     * Called when SDK's onAdImpression fires.
     * This is the ONLY time we should count an impression.
     */
    private fun markSdkImpression(adId: Long) {
        val position = adIdToPosition[adId]
        if (position == null) {
            Log.w(TAG, "SDK impression fired for adId $adId but no position mapping found")
            return
        }

        val adWrapper = pendingImpressionAds.remove(position)
        if (adWrapper != null) {
            impressedAds[position] = adWrapper.ad
            sdkImpressionReceived[position] = true
            adIdToPosition.remove(adId)
            Log.d(TAG, "✅ SDK impression confirmed for position $position (adId: $adId)")
            updateCounts()
        } else {
            Log.w(TAG, "SDK impression for position $position but ad not in pending map")
        }
    }

    private fun handleSdkImpression(adId: Long) {
        markSdkImpression(adId)
    }

    private fun handleLoadFailure(error: LoadAdError) {
        when (error.code) {
            3 -> { // NO_FILL
                Log.d(TAG, "No fill - ad inventory empty (waiting ${NO_FILL_BACKOFF_MS/1000}s before retry)")
                lastNoFillTime.set(System.currentTimeMillis())
            }
            1 -> { // RATE LIMITED
                Log.w(TAG, "Rate limited by AdMob - entering cooldown")
                isInCooldown.set(true)
                _isInBackoff.value = true
                cooldownEndTime = System.currentTimeMillis() + AdConfig.BACKOFF_COOLDOWN_MS

                mainHandler.postDelayed({
                    Log.d(TAG, "Rate limit cooldown ended.")
                    isInCooldown.set(false)
                    _isInBackoff.value = false
                    consecutiveFailures.set(0)
                }, AdConfig.BACKOFF_COOLDOWN_MS)
            }
            else -> {
                val failures = consecutiveFailures.incrementAndGet()
                Log.w(TAG, "Real ad error (code: ${error.code}): $failures consecutive failures")

                if (failures >= AdConfig.MAX_CONSECUTIVE_FAILURES) {
                    Log.e(TAG, "Max failures reached. Entering cooldown.")
                    isInCooldown.set(true)
                    _isInBackoff.value = true
                    cooldownEndTime = System.currentTimeMillis() + AdConfig.BACKOFF_COOLDOWN_MS

                    mainHandler.postDelayed({
                        Log.d(TAG, "Cooldown ended.")
                        isInCooldown.set(false)
                        _isInBackoff.value = false
                        consecutiveFailures.set(0)
                        val context = contextRef?.get() ?: appContext
                        preloadAds(context, 1)
                    }, AdConfig.BACKOFF_COOLDOWN_MS)
                }
            }
        }
    }

    private fun calculateBackoffDelay(failureCount: Int): Long {
        val exponentialDelay = AdConfig.INITIAL_RETRY_DELAY_MS * (1L shl failureCount.coerceAtMost(5))
        val jitter = (Math.random() * 0.25 * exponentialDelay).toLong()
        return exponentialDelay + jitter
    }

    /**
     * Get an ad for a specific position.
     */
    fun getAdForPosition(position: Int): NativeAd? {
        // Check if already impressed
        impressedAds[position]?.let {
            Log.d(TAG, "Returning impressed ad for position $position")
            return it
        }

        // Check if pending impression
        pendingImpressionAds[position]?.let {
            Log.d(TAG, "Returning pending ad for position $position")
            return it.ad
        }

        // Take from available pool
        val adWrapper = availableAds.poll()
        if (adWrapper != null) {
            pendingImpressionAds[position] = adWrapper
            adIdToPosition[adWrapper.adId] = position
            updateCounts()
            Log.d(TAG, "Assigned new ad (adId: ${adWrapper.adId}) to position $position. Available: ${availableAds.size}")

            if (availableAds.size < LOW_THRESHOLD) {
                triggerBackgroundReload()
            }
            return adWrapper.ad
        }

        Log.w(TAG, "No ad available for position $position")
        return null
    }

    /**
     * Mark impression - now just for internal tracking sync.
     * The real impression is tracked by SDK's onAdImpression.
     */
    fun markImpression(position: Int) {
        if (sdkImpressionReceived[position] == true) {
            Log.d(TAG, "SDK already confirmed impression for position $position")
            return
        }

        val adWrapper = pendingImpressionAds.remove(position)
        if (adWrapper != null) {
            impressedAds[position] = adWrapper.ad
            adIdToPosition.remove(adWrapper.adId)
            Log.d(TAG, "Internal impression tracked for position $position (awaiting SDK confirmation)")
            updateCounts()
        }
    }

    /**
     * Check if SDK has confirmed impression for position
     */
    fun hasSdkImpression(position: Int): Boolean = sdkImpressionReceived[position] == true

    fun hasAdForPosition(position: Int): Boolean =
        impressedAds.containsKey(position) ||
                pendingImpressionAds.containsKey(position) ||
                availableAds.isNotEmpty()

    fun releaseAd(position: Int) {
        sdkImpressionReceived.remove(position)

        impressedAds.remove(position)?.let { ad ->
            ad.destroy()
            Log.d(TAG, "Destroyed impressed ad from position $position")
            updateCounts()
            return
        }

        pendingImpressionAds.remove(position)?.let { adWrapper ->
            adIdToPosition.remove(adWrapper.adId)

            if (availableAds.size < MAX_POOL_SIZE) {
                availableAds.offer(adWrapper)
                Log.d(TAG, "Returned unimpressed ad (adId: ${adWrapper.adId}) from position $position to queue")
            } else {
                adWrapper.ad.destroy()
                Log.d(TAG, "Queue full, destroyed unimpressed ad from position $position")
            }
            updateCounts()
        }
    }

    fun detachFromPosition(position: Int) {
        Log.d(TAG, "Detached position $position (ad preserved)")
    }

    fun reattachPosition(position: Int): NativeAd? {
        return impressedAds[position]
            ?: pendingImpressionAds[position]?.ad
    }

    private fun triggerBackgroundReload() {
        if (_isLoading.value || isPreloadInProgress.get() || isInCooldown.get()) return

        val context = contextRef?.get() ?: appContext
        val toLoad = DEFAULT_POOL_SIZE - availableAds.size
        if (toLoad > 0) {
            Log.d(TAG, "Triggering background reload of $toLoad ads")
            mainHandler.postDelayed({ preloadAds(context, toLoad) }, 500)
        }
    }

    private fun updateCounts() {
        _availableCount.value = availableAds.size
        _totalCount.value = availableAds.size + pendingImpressionAds.size + impressedAds.size
    }

    fun getStatus(): QueueStatus = QueueStatus(
        availableAds = availableAds.size,
        pendingImpressionAds = pendingImpressionAds.size,
        impressedAds = impressedAds.size,
        isLoading = _isLoading.value,
        totalLoaded = totalLoaded.get(),
        totalFailed = totalFailed.get(),
        consecutiveFailures = consecutiveFailures.get(),
        isInCooldown = isInCooldown.get()
    )

    fun clearImpressedAds() {
        Log.d(TAG, "Clearing ${impressedAds.size} impressed ads")
        impressedAds.values.forEach { it.destroy() }
        impressedAds.clear()
        sdkImpressionReceived.clear()
        updateCounts()
    }

    fun clear() {
        Log.d(TAG, "Clearing NativeAdQueue completely")

        // Cancel any pending serial loads
        mainHandler.removeCallbacksAndMessages(null)

        while (availableAds.isNotEmpty()) {
            availableAds.poll()?.ad?.destroy()
        }

        pendingImpressionAds.values.forEach { it.ad.destroy() }
        pendingImpressionAds.clear()

        impressedAds.values.forEach { it.destroy() }
        impressedAds.clear()

        sdkImpressionReceived.clear()
        adIdToPosition.clear()

        currentlyLoading.set(0)
        totalLoaded.set(0)
        totalFailed.set(0)
        consecutiveFailures.set(0)
        isPreloadInProgress.set(false)
        isInCooldown.set(false)
        _isLoading.value = false
        _isInBackoff.value = false
        updateCounts()
    }

    fun destroy() {
        clear()
        mainHandler.removeCallbacksAndMessages(null)
        contextRef = null
        Log.d(TAG, "NativeAdQueue destroyed")
    }

    data class QueueStatus(
        val availableAds: Int,
        val pendingImpressionAds: Int,
        val impressedAds: Int,
        val isLoading: Boolean,
        val totalLoaded: Int,
        val totalFailed: Int,
        val consecutiveFailures: Int,
        val isInCooldown: Boolean
    ) {
        val totalAds: Int get() = availableAds + pendingImpressionAds + impressedAds
    }

    companion object {
        private const val TAG = "NativeAdQueue"
        private const val DEFAULT_POOL_SIZE = 5
        private const val MAX_POOL_SIZE = 10
        private const val LOW_THRESHOLD = 2
    }
}