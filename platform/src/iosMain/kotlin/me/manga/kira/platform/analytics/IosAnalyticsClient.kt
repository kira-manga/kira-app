package me.manga.kira.platform.analytics

import co.touchlab.kermit.Logger

/**
 * iOS actual for [AnalyticsClient] — no-op.
 *
 * Firebase iOS SDK is not wired in Phase 8; APNS / Firebase iOS integration is scheduled for
 * Phase 12 once the CocoaPods / cinterop story is finalized. Calls are surfaced via Kermit at
 * debug level so developers exercising flows on the iOS simulator can confirm the facade is
 * being reached without observing real Firebase events.
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/analytics/AnalyticsClient.ios.kt`.
 */
class IosAnalyticsClient : AnalyticsClient {

    private val log = Logger.withTag(TAG)

    override fun logEvent(name: String, params: Map<String, Any?>) {
        log.d { "logEvent($name, $params) — no-op on iOS" }
    }

    override fun setUserProperty(key: String, value: String?) {
        log.d { "setUserProperty($key, $value) — no-op on iOS" }
    }

    override fun setUserId(id: String?) {
        log.d { "setUserId($id) — no-op on iOS" }
    }

    private companion object {
        const val TAG = "AnalyticsClient.ios"
    }
}

/*
 * §253 audit-trail postscript — cluster264 §253 sweep (2026-05-29)
 * Unit kind: platform-facade (iOS actual leaf of a 3-actual fan).
 * Classification: FULFILLED-PORT.
 *
 * LIVE evidence:
 *  - Implements the rework interface at platform/src/commonMain/.../platform/
 *    analytics/AnalyticsClient.kt:20 ("interface AnalyticsClient", cluster148
 *    Task #604). This iOS leaf is "class IosAnalyticsClient : AnalyticsClient"
 *    at line 15.
 *  - DI status: NO rework Koin binding. Grep for "me.manga.kira.platform.
 *    analytics" finds it only inside the four facade files. The graph-LIVE
 *    analytics surface on iOS today is the LEGACY ":shared" expect/actual,
 *    bound at PlatformModule.ios.kt:100 as "single -brace- AnalyticsClient()
 *    -brace-" (no-arg). The legacy iOS module KDoc (PlatformModule.ios.kt:188-
 *    195) explicitly notes that when the Phase-12 CocoaPods/cinterop port
 *    lands, only the actual-class body changes and the binding line stays.
 *    This rework leaf is therefore FULFILLED-PORT but FORECAST-NOT-YET-
 *    FULFILLED, mirroring the cluster148 commonMain posture.
 *
 * Delta-axes (iOS actual, vs Android and Desktop siblings):
 *  1. Platform API — none yet. The Firebase iOS SDK is NOT wired in Phase 8;
 *     it is scheduled for Phase 12 once the CocoaPods/cinterop story settles.
 *     Today the only API touched is Kermit. This is a DEFERRED no-op (a real
 *     backend is planned), distinct from Desktop's PERMANENT no-op stance.
 *  2. Threading/dispatcher — none. Three synchronous "log.d" emitters, no
 *     coroutine or dispatcher; identical non-suspending shape to the siblings.
 *  3. Error handling — none required; each method formats args to a debug
 *     string and returns. No Bundle coercion, no null-rejection (those are
 *     Android-only concerns).
 *  4. DI binding mechanism — no-arg constructor, bound on iOS as "single
 *     -brace- AnalyticsClient() -brace-". Matches Desktop's no-arg shape;
 *     differs from Android's Context-arg constructor. When Phase 12 lands the
 *     binding shape need not change — only this class body.
 *  5. Behavioural-contract parity (3-actual fan) — confirmed: logEvent,
 *     setUserProperty, and setUserId are each overridden with the interface
 *     signatures. iOS shares the log-and-return body with Desktop; Android
 *     diverges with real Firebase work. Contract shape is uniform across all
 *     three actuals — only the iOS leaf carries a future-backend forecast.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class-level block at line 5) and no inline block comments; the appended
 * block adds exactly 1 opener and 1 closer, with no interior slash-star,
 * star-slash, or slash-star-star sequences. Balanced.
 */
