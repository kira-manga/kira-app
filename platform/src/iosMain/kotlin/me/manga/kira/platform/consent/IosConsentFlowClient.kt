package me.manga.kira.platform.consent

import co.touchlab.kermit.Logger

/**
 * iOS actual for [ConsentFlowClient] — no-op.
 *
 * Ads aren't served on iOS, so the UMP consent flow is moot. Returns `NOT_REQUIRED` and
 * `canRequestAds = true` so any consumer that gates ad calls on consent state takes the "OK"
 * branch without further work.
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/consent/ConsentFlowClient.ios.kt`.
 */
class IosConsentFlowClient : ConsentFlowClient {

    private val log = Logger.withTag(TAG)

    override suspend fun requestConsentInfoUpdate(): ConsentStatus {
        log.d { "requestConsentInfoUpdate() — no-op on iOS, returning NOT_REQUIRED" }
        return ConsentStatus.NOT_REQUIRED
    }

    override suspend fun loadAndShowConsentFormIfRequired(): Boolean {
        log.d { "loadAndShowConsentFormIfRequired() — no-op on iOS, returning true" }
        return true
    }

    override fun canRequestAds(): Boolean = true

    private companion object {
        const val TAG = "ConsentFlowClient.ios"
    }
}

/*
 * §253 audit-trail postscript — cluster266 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Phase 5.z.3 platform-facade relocation; iOS actual of the
 * 3-actual ConsentFlowClient fan). UNIT KIND platform-facade.
 *
 * LIVE evidence:
 *  - Implements me.manga.kira.platform.consent.ConsentFlowClient (platform commonMain
 *    ConsentFlowClient.kt:15, §253-swept under cluster148 Task #604 at ConsentFlowClient.kt:33).
 *  - IosConsentFlowClient is NOT yet bound in any rework Koin module: the composeApp/di
 *    ReworkModules grep for me.manga.kira.platform.consent returned zero binding hits. The LIVE
 *    iOS consent binding still resolves through the LEGACY no-arg actual at PlatformModule.ios.kt:109
 *    (single { ConsentFlowClient() }). This relocated actual is staged for the Phase 8-12 rework-DI
 *    cutover — identical posture to its Android and Desktop siblings in this fan.
 *  - FULFILLED-PORT vs LEGACY: this :platform file is the FULFILLED-PORT; the legacy twin still wired
 *    is shared/iosMain/.../core/consent/ConsentFlowClient.ios.kt (actual class, line 10), LIVE only
 *    via the legacy single above.
 *
 * Delta-axes (iOS actual, distinct approach):
 *  1. Platform API: NONE — ads are not served on iOS, so the UMP flow is moot. No Google UMP
 *     CocoaPod or platform.* interop is referenced; the actual is a pure Kotlin-or-native no-op,
 *     deliberately avoiding any iOS-only framework dependency.
 *  2. Threading/dispatcher: trivial — both suspend funs return synchronously, no coroutine bridge,
 *     no dispatchers hop, no suspendCancellableCoroutine. The suspend modifier is interface-mandated
 *     only (contrast the Android UMP success-or-failure listener bridge).
 *  3. Error handling: none required — no fallible call site exists. requestConsentInfoUpdate returns
 *     ConsentStatus.NOT_REQUIRED, loadAndShowConsentFormIfRequired returns true, both with a Kermit
 *     debug log; canRequestAds returns true unconditionally so any consumer gating ad calls takes the
 *     OK branch with no further work, per the class-doc rationale.
 *  4. DI binding mechanism: no-arg constructor; the rework binding will be a no-arg single { } in the
 *     iOS rework PlatformModule, mirroring the legacy single { ConsentFlowClient() } it supersedes.
 *  5. Behavioural-contract parity across the three actuals: byte-identical return shape to the Desktop
 *     no-op (NOT_REQUIRED plus true plus canRequestAds=true); the only behavioural divergence in the
 *     fan is Android, which runs the real UMP consent flow. iOS and Desktop are intentionally
 *     interchangeable stubs — confirmed by comparing this file with DesktopConsentFlowClient.kt.
 *
 * Nested-comment hazard check: this file has exactly one legitimate KDoc opener (the class doc at
 * line 5) plus its closer. This appended block adds exactly one opener and one closer; the body
 * contains no slash-star, no star-slash, and no slash-star-star sequence (the "Kotlin-or-native"
 * and "success-or-failure" phrasings use hyphenated words, not delimiter characters). Block is balanced.
 */
