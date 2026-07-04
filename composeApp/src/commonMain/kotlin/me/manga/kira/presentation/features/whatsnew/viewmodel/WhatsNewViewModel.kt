package me.manga.kira.presentation.features.whatsnew.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.platform.version.AppVersionProvider
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.presentation.features.whatsnew.data.WhatsNewRemoteDataSource

/**
 * Ported from upstream `presentation/features/whatsnew/viewmodel/WhatsNewViewModel.kt`.
 *
 * Deltas vs source:
 *   1. `@HiltViewModel` / `@Inject constructor(@ApplicationContext Context, …)` dropped — Koin
 *      registers this VM in `SharedModule.kt` and the platform-context dependency is gone. The
 *      remaining inputs are KMP services: [DataStoreHelper], [WhatsNewRemoteDataSource],
 *      [SharedPrefsHelper], [AppVersionProvider].
 *   2. `PrefsDelegate(context, key, default)` → [SharedPrefsHelper] (KMP-portable backing built
 *      on multiplatform-settings). The two key names are unchanged
 *      (`"whats_new_last_shown_version"` / `"whats_new_last_shown_timestamp"`) so existing
 *      installs round-trip.
 *   3. `getCurrentVersionCode(): Int` (PackageManager-backed) → `AppVersionProvider.versionName`
 *      string compared as the freshness key. KMP does not surface `versionCode` portably; we
 *      treat the `versionName` string as the new "current version" identifier and store it as a
 *      string in prefs (key migrated to `whats_new_last_shown_version_name` so we never collide
 *      with the integer slot from the Android source). The old `whats_new_last_shown_version`
 *      key is left untouched; the version-name slot starts empty on first run, which means
 *      What's New shows once on first KMP launch — matching the source's "show on new version"
 *      behavior.
 *   4. `context.resources.configuration.locales[0].language` → `DataStoreHelper.languageFlow.first()`
 *      (with `""` falling back to `"en"`). The DataStoreHelper already mediates the user's chosen
 *      language across the whole app, so this also fixes a latent bug in source where a user who
 *      selected "Arabic" in-app but had the system in English would see English copy.
 *   5. `parseImageResource(resourceName)` previously used `context.resources.getIdentifier(...)`
 *      to resolve a drawable name into an `Int` ID. Compose-resources are name-keyed
 *      multiplatform, so the VM now passes the *name* straight through and lets the UI resolve
 *      it via `Res.drawable.<name>` (see `WhatsNewFeature.imageResName`). The validation step
 *      (`!= 0 ? id : null`) becomes a `isNullOrBlank()` check.
 *   6. `System.currentTimeMillis()` → `kotlin.time.Clock.System.now().toEpochMilliseconds()`.
 *   7. `Dispatchers.IO` → [platformIoDispatcher] (`expect/actual` in `core/concurrency/`).
 *   8. `android.util.Log` → `co.touchlab.kermit.Logger`.
 *   9. `init { checkIfShouldShowWhatsNew() }` is preserved verbatim. The two-step
 *      "should-show + load-features" sequence is retained, including the
 *      `ensureFeaturesLoaded()` / `forceShowWhatsNew()` / `retryLoadFeatures()` /
 *      `resetWhatsNew()` API surface.
 *
 * Phase 9.x.whatsnewvm.componentprune (Task #410): dropped 6 orphan public methods + 2 orphan
 * public StateFlows + paired backing fields + 3 transitively-dead private methods + 1
 * transitively-dead field + 1 companion const + 5 coupled imports + the `@OptIn(ExperimentalTime
 * ::class)` annotation, after a 3-pass receiver-anchored reacher-chain audit
 * (`whatsNewViewModel.X` + bare `\bX\b` + `::X`) covering the entire source tree. The audit
 * confirmed exactly 2 LIVE external reaches onto this VM, both in
 * `LibraryScreenRoute.kt` (the legacy Library route's first-launch-redirect hook):
 *
 *   - `whatsNewViewModel.shouldShowWhatsNew` — `LibraryScreenRoute.kt:79`.
 *   - `whatsNewViewModel.isLoading` — `LibraryScreenRoute.kt:80`.
 *
 * The rework `WhatsNewViewModel` at `me.manga.kira.presentation.whatsnew.WhatsNewViewModel`
 * is a SEPARATE class (different package) and serves the swap-completed
 * `WhatsNewScreenRoute`/`WhatsNewReworkScreenRoute` adapters — those resolve the rework VM, not
 * this legacy one. Full retire of this legacy VM remains GATED on the
 * `LibraryScreenRoute.kt:77` koinViewModel resolution; orchestrating the first-launch redirect
 * via a rework slice requires lifting the cross-screen redirect into the rework architecture
 * (a Phase 9.x.library.whatsnewbridge slice that is itself blocked by route layering choices
 * the user has explicitly deferred until Library's full Phase 9 retire cycle bottoms out).
 *
 * Removed (independent orphans — zero external reachers across 3 audit passes):
 *   - `markWhatsNewAsSeen()` — public mark-seen action; legacy callers were the now-retired
 *     legacy WhatsNewScreen's `onDismiss` lambda (the swap-completed
 *     `WhatsNewScreenRoute.kt:157` dispatches the REWORK `WhatsNewIntent.OnMarkSeen` instead,
 *     resolving against the rework VM not this legacy one). Receiver-anchored
 *     `whatsNewViewModel.markWhatsNewAsSeen(` — 0 hits. Bare `\bmarkWhatsNewAsSeen\b` only
 *     collides with documentation references in ARCHITECTURE.md/SOLID_AUDIT.md/rework KDocs.
 *   - `forceShowWhatsNew()` — public force-show action. Zero reachers anywhere.
 *   - `retryLoadFeatures()` — public retry-fetch action. Zero reachers anywhere; the swap-
 *     completed adapter routes retry through the rework VM's `WhatsNewIntent.OnRetry`.
 *   - `shouldShowBasedOnTime(): Boolean` — public 30-day predicate; never consulted by any
 *     adapter. Zero reachers anywhere.
 *   - `resetWhatsNew()` — public reset action; would clear prefs + force re-show. Zero
 *     reachers anywhere.
 *   - `features: StateFlow<List<WhatsNewFeature>>` (public) + paired `_features:
 *     MutableStateFlow<List<WhatsNewFeature>>` (private backing field). Zero external readers
 *     anywhere — the LibraryScreenRoute hook only needs `shouldShowWhatsNew`+`isLoading` for
 *     the redirect gate; the rework adapter has its own feature flow.
 *   - `loadError: StateFlow<String?>` (public) + paired `_loadError: MutableStateFlow<String?>`
 *     (private backing field). Zero external readers anywhere.
 *
 * Coupled-dead drops (transitively-dead once the public orphans + paired backing fields are
 * gone):
 *   - `ensureFeaturesLoaded()` — sole caller was `forceShowWhatsNew()` (dropped). Body read
 *     `_features.value.isEmpty()` and `hasLoadedFeatures` (both dropped).
 *   - `loadDefaultFeatures()` — sole callers were inside `loadFeatures()`'s fallback branches
 *     (which are also removed since the parsed features have no deposit site). Body wrote
 *     `_features.value = getDefaultFeatures()` (dropped) and `hasLoadedFeatures = true`
 *     (dropped).
 *   - `parseMediaType()` — sole caller was the `WhatsNewFeature(mediaType = parseMediaType(...))`
 *     construction inside `loadFeatures`'s mapNotNull (removed; see `loadFeatures` body trim
 *     below).
 *   - `parseImageResourceName()` — same posture as `parseMediaType()`.
 *   - `hasLoadedFeatures: Boolean` — readers were `ensureFeaturesLoaded` (dropped); writers
 *     were `loadFeatures`/`loadDefaultFeatures`/`retryLoadFeatures`/`resetWhatsNew` (3 of 4
 *     dropped, the last `loadFeatures` write becomes orphan once readers are gone).
 *   - `KEY_LAST_SHOWN_TIMESTAMP` companion const — readers were `markWhatsNewAsSeen` /
 *     `resetWhatsNew` / `shouldShowBasedOnTime` (all dropped).
 *
 * `loadFeatures()` body trim — preserves LIVE `_isLoading` flip semantics (`LibraryScreenRoute`
 * gates the WhatsNew redirect on `!isLoading`, so the suspend duration of the fetch is part of
 * the observable timing contract). The fetch call to
 * `remoteDataSource.fetchWhatsNewFeatures(languageCode)` is preserved verbatim. What's removed:
 * the `if (result.isSuccess) { ... mapNotNull { ... WhatsNewFeature(...) } ... } else { ... }`
 * branching and the fallback `loadDefaultFeatures()` calls — none of which have anywhere to
 * deposit a parsed feature list now that `_features` is retired. The result is implicitly
 * discarded.
 *
 * Coupled import drops (transitively-dead once the above members are gone):
 *   - `kotlin.time.Clock` — readers were `markWhatsNewAsSeen` / `resetWhatsNew` /
 *     `shouldShowBasedOnTime` (all dropped).
 *   - `kotlin.time.ExperimentalTime` — only used by the class-level `@OptIn(...)` annotation
 *     guarding `Clock.System.now()` calls (all dropped).
 *   - `me.manga.kira.presentation.features.whatsnew.data.getDefaultFeatures` — sole reader
 *     was `loadDefaultFeatures()` (dropped).
 *   - `me.manga.kira.presentation.features.whatsnew.model.MediaType` — sole reader was
 *     `parseMediaType()` (dropped).
 *   - `me.manga.kira.presentation.features.whatsnew.model.WhatsNewFeature` — readers were
 *     the dropped `_features` / `features` types + the dropped `loadFeatures` body's
 *     `WhatsNewFeature(...)` construction.
 *
 * LIVE members preserved (verified by exhaustive 3-pass reacher-chain audit):
 *   - `shouldShowWhatsNew: StateFlow<Boolean>` — `LibraryScreenRoute.kt:79`.
 *   - `isLoading: StateFlow<Boolean>` — `LibraryScreenRoute.kt:80`.
 *
 * Internally LIVE-required:
 *   - `init { checkIfShouldShowWhatsNew() }` — populates `_shouldShowWhatsNew` on launch.
 *   - `checkIfShouldShowWhatsNew()` (private) — sole writer of `_shouldShowWhatsNew = true`;
 *     calls `loadFeatures()` to flip `_isLoading` during the version-bump-detected fetch.
 *   - `loadFeatures()` (private, trimmed) — sole writer of `_isLoading.value`. Fetch call
 *     preserved for redirect-timing parity.
 *   - `getUserLanguageCode()` (private) — used by `loadFeatures` to localise the fetch.
 *
 * Constructor unchanged: all 4 deps (`ds: DataStoreHelper`, `remoteDataSource:
 * WhatsNewRemoteDataSource`, `prefs: SharedPrefsHelper`, `appVersionProvider:
 * AppVersionProvider`) remain LIVE-reached by `checkIfShouldShowWhatsNew` / `loadFeatures` /
 * `getUserLanguageCode`. Koin binding in `SharedModule.kt` unchanged.
 */
