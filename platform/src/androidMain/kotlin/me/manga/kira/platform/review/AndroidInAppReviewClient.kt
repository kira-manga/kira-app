package me.manga.kira.platform.review

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import me.manga.kira.platform.activity.ForegroundActivityProvider

/**
 * Android actual for [InAppReviewClient].
 *
 * Delegates to Play Core's `ReviewManager`. Both `requestReviewFlow()` and `launchReviewFlow()`
 * need a foreground `Activity` — the [activityProvider] is a [ForegroundActivityProvider] and
 * the rework's standard way of acquiring one from a singleton without threading it through
 * Koin's commonMain bindings.
 *
 * Verbatim semantic port from legacy
 * `:shared/androidMain/.../core/review/InAppReviewClient.android.kt`. The "return `true` whenever
 * the flow completed without throwing" behavior is preserved — Play Core deliberately does not
 * surface whether the dialog actually rendered on this device (Google rate-limits the surface),
 * so the same caveat the legacy KDoc documented applies here.
 */
class AndroidInAppReviewClient(
    context: Context,
    private val activityProvider: ForegroundActivityProvider = { null },
) : InAppReviewClient {

    private val log = Logger.withTag(TAG)
    private val manager: ReviewManager = ReviewManagerFactory.create(context.applicationContext)

    override suspend fun requestReview(): Boolean {
        val activity = activityProvider() ?: run {
            log.w { "requestReview: no foreground Activity available" }
            return false
        }
        return try {
            val reviewInfo = manager.requestReviewFlow().await()
            manager.launchReviewFlow(activity, reviewInfo).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "requestReview failed" }
            false
        }
    }

    private companion object {
        const val TAG = "InAppReviewClient.android"
    }
}

/*
 * §253 audit-trail postscript — cluster275 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-FACADE-NOT-YET-BOUND (Android actual).
 *
 * Cross-reference: this is the Android leaf of a 3-actual platform-facade fan
 * for the commonMain interface InAppReviewClient at
 * platform/src/commonMain/.../platform/review/InAppReviewClient.kt:57
 * (already swept under cluster148, Task #604 — its own audit-trail postscript
 * is preserved verbatim at lines 20-55 there). FULFILLED-PORT: the Phase 5.z.1
 * (Task #188) relocation shipped all three actuals — Android (this file),
 * Desktop, iOS — implementing the single suspend fun requestReview() Boolean.
 *
 * LIVE evidence — nuanced. The REWORK :platform facade and its three actuals
 * are NOT yet bound in any :composeApp rework Koin module: a repo-wide grep for
 * AndroidInAppReviewClient / DesktopInAppReviewClient / IosInAppReviewClient
 * across composeApp returned zero call sites. What is LIVE today is the LEGACY
 * shadow SPI — the expect class InAppReviewClient bound at
 * shared/src/androidMain/.../di/PlatformModule.android.kt:124
 * (single InAppReviewClient androidContext), with iOS at
 * shared/.../di/PlatformModule.ios.kt:108 and Desktop at
 * shared/.../di/PlatformModule.desktop.kt:108. The rework interface remains a
 * relocated-but-unwired facade pending the Task #422 §250 shadow-legacy-facade
 * retire decision; treat it as LIVE-by-design (the relocation is complete and
 * correct) rather than orphaned.
 *
 * Delta-axes (Android actual):
 *  1. Platform API: Play Core ReviewManagerFactory.create(applicationContext)
 *     yielding a ReviewManager; requestReviewFlow() then launchReviewFlow(
 *     activity, reviewInfo). The only actual that touches a real review surface.
 *  2. Threading: both Play Core Tasks are bridged with kotlinx-coroutines-play-
 *     services .await(); requestReview() is suspend, so the caller's dispatcher
 *     governs — no internal dispatcher hop.
 *  3. Error handling: try-catch over Exception returning false on failure, plus
 *     an early false when activityProvider() yields null (logged via Kermit
 *     warn). Mirrors the legacy "return true only if the flow completed without
 *     throwing" semantic preserved from the :shared android actual.
 *  4. DI binding mechanism: plain class ctor (context, activityProvider:
 *     ForegroundActivityProvider = empty-lambda default). The default keeps the
 *     graph green until a Phase 11 ActivityHolder supplies a real lambda — same
 *     convention as AndroidAppUpdateClient / AndroidConsentFlowClient /
 *     AndroidAdProvider.
 *  5. Contract parity across the fan: Android is the only FULL implementation;
 *     Desktop and iOS are no-ops returning false. All three honour the same
 *     "false means review-not-shown, consumer falls through" contract.
 *
 * Nested-comment hazard check: exactly one legitimate KDoc opener heads the
 * class (the block at lines 10-23) plus this appended block; this appended
 * block is balanced — one opener, one closer, and the prose contains no
 * interior comment delimiters (no slash-star, no star-slash, no slash-star-star).
 */
