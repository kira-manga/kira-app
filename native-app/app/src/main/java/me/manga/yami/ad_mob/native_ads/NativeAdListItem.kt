package me.manga.yamiapk.ad_mob.native_ads

import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.ads.nativead.NativeAd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.manga.yamiapk.ad_mob.native_ads.di.getNativeAdQueue
import me.manga.yamiapk.databinding.LayoutNativeAdMedium2Binding

private const val TAG = "NativeAdListItem"

/**
 * Native Ad List Item with FIXED lifecycle management.
 *
 * CRITICAL FIX: Consolidated two separate DisposableEffects into one to prevent
 * race conditions between lifecycle observer cleanup and ad release cleanup.
 *
 * Previous issue: Two DisposableEffects with different keys could run their
 * onDispose callbacks in unpredictable order, causing:
 * - Lifecycle observer removed before ad cleanup
 * - Ad released while still attached to lifecycle
 * - Inconsistent state in NativeAdQueue
 *
 * @param position Unique position/index for this ad slot
 * @param modifier Modifier for the composable
 * @param persistAcrossNavigation If true, ads survive navigation (default: true)
 * @param onHide Called when no ad is available
 */
@Composable
fun NativeAdListItem(
    position: Int,
    modifier: Modifier = Modifier,
    persistAcrossNavigation: Boolean = true,
    onHide: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val loadingColor = MaterialTheme.colorScheme.onBackground
    val scope = rememberCoroutineScope()

    // Use remember with context.applicationContext to ensure stable reference
    // This prevents recreation when activity context changes (e.g., configuration change)
    val nativeAdQueue = remember(context.applicationContext) {
        getNativeAdQueue(context.applicationContext)
    }

    // Use rememberUpdatedState for callbacks to prevent stale captures
    val currentOnHide by rememberUpdatedState(onHide)
    val currentNativeAdQueue by rememberUpdatedState(nativeAdQueue)

    var nativeAd by remember(position) { mutableStateOf<NativeAd?>(null) }
    var isAdBound by remember(position) { mutableStateOf(false) }
    var loadAttempted by remember(position) { mutableStateOf(false) }

    val isQueueLoading by nativeAdQueue.isLoading.collectAsState()
    val availableCount by nativeAdQueue.availableCount.collectAsState()

    DisposableEffect(lifecycleOwner, position, persistAcrossNavigation) {
        // Create lifecycle observer for pause/resume handling
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (persistAcrossNavigation) {
                        currentNativeAdQueue.detachFromPosition(position)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    val existingAd = currentNativeAdQueue.reattachPosition(position)
                    if (existingAd != null && nativeAd == null) {
                        nativeAd = existingAd
                        isAdBound = false
                    }
                }
                else -> {}
            }
        }

        // Attach observer
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            // ┌─────────────────────────────────────────────────────────────┐
            // │ CLEANUP ORDER IS CRITICAL - DO NOT REORDER                  │
            // │                                                             │
            // │ 1. Remove lifecycle observer FIRST                          │
            // │    - Prevents observer from firing during cleanup           │
            // │    - No more detach/reattach calls after this               │
            // │                                                             │
            // │ 2. Then handle ad cleanup                                   │
            // │    - Safe because no lifecycle events can interfere         │
            // │    - Deterministic release based on impression state        │
            // └─────────────────────────────────────────────────────────────┘

            // Step 1: Remove lifecycle observer
            lifecycleOwner.lifecycle.removeObserver(observer)

            // Step 2: Handle ad cleanup
            val hasImpression = currentNativeAdQueue.hasSdkImpression(position)
            Log.d(TAG, "Disposing position $position, sdkImpression=$hasImpression, persist=$persistAcrossNavigation")

            // Release ad if:
            // - persistAcrossNavigation is false (always release)
            // - OR ad has been impressed (safe to release, won't be reused)
            if (!persistAcrossNavigation || hasImpression) {
                currentNativeAdQueue.releaseAd(position)
            }
            // If persistAcrossNavigation is true AND no impression yet,
            // the ad stays in pendingImpressionAds for potential reattachment
        }
    }

    // Try to get/restore ad for this position
    LaunchedEffect(position, availableCount, loadAttempted) {
        if (nativeAd != null || loadAttempted) return@LaunchedEffect

        // First check if we have an existing ad
        val existingAd = nativeAdQueue.reattachPosition(position)
        if (existingAd != null) {
            nativeAd = existingAd
            isAdBound = false
            loadAttempted = true
            Log.d(TAG, "Restored existing ad for position $position")
            return@LaunchedEffect
        }

        // Try to get new ad
        val ad = nativeAdQueue.getAdForPosition(position)
        if (ad != null) {
            nativeAd = ad
            isAdBound = false
            loadAttempted = true
            Log.d(TAG, "Got new ad for position $position")
        } else if (!isQueueLoading && availableCount == 0) {
            Log.d(TAG, "No ads available for position $position, triggering preload")
            loadAttempted = true

            nativeAdQueue.preloadAds(context, 3) { loaded, _ ->
                scope.launch(Dispatchers.Main) {
                    if (loaded > 0 && nativeAd == null) {
                        val newAd = nativeAdQueue.getAdForPosition(position)
                        if (newAd != null) {
                            nativeAd = newAd
                            isAdBound = false
                        }
                    }
                }
            }
        }
    }

    // Render states
    val shouldShowShimmer = nativeAd == null && (isQueueLoading || !loadAttempted)
    val shouldShowAd = nativeAd != null
    val shouldHide = nativeAd == null && loadAttempted && !isQueueLoading

    when {
        shouldHide -> {
            Box(modifier = modifier.fillMaxWidth().height(0.dp))
            LaunchedEffect(Unit) { currentOnHide() }
        }

        shouldShowShimmer -> {
            AndroidViewBinding(
                factory = LayoutNativeAdMedium2Binding::inflate,
                modifier = modifier,
                update = {
                    shimmerFrameLayout.visibility = View.VISIBLE
                    nativeAdView.visibility = View.GONE
                }
            )
        }

        shouldShowAd -> {
            AndroidViewBinding(
                factory = LayoutNativeAdMedium2Binding::inflate,
                modifier = modifier,
                update = {
                    btnCta1.setTextColor(loadingColor.toArgb())

                    val ad = nativeAd
                    if (ad != null && !isAdBound) {
                        bindAdToView(ad, this)
                        isAdBound = true

                        shimmerFrameLayout.visibility = View.GONE
                        nativeAdView.visibility = View.VISIBLE

                        Log.d(TAG, "Ad bound for position $position - SDK will track impression")
                    }
                }
            )
        }
    }
}

