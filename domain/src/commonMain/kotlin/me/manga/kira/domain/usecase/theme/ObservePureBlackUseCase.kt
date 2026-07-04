package me.manga.kira.domain.usecase.theme

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.ThemeRepository

/**
 * Observe the PureBlack/OLED variant flag.
 *
 * Phase 7.x.theme.pureblack. The rework `ThemeViewModel` injects this use case alongside
 * [ObserveAppThemeUseCase] and combines both flows in its `init {}` collector to project the
 * full theming state ([me.manga.kira.presentation.theme.ThemeState]). The `:ui` composable
 * renders a Material 3 `Switch` row whose checked-state reflects each emission.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ThemeRepository.observePureBlack]". Co-located
 * with [ObserveAppThemeUseCase] in the `theme` use case package because PureBlack is a sub-aspect
 * of theming, not a separate concern. A future read-side composition (e.g., combining theme +
 * pureblack into an "effective dark scheme" tuple at the domain layer) would live in a new
 * `ObserveEffectiveThemeUseCase` here — the existing two `Observe*` cases stay independent
 * pass-throughs.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `themeReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster115.staleKdocSweep.cascade,
 * Task #571, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-fifth sibling of the cluster57-114 sweep — wave-15
 * `:domain/usecase/theme/` batch alongside ObserveAppThemeUseCase.kt
 * plus SetAppThemeUseCase.kt plus SetPureBlackUseCase.kt):
 *  (a) "Phase 7.x.theme.pureblack — rework ThemeViewModel injects this
 *  use case alongside ObserveAppThemeUseCase plus combines both flows in
 *  its init {} collector to project the full theming state (ThemeState);
 *  the `:ui` composable renders a Material 3 Switch row whose checked-
 *  state reflects each emission" — MIXED LIVE-PLUS-STALE-WORDING. The
 *  injection-alongside-ObserveAppThemeUseCase plus init-block-projection
 *  plus M3-Switch-row-rendering framing remains LIVE: ThemeViewModel.kt
 *  L116 primary constructor binds `observePureBlack: ObservePureBlack-
 *  UseCase`; L130-134 init block hosts `observePureBlack().onEach {
 *  snapshot -> updateState { it.copy(isLoading = false, pureBlack =
 *  snapshot) } }.launchIn(viewModelScope)` collector. HOWEVER, the
 *  "combines both flows in its init {} collector" wording is STALE —
 *  per cluster105 ThemeViewModel classification (b), the VM uses TWO
 *  INDEPENDENT `launchIn` collectors deliberately to preserve
 *  orthogonality (toggling PureBlack does NOT re-trigger the theme
 *  projection downstream, plus vice versa). The use case itself is
 *  not combined at the domain layer — the VM hosts two parallel
 *  collectors, each updating only its own ThemeState field. Wording-
 *  drift mirror of cluster111 MarkWhatsNewSeenUseCase classification
 *  (a) — immaterial to function but inconsistent with realization.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to ThemeRepository.
 *  observePureBlack; co-located with ObserveAppThemeUseCase because
 *  PureBlack is a sub-aspect of theming; future ObserveEffectiveTheme-
 *  UseCase combining theme + pureblack tuple would live here" — LIVE-
 *  NOT-STALE plus FORECAST-NOT-YET-FULFILLED. L27 realization
 *  `repository.observePureBlack()` single-line pass-through; Theme-
 *  RepositoryImpl `:data` impl verified at cluster11 sibling sweep
 *  (Task #467). Recursive search for ObserveEffectiveThemeUseCase
 *  symbol returns zero matches — the predicted composition use case
 *  does NOT yet exist; the existing two `Observe*` cases remain
 *  independent pass-throughs as forecast. Peer cross-ref to Observe-
 *  AppThemeUseCase classification (d) — same forecast posture.
 *  (c) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `themeReworkModule`" — LIVE-NOT-STALE. ThemeReworkModule.
 *  kt L108 `factory { ObservePureBlackUseCase(get()) }` realization
 *  confirms factory lifecycle (stateless, cheap to construct, never
 *  shared).
 *  Three classifications STAND on their own merits as a faithful
 *  ObservePureBlackUseCase manifest. The cluster105-discovered two-
 *  independent-collectors realization at the VM layer is captured under
 *  classification (a) as a MIXED LIVE-PLUS-STALE-WORDING drift —
 *  immaterial given the use case itself is single-line pass-through.
 *  Original Phase 7.x.theme.pureblack-era prose preserved verbatim per
 *  the audit-trail-preservation convention.
 */
class ObservePureBlackUseCase(
    private val repository: ThemeRepository,
) {
    operator fun invoke(): Flow<Boolean> = repository.observePureBlack()
}
