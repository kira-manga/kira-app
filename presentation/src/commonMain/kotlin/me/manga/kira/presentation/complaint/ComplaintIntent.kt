package me.manga.kira.presentation.complaint

import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.presentation.mvi.MviIntent

/**
 * Intents accepted by the rework Feedback Manager screen.
 *
 * Phase 7.x.complaint.foundation rework: foundation list/search/filter variants.
 * Phase 7.x.complaint.actions rework: action-dialog variants (OnRowClick, OnDismissActionDialog,
 *   OnSelectAction, OnSubmitReply, OnSubmitEdit, OnConfirmDelete).
 * Phase 7.x.complaint.usercopy rework: long-press body-copy variant ([OnCopyBody]) —
 *   mirrors `AdminComplaintIntent.OnCopyBody` from Phase 7.x.complaint.admin.copy.
 *
 * Foundation variants:
 *  - [OnRetry]: re-runs the fetch after a failure. Also valid as a manual refresh — the user-
 *    facing affordance is the inline error's Retry button, but if a future slice surfaces a
 *    pull-to-refresh gesture, the same intent handles it.
 *  - [OnSearchChange]: user typed into the search box. Triggers a state mutation +
 *    [ComplaintState.filtered] recompute. Empty [query] effectively disables search (matches
 *    everything).
 *  - [OnStatusFilter]: user tapped a status chip. Triggers a state mutation +
 *    [ComplaintState.filtered] recompute. Pass `null` to clear the filter (the chip-row's
 *    "All" affordance + the "tap same chip twice" affordance both map to `null`).
 *  - [OnClearSearch]: convenience — clear the search text without touching the status filter.
 *    The trailing-X icon in the search box fires this. Could be modelled as
 *    `OnSearchChange("")`; kept as its own variant for clarity (and so future analytics can
 *    distinguish user-initiated clears from typing).
 *
 * Action-dialog variants:
 *  - [OnRowClick]: user tapped a complaint row. The VM opens the dialog at
 *    [ActionDialogMode.MENU] and stores the tapped record as [ComplaintState.activeComplaint].
 *  - [OnDismissActionDialog]: user pressed back or tapped outside the dialog. The VM clears
 *    [ComplaintState.actionDialogMode] back to [ActionDialogMode.NONE] and
 *    [ComplaintState.activeComplaint] back to null.
 *  - [OnSelectAction]: user picked a sub-mode (Reply / Edit / Delete) from the menu, OR
 *    navigated back to the menu from a sub-mode. Transitions
 *    [ComplaintState.actionDialogMode] to the supplied [mode] (must not be
 *    [ActionDialogMode.NONE] — use [OnDismissActionDialog] for that).
 *  - [OnSubmitReply]: user pressed Send on the Reply panel. Triggers
 *    [me.manga.kira.domain.usecase.complaint.ReplyToComplaintUseCase] against
 *    [ComplaintState.activeComplaint]. On success: dismiss dialog, emit success effect,
 *    refire `loadList()`. On failure: keep dialog open at REPLY, emit error effect.
 *  - [OnSubmitEdit]: user pressed Save on the Edit panel. Triggers
 *    [me.manga.kira.domain.usecase.complaint.EditComplaintUseCase]. Same success/failure
 *    flow as [OnSubmitReply].
 *  - [OnConfirmDelete]: user pressed Delete-forever on the Delete panel. Triggers
 *    [me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase] against
 *    [ComplaintState.activeComplaint.id]. Same success/failure flow.
 *
 * **OCP (contract §6)**: sealed interface — closed under modification, open under extension.
 * The foundation slice's KDoc explicitly anticipated these action variants as "sibling
 * additions without touching the existing variants" — the actions slice is exactly that.
 *
 * **Why all use `data` modifiers**: per the rework MVI contract — sealed-interface variants are
 * `data class` (when they carry payload) or `data object` (when they don't). This avoids the
 * default identity-based equality on plain `object` variants.
 *
 * **Why `OnSelectAction` instead of separate OnPickReply/OnPickEdit/OnPickDelete/OnBackToMenu**:
 * the four payload-free transitions all do the same VM-level work (change a state field). A
 * single intent with an enum payload keeps the surface small and lets the `:ui` dialog dispatch
 * `OnSelectAction(ActionDialogMode.MENU)` for both "user picked Menu from a sub-mode's back
 * button" and the (currently no-op but reserved) "VM internally returns to menu".
 *
 * **Audit-trail postscript** (Phase 9.x.cluster105.staleKdocSweep.cascade,
 * Task #561, 2026-05-28): the file-scope intent-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-fifth sibling of the cluster57-104 sweep —
 * sibling of cluster105 ThemeEffect.kt plus ThemeState.kt plus Theme-
 * ViewModel.kt plus LibraryEffect.kt):
 *  (a) "Phase 7.x.complaint.foundation rework: foundation list/search/
 *  filter variants. Phase 7.x.complaint.actions rework: action-dialog
 *  variants. Phase 7.x.complaint.usercopy rework: long-press body-copy
 *  variant ([OnCopyBody])" — LIVE-NOT-STALE. Recursive count of variants
 *  L66-125 confirms 11 sealed variants exactly: 4 foundation (OnRetry,
 *  OnSearchChange, OnStatusFilter, OnClearSearch) plus 6 actions
 *  (OnRowClick, OnDismissActionDialog, OnSelectAction, OnSubmitReply,
 *  OnSubmitEdit, OnConfirmDelete) plus 1 usercopy (OnCopyBody).
 *  (b) "Foundation variants" individual rationale (OnRetry as manual
 *  refresh, OnSearchChange empty-disables-search, OnStatusFilter null-
 *  clears-filter, OnClearSearch as convenience variant) — LIVE-NOT-
 *  STALE. ComplaintViewModel.kt `handle` branches verified at cluster30
 *  sweep (Task #486) all four foundation variants route to expected
 *  reducer paths.
 *  (c) "Action-dialog variants" individual rationale (OnRowClick opens
 *  MENU mode, OnDismissActionDialog clears to NONE plus null active,
 *  OnSelectAction transitions modes excluding NONE, OnSubmitReply/Edit/
 *  ConfirmDelete drive use cases) — LIVE-NOT-STALE. ComplaintViewModel.
 *  kt `handle` branches plus ComplaintState.kt activeComplaint plus
 *  actionDialogMode field-shape verified at cluster30 plus cluster31
 *  surveys.
 *  (d) "OCP (contract §6): sealed interface — closed under modification,
 *  open under extension. The foundation slice's KDoc explicitly antici-
 *  pated these action variants as `sibling additions without touching
 *  the existing variants` — the actions slice is exactly that" — LIVE-
 *  NOT-STALE. The two-phase extension lineage (foundation §251 rename-to
 *  actions §252 rename-to usercopy §274) preserved verbatim — no
 *  rewrites to prior foundation variants when actions plus usercopy
 *  variants landed.
 *  (e) "Why all use `data` modifiers" rationale — LIVE-NOT-STALE. Count
 *  of `data class` plus `data object` declarations confirms all 11
 *  variants use `data`; no plain `object` slipped in across the three-
 *  slice landing.
 *  (f) "Why `OnSelectAction` instead of separate OnPickReply/OnPickEdit/
 *  OnPickDelete/OnBackToMenu" rationale — LIVE-NOT-STALE. Single
 *  `data class OnSelectAction(val mode: ActionDialogMode)` variant
 *  realization at L95; no per-mode variant duplication; the enum-
 *  payload posture preserved.
 *  Six classifications STAND on their own merits as a faithful
 *  ComplaintIntent surface manifest. Original Phase 7.x.complaint-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface ComplaintIntent : MviIntent {

    /** Re-run the user-complaints fetch after a failure (or for manual refresh). */
    data object OnRetry : ComplaintIntent

    /** User typed [query] into the search box. */
    data class OnSearchChange(val query: String) : ComplaintIntent

    /**
     * User tapped a status chip. Pass `null` to clear the status filter; pass a
     * [ComplaintStatus] to filter to that status.
     */
    data class OnStatusFilter(val status: ComplaintStatus?) : ComplaintIntent

    /** User tapped the trailing-X in the search box (clears the search text, keeps the filter). */
    data object OnClearSearch : ComplaintIntent

    /** User tapped a complaint row — open the action dialog at MENU mode. */
    data class OnRowClick(val complaint: ComplaintSummary) : ComplaintIntent

    /** User dismissed the action dialog (back press, outside tap). */
    data object OnDismissActionDialog : ComplaintIntent

    /**
     * User picked a sub-mode within the action dialog. Valid values are
     * [ActionDialogMode.MENU], [ActionDialogMode.REPLY], [ActionDialogMode.EDIT], or
     * [ActionDialogMode.DELETE] — DO NOT pass [ActionDialogMode.NONE]; use
     * [OnDismissActionDialog] for dismissal.
     */
    data class OnSelectAction(val mode: ActionDialogMode) : ComplaintIntent

    /** User pressed Send on the Reply panel with the typed [body]. */
    data class OnSubmitReply(val body: String) : ComplaintIntent

    /** User pressed Save on the Edit panel with the new [subject] and [body]. */
    data class OnSubmitEdit(val subject: String, val body: String) : ComplaintIntent

    /** User confirmed deletion on the Delete panel. */
    data object OnConfirmDelete : ComplaintIntent

    // ── Phase 7.x.complaint.usercopy ─────────────────────────────────────────────────────

    /**
     * User long-pressed a complaint row's body text. Triggers a snackbar emission confirming the
     * clipboard write (`"Copied to clipboard"`).
     *
     * **Payload-free**: the clipboard write itself happens at the `:ui` boundary (Compose
     * composition local — `LocalClipboardManager.current`); the intent's sole purpose is to
     * centralise the snackbar emission via the VM, matching the admin-side posture from
     * `AdminComplaintIntent.OnCopyBody` (Phase 7.x.complaint.admin.copy). The legacy user-side
     * does not long-press-copy bodies (only admin does); the rework adds this as a usability
     * parity improvement WITH admin — the user-side `ComplaintRow` body is now equally
     * copyable.
     *
     * **Not gated by [me.manga.kira.presentation.complaint.ComplaintState.isSubmittingAction]**:
     * copy is a passive, non-destructive read. Allowing it during a pending mutation is harmless
     * and matches the admin-side ergonomics.
     */
    data object OnCopyBody : ComplaintIntent
}
