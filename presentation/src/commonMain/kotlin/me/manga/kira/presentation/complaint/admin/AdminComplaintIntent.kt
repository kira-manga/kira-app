package me.manga.kira.presentation.complaint.admin

import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.presentation.mvi.MviIntent

/**
 * Intents accepted by the rework admin Complaint dashboard screen.
 *
 * Phase 7.x.complaint.admin rework: foundation list/search/filter variants only. The legacy
 * admin screen surfaces 6 additional mutation actions (status change, edit, closure-reason,
 * delete, bulk-update, bulk-delete) plus statistics aggregation — all deferred to a future
 * `Phase 7.x.complaint.admin.actions` slice. The OCP §6 extension hook this sealed interface
 * provides means those additions slot in as sibling variants without changing the foundation
 * surface.
 *
 * Foundation variants:
 *  - [OnRetry]: re-runs the fetch after a failure. Also valid as a manual refresh — the user-
 *    facing affordance is the inline error's Retry button; a future pull-to-refresh gesture
 *    would map to the same intent.
 *  - [OnSearchChange]: user typed into the search box. Triggers a state mutation +
 *    [AdminComplaintState.filtered] recompute. Empty [query] effectively disables search
 *    (matches everything).
 *  - [OnClearSearch]: convenience — clear the search text without touching the filters. The
 *    trailing-X icon in the search box fires this. Could be modelled as `OnSearchChange("")`;
 *    kept as its own variant for clarity (and so future analytics can distinguish user-initiated
 *    clears from typing).
 *  - [OnStatusFilter]: user tapped a status chip. Triggers a state mutation +
 *    [AdminComplaintState.filtered] recompute. Pass `null` to clear the filter (the chip-row's
 *    "All" affordance + the "tap same chip twice" affordance both map to `null`).
 *  - [OnTypeFilter]: user tapped a type chip. Same semantics as [OnStatusFilter] but for the
 *    type axis.
 *
 * **Why two filter axes vs the user-side's one**: the admin foundation surface matches the
 * legacy admin screen's 2-axis filter (status + type) — see legacy
 * `AdminComplaintScreen.kt:140-141`. The user-side foundation only has status because the user-
 * side legacy screen also has only status. Parity intent, parity surface.
 *
 * **OCP (contract §6)**: sealed interface — closed under modification, open under extension.
 * Future admin-actions slice variants (e.g., `OnSelectComplaintForStatusChange(complaint)`,
 * `OnConfirmStatusChange(complaint, newStatus)`, `OnDeleteComplaint(id)`, `OnEditComplaint(...)`,
 * `OnAddClosureReason(complaint, reason)`, `OnToggleStatsCard`) slot in without breaking the
 * existing variants.
 *
 * **Why `data object` for payload-free / `data class` for payloads**: per the rework MVI contract —
 * sealed-interface variants are `data class` (when they carry payload) or `data object` (when
 * they don't). This avoids the default identity-based equality on plain `object` variants.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster17.staleKdocSweep.cascade,
 * Task #473, 2026-05-28): three categories of stale + inverted citations
 * appear above:
 *  - Lines 11-16 ("foundation list/search/filter variants only ... 6
 *    additional mutation actions ... all deferred to a future
 *    `Phase 7.x.complaint.admin.actions` slice") + Lines 40-44 (OCP-hook
 *    forecast for `OnSelectComplaintForStatusChange` / `OnConfirmStatusChange`
 *    / `OnDeleteComplaint` / `OnEditComplaint` / `OnAddClosureReason` /
 *    `OnToggleStatsCard`). FACTUALLY INVERTED — Tasks #§§259-266 shipped
 *    all 6 mutations + sort + copy + stats card + version filter +
 *    edit. The file itself enumerates the now-landed variants at lines
 *    73-182 (`OnAppVersionFilter` / `OnSortChange` / `OnRowClick` /
 *    `OnDismissActionDialog` / `OnSelectAction` / `OnSubmitStatusChange` /
 *    `OnSubmitClosureReason` / `OnConfirmDelete` / `OnSubmitEdit` /
 *    `OnCopyBody`), and the §171 admin Complaint ladder closed at rung 9
 *    (§176 — Phase 7.x.complaint.admindate, Task #272). The OCP-forecast
 *    variant names map semantically 1:1 to the landed variants (refined
 *    for SRP — e.g., `OnSelectComplaintForStatusChange` + `OnConfirmStatusChange`
 *    collapsed into `OnRowClick` + `OnSelectAction` + `OnSubmitStatusChange`
 *    state machine). Mirror of §445 + §470 + §471 + §472 fulfilled-deferral-
 *    inversion precedent.
 *  - Lines 36-38 ("Why two filter axes vs the user-side's one ... see
 *    legacy `AdminComplaintScreen.kt:140-141`"): the legacy
 *    `:composeApp/.../admin/complaint/AdminComplaintScreen.kt` was retired
 *    in Phase 9.x.admincomplaint.retire (§366 sweep, commit `48a5c2b`
 *    "(1/2): delete orphan legacy admin VM + screen + 2 helpers + drop
 *    Koin binding"); verified by filesystem check returning zero hits.
 *    The 2-axis filter (status + type) + 1-axis user-side (status) parity-
 *    intent framing stands on its own merits past the §366 retire — the
 *    rework AdminComplaintState now carries THREE filter axes (status +
 *    type + appVersion via §264 versionfilter) and the foundation parity-
 *    intent rationale remains correct (admin needs more filter axes than
 *    user-side because admin sees more complaints across more dimensions).
 *  - Line 96 ("matches legacy posture — sort runs after filter, same
 *    posture as legacy `AdminComplaintScreen.kt:198-209`"): the cited
 *    line range pointed into the §366-retired legacy admin screen.
 *    Verified by filesystem check returning zero hits. The sort-runs-
 *    after-filter posture remains correct in the rework VM (verified at
 *    [AdminComplaintViewModel] — the `recompute` helper applies filters
 *    then sorts the result). The legacy-line-range citation is historical;
 *    the rule itself is load-bearing and continues to govern recompute
 *    ordering.
 * The OCP-via-sealed-interface rationale + 2-vs-1-filter-axes parity
 * framing + sort-after-filter ordering + `data class`/`data object`
 * payload convention all stand on their own merits past the §366 retire
 * + the §§259-266 + §272 fulfilled-deferral landings. The rework
 * AdminComplaintIntent surface remains LIVE as the canonical 11-variant
 * intent contract for the rework AdminComplaintViewModel, with each
 * variant's KDoc (lines 52-182) carrying the per-variant semantic
 * contract. Original §253-era prose preserved verbatim per the audit-
 * trail-preservation convention — the citations are historical record
 * of the design lineage including the deferral forecasts that were
 * subsequently fulfilled across §§259-266.
 *
 * **GAP-CMP-20 correction (NP P3)**: prior prose in this KDoc and the
 * §265 task title claimed admin bulk multi-select "landed". It did NOT —
 * there is no bulk Intent variant here (no `OnEnterSelectionMode` /
 * `OnToggleSelection` / `OnBulkUpdateStatus` / `OnBulkDelete`), no bulk
 * State field, and no `ShowBulkActionResult` Effect. This matches the OLD
 * native target, which also never wired its `bulkUpdateStatus` /
 * `bulkDeleteComplaints` VM methods to any UI — so dropping bulk is the
 * correct parity disposition (GAP-CMP-20 RECOMMEND: REMOVE). The "+ bulk"
 * fragment was removed from the §§259-266 fulfilled-landings list above;
 * all four native admin mutations remain reachable per-complaint via the
 * row-tap MENU dialog.
 */
