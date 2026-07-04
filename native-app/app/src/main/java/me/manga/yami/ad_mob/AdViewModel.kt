package me.manga.yamiapk.ad_mob

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.ad_mob.native_ads.NativeAdQueue
import me.manga.yamiapk.ad_mob.rewarded.RewardedAdManager
import me.manga.yamiapk.core.storage.DataStoreHelper
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * ViewModel for managing ad-related operations
 *
 * Handles:
 * - Download counting and ad triggering
 * - Rewarded ad showing with proper callback handling
 * - State exposure for UI
 * - Thread-safe operations
 *
 * CRITICAL FIX: Added proper feedback when concurrent download requests are rejected
 */
@HiltViewModel
class AdViewModel @Inject constructor(
    private val dataStoreHelper: DataStoreHelper,
    private val rewardedAdManager: RewardedAdManager,
    private val nativeAdQueue: NativeAdQueue,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isShowingAd = MutableStateFlow(false)
    val isShowingAd: StateFlow<Boolean> = _isShowingAd.asStateFlow()

    private val _downloadCount = MutableStateFlow(0)
    val downloadCount: StateFlow<Int> = _downloadCount.asStateFlow()

    private val _lastAdResult = MutableStateFlow<AdShowResult?>(null)
    val lastAdResult: StateFlow<AdShowResult?> = _lastAdResult.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // NEW: Expose download processing state for UI feedback
    // ═══════════════════════════════════════════════════════════════════════════
    private val _isProcessingDownload = MutableStateFlow(false)
    val isProcessingDownload: StateFlow<Boolean> = _isProcessingDownload.asStateFlow()

    private val isProcessingDownloadFlag = AtomicBoolean(false)

    init {
        loadInitialDownloadCount()
        ensureAdsPreloaded()
    }

    private fun loadInitialDownloadCount() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = getDownloadedChaptersCount()
            withContext(Dispatchers.Main) {
                _downloadCount.value = count
            }
        }
    }

    private fun ensureAdsPreloaded() {
        if (!rewardedAdManager.isReady() && !rewardedAdManager.isLoading()) {
            rewardedAdManager.preload()
        }
        if (!nativeAdQueue.isLoading.value) {
            nativeAdQueue.preloadAds(context)
        }
    }

    /**
     * Called when a download is initiated.
     * Shows ad if threshold is reached, otherwise proceeds with download.
     *
     * CRITICAL FIX: Now provides proper feedback when request is rejected due to
     * concurrent processing. Previously, users got no callback and were left hanging.
     *
     * @param context Activity context for showing ads
     * @param onProceed Called when download should proceed (after ad or directly)
     * @param onBlocked Called when download is blocked (ad dismissed without reward OR concurrent request)
     * @param onAlreadyProcessing Optional callback specifically for concurrent request rejection.
     *                            If not provided, onBlocked will be called instead.
     */
    fun onDownloadStarted(
        context: Context,
        onProceed: () -> Unit,
        onBlocked: (() -> Unit)? = null,
        onAlreadyProcessing: (() -> Unit)? = null  // NEW: Specific callback for concurrent requests
    ) {
        // ═══════════════════════════════════════════════════════════════════════════
        // CRITICAL FIX: Provide feedback when concurrent request is rejected
        // ═══════════════════════════════════════════════════════════════════════════
        //
        // Previous behavior:
        //   if (!isProcessingDownload.compareAndSet(false, true)) {
        //       Log.w(TAG, "Already processing a download request")
        //       return  // ❌ User gets NO feedback - they're left waiting forever
        //   }
        //
        // Fixed behavior:
        //   - Call onAlreadyProcessing if provided (for specific UI like "Please wait...")
        //   - Fall back to onBlocked if onAlreadyProcessing not provided
        //   - User ALWAYS gets feedback
        // ═══════════════════════════════════════════════════════════════════════════

        if (!isProcessingDownloadFlag.compareAndSet(false, true)) {
            Log.w(TAG, "Already processing a download request - notifying caller")

            // Notify caller about the concurrent request
            if (onAlreadyProcessing != null) {
                onAlreadyProcessing()
            } else {
                // Fall back to onBlocked so user always gets feedback
                onBlocked?.invoke()
            }
            return
        }

        // Update StateFlow for UI observation
        _isProcessingDownload.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentCount = getDownloadedChaptersCount()
                val newCount = currentCount + 1
                Log.d(TAG, "Download started. Count: $currentCount -> $newCount")

                if (shouldShowAd(newCount)) {
                    Log.d(TAG, "Ad threshold reached at $newCount")
                    withContext(Dispatchers.Main) {
                        showRewardedAdForDownload(context, newCount, onProceed, onBlocked)
                    }
                } else {
                    saveDownloadedChaptersCount(newCount)
                    withContext(Dispatchers.Main) {
                        _downloadCount.value = newCount
                        Log.d(TAG, "No ad needed. Proceeding with download.")
                        resetProcessingState()
                        onProceed()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDownloadStarted", e)
                withContext(Dispatchers.Main) {
                    resetProcessingState()
                    // On error, still proceed with download to avoid blocking user
                    onProceed()
                }
            }
        }
    }

    /**
     * Overload for backward compatibility - calls the new version without onAlreadyProcessing
     */
    @Deprecated(
        message = "Use the version with onAlreadyProcessing parameter for better UX",
        replaceWith = ReplaceWith("onDownloadStarted(context, onProceed, onBlocked, null)")
    )
    fun onDownloadStartedLegacy(
        context: Context,
        onProceed: () -> Unit,
        onBlocked: (() -> Unit)? = null
    ) {
        onDownloadStarted(context, onProceed, onBlocked, null)
    }

    private fun showRewardedAdForDownload(
        context: Context,
        newCount: Int,
        onProceed: () -> Unit,
        onBlocked: (() -> Unit)?
    ) {
        if (_isShowingAd.value) {
            Log.w(TAG, "Already showing ad - proceeding without ad")
            resetProcessingState()
            onProceed()
            return
        }

        if (!rewardedAdManager.isReady()) {
            Log.d(TAG, "Ad not ready - proceeding without ad")
            viewModelScope.launch(Dispatchers.IO) {
                saveDownloadedChaptersCount(newCount)
                withContext(Dispatchers.Main) {
                    _downloadCount.value = newCount
                }
            }
            resetProcessingState()
            onProceed()
            return
        }

        _isShowingAd.value = true

        rewardedAdManager.show(context) { result ->
            _isShowingAd.value = false
            _lastAdResult.value = result
            resetProcessingState()

            when (result) {
                is AdShowResult.RewardEarned -> {
                    Log.d(TAG, "Reward earned: ${result.type} x${result.amount}")
                    viewModelScope.launch(Dispatchers.IO) {
                        saveDownloadedChaptersCount(newCount)
                        withContext(Dispatchers.Main) {
                            _downloadCount.value = newCount
                        }
                    }
                    onProceed()
                }
                is AdShowResult.Dismissed -> {
                    Log.d(TAG, "Ad dismissed without reward - blocking download")
                    onBlocked?.invoke()
                }
                is AdShowResult.Failed -> {
                    Log.e(TAG, "Ad failed to show: ${result.error}")
                    viewModelScope.launch(Dispatchers.IO) {
                        saveDownloadedChaptersCount(newCount)
                        withContext(Dispatchers.Main) {
                            _downloadCount.value = newCount
                        }
                    }
                    onProceed()
                }
            }
        }
    }

    /**
     * Helper to reset processing state consistently
     */
    private fun resetProcessingState() {
        isProcessingDownloadFlag.set(false)
        _isProcessingDownload.value = false
    }

    /**
     * Show rewarded ad manually.
     */
    fun showRewardedAdManually(
        context: Context,
        onResult: (AdShowResult) -> Unit = {}
    ) {
        if (_isShowingAd.value) {
            Log.w(TAG, "Already showing ad")
            onResult(AdShowResult.Failed("Already showing ad"))
            return
        }

        if (!rewardedAdManager.isReady()) {
            Log.d(TAG, "Ad not ready")
            if (rewardedAdManager.isLoading()) {
                onResult(AdShowResult.Failed("Ad is loading, please try again"))
                return
            }
            // Use production ad unit ID, not test ID
            rewardedAdManager.load(AdConfig.REWARDED_AD_UNIT_ID) { loadResult ->
                Log.d(TAG, "Ad load result: $loadResult")
            }
            onResult(AdShowResult.Failed("Ad not ready, loading now. Please try again."))
            return
        }

        _isShowingAd.value = true
        rewardedAdManager.show(context) { result ->
            _isShowingAd.value = false
            _lastAdResult.value = result
            onResult(result)
        }
    }

    private fun shouldShowAd(downloadedCount: Int): Boolean =
        downloadedCount > 0 && downloadedCount % AdConfig.DOWNLOAD_AD_INTERVAL == 0

    private suspend fun getDownloadedChaptersCount(): Int = try {
        val prefs = dataStoreHelper.dataStore.data.first()
        prefs[intPreferencesKey(DOWNLOADED_CHAPTERS_COUNT_KEY)] ?: 0
    } catch (e: Exception) {
        Log.e(TAG, "Error getting download count", e)
        0
    }

    private suspend fun saveDownloadedChaptersCount(count: Int) {
        try {
            dataStoreHelper.dataStore.edit { prefs ->
                prefs[intPreferencesKey(DOWNLOADED_CHAPTERS_COUNT_KEY)] = count
            }
            Log.d(TAG, "Saved download count: $count")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving download count", e)
        }
    }

    fun getDownloadsUntilNextAd(): Int {
        val current = _downloadCount.value
        if (current == 0) return AdConfig.DOWNLOAD_AD_INTERVAL
        val nextThreshold = ((current / AdConfig.DOWNLOAD_AD_INTERVAL) + 1) * AdConfig.DOWNLOAD_AD_INTERVAL
        return nextThreshold - current
    }

    fun resetDownloadCounter() {
        viewModelScope.launch(Dispatchers.IO) {
            saveDownloadedChaptersCount(0)
            withContext(Dispatchers.Main) {
                _downloadCount.value = 0
            }
            Log.d(TAG, "Download counter reset")
        }
    }

    fun isRewardedAdReady(): Boolean = rewardedAdManager.isReady()

    fun preloadAds() = ensureAdsPreloaded()

    fun clearLastAdResult() {
        _lastAdResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "AdViewModel cleared")
        resetProcessingState()
    }

    companion object {
        private const val TAG = "AdViewModel"
        private const val DOWNLOADED_CHAPTERS_COUNT_KEY = "downloaded_chapters_count"
    }
}