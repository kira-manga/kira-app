package me.manga.yamiapk.ad_mob.bannars

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import me.manga.yamiapk.ad_mob.AdConfig

private const val TAG = "BannerAdView"
private const val MAX_RETRY_COUNT = 3

/**
 * Banner ad composable with proper lifecycle management.
 *
 * Features:
 * - Handles preview mode
 * - Proper cleanup on dispose (including pending callbacks)
 * - Retry logic on failures
 * - Lifecycle-aware (pauses/resumes with activity)
 */
@Composable
fun BannerAdView(
    modifier: Modifier = Modifier,
    adSize: AdSize = AdSize.BANNER,
    adUnitId: String = AdConfig.BANNER_AD_UNIT_ID,
    onAdLoaded: () -> Unit = {},
    onAdFailed: (String) -> Unit = {},
    onAdImpression: () -> Unit = {}
) {

    Log.i("testprodactionBannerAdView",adUnitId.toString())

    if (LocalInspectionMode.current) {
        BannerPreviewPlaceholder(modifier)
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    var adViewRef by remember { mutableStateOf<AdView?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }

    // Combined lifecycle and cleanup handling
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> adViewRef?.resume()
                Lifecycle.Event.ON_PAUSE -> adViewRef?.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d(TAG, "Disposing banner ad view")
            lifecycleOwner.lifecycle.removeObserver(observer)
            adViewRef?.handler?.removeCallbacksAndMessages(null)
            adViewRef?.destroy()
            adViewRef = null
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                AdView(ctx).apply {
                    adViewRef = this
                    setAdSize(adSize)
                    this.adUnitId = adUnitId

                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            Log.d(TAG, "Banner ad loaded")
                            retryCount = 0
                            onAdLoaded()
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            Log.e(TAG, "Banner ad failed: ${error.message} (code: ${error.code})")


                            val nonRetryable = setOf(
                                AdRequest.ERROR_CODE_INVALID_REQUEST,
                                AdRequest.ERROR_CODE_APP_ID_MISSING
                            )

                            if (error.code in nonRetryable) {
                                Log.e(TAG, "Non-retryable ad error: ${error.code}")
                                onAdFailed(error.message)
                                return
                            }
                            if (retryCount < MAX_RETRY_COUNT) {
                                retryCount++
                                val delay = AdConfig.INITIAL_RETRY_DELAY_MS * (1L shl (retryCount - 1))
                                Log.w(TAG, "Retrying banner (attempt $retryCount) after ${delay}ms")

                                // FIX: Corrected the double negation bug
                                // Was: if (adViewRef != null && !isAttachedToWindow.not())
                                // The original had confusing double negation that was likely a bug
                                handler?.postDelayed({
                                    if (adViewRef != null && isAttachedToWindow) {
                                        loadAd(AdRequest.Builder().build())
                                    }
                                }, delay)
                            } else {
                                Log.e(TAG, "Banner failed after $MAX_RETRY_COUNT attempts")
                                retryCount = 0
                                onAdFailed(error.message)
                            }
                        }

                        override fun onAdOpened() {
                            Log.d(TAG, "Banner ad opened")
                        }

                        override fun onAdClicked() {
                            Log.d(TAG, "Banner ad clicked")
                        }

                        override fun onAdImpression() {
                            Log.d(TAG, "Banner ad impression")
                            onAdImpression()
                        }

                        override fun onAdClosed() {
                            Log.d(TAG, "Banner ad closed")
                        }
                    }

                    loadAd(AdRequest.Builder().build())
                }
            }
        )
    }
}

/**
 * Adaptive banner that adjusts to screen width.
 */
@Composable
fun AdaptiveBannerAdView(
    modifier: Modifier = Modifier,
    adUnitId: String = AdConfig.BANNER_AD_UNIT_ID,
    onAdLoaded: () -> Unit = {},
    onAdFailed: (String) -> Unit = {},
    onAdImpression: () -> Unit = {}
) {

    Log.i("testprodactionBannerAdView",adUnitId.toString())

    if (LocalInspectionMode.current) {
        BannerPreviewPlaceholder(modifier)
        return
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var adViewRef by remember { mutableStateOf<AdView?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }

    // Combined lifecycle and cleanup handling
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> adViewRef?.resume()
                Lifecycle.Event.ON_PAUSE -> adViewRef?.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d(TAG, "Disposing adaptive banner")
            lifecycleOwner.lifecycle.removeObserver(observer)
            adViewRef?.handler?.removeCallbacksAndMessages(null)
            adViewRef?.destroy()
            adViewRef = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            val displayMetrics = ctx.resources.displayMetrics
            val adWidthPixels = displayMetrics.widthPixels.toFloat()
            val density = displayMetrics.density
            val adWidth = (adWidthPixels / density).toInt()

            val adaptiveSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidth)

            AdView(ctx).apply {
                adViewRef = this
                setAdSize(adaptiveSize)
                this.adUnitId = adUnitId

                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        Log.d(TAG, "Adaptive banner loaded")
                        retryCount = 0
                        onAdLoaded()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e(TAG, "Adaptive banner failed: ${error.message}")

                        if (retryCount < MAX_RETRY_COUNT) {
                            retryCount++
                            val delay = AdConfig.INITIAL_RETRY_DELAY_MS * (1L shl (retryCount - 1))
                            Log.w(TAG, "Retrying adaptive banner (attempt $retryCount) after ${delay}ms")

                            // FIX: Same fix as above - proper attachment check
                            handler?.postDelayed({
                                if (adViewRef != null && isAttachedToWindow) {
                                    loadAd(AdRequest.Builder().build())
                                }
                            }, delay)
                        } else {
                            retryCount = 0
                            onAdFailed(error.message)
                        }
                    }

                    override fun onAdImpression() {
                        Log.d(TAG, "Adaptive banner impression")
                        onAdImpression()
                    }
                }

                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

@Composable
private fun BannerPreviewPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Banner Ad",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BannerAdViewPreview() {
    BannerAdView()
}

@Preview(showBackground = true)
@Composable
private fun AdaptiveBannerPreview() {
    AdaptiveBannerAdView()
}