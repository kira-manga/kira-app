package me.manga.kira.presentation.theme

import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.presentation.mvi.MviState

/**
 * Theme picker MVI state.
 *
 * Phase 7.x.theme rework + Phase 7.x.theme.pureblack extension. Holds the currently-selected
 * theme tri-state, the orthogonal PureBlack/OLED variant flag, plus an [isLoading] flag
 * covering the gap between subscription and the first emissions from
 * [me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase] +
 * [me.manga.kira.domain.usecase.theme.ObservePureBlackUseCase].
 *
 * The state is **flow-driven**: the VM's `init {}` collector projects each upstream emission
 * into a fresh field value. Theme changes propagate naturally — the `SharedPreferences` writes
 * via [SetAppThemeUseCase] / [SetPureBlackUseCase] re-emit through `legacy.darkModeFlow` /
 * `legacy.followSystemFlow` / `legacy.pureBlackFlow` → the `:data` impl → here, so the screen
 * is reactive without an explicit `OnRefresh` intent.
 *
 * No `error` field — the upstream is a pure preference flow (no I/O), and the writes are sync
 * `SharedPreferences.putBoolean` calls whose runtime-failure modes are vanishingly small. Same
 * no-`error` posture as [me.manga.kira.presentation.sources.SourcesState] /
 * [me.manga.kira.presentation.updates.UpdatesState].
 *
 * **First-run defaults**:
 *  - [theme] = [AppTheme.System] — matches `SettingsRepository.followSystemFlow`'s `true`
 *    default in `:shared`.
 *  - [pureBlack] = `true` — matches `SettingsRepository.isPureBlack()` default; existing users
 *    see no behaviour change after the rework picker ships.
 *
 * If the upstream takes a tick to emit, the picker initially renders System + PureBlack-on with
 * [isLoading] still `true`; the user can't perceive the gap.
 *
 * Contract §6 SRP: one rule — "what the theme picker renders right now". No business logic, no
 * derivation beyond what the picker reads.
 *
 * Contract §17: no `Any`, no `!!`, no `lateinit`. All fields are concrete value types with
 * sensible defaults.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster105.staleKdocSweep.cascade,
 * Task #561, 2026-05-28): the file-scope state-shape manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-fifth sibling of the cluster57-104 sweep — sibling
 * of cluster105 ThemeEffect.kt plus ThemeViewModel.kt):
 *  (a) "Holds the currently-selected theme tri-state, the orthogonal
 *  PureBlack/OLED variant flag, plus an `isLoading` flag covering the
 *  gap between subscription and the first emissions from
 *  [ObserveAppThemeUseCase] plus [ObservePureBlackUseCase]" — LIVE-NOT-
 *  STALE. L41-45 data-class shape verbatim — `isLoading: Boolean = true`
 *  plus `theme: AppTheme = AppTheme.System` plus `pureBlack: Boolean =
 *  true` (three `val`-only properties, no `var`).
 *  (b) "The state is flow-driven — the VM's `init {}` collector projects
 *  each upstream emission into a fresh field value. Theme changes
 *  propagate naturally" — LIVE-NOT-STALE. ThemeViewModel.kt L73-85 init
 *  block hosts two independent `launchIn(viewModelScope)` collectors;
 *  each `onEach { ... updateState { ... } }` projects exactly one field
 *  per emission. No explicit `OnRefresh` intent appears on ThemeIntent.
 *  (c) "No `error` field — the upstream is a pure preference flow (no
 *  I/O), and the writes are sync `SharedPreferences.putBoolean` calls.
 *  Same no-`error` posture as [SourcesState] / [UpdatesState]" — LIVE-
 *  NOT-STALE. The data-class has no `error` field; ThemeViewModel.kt
 *  upstream collectors LACK `.catch {}` operators (per ThemeViewModel
 *  KDoc clause "Why no `catch {}` on the upstreams"). SourcesState plus
 *  UpdatesState peer cross-refs verified at cluster31 `:presentation
 *  State tier survey` (Task #487).
 *  (d) "First-run defaults" — LIVE-NOT-STALE. L43 `theme = AppTheme.
 *  System` matches `SettingsRepository.followSystemFlow`'s `true`
 *  default per the legacy `:shared` audit-trail; L44 `pureBlack = true`
 *  matches `SettingsRepository.isPureBlack()` default.
 *  (e) "Contract §6 SRP plus §17 (no `Any` / `!!` / `lateinit`)" — LIVE-
 *  NOT-STALE. Three `val`-only properties of concrete value types
 *  (Boolean plus AppTheme plus Boolean); no `Any`, no `!!`, no
 *  `lateinit`. SRP one-rule preserved (what the picker renders right
 *  now, no business logic).
 *  Five classifications STAND on their own merits as a faithful Theme-
 *  State manifest. Original Phase 7.x.theme-era prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
data class ThemeState(
    val isLoading: Boolean = true,
    val theme: AppTheme = AppTheme.System,
    val pureBlack: Boolean = true,
) : MviState
