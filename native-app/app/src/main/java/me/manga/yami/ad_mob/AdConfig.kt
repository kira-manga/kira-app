package me.manga.yamiapk.ad_mob

import me.manga.yamiapk.BuildConfig

/**
 * Centralized ad configuration.
 * All ad unit IDs and settings in one place for easy management.
 *
 * IMPORTANT: Ad Unit IDs are now loaded from BuildConfig to prevent
 * exposure in source control. Configure them in your build.gradle:
 *
 * android {
 *     buildTypes {
 *         debug {
 *             buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
 *             buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/2247696110\"")
 *             buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
 *         }
 *         release {
 *             buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"${findProperty('ADMOB_REWARDED_ID') ?: ''}\"")
 *             buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"${findProperty('ADMOB_NATIVE_ID') ?: ''}\"")
 *             buildConfigField("String", "BANNER_AD_UNIT_ID", "\"${findProperty('ADMOB_BANNER_ID') ?: ''}\"")
 *         }
 *     }
 * }
 *
 * Then in local.properties (git-ignored):
 * ADMOB_REWARDED_ID=ca-app-pub-xxxxx/xxxxx
 * ADMOB_NATIVE_ID=ca-app-pub-xxxxx/xxxxx
 * ADMOB_BANNER_ID=ca-app-pub-xxxxx/xxxxx
 */
object AdConfig {
    // Production Ad Unit IDs - loaded from BuildConfig
    val REWARDED_AD_UNIT_ID: String
        get() = BuildConfig.REWARDED_AD_UNIT_ID

    val NATIVE_AD_UNIT_ID: String
        get() = BuildConfig.NATIVE_AD_UNIT_ID

    val BANNER_AD_UNIT_ID: String
        get() = BuildConfig.BANNER_AD_UNIT_ID


    // Test Ad Unit IDs (Google's official test IDs)
    object Test {
        const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
        const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
        const val BANNER = "ca-app-pub-3940256099942544/6300978111"
    }

    // Retry configuration
    const val MAX_RETRY_COUNT = 3
    const val INITIAL_RETRY_DELAY_MS = 1000L

    // Exponential backoff configuration
    const val MAX_CONSECUTIVE_FAILURES = 5
    const val BACKOFF_COOLDOWN_MS = 60_000L // 1 minute cooldown after max failures

    // Ad intervals - IMPORTANT: Keep this at 8+ for policy compliance
    // AdMob recommends no more than 1 ad per screen of content
    const val DOWNLOAD_AD_INTERVAL = 6

    // Native ad interleave settings
    // Recommended: First ad after 4-5 items, then every 8-10 items
    val NATIVE_AD_INITIAL_BREAKPOINTS = listOf(5, 10)
    const val NATIVE_AD_INTERVAL = 10

    // Preload delays
    const val PRELOAD_DELAY_AFTER_IMPRESSION_MS = 500L
    const val PRELOAD_DELAY_AFTER_DISMISS_MS = 500L

    // Timeout for stuck showing state (safety reset)
    const val SHOWING_TIMEOUT_MS = 30_000L

    // Impression validation delay (ensure ad is actually visible)
    const val IMPRESSION_VALIDATION_DELAY_MS = 100L

    /**
     * Check if we're in debug mode (should use test ads)
     */
    val isDebugMode: Boolean
        get() = BuildConfig.DEBUG

    /**
     * Get the appropriate ad unit ID based on build type
     */
    fun getRewardedAdUnitId(): String =
        if (isDebugMode) Test.REWARDED else REWARDED_AD_UNIT_ID

    fun getNativeAdUnitId(): String =
        if (isDebugMode) Test.NATIVE else NATIVE_AD_UNIT_ID

    fun getBannerAdUnitId(): String =
        if (isDebugMode) Test.BANNER else BANNER_AD_UNIT_ID
}