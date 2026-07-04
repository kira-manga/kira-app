package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.theme.ThemeViewModel
import me.manga.kira.ui.themepicker.ThemeScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework Theme picker screen (Phase 7.x.theme).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe `composable<Screen.ThemeRework>`) and the
 * `:ui/.../themepicker/ThemeScreen` composable. Owns the rework [ThemeViewModel] via Koin.
 *
 * **Back affordance**: the rework theme picker is a terminal display-and-toggle screen — three
 * tabs (Light / Dark / System) with no outbound links. The screen is reached from the rework
 * Settings hub, so the adapter wires `onBack = { navController.safePopBackStack() }` (rendered
 * as the TopAppBar back arrow — Desktop has no system back button). [backStackEntry] is
 * accepted for parity with the sibling route-adapter signatures but not consulted.
 *
 * **Onboarding chain not consumed here**: the legacy onboarding flow's
 * [me.manga.kira.navigation.routes.ThemeSelectionScreenRoute] (the `Screen.Theme` step in
 * the Welcome → Theme → Sources → RepoSettings → Library wizard chain) calls an `onContinue`
 * callback that advances to the next onboarding step, AND overlays animated background +
 * notification-permission grant chrome + auto-requests POST_NOTIFICATIONS on first
 * composition. After Phase 7.x.theme.onboardingcontinue + onboardingpermission the rework
 * [me.manga.kira.ui.themepicker.ThemeScreen] accepts three optional parameters
 * (`onContinue`, `hasNotificationPermission`, `onRequestNotificationPermission`) covering
 * the Continue button + notification grant row + permission-gated Continue enablement.
 * This `Screen.ThemeRework` route does NOT pass any of them (defaults preserve the
 * standalone-picker behaviour — the route is reached from the rework Settings hub as a
 * terminal screen, NOT as a wizard step, so there's no next-step to navigate to and no
 * reason to auto-request notifications). The `AnimatedBackground` gradient overlay from
 * the legacy + the auto-request lifecycle (LaunchedEffect-fired request + toast-on-denial)
 * are still deferred: the overlay is purely decorative (separate `onboardingbackground`
 * gap-lift), and the auto-request lifecycle is an onboarding-route-adapter concern that
 * the future Phase 7.x.theme.swap caller (NOT this route) will own.
 *
 * **Scope**: this is the entry point that proves the Theme slice end-to-end —
 *  1. **Koin DI** ([me.manga.kira.di.themeReworkModule]) resolves the [ThemeViewModel]
 *     constructor's two use cases
 *     ([me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase],
 *     [me.manga.kira.domain.usecase.theme.SetAppThemeUseCase]) and the `single`-scoped
 *     [me.manga.kira.domain.repository.ThemeRepository] they depend on (which
 *     strangler-fig delegates to the legacy `:shared` `SettingsRepository`'s
 *     `darkModeFlow` + `followSystemFlow` + `setDarkMode` + `setFollowSystem` surface).
 *  2. **`:presentation` MVI** plumbing emits
 *     [me.manga.kira.presentation.theme.ThemeState] via `StateFlow`. No `Channel`
 *     emissions because [me.manga.kira.presentation.theme.ThemeEffect] is an empty sealed
 *     interface today.
 *  3. **`:ui` Compose** renders the Material 3 `TabRow` with three text tabs and a centered
 *     spinner for the sub-frame gap before the first upstream emission. No icons — matches
 *     the icon-free posture of the other rework `:ui` screens
 *     (`HistoryScreen` / `StatisticsScreen` / `UpdatesScreen` / `SourcesScreen`).
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [SourcesReworkScreenRoute] / [StatisticsReworkScreenRoute] / [LibraryReworkScreenRoute] /
 * [UpdatesReworkScreenRoute] — `:ui` deliberately depends on `:presentation` (which knows the
 * VM) but NOT on `androidx.navigation` (which is `:composeApp`-level wiring). The Theme screen
 * has no outbound links so this adapter is the thinnest possible — but it stays in
 * `:composeApp` to keep the layer boundary uniform: every screen has a route adapter at this
 * layer.
 *
 * @param navController parent nav controller — consumed by the `onBack` pop.
 * @param backStackEntry passed through for parity with sibling route adapters (unused — the VM
 *                      is `koinViewModel()`-scoped via Koin's ViewModelStoreOwner integration,
 *                      so we don't consult `backStackEntry` for VM scoping here).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster37.staleKdocSweep.cascade,
 * Task #493, 2026-05-28): one stale citation appears in this file's
 * class-level KDoc above, plus several ancillary references that
 * require disambiguation:
 *  - Line 33 cites `AnimatedBackground` as a backtick-prose reference
 *    framed within the deferred-feature paragraph ("The `AnimatedBackground`
 *    gradient overlay from the legacy + the auto-request lifecycle
 *    (LaunchedEffect-fired request + toast-on-denial) are still deferred").
 *  Classified as STALE-SYMBOL-REFERENCE — §142 (Phase 9.x.
 *  onboarding.legacy_retire, Task #307) DELETED the legacy
 *  `:composeApp/.../presentation/features/onboarding/components/
 *  AnimatedBackground.kt` along with 4 sibling legacy onboarding
 *  files as a cascade-orphan-retire chain. A recursive Glob for
 *  `AnimatedBackground.kt` returns NO MATCHES. The bare
 *  `AnimatedBackground` symbol survives only as documentation prose
 *  in sibling theme / welcome / sources screen KDocs + project
 *  documentation Markdown (ARCHITECTURE.md, SOLID_AUDIT.md,
 *  migration logs, PLAN_*.md) — the Kotlin source class itself is
 *  retired. HOWEVER — the architectural rationale of the citation
 *  STANDS on its own merits past the §142 fulfilled landing as a
 *  LIVE design-deferral record: the "deferred gradient overlay
 *  + auto-request lifecycle" forecast describes a future
 *  cosmetic-port intent that remains LIVE as a deferred design
 *  decision; the cite-target file is gone but the conceptual
 *  reference to "the legacy gradient overlay" survives as a
 *  historical pointer to what would be ported if/when the Theme
 *  picker is reached from a wizard step. Post-§142 the rework
 *  `Brush.linearGradient`-sweep substitution (per §142 migration
 *  log L795) is the canonical reference shape if/when ported,
 *  NOT the original Lottie composition.
 *  Ancillary references in the same KDoc are LIVE-NOT-STALE and
 *  require no individual stale-classification on their own merits:
 *  (a) Lines 22-23 — the Dokka link `[me.manga.kira.navigation.
 *  routes.ThemeSelectionScreenRoute]` resolves LIVE: the legacy
 *  onboarding theme-step route adapter survives on disk
 *  (`composeApp/.../navigation/routes/ThemeSelectionScreenRoute.kt`
 *  is present and per its §136/§137-era KDoc routes
 *  `Screen.Theme` to the rework `:ui` ThemeScreen post-§291
 *  theme.swap landing; only the underlying legacy composable
 *  `ThemeSelectionScreen.kt` was retired under §142, not the
 *  route adapter itself);
 *  (b) Line 27 — `[me.manga.kira.ui.themepicker.ThemeScreen]`
 *  Dokka link resolves LIVE (`ui/.../themepicker/ThemeScreen.kt`
 *  is present and is the canonical rework theme-picker
 *  composable);
 *  (c) Lines 40-46 — Koin DI plumbing references
 *  (`[me.manga.kira.di.themeReworkModule]`,
 *  `[ObserveAppThemeUseCase]`, `[SetAppThemeUseCase]`,
 *  `[ThemeRepository]`) all resolve LIVE — `:domain/.../usecase/
 *  theme/` houses both use cases, `:domain/.../repository/
 *  ThemeRepository.kt` is present, and `composeApp/.../di/
 *  ThemeReworkModule.kt` is wired into the active Koin graph;
 *  (d) Lines 45-46 — the strangler-fig "delegates to the legacy
 *  `:shared` `SettingsRepository`'s `darkModeFlow` +
 *  `followSystemFlow` + `setDarkMode` + `setFollowSystem`
 *  surface" reference resolves LIVE — `:shared/.../features/
 *  settings/domain/SettingsRepository.kt` is present as the
 *  strangler-fig back-end, and the four cited members survive
 *  on its interface;
 *  (e) Lines 47-50 — `[me.manga.kira.presentation.theme.
 *  ThemeState]` + `[me.manga.kira.presentation.theme.
 *  ThemeEffect]` Dokka links resolve LIVE in the `:presentation`
 *  theme slice;
 *  (f) Lines 56-62 — the sibling-route-adapter parity
 *  references (`[SourcesReworkScreenRoute]`,
 *  `[StatisticsReworkScreenRoute]`, `[LibraryReworkScreenRoute]`,
 *  `[UpdatesReworkScreenRoute]`) all resolve LIVE in
 *  `composeApp/.../navigation/routes/`;
 *  (g) Lines 26-37 — the legacy-onboarding-wizard-chain
 *  description (Welcome → Theme → Sources → RepoSettings →
 *  Library) describes a HISTORICAL flow that survives in
 *  modified form post-§307 / §347-cluster: the wizard chain
 *  itself is preserved end-to-end via route adapters that now
 *  dispatch to rework `:ui` screens (per §286/§289-§295/§301/
 *  §305 swap landings); the prose describing the legacy
 *  onboarding flow's `onContinue` callback + animated background
 *  + notification-permission auto-request is historically accurate
 *  as the design lineage even though the legacy implementation
 *  files are retired — the rework `:ui` ThemeScreen accepts the
 *  three deferred-onboarding parameters
 *  (`onContinue`, `hasNotificationPermission`,
 *  `onRequestNotificationPermission`) per §136/§137, and the
 *  rework ThemeSelectionScreenRoute adapter wires them when
 *  routing as the wizard's step 2.
 *  Original Phase 7.x.theme-era prose preserved verbatim per the
 *  audit-trail-preservation convention — the citations are
 *  historical record of the design lineage including the
 *  §142-retired AnimatedBackground deferred-feature reference
 *  (the cite-target file is gone but the deferred-design forecast
 *  for a possible future cosmetic port stands as a LIVE intent).
 */
@Composable
fun ThemeReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: ThemeViewModel = koinViewModel()
    ThemeScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
    )
}
