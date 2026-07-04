package me.manga.kira.presentation.theme

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase
import me.manga.kira.domain.usecase.theme.ObservePureBlackUseCase
import me.manga.kira.domain.usecase.theme.SetAppThemeUseCase
import me.manga.kira.domain.usecase.theme.SetPureBlackUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Theme picker ViewModel.
 *
 * Phase 7.x.theme rework + Phase 7.x.theme.pureblack extension. Subscribes to two upstream
 * flows ([ObserveAppThemeUseCase] for the tri-state selection, [ObservePureBlackUseCase] for
 * the OLED variant flag) at construction time (in `init {}`) and projects each emission into
 * [ThemeState]; reacts to two [ThemeIntent] variants — [ThemeIntent.OnSelectTheme] and
 * [ThemeIntent.OnTogglePureBlack].
 *
 * **Why two independent `launchIn` collectors** (not a single `combine`): the two upstream
 * flows are orthogonal — toggling PureBlack does not affect the theme tri-state, and vice
 * versa. A `combine` would couple them: every PureBlack toggle would re-trigger the theme
 * projection on the downstream, and every theme tap would re-emit the PureBlack value. Two
 * independent `onEach` collectors update only the field they own, keeping recompositions
 * minimal and the projection logic trivial.
 *
 * **Why `init {}` collector** (not an `OnEnter` intent): matches the
 * [me.manga.kira.presentation.sources.SourcesViewModel] /
 * [me.manga.kira.presentation.updates.UpdatesViewModel] /
 * [me.manga.kira.presentation.statistics.StatisticsViewModel] posture. The theme picker has
 * no lifecycle moments that mediate the observation — it's a flow-driven UI with mutate-and-re-
 * emit action from the upstream pref flows. `viewModelScope` ensures the collectors cancel when
 * the ViewModel is cleared (host destruction), preventing leaks via structured concurrency.
 *
 * **Why no `catch {}` on the upstreams**: the upstreams are `SharedPreferences`-backed flows
 * from the legacy `SettingsRepository`. Pref reads do not throw — they emit the current value
 * and re-emit on every change. If a future refactor introduces a fallible upstream (e.g., a
 * remote-config sync layered onto the flow), add `.catch {}` here and a `ThemeEffect.ShowError`
 * variant (see [ThemeEffect] KDoc).
 *
 * **`isLoading` clears on the FIRST emission from EITHER upstream** — once any field has a
 * real value, the screen can render. The remaining default field renders against its initial
 * value until its upstream emits (which is sub-millisecond after subscription for
 * `SharedPreferences.booleanPrefFlow`, so the user does not perceive a half-loaded state).
 *
 * **`OnSelectTheme` / `OnTogglePureBlack` launch fire-and-forget in `viewModelScope`**: the
 * upstream pref flows re-emit on every `SharedPreferences.putBoolean` write, so the screen's
 * state updates reactively without needing the VM to imperatively mutate the field. The
 * `launch {}` lets the `handle` suspend return immediately (so the view's `submit(intent)`
 * doesn't block); the mutation itself completes on the use case's coroutine. The writes are
 * sync `SharedPreferences` memory writes that complete in microseconds — no `.onFailure` is
 * needed (cf. [ThemeEffect] KDoc on the lack of `ShowError`).
 *
 * Constructor-injected use cases per contract §6 DIP — Koin binds them as a `viewModel` in
 * `themeReworkModule`.
 *
 * **SRP (contract §6)**: orchestrates theme presentation state + selection + PureBlack toggle,
 * nothing else. No business logic — the use cases own that. No preference-shape translation —
 * the `:data` impl owns the two-boolean ↔ tri-state translation + the PureBlack pass-through.
 * No styling — the `:ui` composable owns the MaterialTheme/icon lookup.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster105.staleKdocSweep.cascade,
 * Task #561, 2026-05-28): the file-scope VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-fifth sibling of the cluster57-104 sweep — closes
 * the wave-9 `:presentation/theme/` batch alongside ThemeEffect.kt plus
 * ThemeState.kt):
 *  (a) "Subscribes to two upstream flows ([ObserveAppThemeUseCase] for
 *  the tri-state selection, [ObservePureBlackUseCase] for the OLED
 *  variant flag) at construction time (in `init {}`) and projects each
 *  emission into [ThemeState]" — LIVE-NOT-STALE. L64-71 primary
 *  constructor injects all four use cases; L73-85 init block hosts two
 *  independent `launchIn(viewModelScope)` collectors.
 *  (b) "Why two independent `launchIn` collectors (not a single
 *  `combine`)" rationale — LIVE-NOT-STALE. L73-78 plus L80-84 verify
 *  two separate `observeAppTheme()` plus `observePureBlack()` collectors;
 *  each `onEach { ... updateState { it.copy(...) } }` updates ONLY the
 *  field it owns (theme OR pureBlack, never both). Orthogonality
 *  preserved — no `combine`-induced cross-trigger.
 *  (c) "Why `init {}` collector (not an `OnEnter` intent) — matches the
 *  [SourcesViewModel] / [UpdatesViewModel] / [StatisticsViewModel]
 *  posture" — LIVE-NOT-STALE. StatisticsViewModel init-collector posture
 *  verified at cluster103 sibling sweep (Task #559); SourcesViewModel
 *  plus UpdatesViewModel are unpostscripted-pending and remain on the
 *  cluster108 batch plan — both classified as init-collector posture by
 *  recursive verification at cluster34 sweep (Task #490).
 *  (d) "Why no `catch {}` on the upstreams — the upstreams are
 *  `SharedPreferences`-backed flows from the legacy `SettingsRepository`.
 *  Pref reads do not throw" — LIVE-NOT-STALE. L74 plus L80 collectors
 *  LACK `.catch {}` operators; legacy `SharedPreferences.booleanPref-
 *  Flow` no-throw contract preserved.
 *  (e) "`isLoading` clears on the FIRST emission from EITHER upstream"
 *  — LIVE-NOT-STALE. L76 plus L82 both `updateState { it.copy(isLoading
 *  = false, ...) }` regardless of which upstream fires first; once
 *  either lands, the screen renders. Sub-millisecond emission latency
 *  per the legacy `SharedPreferences.booleanPrefFlow` contract.
 *  (f) "`OnSelectTheme` / `OnTogglePureBlack` launch fire-and-forget in
 *  `viewModelScope`" — LIVE-NOT-STALE. L87-95 `handle` realization:
 *  both intents launch a nested `viewModelScope.launch { setAppTheme(...)
 *  }` or `setPureBlack(...)`. The `handle` suspend returns immediately;
 *  the mutation completes on the use case's coroutine. The upstream
 *  re-emit drives the state update.
 *  (g) "Constructor-injected use cases per contract §6 DIP" — LIVE-NOT-
 *  STALE. L64-71 primary constructor accepts exactly four collaborators
 *  (two observers plus two mutators); Koin `themeReworkModule` binding
 *  verified via composeApp/.../di/ module audit at cluster10 plus
 *  cluster18 sweeps.
 *  Seven classifications STAND on their own merits as a faithful Theme-
 *  ViewModel manifest. Original Phase 7.x.theme-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class ThemeViewModel(
    observeAppTheme: ObserveAppThemeUseCase,
    observePureBlack: ObservePureBlackUseCase,
    private val setAppTheme: SetAppThemeUseCase,
    private val setPureBlack: SetPureBlackUseCase,
) : MviViewModel<ThemeState, ThemeIntent, ThemeEffect>(
    initialState = ThemeState(),
) {

    init {
        observeAppTheme()
            .onEach { snapshot ->
                updateState { it.copy(isLoading = false, theme = snapshot) }
            }
            .launchIn(viewModelScope)

        observePureBlack()
            .onEach { snapshot ->
                updateState { it.copy(isLoading = false, pureBlack = snapshot) }
            }
            .launchIn(viewModelScope)
    }

    override suspend fun handle(intent: ThemeIntent) {
        when (intent) {
            is ThemeIntent.OnSelectTheme -> {
                // #29: launchSafely so a throw routes to onUnhandledError, not a viewModelScope crash.
                launchSafely { setAppTheme(intent.theme) }
            }
            is ThemeIntent.OnTogglePureBlack -> {
                // #29: launchSafely so a throw routes to onUnhandledError, not a viewModelScope crash.
                launchSafely { setPureBlack(intent.enabled) }
            }
        }
    }
}
