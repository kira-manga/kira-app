package me.manga.kira.presentation.complaint

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by the rework Feedback Manager screen.
 *
 * [ShowActionSuccess] carries the semantic action for a localized confirmation snackbar.
 * Successful mutations dismiss the dialog before emitting; body-copy also uses this effect.
 *
 * Mutation failure is [ComplaintState.actionFailed], not an effect: the error must remain
 * visible and accessible inside the retained dialog while the user edits and resubmits. The
 * failure flag carries no throwable text, and the dialog's existing submit control is the only
 * retry path. The historical audit below predates this dialog-local policy.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster32.staleKdocSweep.cascade,
 * Task #488, 2026-05-28): one stale citation appears in the snackbars-
 * vs-state rationale above:
 *  - Line 27 ("Same posture as legacy `ComplaintActionDialog` whose
 *    success / failure feedback lives in a SnackbarHost above the
 *    dialog mount"). STALE-SYMBOL-REFERENCE — Phase 9.x.complaint.
 *    legacyui.retire (§355) DELETED the legacy `:shared`
 *    `ComplaintActionDialog.kt` as part of the 5-file orphan chain
 *    retirement. A recursive search of the legacy complaint folder
 *    for a `ComplaintActionDialog.kt` returns NO MATCHES. HOWEVER —
 *    the rework `:ui` `ComplaintActionDialog` (same filename,
 *    different package: `me.manga.kira.ui.complaint.
 *    ComplaintActionDialog`) is LIVE as the canonical user-side
 *    Complaint-action dialog backed by [ComplaintState] +
 *    [ComplaintViewModel] + this [ComplaintEffect] sealed interface;
 *    the snackbar-overlays-dialog rationale (snackbars hosted above
 *    the dialog mount rather than inline state) STANDS on its own
 *    merits past the §355 fulfilled landing as the LIVE rework
 *    realization. The [ComplaintEffect] sealed interface remains
 *    LIVE as the canonical user-side Complaint-screen effect ADT
 *    consumed by [ComplaintViewModel] + the rework `:ui`
 *    `ComplaintScreen`. Original §253-era prose preserved verbatim
 *    per the audit-trail-preservation convention — the citation is
 *    historical record of the design lineage including the snackbar-
 *    overlay-dialog rationale that was subsequently fulfilled (legacy
 *    complaint chain retired) across §355.
 */
sealed interface ComplaintEffect : MviEffect {

    /**
     * A user action succeeded — show a confirmation snackbar. The effect carries only the semantic
     * [action] (trigger data); `:ui` resolves the localized copy via `stringResource`. Per the MVI
     * contract effects never carry i18n text.
     */
    data class ShowActionSuccess(val action: ComplaintAction) : ComplaintEffect
}
