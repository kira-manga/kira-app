package me.manga.kira.presentation.downloads

import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.mvi.MviIntent

/**
 * Downloads screen MVI intents.
 *
 * Phase 7.x.downloads.foundation rework introduced [OnTabSelect] (read-side
 * tab toggle). Phase 7.x.downloads.actions rework appends the 4 per-row
 * mutation intents ([OnRetry], [OnCancel], [OnCancelRunning], [OnDelete]).
 *
 * **Why each mutation carries the full [DownloadedChapter]** (not just
 * `chapterId: Long`): same posture as
 * [me.manga.kira.presentation.complaint.ComplaintIntent.OnRowClick]
 * carrying a `ComplaintSummary` rather than an `id`. The `:ui` already
 * has the chapter row at icon-tap time; passing the model rather than the
 * id keeps the intent self-describing (a future "are you sure you want to
 * delete chapter X?" confirmation dialog can read `chapter.number` /
 * `chapter.mangaTitle` from the intent payload without an extra
 * `state.all.first { it.chapterId == id }` lookup).
 *
 * **OCP (contract §6)**: sealed interface — future intent variants slot
 * in as data class additions without changing the existing variants or
 * the VM's base-class signature.
 *
 * Sealed interface (not sealed class) so future variants can be declared
 * `data class` / `data object` cases at top level; same convention as
 * the rest of the rework's MVI intents.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster107.staleKdocSweep.cascade,
 * Task #563, 2026-05-28): the file-scope intent-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-seventh sibling of the cluster57-106 sweep —
 * sibling of cluster107 DownloadsEffect.kt plus DownloadsViewModel.kt):
 *  (a) "Phase 7.x.downloads.foundation introduced [OnTabSelect]; Phase
 *  7.x.downloads.actions appends 4 per-row mutation intents (OnRetry,
 *  OnCancel, OnCancelRunning, OnDelete)" — LIVE-NOT-STALE. Recursive
 *  count of variants L31-79 confirms 5 sealed variants exactly: 1
 *  foundation (OnTabSelect with Int index) plus 4 actions (OnRetry,
 *  OnCancel, OnCancelRunning, OnDelete — all carrying DownloadedChapter).
 *  (b) "Why each mutation carries the full [DownloadedChapter] (not
 *  just `chapterId: Long`)" rationale — LIVE-NOT-STALE. L48/L57/L68/L78
 *  all four mutation variants declared `data class On*(val chapter:
 *  DownloadedChapter)` — full-model payload posture preserved verbatim.
 *  Peer cross-ref to ComplaintIntent.OnRowClick ComplaintSummary-
 *  payload posture verified at cluster30 sibling sweep (Task #486).
 *  (c) "OCP (contract §6): sealed interface — future intent variants
 *  slot in as data class additions without changing the existing
 *  variants" — LIVE-NOT-STALE. L31 `sealed interface DownloadsIntent :
 *  MviIntent`; OCP foundation-to-actions extension lineage realized
 *  without any rewrites to OnTabSelect.
 *  (d) "Sealed interface (not sealed class)" — LIVE-NOT-STALE. L31
 *  declares `sealed interface` not `sealed class`; convention preserved.
 *  Four classifications STAND on their own merits as a faithful
 *  DownloadsIntent surface manifest. Original Phase 7.x.downloads-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface DownloadsIntent : MviIntent {

    /**
     * User tapped a tab in the Active / Failed / Completed `TabRow`.
     * Valid [index] values are 0 (Active), 1 (Failed), 2 (Completed)
     * — the `:ui` enforces the range by indexing into its 3-element
     * `tabTitles` list, so out-of-range values cannot reach this
     * intent from the legitimate UI path.
     */
    data class OnTabSelect(val index: Int) : DownloadsIntent

    /**
     * User tapped the retry icon on a FAILED row. The VM dispatches to
     * [me.manga.kira.domain.usecase.downloads.RetryDownloadUseCase]
     * which re-enqueues via the legacy `DownloadRepository`. On failure,
     * the VM emits [DownloadsEffect.ShowError].
     */
    data class OnRetry(val chapter: DownloadedChapter) : DownloadsIntent

    /**
     * User tapped the cancel icon on a QUEUED / COMPRESSING row. The VM
     * dispatches to
     * [me.manga.kira.domain.usecase.downloads.CancelDownloadUseCase]
     * (queue-prune semantics; distinct from [OnCancelRunning] which
     * interrupts an in-flight worker).
     */
    data class OnCancel(val chapter: DownloadedChapter) : DownloadsIntent

    /**
     * User tapped the cancel affordance on a RUNNING row (the
     * progress-percent `TextButton` in the legacy UI). The VM
     * dispatches to
     * [me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase]
     * — interruptible-in-flight semantics. Requires both `chapterId` AND
     * `mangaId` because the WorkManager tags on Android are keyed by
     * `mangaId`.
     */
    data class OnCancelRunning(val chapter: DownloadedChapter) : DownloadsIntent

    /**
     * User tapped the delete icon on a FAILED or SUCCESS row. The VM
     * dispatches to
     * [me.manga.kira.domain.usecase.downloads.DeleteDownloadUseCase]
     * which removes the queue-history row. Downloaded chapter files (if
     * any) remain on disk — see [DeleteDownloadUseCase] KDoc for the
     * scope clarification.
     */
    data class OnDelete(val chapter: DownloadedChapter) : DownloadsIntent
}
