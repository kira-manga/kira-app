package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.complaint.admin.AdminComplaintViewModel
import me.manga.kira.ui.complaint.admin.AdminComplaintScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the user-facing `Screen.ComplaintAdmin` entry.
 *
 * **Phase 9.x.admincomplaint.swap (Task #365)** route-swap: this adapter now renders the rework
 * `:ui/.../complaint/admin/AdminComplaintScreen` via the Koin-bound rework `AdminComplaintViewModel`
 * from `:presentation/.../complaint/admin/`. Before this swap, the same `Screen.ComplaintAdmin`
 * binding rendered the legacy `:composeApp/.../admin/complaint/AdminComplaintScreen` via the
 * legacy `:shared/.../admin/complaint/AdminComplaintViewModel`. The §171 admin Complaint ladder
 * closed at rung 9 (`§176` — `Phase 7.x.complaint.admindate`, Task #272) with full feature parity
 * vs the legacy admin screen across the foundation (§171), 3-mutation actions (§172), edit (§173),
 * sort dropdown (§174), long-press body-copy (§175), statistics card, app-version filter, bulk
 * mutations (#265), chip-row semver sort (#266), and admindate timestamp. The swap was unblocked
 * at that point.
 *
 * Same swap shape as §138/§140/§285-§295/§301/§305/§346 — the rework composable becomes the
 * canonical renderer for an existing `Screen.*` enum case; the parallel `Screen.ComplaintAdminRework`
 * debug route + `AdminComplaintReworkScreenRoute` adapter + legacy `AdminComplaintViewModel` /
 * legacy admin `AdminComplaintScreen` retirement is a follow-on `Phase 9.x.admincomplaint.retire`
 * slice (deferred to respect the 5-file commit cap).
 *
 * **Live navigators today**: NONE pointed at `Screen.ComplaintAdmin` directly. Both the legacy
 * `SettingsRoute` (`SettingsRoute.kt:121`) and the rework `SettingsReworkScreenRoute`
 * (`SettingsReworkScreenRoute.kt:100`) route admin users to `Screen.ComplaintAdminRework` already.
 * This swap re-points `Screen.ComplaintAdmin`'s renderer to the rework screen so any future caller
 * (or external deep-link) lands on the rework regardless of which `Screen.*` it names.
 *
 * **Dropped from the legacy adapter**:
 *  - `complaintsState by viewModel.complaints.collectAsState()` → rework VM exposes
 *    `state.filtered: List<ComplaintSummary>` via the strict-MVI `state` flow; `:ui` reads it
 *    directly via `viewModel.state.collectAsState()`.
 *  - `LaunchedEffect(Unit) { viewModel.loadAllComplaints() }` → rework VM auto-loads in `init {}`
 *    via `loadList()` (see `AdminComplaintViewModel.kt:60`).
 *  - 6 callback wirings (`onRetry`, `onUpdateComplaintStatus`, `onDeleteComplaint`,
 *    `onUpdateComplaint`, `onAddClosureReason`, `onShowMessage`) → rework `:ui` dispatches
 *    `AdminComplaintIntent.OnRetry` / `OnSubmitStatusChange` / `OnConfirmDelete` /
 *    `OnSubmitEdit` / `OnSubmitClosureReason` directly via `viewModel.submit(...)`.
 *  - `ToastShower koinInject()` + `onShowMessage = { toastShower.showShort(it) }` →
 *    rework emits `AdminComplaintEffect.ShowSuccessMessage` / `ShowErrorMessage` via the MVI
 *    effect channel; `:ui` collects effects and surfaces them in a `SnackbarHostState` (see
 *    `AdminComplaintScreen.kt:33`). No platform-coupled `ToastShower` dependency at the adapter.
 *
 * **Preserved by the rework**:
 *  - Same upstream Firestore `complaints` collection (legacy `GetAllComplaintUseCase` reached via
 *    strangler-fig `AdminComplaintListRepositoryImpl`); a user-side submission via the legacy
 *    `ComplaintViewModel.sendComplaint` path or via the Request-Language slice's
 *    `LanguageViewModel` lands in the same collection, so the swap preserves wire compatibility.
 *  - Admin-only access gate at the calling site (both Settings hub adapters already restrict
 *    `Screen.ComplaintAdminRework` / `Screen.ComplaintAdmin` navigation to `Admin.isAdmin == true`).
 *    The adapter itself does NOT re-check admin status — same posture as the legacy adapter.
 *
 * @param navController parent nav controller — `safePopBackStack()` is invoked on back to avoid
 *                      dead-ending the user. Same posture as `AdminComplaintReworkScreenRoute`.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures (unused —
 *                      the rework `AdminComplaintViewModel` is `koinViewModel()`-scoped via
 *                      Koin's ViewModelStoreOwner integration, so we don't consult
 *                      `backStackEntry` for VM scoping here).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster7.staleKdocSweep.cascade,
 * Task #463, 2026-05-28): two stale citations into the §366-retired
 * legacy admin Complaint surfaces appear above:
 *  - Lines 17-18 (pre-swap context opener): "Before this swap, the same
 *    `Screen.ComplaintAdmin` binding rendered the legacy
 *    `:composeApp/.../admin/complaint/AdminComplaintScreen` via the
 *    legacy `:shared/.../admin/complaint/AdminComplaintViewModel`".
 *  - Lines 26-29 (parallel-rework-route + retirement-forecast bullet):
 *    "the parallel `Screen.ComplaintAdminRework` debug route +
 *    `AdminComplaintReworkScreenRoute` adapter + legacy
 *    `AdminComplaintViewModel` / legacy admin `AdminComplaintScreen`
 *    retirement is a follow-on `Phase 9.x.admincomplaint.retire`
 *    slice".
 *  - Lines 37-50 ("Dropped from the legacy adapter" bullets — multiple
 *    cross-references to the now-retired legacy adapter's wiring
 *    posture).
 * The legacy `:shared/.../admin/complaint/AdminComplaintViewModel.kt` +
 * legacy `:composeApp/.../admin/complaint/AdminComplaintScreen.kt` + 2
 * helpers were retired in Phase 9.x.admincomplaint.retire (§366 sweep,
 * commit `48a5c2b` "(1/2): delete orphan legacy admin VM + screen + 2
 * helpers + drop Koin binding"); verified by a filesystem check
 * returning zero hits for those paths. The lines 26-29 retirement
 * forecast was a fulfilled prediction — the "follow-on Phase
 * 9.x.admincomplaint.retire slice" materialised exactly as anticipated.
 * The swap-shape-preserved + admin-gate-at-calling-site + same-upstream-
 * Firestore-collection rationales all stand on their own merits — the
 * rework AdminComplaintScreen remains LIVE as the canonical renderer for
 * `Screen.ComplaintAdmin`, documented inline above and via the
 * §§258-266 + §365 KDocs, independent of which legacy file originally
 * implemented the equivalent admin flows. Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citations are historical record of the design lineage; the rework
 * AdminComplaintScreenRoute continues to surface the documented
 * affordances past the §366 retire.
 */
@Composable
fun AdminComplaintScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: AdminComplaintViewModel = koinViewModel()
    AdminComplaintScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
    )
}
