package me.manga.kira.platform.analytics

import co.touchlab.kermit.Logger

/**
 * Desktop actual for [AnalyticsClient] — no-op.
 *
 * Firebase has no first-party JVM/desktop SDK; the REST-based Measurement Protocol is out of
 * scope. Calls are surfaced via Kermit at debug level for diagnostic visibility.
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/analytics/AnalyticsClient.desktop.kt`.
 */
class DesktopAnalyticsClient : AnalyticsClient {

    private val log = Logger.withTag(TAG)

    override fun logEvent(name: String, params: Map<String, Any?>) {
        log.d { "logEvent($name, $params) — no-op on Desktop" }
    }

    override fun setUserProperty(key: String, value: String?) {
        log.d { "setUserProperty($key, $value) — no-op on Desktop" }
    }

    override fun setUserId(id: String?) {
        log.d { "setUserId($id) — no-op on Desktop" }
    }

    private companion object {
        const val TAG = "AnalyticsClient.desktop"
    }
}

/*
 * §253 audit-trail postscript — cluster264 §253 sweep (2026-05-29)
 * Unit kind: platform-facade (Desktop actual leaf of a 3-actual fan).
 * Classification: FULFILLED-PORT.
 *
 * LIVE evidence:
 *  - Implements the rework interface at platform/src/commonMain/.../platform/
 *    analytics/AnalyticsClient.kt:20 ("interface AnalyticsClient", cluster148
 *    Task #604). This Desktop leaf is "class DesktopAnalyticsClient :
 *    AnalyticsClient" at line 13.
 *  - DI status: NO rework Koin binding. The package "me.manga.kira.platform.
 *    analytics" is referenced nowhere outside the four facade files (grep-
 *    verified). The graph-LIVE analytics surface today is the LEGACY ":shared"
 *    expect/actual, bound on Desktop at PlatformModule.desktop.kt:100 as
 *    "single -brace- AnalyticsClient() -brace-" (no-arg). So this leaf is
 *    FULFILLED-PORT in code but FORECAST-NOT-YET-FULFILLED at wiring — matching
 *    the cluster148 commonMain classification (LIVE-NOT-STALE plus FORECAST-
 *    NOT-YET-FULFILLED).
 *
 * Delta-axes (Desktop actual, vs Android and iOS siblings):
 *  1. Platform API — none. Firebase ships no first-party JVM/desktop SDK and
 *     the REST Measurement Protocol is intentionally out of scope, so this is
 *     a PERMANENT no-op (distinct from iOS, whose no-op is a Phase-12 DEFERRAL,
 *     not a permanent stance). The only API touched is Kermit's Logger.
 *  2. Threading/dispatcher — none. The three methods are synchronous and emit
 *     a single "log.d" line each; no coroutine, no dispatcher hop. Same non-
 *     suspending shape as the other two actuals.
 *  3. Error handling — trivially cannot fail: each method formats its args
 *     into a debug string and returns. No Bundle coercion (that lives only in
 *     the Android leaf), no null-rejection branch, no try-catch needed.
 *  4. DI binding mechanism — no-arg constructor; the legacy Desktop module
 *     binds it as "single -brace- AnalyticsClient() -brace-". Contrasts with
 *     Android's Context-arg constructor; matches iOS's no-arg shape.
 *  5. Behavioural-contract parity (3-actual fan) — confirmed: all three
 *     members (logEvent, setUserProperty, setUserId) are overridden with the
 *     interface's exact signatures. Desktop and iOS share the log-and-return
 *     body; Android diverges by doing real Firebase work. Contract shape is
 *     identical across all three; only the side effect differs.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class-level block at line 5) and no inline block comments; the appended
 * block adds exactly 1 opener and 1 closer, with no interior slash-star,
 * star-slash, or slash-star-star sequences. Balanced.
 */
