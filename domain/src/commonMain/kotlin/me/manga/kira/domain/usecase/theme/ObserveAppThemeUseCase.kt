package me.manga.kira.domain.usecase.theme

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.domain.repository.ThemeRepository

/**
 * Observe the user's current application theme.
 *
 * Phase 7.x.theme rework. The rework `ThemeViewModel` injects this use case and subscribes in
 * `init {}` to project each [AppTheme] emission into its MVI state. The `:ui` composable renders
 * the picker's selected-tab based on the projected state value.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ThemeRepository.observeAppTheme]". The flow
 * source (legacy two-boolean translation) lives in the `:data` impl; the use case is a stable
 * presentation-layer dependency and a test seam.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase] /
 * [me.manga.kira.domain.usecase.history.ObserveHistoryUseCase] — presentation depends on use
 * cases (DIP), not on repositories directly; future composition (e.g., combine with PureBlack to
 * compute an effective theme tuple, or apply a system-uiMode fallback for first-run) lives in
 * the use case, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `themeReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster115.staleKdocSweep.cascade,
 * Task #571, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-fifth sibling of the cluster57-114 sweep — opens the
 * wave-15 `:domain/usecase/theme/` batch alongside SetAppThemeUseCase.kt
 * plus ObservePureBlackUseCase.kt plus SetPureBlackUseCase.kt, partnering
 * the cluster105 sibling sweep of `:presentation/theme/`):
 *  (a) "Phase 7.x.theme rework — rework ThemeViewModel injects this use
 *  case plus subscribes in init {} to project each AppTheme emission into
 *  its MVI state; the `:ui` composable renders the picker's selected-tab
 *  based on the projected state value" — LIVE-NOT-STALE. ThemeViewModel.
 *  kt L115 primary constructor binds `observeAppTheme: ObserveAppTheme-
 *  UseCase`; L123-128 init block hosts `observeAppTheme().onEach {
 *  snapshot -> updateState { it.copy(isLoading = false, theme = snapshot)
 *  } }.launchIn(viewModelScope)` collector — independent of the Observe-
 *  PureBlackUseCase collector at L130-134 (orthogonality preserved per
 *  cluster105 sibling sweep classification (b)).
 *  (b) "Contract §6 SRP owns ONE rule — delegate to ThemeRepository.
 *  observeAppTheme; the flow source (legacy two-boolean translation)
 *  lives in the `:data` impl; the use case is a stable presentation-
 *  layer dependency plus a test seam" — LIVE-NOT-STALE. L31 realization
 *  `repository.observeAppTheme()` single-line pass-through; ThemeReposit-
 *  oryImpl `:data` impl verified at cluster11 sibling sweep (Task #467)
 *  plus cluster23 sibling sweep (Task #479) — wraps the legacy Settings-
 *  Repository two-boolean (`darkMode` plus `followSystem`) flow shape.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  presentation depends on use cases (DIP), not on repositories
 *  directly; peer cross-ref to ObserveSourcesUseCase plus ObserveHistory-
 *  UseCase" — LIVE-NOT-STALE. Peer pure-delegate posture verified at
 *  cluster26 sibling sweep (Task #482) plus cluster112 sibling sweep
 *  (Task #568 — ObserveHistoryUseCase).
 *  (d) "Future composition — combine with PureBlack to compute an
 *  effective theme tuple, or apply a system-uiMode fallback for first-
 *  run" — FORECAST-NOT-YET-FULFILLED. Per cluster105 ThemeViewModel
 *  classification (b), the VM uses TWO INDEPENDENT `launchIn` collectors
 *  for orthogonality — the predicted ObserveEffectiveThemeUseCase
 *  combining theme + pureblack tuple does NOT yet exist (recursive search
 *  returns zero matches). Forecast posture preserved verbatim; peer
 *  cross-ref to ObservePureBlackUseCase classification (b).
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `themeReworkModule`" — LIVE-NOT-STALE. ThemeReworkModule.
 *  kt L107 `factory { ObserveAppThemeUseCase(get()) }` realization
 *  confirms factory lifecycle (stateless, cheap to construct, never
 *  shared).
 *  Five classifications STAND on their own merits as a faithful
 *  ObserveAppThemeUseCase manifest. Original Phase 7.x.theme-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveAppThemeUseCase(
    private val repository: ThemeRepository,
) {
    operator fun invoke(): Flow<AppTheme> = repository.observeAppTheme()
}
