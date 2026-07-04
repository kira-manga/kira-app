package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure rule for the coroutine download engine's terminal notification (B5, stuck silent entries).
 * No I/O — mirrors the decision `CoroutineDownloadRepositoryImpl`'s worker loop makes after a
 * chapter job finishes: the silent per-page progress entry must end in exactly one of a
 * banner+sound completion, a failure alert, or a **clear** — a user cancel or a row deleted
 * mid-download must never leave a stale progress/"Finalizing…" entry in the shade forever.
 *
 * (The iOS background engine's four explicit cancel/delete paths clear the notifier at the call
 * site — deleteDownload / onCancel / cancelARunningChapter / cancelAllDownloads; this rule is the
 * coroutine engine's reactive equivalent, driven by the row's post-job state.)
 */
object NotifierRules {

    /** The one notifier action a finished chapter job maps to. */
    enum class TerminalNotification {
        /** Terminal SUCCESS → the alerting completion notice. */
        COMPLETE,

        /** Terminal FAILED (a real failure, not a user cancel) → the failure alert. */
        FAILED,

        /** User cancel (FAILED + the cancelled-by-user sentinel) or row deleted mid-download → remove the entry. */
        CLEAR,

        /** Not terminal (still running / re-queued) → leave the progress entry alone. */
        NONE,
    }

    /**
     * Classifies the row re-read after a chapter job completes. [state]/[errorMsg] come from that
     * re-read ([state] `null` = the row is gone — the user deleted the download while it ran);
     * [cancelledSentinel] is the engine's `errorMsg` marker for a user cancel.
     */
    fun onJobFinished(
        state: DownloadingState?,
        errorMsg: String?,
        cancelledSentinel: String,
    ): TerminalNotification = when (state) {
        DownloadingState.SUCCESS -> TerminalNotification.COMPLETE
        DownloadingState.FAILED ->
            if (errorMsg == cancelledSentinel) TerminalNotification.CLEAR else TerminalNotification.FAILED
        null -> TerminalNotification.CLEAR
        else -> TerminalNotification.NONE
    }
}
