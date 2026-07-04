package me.manga.kira.presentation.complaint.admin

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by the rework admin Complaint dashboard screen.
 *
 * Phase 7.x.complaint.admin rework: introduced as an empty sealed interface (extensibility hook
 * for follow-on admin-actions slices). The foundation surface has no effects today — read-only
 * load/search/filter is pure state transitions; the inline-error-with-Retry pattern lives in
 * state ([AdminComplaintState.error]), not effects.
 *
 * **OCP (contract §6)**: closed under modification, open under extension. The future admin-
 * actions slice will append variants exactly like the user-side foundation → actions slice did
 * (see [me.manga.kira.presentation.complaint.ComplaintEffect]'s `ShowSuccessMessage` /
 * `ShowErrorMessage`). Anticipated extensions:
 *  - `ShowStatusChangeSuccess(message: String)` — fired after a status-change use case succeeds.
 *    Drives a confirmation snackbar.
 *  - `ShowStatusChangeError(message: String)` — fired on status-change failure.
 *  - `ShowEditSuccess(message: String)` / `ShowEditError(message: String)`.
 *  - `ShowDeleteSuccess(message: String)` / `ShowDeleteError(message: String)`.
 *  - `ShowClosureReasonSuccess(message: String)` / `ShowClosureReasonError(message: String)`.
 *  - `ShowBulkActionResult(successCount: Int, failureCount: Int)`.
 *  - `CopyToClipboard(text: String)` — for the legacy long-press body-copy affordance, if ported.
 *
 * **Why declare an empty sealed interface today rather than `Nothing`?**: same rationale as the
 * user-side foundation slice — extending `MviViewModel<S, I, Nothing>` would force a slightly
 * uglier signature and lose the OCP §6 extension hook. The empty-sealed-interface pattern is
 * the rework convention for "no effects today; reserved for tomorrow".
 *
 * **`data class` modifier rationale**: per the rework MVI contract — when variants are added,
 * they use `data class` (payload) or `data object` (payload-free) so structural equality
 * applies. The empty surface today has no variants, but future additions follow the same
 * convention.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster17.staleKdocSweep.cascade,
 * Task #473, 2026-05-28): two categories of fulfilled-prediction citations
 * appear above:
 *  - Lines 7-11 ("introduced as an empty sealed interface (extensibility
 *    hook for follow-on admin-actions slices). The foundation surface has
 *    no effects today — read-only load/search/filter is pure state
 *    transitions"): FACTUALLY INVERTED — Phase 7.x.complaint.admin.actions
 *    (§259, Task #259) materialised the effect surface; lines 36-58
 *    below define `ShowSuccessMessage` + `ShowErrorMessage` data classes.
 *    The "empty sealed interface" + "no effects today" framing is
 *    historical record of the foundation-slice initial posture; the
 *    surface is no longer empty. Mirror of §445 + §470 + §471 + §472
 *    fulfilled-deferral-inversion precedent.
 *  - Lines 14-25 (8-variant per-action OCP forecast:
 *    `ShowStatusChangeSuccess` / `ShowStatusChangeError` /
 *    `ShowEditSuccess` / `ShowEditError` / `ShowDeleteSuccess` /
 *    `ShowDeleteError` / `ShowClosureReasonSuccess` /
 *    `ShowClosureReasonError` / `ShowBulkActionResult` /
 *    `CopyToClipboard`). FULFILLED-WITH-REFINEMENT — Phase 7.x.complaint.
 *    admin.actions (§259) + §260 admin.edit + §262 admin.copy + §265
 *    admin.bulk collapsed the 8-per-action surface into 2 consolidated
 *    variants (`ShowSuccessMessage(message: String)` +
 *    `ShowErrorMessage(message: String)` at lines 51 + 58 below) plus
 *    Compose-`LocalClipboardManager` boundary handling at the `:ui`
 *    layer for body-copy (the §262 admin.copy slice keeps `OnCopyBody`
 *    intent-side; clipboard write is Compose-side; the success snackbar
 *    fires through `ShowSuccessMessage`). Semantic equivalent — `:ui`
 *    just shows a snackbar with the text; differentiating in the type
 *    would only matter if rendering varied per action (it does not).
 *    Refined for SRP. Mirror of §463 cluster7 + §471 cluster15
 *    consolidated-effect precedent.
 * The OCP §6 extensibility-hook rationale + sealed-interface-vs-Nothing
 * convention + data class/data object payload contract all stand on
 * their own merits past the §§259-260-262-265 fulfilled-deferral
 * landings. The rework AdminComplaintEffect surface remains LIVE as the
 * canonical 2-variant effect contract for the rework AdminComplaintViewModel,
 * powering the snackbar host in the rework `:ui` AdminComplaintScreen via
 * `LaunchedEffect(viewModel) { viewModel.effects.collectLatest { ... } }`.
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of the
 * design lineage including the 8-variant forecast that was subsequently
 * fulfilled-with-refinement as the 2-variant consolidated surface.
 */
sealed interface AdminComplaintEffect : MviEffect {

    // ── Phase 7.x.complaint.admin.actions ────────────────────────────────────────────────

    /**
     * Show a transient success snackbar after a successful admin mutation. The effect carries only
     * the semantic [action] (trigger data); `:ui` resolves the localized copy via `stringResource`.
     * Per the MVI contract effects never carry i18n text.
     */
    data class ShowActionSuccess(val action: AdminComplaintAction) : AdminComplaintEffect

    /**
     * Show a generic localized error snackbar after a failed admin mutation. The underlying
     * throwable is logged in the VM (never surfaced raw to the operator / leaked to a snackbar).
     */
    data object ShowActionFailure : AdminComplaintEffect
}
