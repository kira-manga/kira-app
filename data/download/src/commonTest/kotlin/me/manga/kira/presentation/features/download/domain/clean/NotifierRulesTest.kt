package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure-logic regression tests for [NotifierRules] (B5, stuck silent notification entries). Locks
 * the coroutine engine's terminal-notification decision: every finished chapter job maps to exactly
 * one of complete / failed / clear / leave-alone, and both B5 hazards (user cancel; row deleted
 * mid-download) map to CLEAR so no stale progress/"Finalizing…" entry survives.
 */
class NotifierRulesTest {

    private val sentinel = "__cancelled_by_user__"

    @Test
    fun success_postsCompletion() {
        assertEquals(
            NotifierRules.TerminalNotification.COMPLETE,
            NotifierRules.onJobFinished(DownloadingState.SUCCESS, errorMsg = null, cancelledSentinel = sentinel),
        )
        // A leftover errorMsg from an earlier failed attempt must not demote a SUCCESS.
        assertEquals(
            NotifierRules.TerminalNotification.COMPLETE,
            NotifierRules.onJobFinished(DownloadingState.SUCCESS, errorMsg = "old failure", cancelledSentinel = sentinel),
        )
    }

    @Test
    fun userCancel_clearsInsteadOfFailing() {
        // B5 core: a cancel is not a failure — the progress entry is removed, no alert fires.
        assertEquals(
            NotifierRules.TerminalNotification.CLEAR,
            NotifierRules.onJobFinished(DownloadingState.FAILED, errorMsg = sentinel, cancelledSentinel = sentinel),
        )
    }

    @Test
    fun realFailure_postsFailureAlert() {
        assertEquals(
            NotifierRules.TerminalNotification.FAILED,
            NotifierRules.onJobFinished(DownloadingState.FAILED, errorMsg = "No images for chapter", cancelledSentinel = sentinel),
        )
        // A FAILED row with no message at all is still a real failure, not a cancel.
        assertEquals(
            NotifierRules.TerminalNotification.FAILED,
            NotifierRules.onJobFinished(DownloadingState.FAILED, errorMsg = null, cancelledSentinel = sentinel),
        )
    }

    @Test
    fun rowDeletedMidDownload_clears() {
        // B5 core: the user deleted the download while the job ran → the row is gone; the entry
        // must be removed, whatever errorMsg would have said.
        assertEquals(
            NotifierRules.TerminalNotification.CLEAR,
            NotifierRules.onJobFinished(state = null, errorMsg = null, cancelledSentinel = sentinel),
        )
    }

    @Test
    fun nonTerminalStates_leaveProgressAlone() {
        // Exhaustive over the enum so a future DownloadingState addition forces a deliberate decision:
        // a still-active / re-queued row keeps its silent progress entry (no premature alert or clear).
        val nonTerminal = DownloadingState.entries.filter {
            NotifierRules.onJobFinished(it, errorMsg = null, cancelledSentinel = sentinel) ==
                NotifierRules.TerminalNotification.NONE
        }.toSet()
        assertEquals(
            setOf(
                DownloadingState.QUEUED,
                DownloadingState.RUNNING,
                DownloadingState.DOWNLOADED,
                DownloadingState.COMPRESSING,
            ),
            nonTerminal,
        )
    }
}