sealed interface AdminComplaintIntent : MviIntent {

    /** Re-run the all-complaints fetch after a failure (or for manual refresh). */
    data object OnRetry : AdminComplaintIntent

    /**
     * GAP-CMP-15 — user tapped the TopAppBar show/hide-statistics toggle. Flips
     * [me.manga.kira.presentation.complaint.admin.AdminComplaintState.showStats]. Mirrors the
     * native admin Visibility/VisibilityOff IconButton that gates the StatisticsCard.
     */
    data object OnToggleStatsCard : AdminComplaintIntent

    /** User typed [query] into the search box. */
    data class OnSearchChange(val query: String) : AdminComplaintIntent

    /** User tapped the trailing-X in the search box (clears the search text, keeps the filters). */
    data object OnClearSearch : AdminComplaintIntent

    /**
     * User tapped a status chip. Pass `null` to clear the status filter; pass a
     * [ComplaintStatus] to filter to that status.
     */
    data class OnStatusFilter(val status: ComplaintStatus?) : AdminComplaintIntent

    /**
     * User tapped a type chip. Pass `null` to clear the type filter; pass a [ComplaintType] to
     * filter to that type.
     */
    data class OnTypeFilter(val type: ComplaintType?) : AdminComplaintIntent

    // ── Phase 7.x.complaint.admin.versionfilter ──────────────────────────────────────────

    /**
     * User tapped an app-version chip. Pass `null` to clear the version filter; pass a non-empty
     * `String` to filter to complaints whose `appVersion` matches exactly.
     *
     * Semantics: when [appVersion] is non-null, complaints with `appVersion == null` are
     * EXCLUDED from [me.manga.kira.presentation.complaint.admin.AdminComplaintState.filtered]
     * (matches legacy posture — the "All" chip is the only path to see null-version complaints).
     * Single-select with toggle-off — tapping the same chip again passes `null` to clear.
     *
     * Chip list is derived at the `:ui` boundary from
     * `state.all.mapNotNull { it.appVersion }.distinct().sorted()` — only versions actually
     * present in the dataset render as chips. No risk of a "filter to a version that doesn't
     * exist" UX dead-end.
     */
    data class OnAppVersionFilter(val appVersion: String?) : AdminComplaintIntent

