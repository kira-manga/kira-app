package me.manga.kira.platform.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Android actual for [AnalyticsClient] — delegates to `FirebaseAnalytics.getInstance(context)`.
 *
 * Translates the common `Map<String, Any?>` into a [Bundle] using the type rules Firebase
 * Analytics natively supports (String / Long / Int / Double / Float / Boolean). Unknown types
 * fall back to `toString()` to preserve the diagnostic value of the event.
 *
 * Verbatim semantic port from legacy `:shared/androidMain/.../core/analytics/AnalyticsClient.android.kt`.
 * Preserves:
 *  - `applicationContext` unwrap on `FirebaseAnalytics.getInstance(...)` so the singleton does
 *    not retain an Activity (Firebase caches per-context internally).
 *  - `null` parameter values are silently skipped — Firebase Analytics rejects null values
 *    (the rejection logs a warning that's noisy in release builds). The legacy comment is
 *    preserved verbatim because it documents the load-bearing reason for the `null` branch.
 *  - Empty-map fast path: `analytics.logEvent(name, null)` avoids allocating an empty Bundle.
 */
class AndroidAnalyticsClient(context: Context) : AnalyticsClient {

    private val analytics: FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context.applicationContext)

    override fun logEvent(name: String, params: Map<String, Any?>) {
        if (params.isEmpty()) {
            analytics.logEvent(name, null)
            return
        }
        val bundle = Bundle().apply {
            for ((key, value) in params) {
                when (value) {
                    null -> { /* skip null entries — Firebase doesn't accept null parameter values */ }
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Double -> putDouble(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }
        analytics.logEvent(name, bundle)
    }

    override fun setUserProperty(key: String, value: String?) {
        analytics.setUserProperty(key, value)
    }

    override fun setUserId(id: String?) {
        analytics.setUserId(id)
    }
}

/*
 * §253 audit-trail postscript — cluster264 §253 sweep (2026-05-29)
 * Unit kind: platform-facade (Android actual leaf of a 3-actual fan).
 * Classification: FULFILLED-PORT.
 *
 * LIVE evidence:
 *  - The rework facade interface lives at platform/src/commonMain/.../platform/
 *    analytics/AnalyticsClient.kt:20 ("interface AnalyticsClient"), swept in
 *    cluster148 (Task #604). This Android implementer ("class AndroidAnalytics
 *    Client(context: Context) : AnalyticsClient" at line 23) is the Firebase-
 *    backed leaf of that contract.
 *  - DI status: NO rework Koin binding exists yet. A repo-wide grep for the
 *    package "me.manga.kira.platform.analytics" returns matches ONLY inside
 *    the four facade files themselves (interface plus 3 actuals) — zero
 *    consumer or module reach. The analytics surface that is LIVE in the graph
 *    today is the LEGACY ":shared" expect/actual: bound at PlatformModule.
 *    android.kt:114 as "single -brace- AnalyticsClient(androidContext()) -brace-".
 *    The rework leaf is FULFILLED-PORT (code shipped, contract honored) but
 *    FORECAST-NOT-YET-FULFILLED at the wiring layer — exactly the posture the
 *    cluster148 commonMain-interface postscript recorded (LIVE-NOT-STALE plus
 *    FORECAST-NOT-YET-FULFILLED).
 *
 * Delta-axes (Android actual, vs the two no-op siblings):
 *  1. Platform API — wraps "FirebaseAnalytics.getInstance(context.application
 *     Context)". This is the ONLY actual of the three with a real backend; iOS
 *     and Desktop are diagnostic no-ops (Kermit "log.d").
 *  2. Threading/dispatcher — none. All three methods are synchronous fire-and-
 *     forget; Firebase batches and flushes events on its own internal thread,
 *     so the facade neither suspends nor hops dispatchers. Contract parity:
 *     all three actuals expose the same non-suspending signatures.
 *  3. Error handling — defensive type coercion in "logEvent": the "Map -String,
 *     Any-null-" is folded into a Bundle honoring Firebase's accepted scalar
 *     types (String, Int, Long, Float, Double, Boolean); null entries are
 *     skipped (Firebase rejects null param values); unknown types fall back to
 *     "toString()". Empty-map fast path passes a null Bundle to skip allocation.
 *  4. DI binding mechanism — constructor injection of an Android "Context"
 *     ("androidContext()" supplies it in the legacy module). This is the one
 *     actual whose constructor takes an argument; Desktop and iOS are no-arg.
 *  5. Behavioural-contract parity (3-actual fan) — confirmed: the interface's
 *     three members (logEvent, setUserProperty, setUserId) are each overridden
 *     by all three actuals with matching signatures. Android performs real
 *     work; iOS and Desktop log-and-return. Semantics diverge only in side
 *     effect, not in contract shape — intentional per the commonMain KDoc.
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class-level block at line 7) plus 1 inline line-comment at line 36; the
 * appended block adds exactly 1 opener and 1 closer with no interior
 * slash-star, star-slash, or slash-star-star sequences. Balanced.
 */
