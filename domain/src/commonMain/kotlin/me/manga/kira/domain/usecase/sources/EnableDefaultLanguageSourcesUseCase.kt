package me.manga.kira.domain.usecase.sources

import me.manga.kira.domain.repository.SourcesRepository

/**
 * Enable every source matching the user's locale, falling back to English when no native
 * sources exist for that locale.
 *
 * Phase 7.x.sources.onboardingseed. Encapsulates the onboarding step 3 default-seed policy:
 * format the user's locale code as the parenthesised tag the legacy `saveSources` seed
 * uses (`"en"` → `"(EN)"`), pass that as the [SourcesRepository.setLanguageEnabledWithFallback]
 * primary, and pin the fallback to `"(EN)"`. The repository owns the snapshot + fan-out
 * mechanism; this use case owns the policy (which tag, which fallback).
 *
 * **Mirrors legacy** `RepoSettingsViewModel.setLanguageEnabledDefault` semantics verbatim:
 * blank locale coerces to `"en"`; tag uppercases and is wrapped in parens; if no sources
 * match the user's locale the fallback fires and enables every English source. The legacy
 * version inlined this in the VM; the rework lifts it to a use case so the rework
 * `SourcesViewModel` stays a thin dispatcher.
 *
 * **Why a use case for a one-line pass-through** — DIP. The
 * [me.manga.kira.presentation.sources.SourcesViewModel] depends on a stable use-case
 * interface, not on a repository method. Future composition (e.g. adding analytics, gating
 * on a "first onboarding" pref, reconciling with a device-region fallback) lives here, not
 * in the VM. Same posture as [SetLanguageEnabledUseCase] / [SetSourceEnabledUseCase].
 *
 * **`languageTag` shape** — the raw user-selected locale code as persisted by
 * `DataStoreHelper.languageFlow` (e.g. `"en"`, `"fr"`, `""`). Blank coerces to `"en"`
 * matching the legacy `userLanguageCode.ifBlank { "en" }` step. Case is normalized via
 * `.uppercase()` to match the parenthesised tag convention.
 *
 * **Idempotency** — the upstream Room `UPDATE sources SET isEnabled = 1 WHERE name = ?`
 * is idempotent on already-enabled rows, so re-invoking the seed when sources are already
 * enabled is a no-op (the Room flow does NOT re-emit when the row value doesn't change).
 * Callers can fire the seed on every `LaunchedEffect` key change without worrying about
 * accidental toggle-off.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `sourcesReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster118.staleKdocSweep.cascade,
 * Task #574, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-eighth sibling of the cluster57-117 sweep — closes the
 * wave-18 `:domain/usecase/sources/` batch alongside ObserveSourcesUse-
 * Case.kt plus SetSourceEnabledUseCase.kt plus SetLanguageEnabledUseCase
 * .kt):
 *  (a) "Phase 7.x.sources.onboardingseed — encapsulates the onboarding
 *  step 3 default-seed policy: format the user's locale code as the
 *  parenthesised tag the legacy saveSources seed uses (`en` rename-to
 *  `(EN)`), pass that as the SourcesRepository.setLanguageEnabledWith-
 *  Fallback primary, and pin the fallback to `(EN)`; the repository owns
 *  the snapshot plus fan-out mechanism, this use case owns the policy
 *  (which tag, which fallback)" — LIVE-NOT-STALE. SourcesViewModel.kt
 *  L138 primary constructor binds `private val enableDefaultLanguage-
 *  Sources: EnableDefaultLanguageSourcesUseCase`; L166-168 `SourcesIntent
 *  .OnSeedDefaultLanguage rename-to viewModelScope.launch { enable-
 *  DefaultLanguageSources(intent.languageTag) }` realization confirms
 *  the fire-and-forget posture; cluster108 sibling sweep (Task #564)
 *  verified the SourcesIntent.OnSeedDefaultLanguage KDoc at Sources-
 *  Intent.kt:175 plus the §304 onboardingseed gap-lift framing.
 *  (b) "Mirrors legacy RepoSettingsViewModel.setLanguageEnabledDefault
 *  semantics verbatim — blank locale coerces to `en`; tag uppercases
 *  and is wrapped in parens; if no sources match the user's locale the
 *  fallback fires and enables every English source; the legacy version
 *  inlined this in the VM; the rework lifts it to a use case so the
 *  rework SourcesViewModel stays a thin dispatcher" — LIVE-NOT-STALE.
 *  L44-50 realization `val tag = languageTag.ifBlank { "en" }.upper-
 *  case(); repository.setLanguageEnabledWithFallback(primary = "($tag)",
 *  fallback = "(EN)", enabled = true)` matches the framing character-
 *  for-character. Historical-context portion (the legacy version inlined
 *  this in the VM) preserved as archival-but-accurate per the audit-
 *  trail-preservation convention — the rework still mirrors those
 *  semantics regardless of legacy VM retirement waves (the §304
 *  onboardingseed gap-lift task description names the legacy mirror as
 *  the architectural-symmetry target).
 *  (c) "Why a use case for a one-line pass-through — DIP; the Sources-
 *  ViewModel depends on a stable use-case interface, not on a repository
 *  method; future composition (analytics, gating on a first-onboarding
 *  pref, reconciling with a device-region fallback) lives here, not in
 *  the VM; same posture as SetLanguageEnabledUseCase / SetSourceEnabled-
 *  UseCase" — LIVE-NOT-STALE. Peer mutator-DIP rationale cross-refs all
 *  in the wave-18 cluster (SetLanguageEnabledUseCase plus SetSource-
 *  EnabledUseCase, this sweep). Future composition portion (analytics,
 *  first-onboarding pref gate, device-region fallback) — FORECAST-NOT-
 *  YET-FULFILLED. Recursive search returns zero matches for analytics
 *  emit, first-onboarding-pref gate, plus device-region-fallback
 *  reconciliation.
 *  (d) "`languageTag` shape — the raw user-selected locale code as
 *  persisted by DataStoreHelper.languageFlow (e.g. `en`, `fr`, ``);
 *  blank coerces to `en` matching the legacy userLanguageCode.ifBlank {
 *  "en" } step; case is normalized via `.uppercase()` to match the
 *  parenthesised tag convention" — LIVE-NOT-STALE. L44-46 realization
 *  matches the framing exactly: `languageTag.ifBlank { "en" }.upper-
 *  case()` rename-to wrap in `"($tag)"`. Legacy `DataStoreHelper.
 *  languageFlow` is the persistence cell behind the LanguageRepository
 *  rework delegate verified at cluster116 sibling sweep (Task #572 —
 *  ObserveSelectedLanguageUseCase) — the rework persistence path
 *  remains the same Preferences-DataStore Flow<String> cell.
 *  (e) "Idempotency — the upstream Room UPDATE sources SET isEnabled =
 *  1 WHERE name = ? is idempotent on already-enabled rows, so re-
 *  invoking the seed when sources are already enabled is a no-op (the
 *  Room flow does NOT re-emit when the row value doesn't change);
 *  callers can fire the seed on every LaunchedEffect key change without
 *  worrying about accidental toggle-off" — LIVE-NOT-STALE. Room
 *  semantics verified at cluster25 sibling sweep (Task #481) Sources-
 *  RepositoryImpl `:data` impl — the per-language fan-out forwards each
 *  row's UPDATE call, and Room's identity check on the matching row
 *  suppresses no-op flow re-emissions.
 *  (f) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  `factory` in `sourcesReworkModule`" — LIVE-NOT-STALE. SourcesRework-
 *  Module.kt L114 `factory { EnableDefaultLanguageSourcesUseCase(get())
 *  }` realization confirms factory lifecycle; the dedicated §304
 *  framing in the module-level KDoc verified at cluster14 sibling sweep
 *  (Task #470).
 *  Six classifications STAND on their own merits as a faithful Enable-
 *  DefaultLanguageSourcesUseCase manifest. Original Phase
 *  7.x.sources.onboardingseed-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
class EnableDefaultLanguageSourcesUseCase(
    private val repository: SourcesRepository,
) {
    suspend operator fun invoke(languageTag: String) {
        val tag = languageTag.ifBlank { "en" }.uppercase()
        repository.setLanguageEnabledWithFallback(
            primary = "($tag)",
            fallback = "(EN)",
            enabled = true,
        )
    }
}
