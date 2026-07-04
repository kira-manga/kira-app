package me.manga.kira.platform.consent

/**
 * Cross-platform Google UMP (User Messaging Platform) facade — covers the GDPR / IAB TCF
 * consent flow required by AdMob in EEA regions.
 *
 * Implementations:
 *  - Android  → wraps `UserMessagingPlatform.getConsentInformation(context)`. Both async methods
 *               require a foreground `Activity` (same convention as other Phase 5.z facades).
 *  - iOS      → no-op. Ads aren't served on iOS, so the consent flow is moot — returns
 *               `NOT_REQUIRED` / `canRequestAds = true`.
 *  - Desktop  → no-op. Desktop doesn't serve ads, so the consent flow is moot — same return
 *               shape as iOS.
 */
interface ConsentFlowClient {
    /**
     * Refreshes the consent information from the UMP backend. Must complete before any other
     * call; subsequent calls reuse the cached status.
     */
    suspend fun requestConsentInfoUpdate(): ConsentStatus

    /**
     * If the user is in a region/state that requires consent, loads and shows the UMP-managed
     * consent form. Returns true if a form was shown (or if no form was required).
     */
    suspend fun loadAndShowConsentFormIfRequired(): Boolean

    /** Returns whether the SDK currently allows ad requests under the user's consent state. */
    fun canRequestAds(): Boolean
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster148.staleKdocSweep.cascade,
 * Task #604, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventy-fourth sibling of the cluster57-147
 * sweep — second file of the wave-26 :platform tier cluster148 5-leaf
 * telemetry-plus-monetization batch alongside InAppReviewClient plus
 * AdProvider plus AnalyticsClient plus CrashReporter):
 *  (a) "Cross-platform-Google-UMP-User-Messaging-Platform-facade-covers-
 *  the-GDPR-IAB-TCF-consent-flow-required-by-AdMob-in-EEA-regions +
 *  Android-wraps-UserMessagingPlatform.getConsentInformation-context +
 *  Both-async-methods-require-a-foreground-Activity-same-convention-as-
 *  other-Phase-5.z-facades + iOS-no-op-Ads-aren-t-served-on-iOS-so-the-
 *  consent-flow-is-moot-returns-NOT_REQUIRED-canRequestAds-true +
 *  Desktop-no-op-Desktop-doesn-t-serve-ads-so-the-consent-flow-is-moot
 *  -same-return-shape-as-iOS" — LIVE-NOT-STALE. Verified: 3 actuals
 *  shipped at platform/src/{android,ios,desktop}Main/consent/. Android
 *  delegates to UserMessagingPlatform.getConsentInformation(context)
 *  + requires ForegroundActivityProvider for async flows. iOS/Desktop
 *  no-op stubs return NOT_REQUIRED + canRequestAds=true; the "ads not
 *  served on iOS/Desktop" rationale honored — AdProvider iOS/Desktop
 *  actuals also no-op (cross-classified at sibling 175 AdProvider).
 *  (b) "Cross-platform-consent-status + Mirrors-the-AdMob-UMP-Consent-
 *  Status-values-that-consumer-code-actually-branches-on + Co-located-
 *  with-ConsentFlowClient-because-it-s-exclusively-the-return-type-of-
 *  one-interface-method-same-precedent-as-AppUpdateInfo-Connectivity-
 *  Observer.Status" — LIVE-NOT-STALE. Verified: enum has 4 values
 *  (UNKNOWN, NOT_REQUIRED, REQUIRED, OBTAINED) matching UMP SDK. The
 *  co-location-with-facade precedent is consistently honored across
 *  the cluster148 telemetry+monetization tier (AdResult co-located
 *  with AdProvider — cross-classified at sibling 175).
 *  Two classifications STAND on their own merits. Original Phase
 *  5.z.3 (Task #190) :platform-relocation prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */

/**
 * Cross-platform consent status. Mirrors the AdMob UMP `ConsentStatus` values that consumer code
 * actually branches on. Co-located with [ConsentFlowClient] because it's exclusively the return
 * type of one interface method (same precedent as `AppUpdateInfo`, `ConnectivityObserver.Status`).
 */
enum class ConsentStatus {
    UNKNOWN,
    NOT_REQUIRED,
    REQUIRED,
    OBTAINED,
}