private fun bindAdToView(ad: NativeAd, binding: LayoutNativeAdMedium2Binding) {
    with(binding) {
        nativeAdView.apply {
            adChoicesView = adChoice
            bodyView = tvBody
            callToActionView = btnCta
            headlineView = tvHeadline
            iconView = ivAppIcon
            mediaView = mvContent
        }

        tvBody.text = ad.body ?: ""
        tvBody.visibility = if (ad.body != null) View.VISIBLE else View.GONE  // FIX: Use GONE instead of INVISIBLE

        ad.mediaContent?.let {
            mvContent.mediaContent = it
            mvContent.setImageScaleType(ImageView.ScaleType.FIT_XY)
        }

        btnCta.text = ad.callToAction ?: ""
        btnCta.visibility = if (ad.callToAction != null) View.VISIBLE else View.GONE  // FIX: Use GONE

        tvHeadline.text = ad.headline ?: ""

        ad.icon?.drawable?.let { drawable ->
            ivAppIcon.setImageDrawable(drawable)
            ivAppIcon.visibility = View.VISIBLE
        } ?: run {
            ivAppIcon.visibility = View.GONE
        }

        // When setNativeAd() is called, the SDK starts tracking
        // onAdImpression() will fire when the ad is actually visible
        nativeAdView.setNativeAd(ad)

        ad.setOnPaidEventListener { adValue ->
            Log.d(TAG, "Paid event: ${adValue.valueMicros} ${adValue.currencyCode}")
        }
    }
}

@Composable
fun PreloadNativeAdsEffect(
    nativeAdQueue: NativeAdQueue,
    adCount: Int = 5
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        nativeAdQueue.initialize(context)
        nativeAdQueue.preloadAds(context, adCount) { loaded, failed ->
            Log.d(TAG, "Preloaded $loaded ads, $failed failed")
        }
    }
}