package me.manga.kira.presentation.downloads

import me.manga.kira.presentation.mvi.MviEffect

/**
 * Downloads screen MVI effects.
 *
 * Phase 7.x.downloads.foundation rework introduced this as an empty
 * sealed interface (extensibility hook). Phase 7.x.downloads.actions
 * rework appends [ShowError] — fired after a retry / cancel / delete /
 * cancelRunning use case returns [Result.failure]. The `:ui` composable
 * hosts a [androidx.compose.material3.SnackbarHost] and shows [message]
 * as a non-blocking error notification.
 *
 * **Why a single `ShowError` variant** (not separate `ShowSuccess` +
 * `ShowError` like
 * [me.manga.kira.presentation.complaint.ComplaintEffect]): the
 * Downloads mutations have no user-facing success message — the list
 * itself re-renders (Room re-emits, the row state changes to e.g.
 * QUEUED on retry, vanishes on delete). A success snackbar would be
 * redundant chrome. Failures, on the other hand, leave the row visually
 * unchanged (the legacy `DownloadRepository` swallowed worker-enqueue
 * exceptions silently, which the rework explicitly surfaces) — without
 * a snackbar the user has no way to know the tap had no effect.
 *
 * **`message` content**: the throwable's `message` field (e.g.,
 * "download row not found" from
 * [me.manga.kira.data.repository.DownloadsActionRepositoryImpl.retryDownload]
 * when the row was deleted between observe and retry, or a Room write
 * failure / WorkManager enqueue rejection / Ktor connectivity error).
 * Falls back to the throwable's class name then to "Unknown error" if
 * `message` is null — same fallback chain as the rework's other
 * `ShowError`-emitting VMs (Complaint, Feedback).
 *
 * **OCP (contract §6)**: closed under modification, open under
 * extension. Future variants (e.g., `RequestConfirmation(chapterTitle:
 * String)` if the rework later adds a "Delete N rows?" dialog) slot in
 * as new `data class` cases without changing this variant.
 *
 * **`data class` modifier**: sealed-interface variant carrying payload
 * — `data class` so structural equality applies and the `:ui` effect
 * collector can deduplicate emissions within a configuration-change
 * burst. Same posture as
 * [me.manga.kira.presentation.complaint.ComplaintEffect.ShowErrorMessage].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster107.staleKdocSweep.cascade,
 * Task #563, 2026-05-28): the file-scope effect-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-seventh sibling of the cluster57-106 sweep —
 * opens the wave-9 `:presentation/downloads/` batch alongside Downloads-
 * Intent.kt plus DownloadsViewModel.kt):
 *  (a) "Phase 7.x.downloads.foundation rework introduced this as an
 *  empty sealed interface; Phase 7.x.downloads.actions rework appends
 *  [ShowError]" — LIVE-NOT-STALE. L46-54 sealed interface declares
 *  exactly one variant (ShowError data class with `message: String`
 *  payload); the foundation-to-actions append posture preserved
 *  verbatim — no rewrites to the foundation declaration when the
 *  actions slice landed.
 *  (b) "Why a single `ShowError` variant (not separate ShowSuccess plus
 *  ShowError like ComplaintEffect)" rationale — LIVE-NOT-STALE. The
 *  Room-re-emits-on-write success-path posture preserved; Downloads-
 *  ViewModel.kt L37-40 KDoc plus L143-169 handle realization confirm:
 *  successes are silent; failures emit ShowError. Peer cross-ref to
 *  ComplaintEffect ShowSuccess/ShowError pair verified at cluster30
 *  sweep (Task #486).
 *  (c) "`message` content — throwable's message field falling back to
 *  class name then to `Unknown error`" — LIVE-NOT-STALE. DownloadsView-
 *  Model.kt L173-180 `emitOnFailure` realization: `result.exception-
 *  OrNull()?.let { e -> e.message ?: e::class.simpleName } ?: "Unknown
 *  error"`. Fallback chain matches the KDoc verbatim.
 *  (d) "OCP plus `data class` modifier rationale" — LIVE-NOT-STALE. L46
 *  `sealed interface DownloadsEffect : MviEffect` closed-under-modific-
 *  ation; L53 `data class ShowError` allows structural equality.
 *  Four classifications STAND on their own merits as a faithful
 *  DownloadsEffect surface manifest. Original Phase 7.x.downloads-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface DownloadsEffect : MviEffect {

    /**
     * A retry / cancel / delete / cancelRunning use case returned [Result.failure]. Per the MVI
     * contract this effect carries no i18n text: the underlying throwable is logged in the VM and
     * `:ui` resolves a generic localized error message for the snackbar.
     */
    data object ShowActionFailed : DownloadsEffect
}
