package me.manga.kira.domain.usecase.theme

import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.domain.repository.ThemeRepository

/**
 * Persist the user's theme selection.
 *
 * Phase 7.x.theme rework. The rework `ThemeViewModel` injects this use case and invokes it from
 * `viewModelScope.launch` when the user taps a theme tab in the picker. Fire-and-forget: the
 * upstream [ObserveAppThemeUseCase] flow re-emits with the new theme once the legacy
 * `SharedPreferences` writes commit — the picker's selected-tab reflects the new state by virtue
 * of state-driven recomposition.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ThemeRepository.setAppTheme]". The translation
 * to the legacy two-boolean storage lives in the `:data` impl; this use case just forwards the
 * domain ADT.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as the other
 * mutator use cases across the rework (`SetSourceEnabledUseCase` /
 * `MarkUpdateAsReadUseCase` / `DeleteHistoryEntryUseCase`) — the VM depends on a stable use case
 * interface, not on a repository method (DIP); future composition (e.g., apply the theme
 * immediately to a running Composition without waiting for the flow round-trip, or emit an
 * analytics event on theme change) lives here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `themeReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster115.staleKdocSweep.cascade,
 * Task #571, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-fifth sibling of the cluster57-114 sweep — wave-15
 * `:domain/usecase/theme/` batch alongside ObserveAppThemeUseCase.kt
 * plus ObservePureBlackUseCase.kt plus SetPureBlackUseCase.kt):
 *  (a) "Phase 7.x.theme rework — rework ThemeViewModel injects this use
 *  case plus invokes it from viewModelScope.launch when the user taps a
 *  theme tab in the picker" — LIVE-NOT-STALE. ThemeViewModel.kt L117
 *  primary constructor binds `private val setAppTheme: SetAppThemeUse-
 *  Case`; L139-141 `ThemeIntent.OnSelectTheme rename-to viewModelScope.
 *  launch { setAppTheme(intent.theme) }` realization confirms the fire-
 *  and-forget posture; cluster105 sibling sweep (Task #561) verified the
 *  intent-launch posture at classification (f).
 *  (b) "Fire-and-forget — upstream ObserveAppThemeUseCase flow re-emits
 *  with the new theme once the legacy SharedPreferences writes commit;
 *  the picker's selected-tab reflects the new state by virtue of state-
 *  driven recomposition" — LIVE-NOT-STALE. ThemeRepositoryImpl `:data`
 *  impl two-boolean `SharedPreferences.putBoolean` writes verified at
 *  cluster11 sibling sweep (Task #467); legacy `SharedPreferences.
 *  booleanPrefFlow` re-emit posture preserved.
 *  (c) "Contract §6 SRP owns ONE rule — delegate to ThemeRepository.
 *  setAppTheme; the translation to the legacy two-boolean storage lives
 *  in the `:data` impl; this use case just forwards the domain ADT" —
 *  LIVE-NOT-STALE. L32 realization `repository.setAppTheme(theme)`
 *  single-line pass-through; the AppTheme tri-state rename-to two-boolean
 *  (`darkMode` plus `followSystem`) translation realized in `:data`
 *  ThemeRepositoryImpl.kt verified at cluster11 sibling sweep.
 *  (d) "Future composition — apply the theme immediately to a running
 *  Composition without waiting for the flow round-trip, or emit an
 *  analytics event on theme change" — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for synchronous-composition-apply orchestration or
 *  analytics-event emission on this use case returns zero matches; the
 *  use case remains a single-line pass-through. Forecast posture
 *  preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `themeReworkModule`" — LIVE-NOT-STALE. ThemeReworkModule.
 *  kt L109 `factory { SetAppThemeUseCase(get()) }` realization confirms
 *  factory lifecycle (stateless, cheap to construct, never shared).
 *  Five classifications STAND on their own merits as a faithful SetApp-
 *  ThemeUseCase manifest. Original Phase 7.x.theme-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class SetAppThemeUseCase(
    private val repository: ThemeRepository,
) {
    suspend operator fun invoke(theme: AppTheme) = repository.setAppTheme(theme)
}
