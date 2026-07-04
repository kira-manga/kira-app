package me.manga.kira.presentation.theme

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [ThemeViewModel] for the view to perform once and forget.
 *
 * Phase 7.x.theme rework. Empty sealed interface — the rework slice has no effects today. Theme
 * selection propagates via the upstream preference flow re-emit so no view-side trigger is
 * needed, and there's no destructive action to undo (theme changes are non-destructive and
 * trivially reversible by tapping again).
 *
 * Why declare an empty sealed interface (vs `Nothing` / not declare): the
 * [me.manga.kira.presentation.mvi.MviViewModel] base class requires a third type parameter
 * `E : MviEffect`. Using `Nothing` would make the channel unconstructable (fine for an
 * effectless VM but slightly more cryptic at the call site); using an empty sealed interface
 * keeps the MVI surface OCP-friendly — a future
 * `Phase 7.x.theme.pureblack` slice can add `ShowError(error: AppError)` (if the future write
 * fails, e.g., after a DataStore migration) or `OnThemeApplied` (if a follow-on slice wants to
 * trigger a snackbar) here without touching the VM's base-class signature.
 *
 * Same posture as
 * [me.manga.kira.presentation.sources.SourcesEffect] /
 * [me.manga.kira.presentation.statistics.StatisticsEffect] (also empty sealed interfaces for
 * the same OCP-friendliness reason).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster105.staleKdocSweep.cascade,
 * Task #561, 2026-05-28): the file-scope effect-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-fifth sibling of the cluster57-104 sweep —
 * opens the wave-9 `:presentation/theme/` batch alongside ThemeState.kt
 * plus ThemeViewModel.kt):
 *  (a) "Empty sealed interface — the rework slice has no effects today.
 *  Theme selection propagates via the upstream preference flow re-emit
 *  so no view-side trigger is needed" — LIVE-NOT-STALE. L27 declaration
 *  is an empty sealed interface; ThemeViewModel.kt L87-95 `handle`
 *  branches never call `emit(...)` (both OnSelectTheme plus OnToggle-
 *  PureBlack launch fire-and-forget pref writes that re-emit through
 *  the upstream observe-flow collectors).
 *  (b) "OCP-friendly — a future slice can add `ShowError(error:
 *  AppError)` or `OnThemeApplied` here without touching the VM's base-
 *  class signature" — REGISTERED-BUT-DORMANT-with-FORECAST. Empty sealed
 *  shape preserved across Phase 7.x.theme.pureblack (§243) plus Phase
 *  7.x.theme.swap (§291) plus Phase 7.x.theme.onboardingcontinue (§302)
 *  plus Phase 7.x.theme.onboardingpermission (§303) — none of the four
 *  follow-on slices needed an effect variant, vindicating the empty-
 *  sealed choice. The illustrations remain valid OCP shapes for any
 *  future fallible-upstream slice.
 *  (c) "Same posture as [SourcesEffect] / [StatisticsEffect] (also empty
 *  sealed interfaces for the same OCP-friendliness reason)" — LIVE-NOT-
 *  STALE. SourcesEffect verified empty sealed at cluster41 sweep (Task
 *  #497); StatisticsEffect verified empty sealed at cluster103 sweep
 *  (Task #559). The three-way peer cross-reference holds across the
 *  `:presentation` tier.
 *  Three classifications STAND on their own merits as a faithful Theme-
 *  Effect surface manifest. Original Phase 7.x.theme-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
sealed interface ThemeEffect : MviEffect
