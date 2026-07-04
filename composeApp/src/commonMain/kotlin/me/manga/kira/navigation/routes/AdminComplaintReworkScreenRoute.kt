package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.complaint.admin.AdminComplaintViewModel
import me.manga.kira.ui.complaint.admin.AdminComplaintScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework admin Complaint dashboard screen
 * (Phase 7.x.complaint.admin foundation — "Admin Complaints" screen).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe `composable<Screen.ComplaintAdminRework>`)
 * and the `:ui/.../complaint/admin/AdminComplaintScreen` composable. Owns the rework
 * [AdminComplaintViewModel] via Koin.
 *
 * **One outbound link (back)**: the rework admin dashboard's foundation slice has no row-tap
 * navigation today (the legacy admin screen's row-tap opens an inline status-change /
 * edit / closure-reason / delete dialog, NOT a separate screen — all deferred to a future
 * admin-actions slice), so the only outbound nav is the top-bar Back affordance. The `onBack`
 * callback delegates to [me.manga.kira.navigation.safePopBackStack] to avoid dead-ending the
 * user on the root — same posture as [ComplaintReworkScreenRoute] (user-side sibling).
 * Future admin-actions slice additions (status-change / edit / closure-reason / delete inline
 * dialogs) keep the same nav signature — they emit
 * [me.manga.kira.presentation.complaint.admin.AdminComplaintEffect] variants for
 * confirmation snackbars, not outbound nav.
 *
 * **Coexists with legacy [AdminComplaintScreenRoute]**: both routes consume the SAME upstream
 * Firestore `complaints` collection (legacy goes through `AdminComplaintViewModel.loadAll` in
 * `:shared`; rework goes through
 * [me.manga.kira.domain.usecase.complaint.ObserveAllComplaintsUseCase] →
 * [me.manga.kira.data.repository.AdminComplaintListRepositoryImpl] → legacy `:shared`
 * `GetAllComplaintUseCase`). A user-side submission via the legacy
 * [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.sendComplaint]
 * path or via the Request-Language slice's
 * [me.manga.kira.presentation.language.LanguageViewModel] lands in the same collection, so
 * the rework admin LIST surfaces submissions from any sibling write path. Phase 9.x route-swap
 * collapses to the rework path; until then both stay reachable.
 *
 * **Reduced surface vs the legacy [AdminComplaintScreenRoute]**: the rework foundation slice
 * omits 6 admin mutations (status-change, edit, closure-reason, delete, bulk-update,
 * bulk-delete) + statistics aggregation card + sort dropdown + app-version filter + long-press
 * body-copy. All deferrals lift via strict-MVI OCP §6 — sealed
 * [me.manga.kira.presentation.complaint.admin.AdminComplaintIntent] /
 * [me.manga.kira.presentation.complaint.admin.AdminComplaintEffect] accept new variants
 * without breaking the existing five intents (`OnRetry`, `OnSearchChange`, `OnClearSearch`,
 * `OnStatusFilter`, `OnTypeFilter`).
 *
 * **Admin-only access**: navigation TO this route is gated by
 * [me.manga.kira.admin.Admin.isAdmin] at the calling site (the Settings hub adapter — see
 * [SettingsReworkScreenRoute]). This adapter does NOT re-check admin status — same posture as
 * the legacy admin route, which also delegates the gate to its caller. The `:presentation` and
 * `:ui` modules don't know about Admin state — that's a `:composeApp`-level concern.
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [ComplaintReworkScreenRoute] / [LanguageReworkScreenRoute] / etc. — `:ui` deliberately
 * depends on `:presentation` (which knows the VM) but NOT on `androidx.navigation` (which is
 * `:composeApp`-level wiring). The screen has one outbound link (back) handled via a callback
 * so the `:ui` module stays nav-agnostic.
 *
 * **Discoverability**: reachable from the rework Settings hub via the `OnNavigate(COMPLAINT)`
 * intent when [me.manga.kira.admin.Admin.isAdmin] is `true` (see [SettingsReworkScreenRoute]).
 * The legacy [me.manga.kira.navigation.Screen.ComplaintAdmin] route remains bound to
 * [AdminComplaintScreenRoute] (with its mutation dialogs + statistics + sort + app-version
 * filter).
 *
 * @param navController parent nav controller — `safePopBackStack()` is invoked on back to avoid
 *                      dead-ending the user.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures
 *                      (unused — the VM is `koinViewModel()`-scoped via Koin's
 *                      ViewModelStoreOwner integration).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster15.staleKdocSweep.cascade,
 * Task #471, 2026-05-28): four categories of stale + inverted citations
 * appear above:
 *  - Lines 30-40 ("Coexists with legacy [AdminComplaintScreenRoute]"
 *    para): "both routes consume the SAME upstream Firestore `complaints`
 *    collection (legacy goes through `AdminComplaintViewModel.loadAll` in
 *    `:shared`; rework goes through ...
 *    `AdminComplaintListRepositoryImpl` → legacy `:shared`
 *    `GetAllComplaintUseCase`)". The legacy
 *    `:shared/.../admin/complaint/AdminComplaintViewModel.kt` was retired
 *    in Phase 9.x.admincomplaint.retire (§366 sweep, commit `48a5c2b`
 *    "(1/2): delete orphan legacy admin VM + screen + 2 helpers + drop
 *    Koin binding"); verified by filesystem check returning zero hits.
 *    The legacy `:shared/.../features/complaint/viewmodel/ComplaintViewModel.kt`
 *    (cited on line 36 — "user-side submission via the legacy
 *    `ComplaintViewModel.sendComplaint` path") was retired in Phase
 *    9.x.complaintvm.retire (§363 sweep, commit `e2af0d4` "(1/2): delete
 *    unreachable :shared ComplaintViewModel"); verified by filesystem
 *    check returning zero hits. The strangler-fig
 *    `AdminComplaintListRepositoryImpl` continues to delegate to the
 *    `:shared` `GetAllComplaintUseCase` (verified LIVE on disk) — the
 *    same Firestore `complaints` collection remains the cell-of-truth.
 *  - Line 30 ("Coexists with legacy [AdminComplaintScreenRoute]"
 *    framing) + Lines 65-67 (legacy-route-bound enumeration): "Phase 9.x
 *    route-swap collapses to the rework path; until then both stay
 *    reachable" + "The legacy [me.manga.kira.navigation.Screen.ComplaintAdmin]
 *    route remains bound to [AdminComplaintScreenRoute] (with its
 *    mutation dialogs + statistics + sort + app-version filter)". Phase
 *    9.x.admincomplaint.swap (§365, Task #365 — sibling commit to §366)
 *    re-pointed `Screen.ComplaintAdmin`'s rendering adapter to the rework
 *    `AdminComplaintScreen` already. The `AdminComplaintScreenRoute.kt`
 *    adapter file STILL EXISTS but now renders the rework VM + screen
 *    (verified via the §463 cluster7 postscript on
 *    `AdminComplaintScreenRoute.kt`); the "until then both stay reachable"
 *    forecast was a fulfilled prediction — both routes now converge on
 *    the rework path.
 *  - Lines 42-49 ("Reduced surface vs the legacy" para): "the rework
 *    foundation slice omits 6 admin mutations (status-change, edit,
 *    closure-reason, delete, bulk-update, bulk-delete) + statistics
 *    aggregation card + sort dropdown + app-version filter + long-press
 *    body-copy". FACTUALLY INVERTED — the §171 admin Complaint ladder
 *    closed at rung 9 (`§176` — `Phase 7.x.complaint.admindate`, Task
 *    #272) with full feature parity vs the legacy admin screen across
 *    the foundation (§171), 3-mutation actions (§172), edit (§173),
 *    sort dropdown (§174), long-press body-copy (§175), statistics card,
 *    app-version filter, bulk mutations (#265), chip-row semver sort
 *    (#266), and admindate timestamp. Cross-referenced by the §463
 *    postscript on `AdminComplaintScreenRoute.kt:18-23`. The 6
 *    enumerated "deferrals" all LANDED. Mirror of §445 + §470
 *    fulfilled-deferral-inversion precedent.
 *  - Lines 45-49 (OCP-forecast bullet): "All deferrals lift via
 *    strict-MVI OCP §6 — sealed `AdminComplaintIntent` /
 *    `AdminComplaintEffect` accept new variants without breaking the
 *    existing five intents (`OnRetry`, `OnSearchChange`, `OnClearSearch`,
 *    `OnStatusFilter`, `OnTypeFilter`)". Itself a fulfilled prediction —
 *    `OnSubmitStatusChange` / `OnSubmitEdit` / `OnConfirmDelete` /
 *    `OnSubmitClosureReason` / bulk-mutation intents + `ShowSuccessMessage`
 *    / `ShowErrorMessage` effects all materialised across §§172-176 +
 *    #265 + #266 + §272, exactly as anticipated.
 * The "One outbound link (back)" + admin-gate-at-calling-site +
 * `:composeApp`-vs-`:ui`-boundary + Koin DI scope rationales all stand
 * on their own merits past the §363 + §365 + §366 retires/swaps + the
 * §§171-176 + #265 + #266 + §272 fulfilled-parity work. The rework
 * AdminComplaintReworkScreenRoute remains LIVE as the canonical renderer
 * for `Screen.ComplaintAdminRework` and is now joined by the §365-swapped
 * `AdminComplaintScreenRoute` adapter (both converging on the same rework
 * screen + VM through `NavBackStackEntry`-scoped Koin instances).
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of the
 * design lineage including the deferral forecasts that were subsequently
 * fulfilled.
 */
@Composable
fun AdminComplaintReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: AdminComplaintViewModel = koinViewModel()
    AdminComplaintScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
    )
}