    // ── Phase 7.x.complaint.admin.sort ───────────────────────────────────────────────────

    /**
     * User selected a sort mode from the sort dropdown. Triggers a state mutation +
     * [me.manga.kira.presentation.complaint.admin.AdminComplaintState.filtered] recompute
     * (sort runs after filter, same posture as legacy `AdminComplaintScreen.kt:198-209`).
     *
     * Single-select — there is no "null = unsorted" intent because sort is always active. The
     * default sort ([AdminSortMode.DATE_DESC]) IS the unsorted-feeling option. Users pick a
     * different mode to change.
     */
    data class OnSortChange(val mode: AdminSortMode) : AdminComplaintIntent

    // ── Phase 7.x.complaint.admin.actions ────────────────────────────────────────────────

    /**
     * User tapped a complaint row. Opens the admin action menu for [complaint].
     *
     * Gated by [me.manga.kira.presentation.complaint.admin.AdminComplaintState.isSubmittingAction] — re-entry during an in-flight
     * mutation is silently dropped.
     */
    data class OnRowClick(val complaint: ComplaintSummary) : AdminComplaintIntent

    /**
     * User dismissed the action dialog (tapped scrim / pressed back).
     *
     * Gated by [me.manga.kira.presentation.complaint.admin.AdminComplaintState.isSubmittingAction] — dismiss during an in-flight
     * mutation is silently dropped so the user can't lose feedback on a pending write.
     */
    data object OnDismissActionDialog : AdminComplaintIntent

    /**
     * User selected an action from the action menu. Transitions [me.manga.kira.presentation.complaint.admin.AdminComplaintState.actionDialogMode]
     * from [me.manga.kira.presentation.complaint.admin.AdminActionDialogMode.MENU] to the corresponding mode.
     *
     * Gated by [me.manga.kira.presentation.complaint.admin.AdminComplaintState.isSubmittingAction] and by a non-null
     * [me.manga.kira.presentation.complaint.admin.AdminComplaintState.activeComplaint] — silently dropped otherwise.
     */
    data class OnSelectAction(val mode: AdminActionDialogMode) : AdminComplaintIntent

    /**
     * User submitted a new status from the status-change dialog. Fires
     * `ChangeComplaintStatusUseCase`. On success, snackbar + dialog close + list refire. On
     * failure, snackbar + dialog stays open.
     */
    data class OnSubmitStatusChange(val newStatus: ComplaintStatus) : AdminComplaintIntent

    /**
     * User submitted a closure reason from the closure-reason dialog. Fires
     * `AddClosureReasonUseCase`. The repository auto-CLOSES the complaint when its status is
     * OPEN or IN_PROGRESS.
     */
    data class OnSubmitClosureReason(val reason: String) : AdminComplaintIntent

    /**
     * User confirmed the delete in the confirmation dialog. Fires `AdminDeleteComplaintUseCase`.
     */
    data object OnConfirmDelete : AdminComplaintIntent

    // ── Phase 7.x.complaint.admin.edit ───────────────────────────────────────────────────

    /**
     * User submitted an edit (new type + subject + body) from the edit dialog. Fires
     * `AdminEditComplaintUseCase`. The repository applies the new [type] and preserves
     * `userId` / `createdAt` / `status` / `metadata` across the write (closure-reason audit
     * fields survive admin edits).
     *
     * The [type] is admin-mutable — native parity with the `EditComplaintDialog` Type dropdown.
     *
     * Validation: the [me.manga.kira.ui.complaint.admin.AdminComplaintActionDialog] enforces
     * non-blank subject + non-blank body + ≤ 1000 char body before enabling Save. The VM does
     * NOT re-validate (no defense-in-depth — would diverge from the user-side posture).
     *
     * On success: snackbar + dialog close + list refire. On failure: snackbar + dialog stays
     * open (same as the other admin mutations).
     */
    data class OnSubmitEdit(
        val type: ComplaintType,
        val subject: String,
        val body: String,
    ) : AdminComplaintIntent

    // ── Phase 7.x.complaint.admin.copy ───────────────────────────────────────────────────

    /**
     * User long-pressed a complaint row's body text. Triggers a snackbar emission confirming the
     * clipboard write (`"Copied to clipboard"`).
     *
     * **Payload-free**: the clipboard write itself happens at the `:ui` boundary
     * (Compose composition local — `LocalClipboardManager.current`); the intent's sole purpose is
     * to centralize the snackbar emission via the VM, matching the posture of the other admin
     * mutations' success snackbars. The legacy admin's body literal is captured at the row
     * composable, not passed through state — so no payload is needed here.
     *
     * **Not gated by [me.manga.kira.presentation.complaint.admin.AdminComplaintState.isSubmittingAction]**:
     * copy is a passive, non-destructive read. Allowing it during a pending mutation is harmless
     * and matches legacy ergonomics.
     */
    data object OnCopyBody : AdminComplaintIntent
}
