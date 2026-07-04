package me.manga.kira.domain.usecase.language

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.LanguageRepository

/**
 * Observe the user's currently-selected language code.
 *
 * Phase 7.x.language rework. The rework `LanguageViewModel` injects this use case and subscribes
 * in `init {}` to project each emission into [me.manga.kira.presentation.language.LanguageState.selectedCode].
 * The `:ui` composable renders the trailing Done-icon for the row whose `code` matches.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LanguageRepository.observeSelectedLanguageCode]".
 * The DataStore plumbing lives in the legacy `:shared` facade; this use case is a stable
 * presentation-layer dependency and a test seam.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase] /
 * [me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase] — presentation depends on use
 * cases (DIP), not on repositories directly; future composition (e.g., compose with a system-
 * locale fallback to compute an effective language tag for first-run) lives in the use case, not
 * in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `languageReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster116.staleKdocSweep.cascade,
 * Task #572, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-sixth sibling of the cluster57-115 sweep — opens the
 * wave-16 `:domain/usecase/language/` batch alongside GetSupportedLanguages-
 * UseCase.kt plus SetLanguageUseCase.kt, partnering the cluster106 sibling
 * sweep of `:presentation/language/`):
 *  (a) "Phase 7.x.language rework — rework LanguageViewModel injects this
 *  use case plus subscribes in init {} to project each emission into
 *  LanguageState.selectedCode; the `:ui` composable renders the trailing
 *  Done-icon for the row whose `code` matches" — LIVE-NOT-STALE. Language-
 *  ViewModel.kt L153 primary constructor binds `observeSelectedLanguage:
 *  ObserveSelectedLanguageUseCase`; L160-166 init block hosts `observe-
 *  SelectedLanguage().onEach { code -> updateState { it.copy(isLoading =
 *  false, selectedCode = code) } }.launchIn(viewModelScope)` collector;
 *  cluster106 sibling sweep (Task #562) verified the init-collector
 *  posture.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to LanguageRepository.
 *  observeSelectedLanguageCode; the DataStore plumbing lives in the
 *  legacy `:shared` facade; this use case is a stable presentation-layer
 *  dependency plus a test seam" — LIVE-NOT-STALE. L30 realization
 *  `repository.observeSelectedLanguageCode()` single-line pass-through;
 *  LanguageRepositoryImpl `:data` impl verified at cluster25 sibling
 *  sweep (Task #481) — delegates to legacy `SettingsRepository.language-
 *  Flow` (Preferences-DataStore-backed Flow<String> with non-nullable
 *  default).
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  peer cross-ref to ObserveAppThemeUseCase plus ObserveSourcesUseCase;
 *  presentation depends on use cases (DIP), not on repositories directly"
 *  — LIVE-NOT-STALE. Peer use cases verified at cluster115 sibling sweep
 *  (Task #571 — ObserveAppThemeUseCase) plus cluster26 sibling sweep
 *  (Task #482).
 *  (d) "Future composition — compose with a system-locale fallback to
 *  compute an effective language tag for first-run" — FORECAST-NOT-YET-
 *  FULFILLED. Recursive search for system-locale-fallback orchestration
 *  on this use case returns zero matches; the use case remains a single-
 *  line pass-through. Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `languageReworkModule`" — LIVE-NOT-STALE. LanguageRework-
 *  Module.kt L126 `factory { ObserveSelectedLanguageUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to construct,
 *  never shared).
 *  Five classifications STAND on their own merits as a faithful Observe-
 *  SelectedLanguageUseCase manifest. Original Phase 7.x.language-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveSelectedLanguageUseCase(
    private val repository: LanguageRepository,
) {
    operator fun invoke(): Flow<String> = repository.observeSelectedLanguageCode()
}
