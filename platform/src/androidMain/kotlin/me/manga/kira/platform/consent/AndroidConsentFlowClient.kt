package me.manga.kira.platform.consent

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import me.manga.kira.platform.activity.ForegroundActivityProvider
import kotlin.coroutines.resume

/**
 * Android actual for [ConsentFlowClient].
 *
 * Wraps Google UMP's `ConsentInformation`. Both `requestConsentInfoUpdate` and
 * `loadAndShowConsentFormIfRequired` need a foreground `Activity` — same
 * [ForegroundActivityProvider] convention as `AndroidInAppReviewClient` /
 * `AndroidAppUpdateClient`.
 *
 * Verbatim semantic port from legacy
 * `:shared/androidMain/.../core/consent/ConsentFlowClient.android.kt`. Preserves:
 *  - `applicationContext` unwrap on `getConsentInformation(...)` so the manager singleton does
 *    not retain an Activity.
 *  - `suspendCancellableCoroutine` bridging: UMP uses success/failure listeners (not `Task<T>`),
 *    so `kotlinx-coroutines-play-services.await()` does not apply here. The cont-active guard
 *    prevents double-resume if UMP fires both callbacks (defensive — observed in older UMP
 *    versions).
 *  - Private [mapStatus] helper that maps UMP's `ConsentInformation.ConsentStatus.*` ints to the
 *    common [ConsentStatus] enum. Unknown ints fall through to [ConsentStatus.UNKNOWN].
 *  - "Return false / UNKNOWN on any failure" semantics so consumer UI falls through to its
 *    hidden / disabled state.
 */
class AndroidConsentFlowClient(
    context: Context,
    private val activityProvider: ForegroundActivityProvider = { null },
) : ConsentFlowClient {

    private val log = Logger.withTag(TAG)
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)

    override suspend fun requestConsentInfoUpdate(): ConsentStatus {
        val activity = activityProvider() ?: run {
            log.w { "requestConsentInfoUpdate: no foreground Activity available" }
            return ConsentStatus.UNKNOWN
        }
        val params = ConsentRequestParameters.Builder().build()
        return suspendCancellableCoroutine { cont ->
            consentInformation.requestConsentInfoUpdate(
                activity,
                params,
                {
                    if (cont.isActive) cont.resume(mapStatus(consentInformation.consentStatus))
                },
                { error ->
                    log.w { "requestConsentInfoUpdate failed: ${error.errorCode} ${error.message}" }
                    if (cont.isActive) cont.resume(ConsentStatus.UNKNOWN)
                },
            )
        }
    }

    override suspend fun loadAndShowConsentFormIfRequired(): Boolean {
        val activity = activityProvider() ?: run {
            log.w { "loadAndShowConsentFormIfRequired: no foreground Activity available" }
            return false
        }
        return suspendCancellableCoroutine { cont ->
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (error != null) {
                    log.w { "loadAndShowConsentFormIfRequired error: ${error.errorCode} ${error.message}" }
                    if (cont.isActive) cont.resume(false)
                } else {
                    if (cont.isActive) cont.resume(true)
                }
            }
        }
    }

    override fun canRequestAds(): Boolean = consentInformation.canRequestAds()

    private fun mapStatus(raw: Int): ConsentStatus = when (raw) {
        ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentStatus.NOT_REQUIRED
        ConsentInformation.ConsentStatus.REQUIRED -> ConsentStatus.REQUIRED
        ConsentInformation.ConsentStatus.OBTAINED -> ConsentStatus.OBTAINED
        else -> ConsentStatus.UNKNOWN
    }

    private companion object {
        const val TAG = "ConsentFlowClient.android"
    }
}

/*
 * §253 audit-trail postscript — cluster266 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Phase 5.z.3 platform-facade relocation; Android actual of the
 * 3-actual ConsentFlowClient fan). UNIT KIND platform-facade.
 *
 * LIVE evidence:
 *  - Implements the relocated interface me.manga.kira.platform.consent.ConsentFlowClient
 *    (platform commonMain ConsentFlowClient.kt:15), already §253-swept under cluster148 (Task #604,
 *    its own in-file postscript dated 2026-05-28 at ConsentFlowClient.kt:33).
 *  - The concrete class AndroidConsentFlowClient is NOT yet bound in any rework Koin module: a repo
 *    grep for me.manga.kira.platform.consent across composeApp/di ReworkModules returned zero
 *    binding hits; the only matches are cross-classification prose (HighQualitySkiaImageDecoder.kt:252,
 *    ForegroundActivityProvider.kt:12 and :54). The LIVE consent binding still resolves through the
 *    LEGACY expect-or-actual ConsentFlowClient at PlatformModule.android.kt:125
 *    (single { ConsentFlowClient(androidContext()) }). This relocated actual is staged, awaiting the
 *    Phase 8-12 rework-DI cutover — the identical posture recorded for sibling platform fans
 *    (AppUpdateClient cluster227, InAppReviewClient cluster228, the ConsentFlowClient-era cluster229).
 *  - FULFILLED-PORT vs LEGACY: this :platform file is the FULFILLED-PORT; the orphan-pending legacy
 *    twin is shared/androidMain/.../core/consent/ConsentFlowClient.android.kt (actual class, line 21),
 *    still LIVE only because the legacy Koin single above references it.
 *
 * Delta-axes (Android actual, distinct approach):
 *  1. Platform API: Google UMP — UserMessagingPlatform.getConsentInformation(applicationContext);
 *     requestConsentInfoUpdate(activity, params, onSuccess, onFailure) and
 *     UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, onDismiss); canRequestAds()
 *     delegates straight to consentInformation.canRequestAds(). This is the ONLY non-trivial actual
 *     of the fan; iOS and Desktop are no-ops.
 *  2. Threading/dispatcher: suspendCancellableCoroutine bridges UMP's success-or-failure listener
 *     pair (UMP exposes listeners, not a Task, so play-services await does not apply); a cont.isActive
 *     guard prevents double-resume. No explicit dispatcher hop — runs on the caller's coroutine.
 *  3. Error handling: PROCEED-FALLBACK — every failure path resolves to ConsentStatus.UNKNOWN
 *     (update) or false (form-show), logged at warn via Kermit; a null foreground Activity short-
 *     circuits to the same fallbacks so consumer UI takes its hidden-or-disabled branch.
 *  4. DI binding mechanism: per-platform constructor injection of (Context, ForegroundActivityProvider);
 *     the activityProvider defaults to a null-returning lambda until an ActivityHolder is wired in
 *     Phase 11 (see koin-graph-report.md note). Binding will be single { } in the rework PlatformModule.
 *  5. Behavioural-contract parity across the three actuals: all three honour the same return contract
 *     — canRequestAds true-or-real, requestConsentInfoUpdate yields a ConsentStatus, form-show yields
 *     Boolean; Android is the real UMP path, iOS/Desktop return NOT_REQUIRED plus true plus canRequestAds=true.
 *  6. Status mapping: private mapStatus folds UMP's ConsentInformation.ConsentStatus ints to the common
 *     ConsentStatus enum, with the else arm falling through to UNKNOWN — no exhaustive-when crash risk.
 *
 * Nested-comment hazard check: this file has exactly one legitimate KDoc opener (the class doc at
 * line 12) plus its closer. This appended block adds exactly one opener and one closer; the body
 * contains no slash-star, no star-slash, and no slash-star-star sequence (UMP method references and
 * the "true-or-real" phrasing are written without adjacent delimiter characters). Block is balanced.
 */
