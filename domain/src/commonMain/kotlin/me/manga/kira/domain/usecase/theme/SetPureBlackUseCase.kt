package me.manga.kira.domain.usecase.theme

import me.manga.kira.domain.repository.ThemeRepository

/**
 * Set the PureBlack/OLED variant flag.
 *
 * Phase 7.x.theme.pureblack. The rework `ThemeViewModel` invokes this use case from its
 * `ThemeIntent.OnTogglePureBlack` reducer branch (fire-and-forget in `viewModelScope`); the
 * upstream `observePureBlack()` flow re-emits the new value once the legacy
 * `SharedPreferences` write commits, and the screen state updates reactively.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ThemeRepository.setPureBlack]". Co-located
 * with [SetAppThemeUseCase] in the `theme` use case package because PureBlack is a sub-aspect
 * of theming.
 *
 * `suspend operator fun invoke` mirrors [SetAppThemeUseCase] — same signature pattern across
 * the rework theme use case surface. The legacy underlying write is sync
 * `SharedPreferences.putBoolean`; `suspend` is forward-compatibility room for a future
 * DataStore migration.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `themeReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster115.staleKdocSweep.cascade,
 * Task #571, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-fifth sibling of the cluster57-114 sweep — closes the
 * wave-15 `:domain/usecase/theme/` batch alongside ObserveAppThemeUseCase.
 * kt plus SetAppThemeUseCase.kt plus ObservePureBlackUseCase.kt):
 *  (a) "Phase 7.x.theme.pureblack — rework ThemeViewModel invokes this
 *  use case from its ThemeIntent.OnTogglePureBlack reducer branch (fire-
 *  and-forget in viewModelScope)" — LIVE-NOT-STALE. ThemeViewModel.kt
 *  L118 primary constructor binds `private val setPureBlack: SetPure-
 *  BlackUseCase`; L142-144 `ThemeIntent.OnTogglePureBlack rename-to
 *  viewModelScope.launch { setPureBlack(intent.enabled) }` realization
 *  confirms the fire-and-forget posture; cluster105 sibling sweep (Task
 *  #561) verified the intent-launch posture at classification (f).
 *  (b) "Upstream observePureBlack() flow re-emits the new value once the
 *  legacy SharedPreferences write commits, plus the screen state updates
 *  reactively" — LIVE-NOT-STALE. ThemeRepositoryImpl `:data` impl
 *  `SharedPreferences.putBoolean` write verified at cluster11 sibling
 *  sweep (Task #467); legacy `SharedPreferences.booleanPrefFlow` re-emit
 *  posture preserved.
 *  (c) "Contract §6 SRP owns ONE rule — delegate to ThemeRepository.
 *  setPureBlack; co-located with SetAppThemeUseCase in the `theme` use
 *  case package because PureBlack is a sub-aspect of theming" — LIVE-NOT-
 *  STALE. L28 realization `repository.setPureBlack(enabled)` single-line
 *  pass-through.
 *  (d) "`suspend operator fun invoke` mirrors SetAppThemeUseCase — same
 *  signature pattern across the rework theme use case surface; the
 *  legacy underlying write is sync SharedPreferences.putBoolean; suspend
 *  is forward-compatibility room for a future DataStore migration" —
 *  LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED. L28 `suspend operator
 *  fun invoke(enabled: Boolean) = repository.setPureBlack(enabled)`
 *  realization confirms the suspend signature symmetry with SetApp-
 *  ThemeUseCase L32; recursive search for DataStore migration on the
 *  Theme settings cell returns zero matches — the predicted DataStore
 *  port does NOT yet exist; the `:data` impl still backs onto legacy
 *  `SharedPreferences` per cluster11 sibling sweep. Forecast posture
 *  preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `themeReworkModule`" — LIVE-NOT-STALE. ThemeReworkModule.
 *  kt L110 `factory { SetPureBlackUseCase(get()) }` realization confirms
 *  factory lifecycle (stateless, cheap to construct, never shared).
 *  Five classifications STAND on their own merits as a faithful SetPure-
 *  BlackUseCase manifest. Original Phase 7.x.theme.pureblack-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SetPureBlackUseCase(
    private val repository: ThemeRepository,
) {
    suspend operator fun invoke(enabled: Boolean) = repository.setPureBlack(enabled)
}
