package me.manga.kira.presentation.theme

import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the theme picker.
 *
 * Phase 7.x.theme rework + Phase 7.x.theme.pureblack extension. Sealed so the
 * [ThemeViewModel.handle] `when` is exhaustive; adding a new action requires adding a new
 * subclass (OCP — compile-time enforcement that the reducer handles every case).
 *
 * Two variants — the theme tri-state selector + the PureBlack/OLED toggle. No navigation
 * intents (the rework theme route is terminal; the legacy onboarding chain's `onContinue`
 * lives on the legacy route adapter). No `OnEnter` — the VM's `init {}` collector handles
 * initial subscription (same posture as the other rework slices).
 *
 * Contract §6 OCP: adding e.g. `OnResetToDefaults` or `OnPreviewTheme(theme)` is an append
 * here; the VM's exhaustive `when` flags the missing branch at compile time. The
 * [OnTogglePureBlack] variant added in Phase 7.x.theme.pureblack is the canonical example —
 * it slotted in without touching the VM's base class or the upstream use-case signatures.
 *
 * Contract §6 ISP: each variant carries only the minimal payload it needs. Matches the
 * per-action-payload posture of
 * [me.manga.kira.presentation.sources.SourcesIntent.OnToggleSource].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster29.staleKdocSweep.cascade,
 * Task #485, 2026-05-28): one fulfilled-forecast / stale citation
 * appears above:
 *  - Lines 14-15 ("No navigation intents (the rework theme route is
 *    terminal; the legacy onboarding chain's `onContinue` lives on the
 *    legacy route adapter)"). PARTIALLY-FULFILLED-INVERSION combined
 *    with STALE-SYMBOL-REFERENCE — Phase 7.x.theme.onboardingcontinue
 *    (§302) PORTED the legacy onboarding `onContinue` callback + the
 *    Continue button to the rework ThemeScreen itself (the rework now
 *    HOSTS the onboarding-continue affordance natively, no longer
 *    delegating to a legacy adapter). Phase 7.x.theme.swap (§291) then
 *    re-pointed `Screen.Theme`'s rendering adapter to the rework `:ui`
 *    ThemeScreen backed by the rework `ThemeViewModel`. Phase 9.x.
 *    onboarding.legacy_retire (§307) DELETED the legacy onboarding
 *    chain (including the cited "legacy route adapter") in its
 *    entirety; a recursive search of the legacy onboarding folder for
 *    a route-adapter file referencing the onboarding-theme `onContinue`
 *    returns NO MATCHES. HOWEVER — the `:presentation` layer's role
 *    rationale (no navigation intents on this sealed interface) STANDS
 *    on its own merits: the onboarding-continue affordance, even
 *    post-§302, is wired via a `:ui`-callback + `:composeApp` route
 *    adapter posture (the route adapter handles `safeNavigate(Screen.
 *    Sources)` on continue-tap), NOT via a `ThemeIntent.OnContinue`
 *    variant — preserving the "no navigation intents" rule documented
 *    on this sealed interface KDoc. The terminal-route claim in line
 *    14 is now slightly imprecise (the rework theme route can be
 *    entered from `Screen.Welcome` and continue to `Screen.Sources`
 *    during onboarding), but the architectural-rationale that motivated
 *    the no-nav-intents posture (callback-and-route-adapter ownership
 *    of navigation flow) is preserved verbatim. The SRP / OCP / ISP
 *    sub-sections all stand on their own merits past the §§291 + 302 +
 *    307 fulfilled landings. The [ThemeIntent] sealed interface remains
 *    LIVE as the canonical theme-picker intent ADT consumed by
 *    [ThemeViewModel] across both the standalone Settings hub entry +
 *    the onboarding-flow entry from [me.manga.kira.presentation.
 *    welcome.WelcomeScreen] / `:ui`'s `ThemeScreen`. Original §253-era
 *    prose preserved verbatim per the audit-trail-preservation
 *    convention — the citation is historical record of the design
 *    lineage including the deferred-onboarding-continue forecast that
 *    was subsequently fulfilled across §§291 + 302 + 307.
 */
sealed interface ThemeIntent : MviIntent {

    /**
     * User tapped a theme tab in the picker. The VM invokes
     * [me.manga.kira.domain.usecase.theme.SetAppThemeUseCase] in a coroutine; the upstream
     * `observeAppTheme()` flow re-emits with the new theme value once the legacy
     * `SharedPreferences` writes commit.
     *
     * The user can re-tap the currently-selected tab — the use case writes the same value
     * twice, which is a no-op at the `SharedPreferences` level (Android's `putBoolean` short-
     * circuits on equal values). No idempotence guard is needed in the reducer.
     */
    data class OnSelectTheme(val theme: AppTheme) : ThemeIntent

    /**
     * User flipped the PureBlack/OLED switch in the picker. The VM invokes
     * [me.manga.kira.domain.usecase.theme.SetPureBlackUseCase] in a coroutine; the upstream
     * `observePureBlack()` flow re-emits with the new value once the legacy
     * `SharedPreferences.putBoolean` write commits.
     *
     * The variant carries the post-toggle value (not a "toggle" verb) so the reducer is
     * idempotent regardless of state-vs-intent ordering: a `false → false` write is a no-op
     * at the `SharedPreferences` level (Android short-circuits equal-value writes).
     *
     * Orthogonal to [OnSelectTheme] — toggling PureBlack does NOT change the active theme
     * tri-state. When the active theme resolves to Dark, the toggle drives the dark color
     * scheme variant; when it resolves to Light, the toggle has no visual effect but the
     * value still persists for the next dark resolution.
     */
    data class OnTogglePureBlack(val enabled: Boolean) : ThemeIntent
}
