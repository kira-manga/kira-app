package me.manga.kira.platform.consent

import co.touchlab.kermit.Logger

/**
 * Desktop actual for [ConsentFlowClient] — no-op.
 *
 * Desktop doesn't serve ads, so the UMP consent flow is moot. Returns `NOT_REQUIRED` and
 * `canRequestAds = true` — same shape as the iOS no-op.
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/consent/ConsentFlowClient.desktop.kt`.
 */
class DesktopConsentFlowClient : ConsentFlowClient {

    private val log = Logger.withTag(TAG)

    override suspend fun requestConsentInfoUpdate(): ConsentStatus {
        log.d { "requestConsentInfoUpdate() — no-op on Desktop, returning NOT_REQUIRED" }
        return ConsentStatus.NOT_REQUIRED
    }

    override suspend fun loadAndShowConsentFormIfRequired(): Boolean {
        log.d { "loadAndShowConsentFormIfRequired() — no-op on Desktop, returning true" }
        return true
    }

    override fun canRequestAds(): Boolean = true

    private companion object {
        const val TAG = "ConsentFlowClient.desktop"
    }
}

/*
 * §253 audit-trail postscript — cluster266 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Phase 5.z.3 platform-facade relocation; Desktop actual of the
 * 3-actual ConsentFlowClient fan). UNIT KIND platform-facade.
 *
 * LIVE evidence:
 *  - Implements me.manga.kira.platform.consent.ConsentFlowClient (platform commonMain
 *    ConsentFlowClient.kt:15, §253-swept under cluster148 Task #604 at ConsentFlowClient.kt:33).
 *  - DesktopConsentFlowClient is NOT yet bound in any rework Koin module: the composeApp/di
 *    ReworkModules grep for me.manga.kira.platform.consent returned zero binding hits. The LIVE
 *    Desktop consent binding still resolves through the LEGACY no-arg actual at
 *    PlatformModule.desktop.kt:109 (single { ConsentFlowClient() }). This relocated actual is staged
 *    for the Phase 8-12 rework-DI cutover — same staged posture as its sibling fan members.
 *  - FULFILLED-PORT vs LEGACY: this :platform file is the FULFILLED-PORT; the legacy twin still wired
 *    is shared/desktopMain/.../core/consent/ConsentFlowClient.desktop.kt (actual class, line 9),
 *    LIVE only via the legacy single above.
 *
 * Delta-axes (Desktop actual, distinct approach):
 *  1. Platform API: NONE — Desktop serves no ads, so the UMP flow is moot. No UMP, no JVM ad SDK
 *     dependency is pulled; the actual is a pure no-op stub, deliberately the lightest of the fan.
 *  2. Threading/dispatcher: trivial — both suspend funs return synchronously with no coroutine
 *     bridge, no dispatcher hop, no suspendCancellableCoroutine (contrast the Android UMP listener
 *     bridge). The suspend modifier exists only to satisfy the interface.
 *  3. Error handling: none required — there is no fallible call site; requestConsentInfoUpdate returns
 *     ConsentStatus.NOT_REQUIRED, loadAndShowConsentFormIfRequired returns true, both with a Kermit
 *     debug log; canRequestAds returns true unconditionally.
 *  4. DI binding mechanism: no-arg constructor; the rework binding will be a no-arg single { } in the
 *     desktop rework PlatformModule, mirroring the legacy single { ConsentFlowClient() } it supersedes.
 *  5. Behavioural-contract parity across the three actuals: byte-identical return shape to the iOS
 *     no-op (NOT_REQUIRED plus true plus canRequestAds=true); diverges from Android only in that
 *     Android runs the real UMP path. Consumers gating ad calls on consent take the OK branch with no
 *     EEA form ever shown — correct for an ad-free Desktop target.
 *
 * Nested-comment hazard check: this file has exactly one legitimate KDoc opener (the class doc at
 * line 5) plus its closer. This appended block adds exactly one opener and one closer; the body
 * contains no slash-star, no star-slash, and no slash-star-star sequence. Block is balanced.
 */
