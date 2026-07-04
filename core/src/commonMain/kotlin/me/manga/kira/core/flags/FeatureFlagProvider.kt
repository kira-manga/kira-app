package me.manga.kira.core.flags

/**
 * Feature-flag SPI — required by contract §9 maintainability rule
 * ("Feature flags ready: FeatureFlagProvider interface in :core").
 *
 * Why this lives in :core and not :data: feature flags are a cross-cutting concern that domain
 * and presentation may both query. Putting the interface in :core lets every layer depend on it
 * without anyone reaching into :data for a config source.
 *
 * The default implementation [InMemoryFeatureFlagProvider] is appropriate for production until
 * a remote-config backend is wired up (Firebase Remote Config / on-device override store). When
 * that backend exists, swap the Koin binding in :platform — call sites do not change.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster142.staleKdocSweep.cascade,
 * Task #598, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-ninth sibling of the cluster57-141
 * sweep — fourth and closing file of the wave-26 opening cluster142
 * 4-leaf-:core-foundation batch alongside AppResult plus AppError plus
 * Logger; closes cluster142):
 *  (a) "Feature-flag-SPI-required-by-contract-§9-maintainability-rule-
 *  FeatureFlagProvider-interface-in-:core + Why-this-lives-in-:core-
 *  and-not-:data-feature-flags-are-a-cross-cutting-concern-that-domain-
 *  and-presentation-may-both-query + Putting-the-interface-in-:core-
 *  lets-every-layer-depend-on-it-without-anyone-reaching-into-:data-
 *  for-a-config-source + The-default-implementation-InMemoryFeatureFlag-
 *  Provider-is-appropriate-for-production-until-a-remote-config-
 *  backend-is-wired-up-Firebase-Remote-Config-on-device-override-store
 *  + When-that-backend-exists-swap-the-Koin-binding-in-:platform-call-
 *  sites-do-not-change" — LIVE-NOT-STALE plus FORECAST-NOT-YET-
 *  FULFILLED. Verified via recursive grep: FeatureFlagProvider is
 *  declared in :core and referenced ONLY by build.gradle.kts
 *  declarations + the contract documents (ARCHITECTURE.md + SOLID_-
 *  AUDIT.md + ARCHITECTURE_REWORK_CONTRACT.md) — no domain or
 *  presentation site currently consumes it. The SPI is properly
 *  positioned as cross-cutting infrastructure ready for first-consumer
 *  landing, but the actual "wired-up remote-config backend" forecast
 *  remains UNREALIZED — InMemoryFeatureFlagProvider is the only
 *  declared impl, and no Koin binding has been registered yet (no
 *  rework slice has needed a runtime feature flag). The "swap-Koin-
 *  binding-in-:platform-without-call-site-change" promise is
 *  STRUCTURALLY READY — the SPI surface (4-method set: isEnabled +
 *  getString + getInt + getLong, each with a default param) is
 *  type-stable for substitution. No InMemoryFeatureFlagProvider
 *  consumer has emerged because the rework slices that might benefit
 *  from a flag (e.g. the §250 shadow-legacy facade campaign blocker
 *  [Task #422 BLOCKED], pure-black/OLED toggle [Task #243], display-
 *  toggle prefs foundation [Task #333]) all chose direct DataStore-
 *  backed persistence rather than feature-flag dispatch — a deliberate
 *  scope choice during rework.
 *  (b) "Returns-the-value-for-flag-or-default-if-no-override-is-set +
 *  Returns-a-string-valued-flag-or-default + Used-for-endpoint-
 *  overrides-A-B-variants-etc + Returns-an-int-valued-flag-or-default
 *  + Returns-a-long-valued-flag-or-default + Hand-coded-in-memory-
 *  provider-appropriate-before-a-remote-config-backend-ships +
 *  Construct-it-with-the-defaults-the-app-needs-and-bind-it-via-Koin
 *  + The-map-is-immutable-after-construction-no-setters + Hot-
 *  reloading-flags-requires-re-creating-the-provider-keeps-the-SOLID-
 *  OCP-property-you-do-not-mutate-an-existing-provider-you-replace-
 *  the-binding-with-a-new-one" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION (about-shape). Verified: InMemoryFeatureFlagProvider's
 *  constructor takes `overrides: Map<String, Any> = emptyMap()` —
 *  the default of emptyMap means a zero-arg construction returns
 *  defaults for every call. The 4 methods use `(overrides[flag] as? T)
 *  ?: default` — type-narrowed safe-cast preserves the no-untyped-
 *  cast invariant. The immutable-after-construction posture holds —
 *  there are no setter methods declared. The OCP-preserving "replace-
 *  binding-with-a-new-one" pattern is the documented hot-reload
 *  strategy and remains uncontested.
 *  Two classifications STAND on their own merits. Closes cluster142.
 *  Original Phase 2 (Task #153) :core-skeleton-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
interface FeatureFlagProvider {
    /** Returns the value for [flag] or [default] if no override is set. */
    fun isEnabled(flag: String, default: Boolean = false): Boolean

    /** Returns a string-valued flag or [default]. Used for endpoint overrides, A/B variants, etc. */
    fun getString(flag: String, default: String = ""): String

    /** Returns an int-valued flag or [default]. */
    fun getInt(flag: String, default: Int = 0): Int

    /** Returns a long-valued flag or [default]. */
    fun getLong(flag: String, default: Long = 0L): Long
}

/**
 * Hand-coded in-memory provider — appropriate before a remote-config backend ships. Construct it
 * with the defaults the app needs and bind it via Koin.
 *
 * The map is immutable after construction (no setters). Hot-reloading flags requires re-creating
 * the provider — keeps the SOLID-OCP property: you don't mutate an existing provider, you replace
 * the binding with a new one.
 */
class InMemoryFeatureFlagProvider(
    private val overrides: Map<String, Any> = emptyMap(),
) : FeatureFlagProvider {

    override fun isEnabled(flag: String, default: Boolean): Boolean =
        (overrides[flag] as? Boolean) ?: default

    override fun getString(flag: String, default: String): String =
        (overrides[flag] as? String) ?: default

    override fun getInt(flag: String, default: Int): Int =
        (overrides[flag] as? Int) ?: default

    override fun getLong(flag: String, default: Long): Long =
        (overrides[flag] as? Long) ?: default
}
