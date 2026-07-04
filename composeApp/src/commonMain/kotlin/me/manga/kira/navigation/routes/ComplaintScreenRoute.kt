package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.complaint.ComplaintViewModel
import me.manga.kira.ui.complaint.ComplaintScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the [Screen.Complaint] nav entry — **Phase 7.x.complaint.swap**.
 *
 * **What changed in this slice**: this adapter no longer renders the legacy
 * `:composeApp/.../features/complaint/ui/screens/ComplaintScreen.kt` composable backed by the
 * legacy `me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel` +
 * `ToastShower`. It now renders the architecture-rework
 * [me.manga.kira.ui.complaint.ComplaintScreen] backed by
 * [me.manga.kira.presentation.complaint.ComplaintViewModel] (Koin-bound via
 * `complaintReworkModule`). The `Screen.Complaint` route entry stays the same shape
 * (`object Complaint`), so the existing caller stays working without modification:
 *  - `:composeApp/.../features/settings/ui/screens/SettingsScreen.kt:276` (legacy Settings hub's
 *    "Feedback" row — branches admin → `Screen.ComplaintAdmin`; non-admin → `Screen.Complaint`).
 *
 * Both this route and the parallel [Screen.ComplaintRework] route now converge on the same rework
 * screen + rework VM through different `NavBackStackEntry`-scoped VM instances (see
 * **Koin lifecycles** below).
 *
 * **Pre-conditions met by Phase 7.x.complaint.*** slices (Tasks #252 + #267-#274)**: the rework
 * Complaint slice shipped the full feature surface — Loading / Error / Empty / Loaded states,
 * top-bar back affordance (via `ComplaintIcons.ComplaintArrowBack` inline ImageVector), search
 * text field with leading magnifier + trailing clear glyph, status filter chips (ALL / OPEN /
 * RESPONDED / CLOSED / PINNED), results-count row, [ComplaintRow]s with status chip + createdAt
 * timestamp + long-press body-copy (via `combinedClickable` → `ComplaintIntent.OnCopyBody` →
 * `ComplaintEffect.ShowSuccessMessage("Copied to clipboard")` → snackbar — parity with legacy's
 * Toast feedback), [ComplaintActionDialog] mounted when `state.actionDialogMode != NONE` with 4
 * sub-panels (Menu / Reply / Edit / Delete) and status-gated Edit/Delete affordances (hidden for
 * PINNED records), `ComplaintEffect.ShowSuccessMessage` / `ShowErrorMessage` snackbars, and
 * admin-pinned "top FAQ" entries (composed at `:data` via `PinnedComplaints.PINNED_COMPLAINTS`
 * prepended in `ComplaintListRepositoryImpl.loadUserComplaints` — flows through `state.all` and
 * participates in search + status-filter exactly like Firestore-backed records).
 *
 * **Affordance parity vs the legacy adapter**:
 *  - **Initial load** — legacy adapter calls `LaunchedEffect(Unit) { viewModel.loadAll() }` from
 *    the route adapter (delta #1 in the legacy KDoc); the rework VM auto-loads in its `init {}`
 *    block (see `ComplaintViewModel.init`). Identical observable outcome — the list loads as
 *    soon as the screen mounts. Same posture as the rework Language / Statistics / History VMs
 *    that auto-load in `init {}`.
 *  - **Retry** — legacy `onRetry = { viewModel.loadAll() }`; rework dispatches
 *    `ComplaintIntent.OnRetry` from the error-state's Retry button (internal to the screen).
 *  - **Reply / Edit / Delete** — legacy adapter wires three callbacks
 *    (`onReplyComplaint(parent, replyComplaint)` → `viewModel.sendComplaint(...)`;
 *    `onEditComplaint(complaint, editedText)` → `viewModel.updateComplaint(...)`;
 *    `onDeleteComplaint(complaint)` → `viewModel.deleteComplaint(...)`). Rework dispatches
 *    `ComplaintIntent.OnSubmitReply(body)` / `OnSubmitEdit(subject, body)` / `OnConfirmDelete`
 *    (with `activeComplaint` carried in `ComplaintState`) — VM owns the in-flight guard
 *    (`isSubmittingAction`) and routes the use-case `Result` through `completeAction()` (dismiss
 *    + success snackbar + refire `loadList()` on success; keep dialog + error snackbar on
 *    failure). Same observable outcome as legacy (legacy also reloads after each successful
 *    mutation via `loadForUser`).
 *  - **Toast feedback** — legacy `onShowMessage(message) = toastShower.showShort(message)` (delta
 *    #2 in the legacy KDoc; routed through the `ToastShower` expect/actual — Android `Toast`,
 *    iOS `UIAlertController`, Desktop tray notification). Rework `ComplaintEffect.ShowSuccessMessage`
 *    / `ShowErrorMessage` is collected by the rework screen's `LaunchedEffect(viewModel)`
 *    `effects.collectLatest` block and surfaced via `Scaffold.snackbarHost`. Visual delta
 *    (Toast vs Snackbar — same Toast-vs-Snackbar swap posture as the rest of the rework slices).
 *    The "Complaint X copied to clipboard" toast on row long-press becomes "Copied to clipboard"
 *    via `ComplaintIntent.OnCopyBody` → `ShowSuccessMessage`.
 *  - **`onHelp` (403 branch)** — legacy adapter's `onHelp` callback was a `TODO Phase 10.x` to
 *    wire `IntentLauncher.openUrl(...)` on Forbidden 403 errors (delta #3 in the legacy KDoc);
 *    the rework drops the callback because the help-URL launcher is still un-finalised. NOT a
 *    regression — neither route opens a help URL today; both surface a generic error message
 *    instead. Phase 10.x will lift the help-URL strategy across both surfaces in one pass.
 *  - **Back** — legacy `onBackClick = { navController.safePopBackStack() }` (delta #4 in the
 *    legacy KDoc — `safePopBackStack` defensive fallback pops to Library if back stack is empty).
 *    Rework passes the same `onBack = { navController.safePopBackStack() }` callback to the
 *    rework screen, which renders it as a labelled top-bar back affordance with the
 *    `ComplaintIcons.ComplaintArrowBack` inline ImageVector. Same posture as the legacy adapter
 *    — the defensive fallback is preserved verbatim (route is reached from Settings push, parent
 *    stays on stack — fallback unreachable in normal use, kept for safety per delta #4).
 *
 * **Visual delta vs the legacy**: the legacy Complaint screen uses
 * `compose.materialIconsExtended` icons throughout (back-arrow / search-magnifier / clear-X /
 * help-question-mark / reply / edit / delete glyphs). The rework substitutes inline `ImageVector`
 * paths from `ComplaintIcons` for the four screen-level glyphs (back / search / clear /
 * no-matches placeholder) — restored in Phase 7.x.complaint.iconparity (Task #271). Dialog sub-
 * panel affordances stay labelled `TextButton` / `Button` (icon-free posture preserved per the
 * iconparity slice's deliberate scope — adding glyphs to labelled buttons introduces visual noise
 * without parity benefit). No affordance loss — every legacy clickable affordance has a labelled
 * or glyph-decorated rework counterpart.
 *
 * **Layer-boundary preservation**: same posture as [RepoSettingsScreenRoute] (Phase
 * 7.x.reposettings.swap, §123) / [StatisticsScreenRoute] (Phase 7.x.statistics.swap, §124) /
 * [HistoryScreenRoute] (Phase 7.x.history.swap, §125) / [WhatsNewScreenRoute] (Phase
 * 7.x.whatsnew.swap, §126) / [LanguageScreenRoute] (Phase 7.x.language.swap, §127). `:ui`
 * deliberately does NOT depend on `androidx.navigation` (which is `:composeApp`-level wiring);
 * the rework ComplaintScreen exposes only a generic `onBack: () -> Unit` callback so the screen
 * stays nav-host-agnostic. The route adapter at this layer is consequently the thinnest possible
 * — VM resolution + screen call with the back lambda.
 *
 * **Back-press delta vs the legacy adapter**: identical. Legacy passed
 * `onBackClick = { navController.safePopBackStack() }`; rework passes
 * `onBack = { navController.safePopBackStack() }`. The
 * [me.manga.kira.navigation.safePopBackStack] defensive fallback (pop to Library if back
 * stack is empty) is preserved verbatim — unlike §123.5 / §124.5 / §125.5 / §126.5 / §127's
 * other-swap pattern of dropping the fallback (because their rework screens have no
 * `navigationIcon`), the Complaint rework screen DOES render a top-bar back affordance, so the
 * callback must wire through and the fallback stays useful.
 *
 * **MVI surface**: the rework's [ComplaintViewModel] exposes a single
 * `StateFlow<ComplaintState>` (carrying `all: List<ComplaintSummary>` +
 * `filtered: List<ComplaintSummary>` + `searchQuery: String` + `selectedStatus: ComplaintStatus?`
 * + `isLoading: Boolean` + `error: String?` + `activeComplaint: ComplaintSummary?` +
 * `actionDialogMode: ActionDialogMode` + `isSubmittingAction: Boolean`) vs the legacy VM's
 * `allComplaints: StateFlow<UiState<List<Complaint>>>` + direct `sendComplaint(...)` /
 * `updateComplaint(...)` / `deleteComplaint(...)` methods. All mutations flow through
 * `ComplaintIntent` (11 variants — `OnRetry` / `OnSearchChange` / `OnStatusFilter` /
 * `OnClearSearch` / `OnRowClick` / `OnDismissActionDialog` / `OnSelectAction` / `OnSubmitReply`
 * / `OnSubmitEdit` / `OnConfirmDelete` / `OnCopyBody`). One-shot effects
 * (`ComplaintEffect.ShowSuccessMessage` / `ShowErrorMessage`) are collected internally by the
 * rework ComplaintScreen and surfaced as snackbars; the route adapter does not collect effects
 * (the bridge lives inside the screen).
 *
 * **Koin lifecycles** — note that the [ComplaintViewModel] resolved here via [koinViewModel] and
 * the one resolved by [ComplaintReworkScreenRoute] are scoped to their respective
 * NavBackStackEntry (the `ViewModelStoreOwner` integration provided by
 * `androidx.lifecycle.viewmodel.compose`). So even though both routes ultimately render the
 * same screen and rely on the same Koin binding, each route's VM instance is independent. The
 * underlying repository
 * ([me.manga.kira.domain.repository.complaint.ComplaintListRepository] — `single`-scoped via
 * `complaintReworkModule`) is shared across both, AND the legacy Firestore `complaints`
 * collection is the cell of truth — any submission via the legacy `sendComplaint` path or the
 * rework Language slice's Request-a-language pipeline or this route's Reply/Edit/Delete dialog
 * lands in the same collection. Both routes reload the list after mutations (legacy via
 * `loadForUser`, rework via the `loadList()` refire in `completeAction()`), so the surfaces
 * agree across routes for the same user data. Mutations on either route surface on the other on
 * next list-reload.
 *
 * The legacy composable file itself
 * (`composeApp/.../features/complaint/ui/screens/ComplaintScreen.kt`) plus the legacy
 * `me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel` plus the
 * `ComplaintActionDialog` / `ComplaintCard` legacy helpers are no longer user-reachable through
 * this adapter, but stay on disk until **Phase 9.x route-swap retirement sweep** retires them in
 * a coordinated deletion sweep across all retired legacy screens.
 *
 * @param navController parent nav controller — used for the `safePopBackStack()` back affordance.
 * @param backStackEntry passed through for parity with sibling route adapters; unused (the VM is
 *                      `koinViewModel()`-scoped via Koin's ViewModelStoreOwner integration).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster7.staleKdocSweep.cascade,
 * Task #463, 2026-05-28): multiple stale citations into retired legacy
 * surfaces appear above:
 *  - Lines 15-18 ("What changed in this slice" opener): "this adapter no
 *    longer renders the legacy
 *    `:composeApp/.../features/complaint/ui/screens/ComplaintScreen.kt`
 *    composable backed by the legacy
 *    `me.manga.kira.presentation.features.complaint.viewmodel.
 *    ComplaintViewModel` + `ToastShower`".
 *  - Line 23 (live-callers bullet): "`:composeApp/.../features/settings/
 *    ui/screens/SettingsScreen.kt:276`".
 *  - Lines 44-67 (affordance-parity bullets — multiple cross-references
 *    to "legacy adapter" / "legacy `onShowMessage`" / "legacy
 *    `onBackClick`" / "legacy KDoc" — all describing the now-retired
 *    legacy adapter and its surrounding screen + VM trio).
 *  - Lines 140-145 ("legacy composable file" forecast): "The legacy
 *    composable file itself
 *    (`composeApp/.../features/complaint/ui/screens/ComplaintScreen.kt`)
 *    plus the legacy
 *    `me.manga.kira.presentation.features.complaint.viewmodel.
 *    ComplaintViewModel` plus the `ComplaintActionDialog` / `ComplaintCard`
 *    legacy helpers are no longer user-reachable through this adapter,
 *    but stay on disk until **Phase 9.x route-swap retirement sweep**
 *    retires them in a coordinated deletion sweep across all retired
 *    legacy screens".
 * The legacy `:composeApp/.../features/complaint/ui/screens/
 * ComplaintScreen.kt` + `ComplaintActionDialog` / `ComplaintCard` helpers
 * were retired in Phase 9.x.complaint.legacyui.retire (§355 sweep, commit
 * `bfea508` "(1/2): drop 5-file unreachable legacy Complaint chain");
 * the legacy `:shared/.../features/complaint/viewmodel/ComplaintViewModel.kt`
 * was retired in Phase 9.x.complaintvm.retire (§363 sweep, commit
 * `e2af0d4` "(1/2): delete unreachable :shared ComplaintViewModel"); the
 * legacy `composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 * was retired in Phase 9.x.settings.legacy_retire (§354 sweep, commit
 * `5cc42d2` "(1/2): delete 5 orphan settings UI files"); all verified by
 * filesystem checks returning zero hits for those paths. The line 140-145
 * forecast was a fulfilled prediction — the "Phase 9.x route-swap
 * retirement sweep" coordinated deletion materialised exactly as
 * anticipated (across §355 + §363 + §354 sweeps). The rework-screen-
 * canonical rationale (Settings hub now routes to this adapter; rework
 * VM auto-loads in init; intent-driven mutations + effect-driven
 * snackbars; same Firestore collection cell-of-truth across legacy and
 * rework) all stand on their own merits — the rework ComplaintScreen
 * remains LIVE as the SOLE user-side Complaint surface, documented
 * inline above and via the §§251-252 + §§267-274 KDocs, independent of
 * which legacy file originally implemented the equivalent flows.
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of the
 * design lineage; the rework ComplaintScreenRoute continues to surface
 * the documented affordances through all three retires.
 */
@Composable
fun ComplaintScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: ComplaintViewModel = koinViewModel()
    ComplaintScreen(
        viewModel = viewModel,
        onBack = { navController.safePopBackStack() },
    )
}
