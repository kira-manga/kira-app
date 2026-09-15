package me.manga.kira.presentation.complaint.admin

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by the rework admin Complaint dashboard screen.
 *
 * [ShowActionSuccess] carries the semantic action for a localized confirmation snackbar.
 * Successful mutations dismiss the dialog before emitting; body-copy also uses this effect.
 *
 * Mutation failure is [AdminComplaintState.actionFailed], not an effect: the error belongs to
 * the retained dialog's accessibility root, with the existing submit control as the only retry
 * path. List-load failure remains in [AdminComplaintState.error]. Neither state exposes raw
 * throwable text. The historical audit below predates the dialog-local failure policy.
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
}
