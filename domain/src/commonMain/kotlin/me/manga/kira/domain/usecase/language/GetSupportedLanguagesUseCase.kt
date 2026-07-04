package me.manga.kira.domain.usecase.language

import me.manga.kira.domain.model.language.Language
import me.manga.kira.domain.repository.LanguageRepository

/**
 * Resolve the static list of supported languages.
 *
 * Phase 7.x.language rework. The rework `LanguageViewModel` injects this use case and invokes
 * it synchronously in `init {}` to seed [me.manga.kira.presentation.language.LanguageState.languages]
 * from frame 1 (no `Flow` round-trip needed because the list is a compile-time constant in the
 * `:data` impl).
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LanguageRepository.getSupportedLanguages]". The
 * 11-entry native-endonym table lives in the `:data` impl; this use case is a stable
 * presentation-layer dependency and a test seam.
 *
 * Why a use case at all when this is a single-line pass-through: same DIP rationale as the
 * other "single-line pass-through" use cases across the rework
 * ([me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase] /
 * [me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase] / etc.) — the VM depends on a
 * stable use-case interface, not on a repository method. A future composition (e.g., filtering
 * the supported list by a feature-flag, or sorting by user-preferred locale family) lives here,
 * not in the VM.
 *
 * Sync (not `suspend`) because the list is a compile-time constant. If a future slice loads the
 * list from settings / remote-config, the signature can become `suspend` or `Flow<List<Language>>`
 * — but that change propagates to the VM's `init {}` collector, not callers of this method.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `languageReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster116.staleKdocSweep.cascade,
 * Task #572, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-sixth sibling of the cluster57-115 sweep — wave-16
 * `:domain/usecase/language/` batch alongside ObserveSelectedLanguageUse-
 * Case.kt plus SetLanguageUseCase.kt, partnering the cluster106 sibling
 * sweep of `:presentation/language/`):
 *  (a) "Phase 7.x.language rework — rework LanguageViewModel injects this
 *  use case plus invokes it synchronously in init {} to seed LanguageState.
 *  languages from frame 1 (no Flow round-trip needed because the list is
 *  a compile-time constant in the `:data` impl)" — LIVE-NOT-STALE.
 *  LanguageViewModel.kt L154 primary constructor binds `getSupported-
 *  Languages: GetSupportedLanguagesUseCase`; L157 `LanguageState(languages
 *  = getSupportedLanguages())` sync-read realization confirms the frame-1
 *  seed posture; cluster106 sibling sweep (Task #562) verified the sync-
 *  seed posture.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to LanguageRepository.
 *  getSupportedLanguages; the 11-entry native-endonym table lives in the
 *  `:data` impl; this use case is a stable presentation-layer dependency
 *  plus a test seam" — LIVE-NOT-STALE. L36 realization `repository.get-
 *  SupportedLanguages()` single-line pass-through; LanguageRepositoryImpl
 *  `:data` impl verified at cluster23 sibling sweep (Task #479) — wraps
 *  the compile-time-constant 11-entry native-endonym table (no I/O, no
 *  Flow, no caching).
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  peer cross-ref to ObserveAppThemeUseCase plus ObserveSourcesUseCase;
 *  presentation depends on use cases (DIP), not on repositories directly"
 *  — LIVE-NOT-STALE. Peer use cases verified at cluster115 sibling sweep
 *  (Task #571 — ObserveAppThemeUseCase) plus cluster26 sibling sweep
 *  (Task #482 — ObserveSourcesUseCase).
 *  (d) "Future composition — filter the supported list by a feature-flag,
 *  or sort by user-preferred locale family; plus the sync-not-suspend
 *  signature is forward-compatibility room — if a future slice loads the
 *  list from settings / remote-config, the signature can become suspend
 *  or Flow<List<Language>>, but that change propagates to the VM's init {}
 *  collector, not callers of this method" — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for feature-flag-filter or remote-config-load
 *  orchestration on this use case returns zero matches; the use case
 *  remains a sync single-line pass-through plus the VM consumes the list
 *  via a sync init-seed (not a Flow collector). Forecast posture
 *  preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `languageReworkModule`" — LIVE-NOT-STALE. LanguageRework-
 *  Module.kt L125 `factory { GetSupportedLanguagesUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to construct,
 *  never shared).
 *  Five classifications STAND on their own merits as a faithful Get-
 *  SupportedLanguagesUseCase manifest. Original Phase 7.x.language-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
class GetSupportedLanguagesUseCase(
    private val repository: LanguageRepository,
) {
    operator fun invoke(): List<Language> = repository.getSupportedLanguages()
}
