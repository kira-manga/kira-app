package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.downloads.DownloadsViewModel
import me.manga.kira.ui.downloads.DownloadsScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework Downloads screen
 * (Phase 7.x.downloads.foundation).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe
 * `composable<Screen.DownloadsRework>`) and the `:ui/.../downloads/DownloadsScreen`
 * composable. Owns the rework [DownloadsViewModel] via Koin.
 *
 * **One outbound link (back)**: the rework Downloads screen has no row-tap navigation
 * today — the legacy `DownloadsScreen.kt:103` has the same `onBack: () -> Unit` arrow,
 * no other outbound nav from the screen itself. The follow-on actions slice will add
 * retry / cancel / delete *mutation* affordances per row, but those are intra-screen
 * actions (no nav) — the only nav surface stays this one back-arrow.
 *
 * The `onBack` callback delegates to [me.manga.kira.navigation.safePopBackStack] —
 * same posture as [ComplaintReworkScreenRoute] / [AboutScreenRoute] / etc.
 *
 * **Coexists with legacy [DownloadsScreenRoute]**: both routes consume the SAME upstream
 * `DownloadRepository.observeAllDownloads()` flow (legacy goes through
 * `DownloadViewModelv2.downloads`; rework goes through `ObserveDownloadsUseCase`
 * -> `DownloadsRepositoryImpl` -> legacy `DownloadRepository.observeAllDownloads()`).
 * Adds / cancels / state transitions from EITHER route propagate to the other through
 * Room's `Flow<List<...>>`. Phase 9.x route-swap collapses to the rework path; until
 * then both stay reachable.
 *
 * **Reduced surface vs the legacy [DownloadsScreenRoute]**: the rework foundation slice
 * omits the four mutation affordances the legacy renders inline per row:
 *  - `onRetry(ChapterDownloadEntity)` — retry a failed download.
 *  - `onCancel(ChapterDownloadEntity)` — cancel a queued / non-running download.
 *  - `runningChapterCancel(ChapterDownloadEntity)` — cancel an in-flight running
 *    download (separate intent because the legacy worker semantics differ for an
 *    interruptible-in-flight cancel vs a queue-prune cancel).
 *  - `onDelete(ChapterDownloadEntity)` — delete a completed / failed download row.
 *
 * All four lift via strict-MVI OCP §6 — sealed
 * [me.manga.kira.presentation.downloads.DownloadsIntent] /
 * [me.manga.kira.presentation.downloads.DownloadsEffect] accept new variants
 * without breaking the existing one (`OnTabSelect`). The follow-on
 * `Phase 7.x.downloads.actions` slice adds the four mutation intents + their use cases
 * + per-row buttons in `:ui` + per-success `ShowSuccess` / per-failure `ShowError`
 * effect variants for snackbar feedback.
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [ComplaintReworkScreenRoute] / [StatisticsReworkScreenRoute] / etc. — `:ui`
 * deliberately depends on `:presentation` (which knows the VM) but NOT on
 * `androidx.navigation` (which is `:composeApp`-level wiring). The screen has one
 * outbound link (back) handled via a callback so the `:ui` module stays
 * nav-host-agnostic.
 *
 * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
 * `navController.navigate(Screen.DownloadsRework)` from a future developer trigger
 * or a test/debug helper that holds the `NavController`. The legacy
 * [me.manga.kira.navigation.Screen.DownloadsScreen] route remains bound to the
 * legacy [DownloadsScreenRoute] (with its retry/cancel/delete/runningCancel button
 * surface) — the Settings hub's Downloads row (Phase 7.x.settings.downloads) still
 * points at the legacy route until the rework actions slice lands AND a follow-on
 * Phase 7.x.downloads.swap commit redirects the row. Two-step swap avoids dropping
 * the four mutation affordances on the user-reachable path mid-rework.
 *
 * @param navController parent nav controller — `safePopBackStack()` is invoked on back
 *                      to avoid dead-ending the user.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures
 *                      (unused — the VM is `koinViewModel()`-scoped via Koin's
 *                      ViewModelStoreOwner integration).
 *
 * **Audit-trail postscript** (Phase 9.x.downloads.staleKdocSweep.cascade, Task #444,
 * 2026-05-28): the "Coexists with legacy [DownloadsScreenRoute]" / "Reduced surface vs
 * the legacy" / "Discoverability" paragraphs above describe a then-LIVE coexistence
 * topology that has since dissolved at multiple levels:
 *  - `DownloadViewModelv2` was retired in Phase 9.x.downloadvmv2.retire (§439); the
 *    legacy-route consumption chain `DownloadViewModelv2.downloads` no longer exists.
 *  - The legacy `DownloadsScreen` + `DownloadFloatingActionButton` + the rest of the
 *    legacy Downloads UI were retired in Phase 9.x.downloads.legacyui.retire (§352).
 *  - The Phase 7.x.downloads.swap (§295) commit redirected the legacy `Screen.DownloadsScreen`
 *    nav entry to the rework UI, so both [DownloadsScreenRoute] (legacy nav key) and
 *    this [DownloadsReworkScreenRoute] (rework nav key) now render the SAME rework screen
 *    backed by the SAME rework VM (different `NavBackStackEntry`-scoped instances; the
 *    underlying `single`-scoped repositories are shared).
 *  - The four "Reduced surface" affordances (retry / cancel / runningCancel / delete) were
 *    closed in the Phase 7.x.downloads.actions slice (§281) via
 *    [me.manga.kira.domain.repository.DownloadsActionRepository] — the rework no longer
 *    has the affordance gap this KDoc described.
 * Original prose preserved verbatim per §253 — the design-intent of an ISP-clean read-only
 * adapter with later actions sibling stands as historical record; the cited LIVE
 * collaborators have all since retired or been superseded.
 */
@Composable
fun DownloadsReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: DownloadsViewModel = koinViewModel()
    DownloadsScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
    )
}
