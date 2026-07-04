package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.statistics.StatisticsViewModel
import me.manga.kira.ui.statistics.StatisticsScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework Statistics screen (Phase 7.x.statistics).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe `composable<Screen.StatisticsRework>`) and
 * the `:ui/.../statistics/StatisticsScreen` composable. Owns the rework [StatisticsViewModel]
 * via Koin.
 *
 * **One nav callback (back)**: Statistics is a terminal display screen — eight read-only
 * aggregates with no outbound links (no per-manga drill-down, no per-day chart, no "Clear read
 * time" action). The only nav wiring is the top-bar back affordance: [navController] backs the
 * screen's `onBack` via `safePopBackStack`; [backStackEntry] is accepted for parity with the
 * sibling route-adapter signatures but not consulted.
 *
 * **Scope**: this is the entry point that proves the Statistics slice end-to-end —
 *  1. **Koin DI** ([me.manga.kira.di.statisticsReworkModule]) resolves the
 *     [StatisticsViewModel] constructor's single use case
 *     ([me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase]) and the
 *     `single`-scoped [me.manga.kira.domain.repository.ReadingStatisticsRepository] it
 *     depends on (which strangler-fig delegates to the legacy `:shared`
 *     `StatisticsRepository`'s eight aggregate flows).
 *  2. **`:presentation` MVI** plumbing emits `StatisticsState` via `StateFlow`. No `Channel`
 *     emissions because [me.manga.kira.presentation.statistics.StatisticsEffect] is an empty
 *     sealed interface today.
 *  3. **`:ui` Compose** renders the eight numbers in a StatsOverview row + Entries/Chapters
 *     sections, with a loading spinner for the sub-frame gap before the first upstream emission.
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [LibraryReworkScreenRoute] — `:ui` deliberately depends on `:presentation` (which knows the
 * VM) but NOT on `androidx.navigation` (which is `:composeApp`-level wiring). The Statistics
 * screen has no outbound links so this adapter is even thinner than the Library one, but it
 * stays in `:composeApp` to keep the layer boundary uniform: every screen has a route adapter
 * at this layer.
 *
 * @param navController parent nav controller — backs the screen's `onBack` via
 *                      [me.manga.kira.navigation.safePopBackStack] (no other outbound nav).
 * @param backStackEntry passed through for parity with sibling route adapters (unused — the
 *                      VM is `koinViewModel()`-scoped via Koin's ViewModelStoreOwner
 *                      integration, so we don't consult `backStackEntry` for VM scoping here).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster87.staleKdocSweep.cascade,
 * Task #543, 2026-05-28): the 4-section manifest above (No nav
 * callbacks + Scope 3-sub-bullet + ":composeApp not :ui" boundary
 * rationale + per-param explanations) is classified as follows
 * after recursive symbol verification across the KMP graph (twenty-
 * ninth sibling of the cluster57-86 sweep — first route-adapter
 * file visited in the `navigation/routes/` cluster):
 *  (a) "No nav callbacks: Statistics is a terminal display screen —
 *  eight read-only aggregates with no outbound links" — SINCE
 *  SUPERSEDED. The back affordance landed after this sweep: the body
 *  now wires `onBack = { navController.safePopBackStack() }` (see the
 *  inline body comment); the "no outbound links" framing holds for
 *  everything except the back pop.
 *  (b) Scope sub-bullet 1 — LIVE-NOT-STALE. "Koin DI ([me.manga.
 *  yamiapk.di.statisticsReworkModule]) resolves the [StatisticsView-
 *  Model] constructor's single use case ([ObserveReadingStatistics-
 *  UseCase]) and the `single`-scoped [ReadingStatisticsRepository]"
 *  — LIVE realization at L53 (`koinViewModel<StatisticsViewModel>()`)
 *  plus design-rationale cite to a Koin module whose binding is
 *  outside this file's verification scope (cross-module reference,
 *  per §253 documented-not-rewritten convention).
 *  (c) Scope sub-bullet 2 — LIVE-NOT-STALE. ":presentation MVI
 *  plumbing emits `StatisticsState` via `StateFlow`. No `Channel`
 *  emissions because [StatisticsEffect] is an empty sealed
 *  interface today" — LIVE-NOT-STALE design rationale for the
 *  terminal-screen MVI shape; no observable file-scope assertion to
 *  recursively verify here.
 *  (d) Scope sub-bullet 3 — LIVE-NOT-STALE. ":ui Compose renders
 *  the eight numbers in a StatsOverview row + Entries/Chapters
 *  sections, with a loading spinner for the sub-frame gap before
 *  the first upstream emission" — LIVE realization at L54
 *  (`StatisticsScreen(viewModel = viewModel)`); the eight-aggregate
 *  rendering shape lives in the `:ui/.../statistics/StatisticsScreen`
 *  consumer.
 *  (e) ":composeApp and not :ui" boundary rationale — LIVE-NOT-STALE.
 *  "same posture as [LibraryReworkScreenRoute] — `:ui` deliberately
 *  depends on `:presentation` (which knows the VM) but NOT on
 *  `androidx.navigation`" — LIVE realization at the file's package
 *  declaration (L1 `package me.manga.kira.navigation.routes`,
 *  inside `:composeApp`) plus L4-5 imports (`androidx.navigation.
 *  NavBackStackEntry` plus `androidx.navigation.NavController`).
 *  Recursive Grep for `androidx\.navigation` references across `:ui`
 *  module sources matches ZERO live references (boundary holds).
 *  (f) Per-param explanations — SINCE SUPERSEDED for [navController]
 *  (now consulted for the back pop; its `@Suppress` was dropped with
 *  the back-affordance wiring); still accurate for [backStackEntry].
 *  The remaining LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful Statistics-rework route-adapter manifest. Original
 *  Phase 7.x-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
@Composable
fun StatisticsReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: StatisticsViewModel = koinViewModel()
    // Phase 7.x.statistics back affordance: the rework StatisticsScreen now exposes a
    // visible top-bar back button (parity with the legacy screen's ArrowBack IconButton);
    // delegate the pop to safePopBackStack, mirroring the AboutReworkScreenRoute posture.
    StatisticsScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
    )
}
