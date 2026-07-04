package me.manga.yamiapk.ad_mob

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.manga.yamiapk.ad_mob.native_ads.NativeAdQueue
import me.manga.yamiapk.ad_mob.rewarded.RewardedAdManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Centralized Ad Coordinator.
 *
 * Manages initialization and coordination of all ad types.
 * Call [initialize] once in Application.onCreate().
 */
@Singleton
class AdCoordinator @Inject constructor(
    private val rewardedAdManager: RewardedAdManager,
    private val nativeAdQueue: NativeAdQueue
) {
    private var scope: CoroutineScope? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _initializationError = MutableStateFlow<String?>(null)
    val initializationError: StateFlow<String?> = _initializationError.asStateFlow()

    private val initStarted = AtomicBoolean(false)
    private val testDeviceIds = mutableListOf<String>()

    fun addTestDevice(deviceId: String) {
        testDeviceIds.add(deviceId)
    }

    /**
     * Initialize all ad systems.
     * Call this once in Application.onCreate().
     */
    fun initialize(
        application: Application,
        enableTestAds: Boolean = false,
        onComplete: ((success: Boolean) -> Unit)? = null
    ) {
        if (!initStarted.compareAndSet(false, true)) {
            Log.w(TAG, "AdCoordinator initialization already started")
            if (_isInitialized.value) onComplete?.invoke(true)
            return
        }

        Log.d(TAG, "Initializing AdCoordinator")
        _isInitializing.value = true
        _initializationError.value = null

        // Create new scope for this initialization
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        scope?.launch {
            try {
                val sdkInitialized = initializeMobileAdsSdk(application, enableTestAds)
                if (!sdkInitialized) throw Exception("Mobile Ads SDK initialization failed")

                initializeManagers(application)
                preloadAllAds()

                _isInitialized.value = true
                _isInitializing.value = false
                Log.d(TAG, "AdCoordinator initialization complete")
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AdCoordinator", e)
                _initializationError.value = e.message
                _isInitializing.value = false
                initStarted.set(false)
                onComplete?.invoke(false)
            }
        }
    }

    private suspend fun initializeMobileAdsSdk(
        context: Context,
        enableTestAds: Boolean
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            if (enableTestAds || testDeviceIds.isNotEmpty()) {
                val deviceIds = if (enableTestAds) testDeviceIds + "EMULATOR" else testDeviceIds
                val configuration = RequestConfiguration.Builder()
                    .setTestDeviceIds(deviceIds)
                    .build()
                MobileAds.setRequestConfiguration(configuration)
                Log.d(TAG, "Test devices configured: $deviceIds")
            }

            MobileAds.initialize(context) { initializationStatus ->
                val statusMap = initializationStatus.adapterStatusMap
                statusMap.forEach { (adapter, status) ->
                    Log.d(TAG, "Adapter: $adapter, Status: ${status.initializationState}")
                }
                if (continuation.isActive) continuation.resume(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Mobile Ads SDK", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private fun initializeManagers(context: Context) {
        rewardedAdManager.initialize(context)
        nativeAdQueue.initialize(context)
        Log.d(TAG, "All ad managers initialized")
    }

    private fun preloadAllAds() {
        Log.d(TAG, "Preloading all ads")
        rewardedAdManager.preload()
    }

    fun preloadAds() {
        if (!_isInitialized.value) {
            Log.w(TAG, "Cannot preload - not initialized")
            return
        }
        preloadAllAds()
    }

    fun getStatus(): AdSystemStatus = AdSystemStatus(
        isInitialized = _isInitialized.value,
        isInitializing = _isInitializing.value,
        rewardedAdReady = rewardedAdManager.isReady(),
        rewardedAdLoading = rewardedAdManager.isLoading()
    )

    fun isReadyToShowAds(): Boolean = _isInitialized.value && !_isInitializing.value

    fun destroy() {
        Log.d(TAG, "Destroying AdCoordinator")

        // Cancel the coroutine scope - FIX: was missing!
        scope?.cancel()
        scope = null

        rewardedAdManager.destroy()
        nativeAdQueue.destroy()

        _isInitialized.value = false
        _isInitializing.value = false
        initStarted.set(false)
    }

    fun logState() {
        Log.d(TAG, """
            === Ad System State ===
            Initialized: ${_isInitialized.value}
            Initializing: ${_isInitializing.value}
            Error: ${_initializationError.value}
            Rewarded: ${rewardedAdManager.getStateDescription()}
            ======================
        """.trimIndent())
    }

    companion object {
        private const val TAG = "AdCoordinator"
    }
}

/**
 * Data class representing the overall ad system status.
 */
data class AdSystemStatus(
    val isInitialized: Boolean,
    val isInitializing: Boolean,
    val rewardedAdReady: Boolean,
    val rewardedAdLoading: Boolean
) {
    val allAdsReady: Boolean get() = rewardedAdReady
    val anyAdLoading: Boolean get() = rewardedAdLoading
    val canShowAds: Boolean get() = isInitialized && !isInitializing

    override fun toString(): String = buildString {
        appendLine("AdSystemStatus:")
        appendLine("  Initialized: $isInitialized")
        appendLine("  Initializing: $isInitializing")
        appendLine("  Rewarded: ready=$rewardedAdReady, loading=$rewardedAdLoading")
        appendLine("  CanShowAds: $canShowAds")
    }
}