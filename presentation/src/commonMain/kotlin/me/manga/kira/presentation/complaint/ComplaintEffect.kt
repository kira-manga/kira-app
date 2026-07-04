package me.manga.kira.presentation.complaint

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by the rework Feedback Manager screen.
 *
 * Phase 7.x.complaint.foundation rework: introduced as an empty sealed interface (extensibility
 * hook for follow-on slices). Phase 7.x.complaint.actions rework: appends two snackbar variants
 * ([ShowSuccessMessage] / [ShowErrorMessage]) emitted by the action-dialog flow.
 *
 * Variants:
 *  - [ShowSuccessMessage]: fired after a Reply / Edit / Delete use case returns
 *    [Result.success]. The `:ui` composable hosts a [androidx.compose.material3.SnackbarHost]
 *    and shows [message] as a non-blocking confirmation. Examples: "Reply sent",
 *    "Complaint updated", "Complaint deleted".
 *  - [ShowErrorMessage]: fired after a Reply / Edit / Delete use case returns [Result.failure].
 *    [message] is the throwable's `message` (usually a Firestore SDK string like
 *    "PERMISSION_DENIED") with class-name + literal fallbacks — same fallback chain the
 *    foundation slice's [me.manga.kira.presentation.complaint.ComplaintViewModel.loadList]
 *    uses for inline error rendering.
 *
 * **Why effects (snackbars) and not state (inline banner)**: the dialog flow is modal —
 * Reply / Edit / Delete each take ~1-2s on a happy path, and the user's attention is on the
 * dialog. Inline state would force the user to dismiss the dialog before seeing the outcome;
 * snackbars overlay the dialog (or its successor, the list) and read more naturally for
 * "fire-and-forget confirmation" UX. Same posture as legacy `ComplaintActionDialog` whose
 * success / failure feedback lives in a SnackbarHost above the dialog mount.
 *
 * **Why two distinct variants vs a single `ShowMessage(text, isError)`**: a tag on the same
 * variant would let a future bug pass an `isError = false` to a real failure or vice-versa,
 * silently. Separate types make the failure path a compile-time-distinct branch — the `:ui`
 * collector handles each case with its own snackbar styling (severity colour). Same posture as
 * the rework's existing `ShowErrorMessage` variants in other slices.
 *
 * **OCP (contract §6)**: closed under modification, open under extension. The foundation slice's
 * KDoc on the empty surface explicitly anticipated this slice's additions; a future deep-link
 * slice could add `NavigateToComplaintDetail(id: String)` without touching these variants.
 *
 * **`data class` modifier rationale**: per the rework MVI contract — sealed-interface variants
 * carrying payload use `data class` so structural equality applies (not the default
 * identity-based equality of plain `class`). The `:ui` effect collector compares emissions to
 * deduplicate snackbars within a configuration-change burst.
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

    /**
     * A user action (Reply / Edit / Delete) failed — show a generic localized error snackbar. The
     * underlying throwable is logged in the VM (never surfaced raw to the user / leaked to a snackbar).
     */
    data object ShowActionFailure : ComplaintEffect
}