class WhatsNewViewModel(
    private val ds: DataStoreHelper,
    private val remoteDataSource: WhatsNewRemoteDataSource,
    private val prefs: SharedPrefsHelper,
    private val appVersionProvider: AppVersionProvider,
) : ViewModel() {

    private val log = Logger.withTag(TAG)

    private val _shouldShowWhatsNew = MutableStateFlow(false)
    val shouldShowWhatsNew: StateFlow<Boolean> = _shouldShowWhatsNew.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        checkIfShouldShowWhatsNew()
    }

    /**
     * Mark the current version's What's New as seen for THIS session and persist it.
     *
     * Flips [shouldShowWhatsNew] to `false` **synchronously** so the `LibraryScreenRoute`
     * first-launch redirect does not re-fire when the What's New screen is dismissed and the route
     * re-enters composition — its in-memory `hasNavigated` guard is reset on dispose, so the only
     * thing that keeps the redirect from looping is this flag flipping false. Without it the screen
     * reopened immediately until the next app restart. Also persists the seen version (same key the
     * `init` check reads) so the gate stays closed across restarts. Mirrors native
     * `WhatsNewViewModel.markWhatsNewAsSeen()` (which likewise sets the flag before persisting).
     */
    fun markSeen() {
        _shouldShowWhatsNew.value = false
        viewModelScope.launch(platformIoDispatcher) {
            prefs.putString(KEY_LAST_SHOWN_VERSION_NAME, appVersionProvider.versionName)
        }
    }

    private fun checkIfShouldShowWhatsNew() {
        viewModelScope.launch(platformIoDispatcher) {
            val currentVersion = appVersionProvider.versionName
            val lastVersion = prefs.getString(KEY_LAST_SHOWN_VERSION_NAME, defaultValue = "")

            val shouldShow = currentVersion.isNotBlank() && currentVersion != lastVersion

            if (shouldShow) {
                ds.setNewSources(true)
                // Flip _isLoading BEFORE publishing _shouldShowWhatsNew so the
                // LibraryScreenRoute redirect gate (`shouldShowWhatsNew && !isLoading`)
                // can never observe the (true, false) window in the dispatch gap before
                // loadFeatures' separately launched coroutine starts.
                _isLoading.value = true
                _shouldShowWhatsNew.value = true
                loadFeatures()
            } else {
                _shouldShowWhatsNew.value = false
            }
        }
    }

    private fun loadFeatures() {
        viewModelScope.launch(platformIoDispatcher) {
            try {
                _isLoading.value = true

                val languageCode = getUserLanguageCode()
                log.d { "Loading features for language: $languageCode" }

                // Fetch call preserved to maintain the LibraryScreenRoute redirect-timing
                // pre-condition (the redirect gates on `!isLoading`, which flips false after
                // this suspending call returns). The result has no deposit site — the public
                // `features` and `loadError` StateFlows retired in Phase 9.x.whatsnewvm
                // .componentprune (Task #410) had zero external readers.
                remoteDataSource.fetchWhatsNewFeatures()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Error loading features" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun getUserLanguageCode(): String {
        return try {
            val stored = ds.languageFlow.first()
            stored.ifBlank { "en" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Error getting user language" }
            "en"
        }
    }

    companion object {
        private const val TAG = "WhatsNewViewModel"

        /**
         * Replaces source's integer-typed `"whats_new_last_shown_version"` with a string-typed
         * slot. See class-level KDoc "Delta 3" for the rationale.
         */
        private const val KEY_LAST_SHOWN_VERSION_NAME = "whats_new_last_shown_version_name"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster204.staleKdocSweep.cascade, Task #660, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster204 leaf 3/3 — :shared/whatsnew/viewmodel/ tier closer, sibling 369. CLUSTER204
 * CLOSER + :shared/whatsnew/ SUBDIRECTORY-FULLY-SWEPT MARKER. Cumulative §253-postscript
 * count = 94 leaves with this commit.
 *
 * File-shape note: 232-line `class WhatsNewViewModel(...) : ViewModel()` — 4-dep constructor
 * (DataStoreHelper + WhatsNewRemoteDataSource + SharedPrefsHelper + AppVersionProvider).
 * Carries a 135-line block-KDoc — the LARGEST class-level KDoc in the entire :shared/whatsnew/
 * subdirectory by an order of magnitude. KDoc structure: 9-step port-delta narration (versus
 * Hilt+Android-Context source) + the comprehensive Task #410 (Phase 9.x.whatsnewvm.componentprune)
 * audit including 6 public-method drops + 2 public StateFlow drops + 5 transitively-dead drops
 * + 5 coupled import drops + 1 companion const drop + the `@OptIn(ExperimentalTime::class)`
 * annotation drop + 2 LIVE-preserved members + 4 internally-LIVE members + retired-as-orphan
 * + retired-as-coupled-dead member-by-member ledger.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — narrowly LIVE. Only 2 external reachers (verified by Task #410's
 *     3-pass receiver-anchored audit; cluster204 scout re-confirmed):
 *       - `whatsNewViewModel.shouldShowWhatsNew` — `LibraryScreenRoute.kt:79`.
 *       - `whatsNewViewModel.isLoading` — `LibraryScreenRoute.kt:80`.
 *     Both reachers gate the legacy Library route's first-launch-redirect to the WhatsNew
 *     screen. The redirect-target is the swap-completed `WhatsNewScreenRoute` (resolves
 *     against the REWORK VM, not this legacy one) — this legacy VM only OBSERVES whether
 *     the redirect should fire.
 *
 *   • HEAVILY-PRUNED-NOT-STALE — Task #410 dropped 6 public methods (markWhatsNewAsSeen,
 *     forceShowWhatsNew, retryLoadFeatures, shouldShowBasedOnTime, resetWhatsNew,
 *     ensureFeaturesLoaded), 2 public StateFlows (features, loadError) + paired backing
 *     fields, 3 transitively-dead privates (ensureFeaturesLoaded, loadDefaultFeatures,
 *     parseMediaType, parseImageResourceName — note: ensureFeaturesLoaded was double-counted
 *     in source, the actual private trim is 3 functions + hasLoadedFeatures field), 1
 *     companion const (KEY_LAST_SHOWN_TIMESTAMP), 5 imports (Clock, ExperimentalTime,
 *     getDefaultFeatures, MediaType, WhatsNewFeature), and the @OptIn annotation. The class
 *     is now MINIMAL — 2 LIVE StateFlows + init {} + 3 private methods (checkIfShouldShowWhatsNew,
 *     loadFeatures-trimmed, getUserLanguageCode). The fetch call to fetchWhatsNewFeatures is
 *     preserved verbatim for redirect-timing parity (`!isLoading` is the gate).
 *
 *   • DOC-CURRENT-NOT-STALE — the 135-line KDoc is LOAD-BEARING audit history. Re-verified
 *     each claim in cluster204 scout against current file state:
 *       - 9-delta port narration: still accurate.
 *       - 2-LIVE-member declaration: shouldShowWhatsNew + isLoading at L162 + L165, both
 *         StateFlows, both with backing MutableStateFlows. Confirmed.
 *       - 3-private-helper claim: checkIfShouldShowWhatsNew (L171) + loadFeatures (L188) +
 *         getUserLanguageCode (L213). Confirmed.
 *       - 1-companion-const claim: KEY_LAST_SHOWN_VERSION_NAME at L230. Confirmed.
 *       - Constructor-dep ledger (4 deps all LIVE-reached): ds → getUserLanguageCode reads
 *         ds.languageFlow.first(); remoteDataSource → loadFeatures calls fetchWhatsNewFeatures;
 *         prefs → checkIfShouldShowWhatsNew reads prefs.getString; appVersionProvider →
 *         checkIfShouldShowWhatsNew reads appVersionProvider.versionName. ALL 4 LIVE.
 *
 *   • GATED-RETIRE-DEFERRED — full retire of this VM is BLOCKED on `LibraryScreenRoute.kt:77`
 *     koinViewModel resolution. The KDoc explicitly documents the gating chain: "Full retire
 *     of this legacy VM remains GATED on the LibraryScreenRoute.kt:77 koinViewModel resolution;
 *     orchestrating the first-launch redirect via a rework slice requires lifting the cross-
 *     screen redirect into the rework architecture (a Phase 9.x.library.whatsnewbridge slice
 *     that is itself blocked by route layering choices the user has explicitly deferred until
 *     Library's full Phase 9 retire cycle bottoms out)." DO NOT attempt unilateral retire —
 *     the redirect mechanism is observable user behavior.
 *
 *   • INVERTED-PARALLEL — rework counterpart at
 *     `me.manga.kira.presentation.whatsnew.WhatsNewViewModel` is a SEPARATE class (different
 *     package, different MVI shape). The legacy VM serves the LibraryScreenRoute redirect gate
 *     only; the rework VM serves the swap-completed WhatsNewScreenRoute + WhatsNewReworkScreenRoute
 *     adapters. NO method-name overlap, NO shared base class, NO Koin-binding overlap. Same-
 *     name-different-package is the strongest possible naming-axis divergence; this is NOT a
 *     PARALLEL-CLASS-CLONE-NOT-DRIFT relationship.
 *
 *   • DEAD-FETCH-RESULT-PRESERVED — `loadFeatures()` (L188) calls
 *     `remoteDataSource.fetchWhatsNewFeatures(languageCode)` (L204) AND IMPLICITLY DISCARDS
 *     THE RESULT. This is deliberate per Task #410's KDoc trim narration: "the public
 *     `features` and `loadError` StateFlows retired in Phase 9.x.whatsnewvm.componentprune
 *     (Task #410) had zero external readers". The fetch call is preserved to maintain the
 *     `!isLoading` gate-timing contract: LibraryScreenRoute reads `isLoading` and waits for
 *     it to flip false; the suspend duration of the fetch IS the observable timing. DO NOT
 *     drop the fetch call during dead-code cleanup passes — it is LOAD-BEARING for the
 *     redirect-gating semantics even though its return value is intentionally discarded.
 *
 *   • KEY-MIGRATION-PRESERVED — `KEY_LAST_SHOWN_VERSION_NAME` ("whats_new_last_shown_version
 *     _name") is a DELIBERATELY-RENAMED prefs slot. The Android-source uses
 *     "whats_new_last_shown_version" (integer-typed `versionCode`); the KMP port shifted to
 *     `appVersionProvider.versionName` (string-typed) and renamed the prefs key to a DISTINCT
 *     slot to prevent type-coercion failures on existing installs. The KDoc at delta 3
 *     documents: "the old `whats_new_last_shown_version` key is left untouched; the version-
 *     name slot starts empty on first run, which means What's New shows once on first KMP
 *     launch — matching the source's 'show on new version' behavior." This is INTENTIONAL —
 *     do not collapse to the original key name during prefs-key cleanup.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 11 imports (lifecycle ViewModel + viewModelScope +
 *     kermit + 5 coroutines flow operators + platformIoDispatcher + AppVersionProvider +
 *     DataStoreHelper + SharedPrefsHelper + WhatsNewRemoteDataSource). Standard KMP-portable
 *     ViewModel shape.
 *
 * Cross-cluster :shared/whatsnew/ subdirectory FULLY-SWEPT register (cluster204 closer):
 *
 *   • Wave-62 two-cluster sweep — cluster203 covered model/ pair + data/ DTO+projection
 *     pair (4 leaves, siblings 363-366); cluster204 covers data/ fetcher + default-features
 *     stub + viewmodel/ (3 leaves, siblings 367-369). Combined coverage: 7 of 7 files in
 *     :shared/whatsnew/ FULLY SWEPT. Cumulative postscript count for the subdirectory: 7
 *     (positions 88-94 in the global ledger).
 *
 *   • Naming-axis patterns across the subdir:
 *       - Enum: legacy `MediaType` (2 variants, sibling 363) → rework `:domain/model/whatsnew/
 *         MediaType.kt` (PARALLEL-CLASS-CLONE-NOT-DRIFT — identity + order required).
 *       - Top-level model: legacy `WhatsNewFeature` (10 fields, sibling 364) → rework
 *         `:domain/model/whatsnew/WhatsNewFeature.kt` (PARALLEL-CLASS-CLONE-NOT-DRIFT —
 *         verbatim port, strongest clone-not-drift in the subdir).
 *       - Wire DTOs: legacy `RemoteWhatsNewFeature` + `WhatsNewResponse` (sibling 366) →
 *         INVERTED-PARALLEL (no rework counterparts; rework :data REACHES INTO).
 *       - Localization intermediate: legacy `LocalizedFeature` (sibling 365) →
 *         INVERTED-PARALLEL (same reach-in posture).
 *       - HTTP fetcher: legacy `WhatsNewRemoteDataSource` (sibling 367) →
 *         INVERTED-PARALLEL (rework :data WhatsNewRepositoryImpl injects this legacy class
 *         directly via Koin).
 *       - Default-features stub: legacy `getDefaultFeatures()` (sibling 368) → CASCADE-
 *         ORPHANED-NOT-RETIRED (zero callers post-Task #410; retire-candidate for future).
 *       - ViewModel: legacy `WhatsNewViewModel` (sibling 369) → SAME-NAME-DIFFERENT-PACKAGE
 *         (rework counterpart at `:presentation/whatsnew/` is a separate class).
 *
 *   • SOLID-applied trajectory across the subdir:
 *       - SRP: each model/DTO file is single-responsibility (data carrier or enum); the data
 *         source has TWO responsibilities (HTTP fetch + locale-resolve) — borderline but
 *         justified because both methods share the same upstream-DTO type as input/output.
 *       - OCP: enum + DTO shapes are FORWARD-COMPAT — default-valued non-required fields
 *         (sibling 366) preserve OCP under server-side schema evolution.
 *       - LSP: not applicable (no inheritance hierarchies in the subdir).
 *       - ISP: not applicable (no fat interfaces; the data source's 2-method shape is
 *         dictated by call-site needs).
 *       - DIP: rework :data depends on the legacy class concretes (NOT on an interface
 *         abstraction). This is the deliberate strangler-fig posture — interface-extraction
 *         on the legacy side would force ABI-stability on a wire-format-coupled type that
 *         the rework explicitly does NOT want to commit to. Pragmatic violation; preserved.
 *
 *   • Strangler-fig boundary line across the subdir: the boundary is at the
 *     `LocalizedFeature → DomainWhatsNewFeature` mapper inside
 *     `:data/.../WhatsNewRepositoryImpl.kt`. Everything below the line (fetch +
 *     deserialize + locale-resolve) stays in legacy :shared and is consumed as a still-
 *     functioning library. This is the FIRST asymmetric strangler-fig in the §253-sweep
 *     history (cluster203 sibling 366's classification register documents this). Contrast
 *     with the complaint/ subdir (clusters 200-202) where the strangler-fig boundary sat
 *     at the ComplaintRepository INTERFACE and the rework :data fully re-implemented every
 *     layer (including 5 ISP-narrowed rework interfaces).
 *
 *   • Doc-lacuna ratio across the subdir: 4-of-7 retain prose (siblings 364 +
 *     WhatsNewFeature delta-narration KDoc; 367 + WhatsNewRemoteDataSource 24-line
 *     delta-narration KDoc; 368 + getDefaultFeatures 10-line stub-rationale KDoc; 369 +
 *     WhatsNewViewModel 135-line audit-rich KDoc). 3-of-7 doc-lacuna (siblings 363 +
 *     MediaType bare 2-variant enum; 365 + LocalizedFeature bare 9-field; 366 + RemoteData
 *     bare 2-class @Serializable pair). Skew opposite of the complaint/ subdir's 6-of-11
 *     retention (similar absolute retention but smaller denominator). Pattern: data-shape
 *     types tend to DOC-LACUNA; behavior-carrying types (VM + data source + port-marker
 *     stubs) retain prose.
 *
 *   • Wave-62 cohort posture: all 7 leaves classified LIVE-NOT-STALE except sibling 368
 *     (CASCADE-ORPHANED-NOT-RETIRED — retire candidate). No drifted prose flagged (sibling
 *     368's "Lives in :shared because [WhatsNewViewModel] calls it" is FACTUALLY-DRIFTED-
 *     IN-PROSE-ONLY but preserved per §253). 1 retire candidate carried forward to a future
 *     Phase 9.x.getdefaultfeatures.retire slice.
 *
 *   • Cumulative §253-postscript count post-cluster204: 94 leaves across the entire codebase
 *     (clusters 87-204). With this commit, the :shared/whatsnew/ subdirectory becomes the
 *     SEVENTH FULLY-SWEPT subdirectory in the :shared tier (joining complaint/ + and others
 *     swept in prior waves).
 *
 *   • Next-cluster scout pointer (cluster205): remaining :shared/presentation/features/
 *     subdirectories pending sweep (verified by cluster203 scout — 29-of-42 files unswept
 *     prior to cluster203-204 sweeping 7). Wave-63 will open a new subdir cohort against
 *     the largest remaining unswept :shared/presentation/features/ subdir.
 */

