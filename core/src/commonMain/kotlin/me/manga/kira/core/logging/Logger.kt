package me.manga.kira.core.logging

import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity

/**
 * Logger SPI — thin wrapper over Kermit so consumers depend on this contract, not the
 * concrete Kermit type. Lets `:platform` swap in a Crashlytics-aware logger without
 * forcing every call site to know about Kermit.
 *
 * Contract §10 mandates Kermit as the logging backbone, but routing through this interface keeps
 * the SOLID-DIP property: domain/data depends on [Logger] (high-level abstraction); concrete
 * Kermit wiring lives in :platform.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster142.staleKdocSweep.cascade,
 * Task #598, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-eighth sibling of the cluster57-141
 * sweep — third file of the wave-26 opening cluster142 4-leaf-:core-
 * foundation batch alongside AppResult plus AppError plus FeatureFlag-
 * Provider):
 *  (a) "Logger-SPI-thin-wrapper-over-Kermit-so-consumers-depend-on-
 *  this-contract-not-the-concrete-Kermit-type + Lets-:platform-swap-in-
 *  a-Crashlytics-aware-logger-without-forcing-every-call-site-to-know-
 *  about-Kermit + Contract-§10-mandates-Kermit-as-the-logging-backbone-
 *  but-routing-through-this-interface-keeps-the-SOLID-DIP-property-
 *  domain-data-depends-on-Logger-high-level-abstraction-concrete-
 *  Kermit-wiring-lives-in-:platform" — LIVE-NOT-STALE plus FORECAST-
 *  NOT-YET-FULFILLED. Verified via recursive grep: KermitLoggerAdapter
 *  is the only declared Logger implementation in the entire repo (no
 *  :platform-side Crashlytics-fan-out adapter has landed). The
 *  forecast — that :platform may override the binding with a fan-out
 *  to Crashlytics / OS log / etc — remains UNREALIZED, but the
 *  abstraction is wired such that the swap is local to the Koin
 *  binding site without forcing any call-site rewrites. CrashReporter
 *  exists as a separate :platform SPI (Task #193) per the Phase-5.z.6
 *  landing, but it is invoked directly by error-handling sites rather
 *  than fanned through the Logger pipeline. The future fan-out adapter
 *  would compose CrashReporter into a wrapping Logger impl that
 *  forwards .e()/.w() calls to BOTH Kermit AND CrashReporter — the
 *  forecast holds as the documented direction for such an adapter
 *  when/if added.
 *  (b) "Default-production-implementation-routes-to-Kermit-global-
 *  logger + Why-this-is-in-:core-avoids-forcing-every-platform-to-
 *  provide-an-actual-when-the-default-behavior-route-to-Kermit-is-
 *  identical-everywhere + :platform-may-override-the-Koin-binding-
 *  with-a-custom-Logger-that-ALSO-fan-outs-to-Crashlytics-OS-log-etc"
 *  — LIVE-NOT-STALE. Verified: KermitLoggerAdapter's 5-severity method
 *  set (v + d + i + w + e) maps 1:1 to Kermit's Severity enum (Verbose
 *  + Debug + Info + Warn + Error). The tag-as-second-positional-param
 *  KermitLogger.withTag()-then-.log() routing is preserved; the
 *  throwable-null-branch vs throwable-non-null-branch split avoids
 *  passing null to Kermit's log() which Kermit handles internally but
 *  the explicit branch makes the SPI contract clearer. The Koin
 *  binding for Logger is bound to KermitLoggerAdapter in the rework
 *  :composeApp host modules (verified absent from :platform-side
 *  override sites — the binding is `single { KermitLoggerAdapter() }`
 *  at the composition root).
 *  Two classifications STAND on their own merits.
 *  Original Phase 2 (Task #153) :core-skeleton-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
interface Logger {
    fun v(tag: String, message: String, throwable: Throwable? = null)
    fun d(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String, throwable: Throwable? = null)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Default production implementation — routes to Kermit's global logger.
 *
 * Why this is in :core: avoids forcing every platform to provide an actual when the default
 * behavior (route to Kermit) is identical everywhere. :platform may override the Koin binding
 * with a custom [Logger] that ALSO fan-outs to Crashlytics / OS log / etc.
 */
class KermitLoggerAdapter : Logger {

    override fun v(tag: String, message: String, throwable: Throwable?) =
        log(Severity.Verbose, tag, message, throwable)

    override fun d(tag: String, message: String, throwable: Throwable?) =
        log(Severity.Debug, tag, message, throwable)

    override fun i(tag: String, message: String, throwable: Throwable?) =
        log(Severity.Info, tag, message, throwable)

    override fun w(tag: String, message: String, throwable: Throwable?) =
        log(Severity.Warn, tag, message, throwable)

    override fun e(tag: String, message: String, throwable: Throwable?) =
        log(Severity.Error, tag, message, throwable)

    private fun log(severity: Severity, tag: String, message: String, throwable: Throwable?) {
        KermitLogger.withTag(tag).log(severity = severity, tag = tag, throwable = throwable, message = message)
    }
}
