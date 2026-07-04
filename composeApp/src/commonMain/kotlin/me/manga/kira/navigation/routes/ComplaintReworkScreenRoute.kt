package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.complaint.ComplaintViewModel
import me.manga.kira.ui.complaint.ComplaintScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework user-side Complaint LIST screen
 * (Phase 7.x.complaint.foundation — "Feedback Manager" screen).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe `composable<Screen.ComplaintRework>`) and the
 * `:ui/.../complaint/ComplaintScreen` composable. Owns the rework [ComplaintViewModel] via Koin.
 *
 * **One outbound link (back)**: the rework Complaint LIST screen has no row-tap navigation today
 * (the legacy screen's row-tap opens an inline edit/reply/delete dialog, NOT a separate screen),
 * so the only outbound nav is the top-bar Back affordance. The `onBack` callback delegates to
 * [me.manga.kira.navigation.safePopBackStack] to avoid dead-ending the user on the root —
 * same posture as [ComplaintScreenRoute] (legacy line 53). Future action-slice additions
 * (reply / edit / delete inline dialogs) keep the same nav signature — they emit
 * [me.manga.kira.presentation.complaint.ComplaintEffect] variants for confirmation snackbars,
 * not outbound nav.
 *
 * **Coexists with legacy [ComplaintScreenRoute]**: both routes consume the SAME upstream
 * Firestore `complaints` collection (legacy goes through `ComplaintViewModel.loadAll`; rework
 * goes through `ObserveUserComplaintsUseCase` -> `ComplaintListRepositoryImpl` -> legacy
 * `GetUserComplaintUseCase`). Submitting a complaint via the legacy
 * [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.sendComplaint]
 * path or via the Request-Language slice's [me.manga.kira.presentation.language.LanguageViewModel]
 * lands in the same collection, so the rework LIST surfaces submissions from any sibling write
 * path. Phase 9.x route-swap collapses to the rework path; until then both stay reachable.
 *
 * **Reduced surface vs the legacy [ComplaintScreenRoute]**: the rework foundation slice omits:
 *  - Reply dialog ([me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.sendComplaint]).
 *  - Edit dialog (legacy's `updateComplaint`).
 *  - Delete dialog (legacy's `deleteComplaint`).
 *  - `onShowMessage` -> [me.manga.kira.core.platform.ToastShower.showShort] feedback strings.
 *  - `onHelp` (legacy 403-permission-denied -> help-URL TODO; the rework defers the
 *    [me.manga.kira.core.platform.IntentLauncher] integration until the action slice).
 *
 * All deferrals lift via strict-MVI OCP §6 — sealed
 * [me.manga.kira.presentation.complaint.ComplaintIntent] /
 * [me.manga.kira.presentation.complaint.ComplaintEffect] accept new variants without breaking
 * the existing ones (`OnRetry`, `OnSearchChange`, `OnStatusFilter`, `OnClearSearch`). The action
 * follow-on (Phase 7.x.complaint.actions) will add `OnReply(id, body)` / `OnEdit(id, body)` /
 * `OnDelete(id)` intents and `ShowReplySnackbar` / `ShowEditSnackbar` / `ShowDeleteSnackbar`
 * effects + extend this route adapter to host the Snackbar collector + dialog state.
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: same posture as
 * [LanguageReworkScreenRoute] / [StatisticsReworkScreenRoute] / etc. — `:ui` deliberately depends
 * on `:presentation` (which knows the VM) but NOT on `androidx.navigation` (which is
 * `:composeApp`-level wiring). The screen has one outbound link (back) handled via a callback
 * so the `:ui` module stays nav-agnostic.
 *
 * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
 * `navController.navigate(Screen.ComplaintRework)` from a future developer trigger or a
 * test/debug helper that holds the `NavController`. The legacy [me.manga.kira.navigation.Screen.Complaint]
 * route remains bound to the legacy [ComplaintScreenRoute] (with its reply/edit/delete dialog
 * surface).
 *
 * @param navController parent nav controller — `safePopBackStack()` is invoked on back to avoid
 *                      dead-ending the user.
 * @param backStackEntry passed through for parity with sibling route-adapter signatures (unused —
 *                      the VM is `koinViewModel()`-scoped via Koin's ViewModelStoreOwner
 *                      integration).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster15.staleKdocSweep.cascade,
 * Task #471, 2026-05-28): four categories of stale + inverted citations
 * appear above:
 *  - Lines 27-34 ("Coexists with legacy [ComplaintScreenRoute]" para):
 *    "both routes consume the SAME upstream Firestore `complaints`
 *    collection (legacy goes through `ComplaintViewModel.loadAll`; rework
 *    goes through `ObserveUserComplaintsUseCase` -> ...)". The legacy
 *    `:shared/.../features/complaint/viewmodel/ComplaintViewModel.kt`
 *    was retired in Phase 9.x.complaintvm.retire (§363 sweep, commit
 *    `e2af0d4` "(1/2): delete unreachable :shared ComplaintViewModel");
 *    verified by filesystem check returning zero hits. The legacy
 *    `GetUserComplaintUseCase` STILL EXISTS in `:shared` and the
 *    strangler-fig `ComplaintListRepositoryImpl` continues to delegate
 *    to it (verified on disk) — the same Firestore `complaints`
 *    collection remains the cell-of-truth across user-side + admin-side
 *    surfaces.
 *  - Lines 22 + 27 + 60-62 ("[ComplaintScreenRoute]" cross-references):
 *    "same posture as [ComplaintScreenRoute] (legacy line 53)" + "The
 *    legacy [me.manga.kira.navigation.Screen.Complaint] route remains
 *    bound to the legacy [ComplaintScreenRoute] (with its reply/edit/
 *    delete dialog surface)". Phase 7.x.complaint.swap (§293, sibling to
 *    the §463 cluster7 postscript on `ComplaintScreenRoute.kt`)
 *    re-pointed `Screen.Complaint`'s rendering adapter to the rework
 *    `ComplaintScreen` already. The `ComplaintScreenRoute.kt` adapter
 *    file STILL EXISTS but now renders the rework VM + screen; both
 *    `Screen.Complaint` and `Screen.ComplaintRework` converge on the
 *    rework path through `NavBackStackEntry`-scoped Koin instances.
 *  - Lines 36-42 ("Reduced surface vs the legacy" para): "the rework
 *    foundation slice omits: Reply dialog ... Edit dialog ... Delete
 *    dialog ... `onShowMessage` -> ToastShower feedback strings ...
 *    `onHelp` (legacy 403-permission-denied -> help-URL TODO)".
 *    FACTUALLY INVERTED for 4 of 5 sub-bullets — Phase 7.x.complaint.actions
 *    (Task #252) shipped Reply/Edit/Delete inline dialogs (the rework
 *    surfaces a single `ComplaintActionDialog` with 4 sub-panels: Menu /
 *    Reply / Edit / Delete — see §463 postscript on `ComplaintScreenRoute.kt`
 *    lines 36-42); Toast → Snackbar swap landed via `ComplaintEffect.
 *    ShowSuccessMessage` / `ShowErrorMessage`. Only the `onHelp` 403
 *    help-URL launcher remains genuinely deferred (Phase 10.x). Mirror
 *    of §445 + §470 + §471 fulfilled-deferral-inversion precedent.
 *  - Lines 44-50 (OCP-forecast bullet): "The action follow-on (Phase
 *    7.x.complaint.actions) will add `OnReply(id, body)` / `OnEdit(id,
 *    body)` / `OnDelete(id)` intents and `ShowReplySnackbar` /
 *    `ShowEditSnackbar` / `ShowDeleteSnackbar` effects + extend this
 *    route adapter to host the Snackbar collector + dialog state".
 *    Itself a fulfilled prediction — Task #252 materialised the intent
 *    surface (as `OnSubmitReply` / `OnSubmitEdit` / `OnConfirmDelete` +
 *    `OnRowClick` / `OnDismissActionDialog` / `OnSelectAction` /
 *    `OnCopyBody`, an 11-intent total) and the effect surface
 *    (consolidated into `ShowSuccessMessage` / `ShowErrorMessage` rather
 *    than per-action `ShowReplySnackbar` / `ShowEditSnackbar` /
 *    `ShowDeleteSnackbar` — semantic equivalent, simpler surface). The
 *    rework ComplaintScreen now collects effects internally via
 *    `LaunchedEffect(viewModel)` + `effects.collectLatest`, surfaced
 *    via `Scaffold.snackbarHost` — exactly the topology forecast,
 *    refined for SRP.
 * The "One outbound link (back)" + `:composeApp`-vs-`:ui`-boundary +
 * Koin DI scope + same-Firestore-collection rationales all stand on
 * their own merits past the §293 swap + §363 retire + §252 + §§267-274
 * fulfilled-parity work. The rework ComplaintReworkScreenRoute remains
 * LIVE as the canonical renderer for `Screen.ComplaintRework` and is
 * now joined by the §293-swapped `ComplaintScreenRoute` adapter (both
 * converging on the same rework screen + VM through
 * `NavBackStackEntry`-scoped Koin instances). Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citations are historical record of the design lineage including the
 * deferral forecasts that were subsequently fulfilled.
 */
@Composable
fun ComplaintReworkScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: ComplaintViewModel = koinViewModel()
    ComplaintScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
    )
}
