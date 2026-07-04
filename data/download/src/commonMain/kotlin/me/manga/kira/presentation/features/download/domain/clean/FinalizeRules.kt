package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure decision rules for the iOS background engine's finalize step
 * (`BackgroundUrlSessionDownloadRepository.launchFinalize`). No I/O — fully unit-tested, the
 * finalize-side companion of [DownloadRecovery] (B1 COMPRESSING re-drive + B2-durable
 * adopt-recovery).
 *
 * Three decisions gate a finalize attempt:
 *  - **Entry** ([canStartFinalize]): only DOWNLOADED (pages transferred, normal path) or
 *    COMPRESSING (B1 crash recovery — a row a kill left mid-encode is re-driven; finalize is
 *    idempotent) rows may start. Every other state is either not ready (QUEUED/RUNNING) or already
 *    terminal (SUCCESS/FAILED).
 *  - **Artifact** ([selectArtifact]): loose pages on disk → normal finalize (CBZ encode +
 *    bookkeeping). No loose pages but a published `.cbz` → adopt it (B2-durable: `IosCbzWriter`
 *    renames the archive BEFORE deleting the loose sources, so a kill in between leaves only the
 *    `.cbz` — it IS the finished artifact, and failing the chapter on "no pages" would mark a
 *    readable chapter FAILED). Neither → the artifact is genuinely gone; fail the chapter.
 *  - **Failure** ([classifyFinalizeFailure]): a throw out of finalize must not fail a chapter the
 *    user can read. Already SUCCESS → a post-terminal error, ignore. Any readable artifact still on
 *    disk (loose pages or `.cbz`) → keep it readable and let the next window/foreground retry the
 *    archive. Only when nothing is left does the chapter fail.
 */
object FinalizeRules {

    /**
     * True when a row may start a finalize attempt: DOWNLOADED (normal) or COMPRESSING (B1
     * re-drive). Exactly those two — the caller's `finalizing` set separately prevents
     * double-starting a genuinely in-flight encode within a session.
     */
    fun canStartFinalize(state: DownloadingState): Boolean =
        state == DownloadingState.DOWNLOADED || state == DownloadingState.COMPRESSING

    /** What a finalize attempt should operate on. */
    sealed interface Artifact {
        /** Loose pages are on disk → normal finalize (CBZ encode + bookkeeping). */
        data object LoosePages : Artifact

        /** No loose pages, but a published `.cbz` exists → adopt it as the finished artifact (B2-durable). */
        data class AdoptCbz(val cbzPath: String) : Artifact

        /** Neither loose pages nor a `.cbz` → nothing to finalize; fail the chapter. */
        data object Missing : Artifact
    }

    /**
     * Selects the finalize artifact. [existingCbzPath] is a provider (not a value) so the `.cbz`
     * filesystem probe runs ONLY when the loose pages are absent — the happy path must not pay an
     * extra `exists()` per chapter. That laziness is part of the contract and is locked by test.
     */
    fun selectArtifact(loosePagesPresent: Boolean, existingCbzPath: () -> String?): Artifact = when {
        loosePagesPresent -> Artifact.LoosePages
        else -> existingCbzPath()?.let(Artifact::AdoptCbz) ?: Artifact.Missing
    }

    /** What to do when a finalize attempt throws. */
    enum class FailureAction {
        /** The row already reached terminal SUCCESS — the error is post-terminal noise; log and ignore. */
        IGNORE_POST_SUCCESS,

        /**
         * The row MOVED ON while the (off-mutex) finalize ran and this stale attempt no longer owns
         * it: FAILED (a user cancel landed mid-encode — failing again would clobber the
         * cancelled-by-user sentinel and post a duplicate "failed" banner), `null` (the download was
         * deleted mid-encode — writing/notifying a gone row is pure noise), or QUEUED/RUNNING (the
         * user retried a cancelled chapter while the stale encode was still failing — failing "again"
         * would instantly kill the fresh attempt). Log and ignore; the newer lifecycle owns the row.
         */
        IGNORE_STALE_ROW,

        /** A readable artifact (loose pages or `.cbz`) is still on disk — keep the chapter readable; retry later. */
        KEEP_READABLE,

        /** Nothing readable remains — fail the chapter. */
        FAIL,
    }

    /**
     * Classifies a finalize throw. [currentState] is the row re-read AFTER the throw. Only a row
     * still in a finalize-owned state ([canStartFinalize]: DOWNLOADED/COMPRESSING) may be failed or
     * kept-readable by this attempt; SUCCESS is post-terminal noise, and every other state (FAILED /
     * QUEUED / RUNNING / `null`) means the row was re-purposed mid-encode by a cancel, delete, or
     * retry that this stale attempt must not clobber.
     */
    fun classifyFinalizeFailure(currentState: DownloadingState?, artifactPresent: Boolean): FailureAction = when {
        currentState == DownloadingState.SUCCESS -> FailureAction.IGNORE_POST_SUCCESS
        currentState == null || !canStartFinalize(currentState) -> FailureAction.IGNORE_STALE_ROW
        artifactPresent -> FailureAction.KEEP_READABLE
        else -> FailureAction.FAIL
    }

    /**
     * The SUCCESS-path twin of [classifyFinalizeFailure]'s stale-row rule (2026-07 audit): true
     * when a finalize/mark-readable attempt must ABANDON its bookkeeping writes because the row
     * was re-purposed while the (off-mutex) work ran — FAILED (a user cancel landed mid-encode;
     * writing SUCCESS would silently undo the cancel, point `localImagePaths` at files the cancel
     * path deleted, and let the completion banner fire) or `null` (the download was deleted
     * mid-encode). Deliberately NARROWER than [classifyFinalizeFailure]'s stale set: the Desktop
     * coroutine engine calls `ChapterFinalizer.finalize` inline while its row is legitimately
     * RUNNING, so RUNNING/QUEUED must not abandon here — those cancels are handled by job
     * cancellation instead.
     */
    fun shouldAbandonFinalize(state: DownloadingState?): Boolean = state == null || state == DownloadingState.FAILED

    /**
     * True when a USER CANCEL hits a chapter inside its finalize window — DOWNLOADED (pages
     * transferred, encode pending/deferred) or COMPRESSING (encode running). By then the chapter
     * was ALREADY marked readable at transfer-complete ([ChapterFinalizer.markReadable]:
     * `isDownloaded` + `localImagePaths` + the notification row), so flipping the queue row FAILED
     * is NOT enough — the cancel must also revert that bookkeeping and remove the artifact, or the
     * chapter stays "Downloaded" and opens as complete after the cancel (2026-07-04 device smoke).
     * Deliberately NOT true for QUEUED/RUNNING: a cancel there must not clear the bookkeeping of a
     * PREVIOUS successful download of the same chapter (retry-then-cancel case). Same state set as
     * [canStartFinalize] — the finalize-owned states.
     */
    fun cancelMustRevertReadable(state: DownloadingState?): Boolean = state != null && canStartFinalize(state)

    /** What a user cancel must do, AT CANCEL TIME, for one row (see [cancelCleanup]). */
    enum class CancelCleanup {
        /** Terminal/gone row, or a row whose files an in-flight lifecycle owns — touch nothing. */
        NONE,

        /**
         * Mid-transfer cancel (QUEUED/RUNNING): delete THIS cycle's partially-downloaded page
         * files + the manifest, but keep any published `.cbz` — that artifact belongs to a
         * previous COMPLETED download of the same chapter, and its bookkeeping was never touched
         * by this cycle (mobile hardening 2026-07-04: these partials used to linger as orphans).
         */
        DELETE_PARTIAL_PAGES,

        /**
         * Revert the readable bookkeeping NOW; leave the files to the in-flight encode
         * (deleting under IosCbzWriter risks a silently page-short archive) — the post-encode
         * cleanup deletes them the moment the encode is done.
         */
        REVERT_ONLY,

        /** Revert the readable bookkeeping AND delete the artifact (no encode is reading it). */
        REVERT_AND_DELETE_FILES,
    }

    /**
     * The cancel-time cleanup for one row. The REVERT half must happen SYNCHRONOUSLY inside the
     * cancel (2026-07-04 device smoke round 2: deferring it to the post-encode cleanup left the
     * chapter visibly "Downloaded" for the whole remaining encode — seconds — after the user
     * pressed Cancel). File deletion never happens under an in-flight encode: a finalize-window
     * row defers it to the post-encode cleanup, and a QUEUED/RUNNING row that somehow coexists
     * with an encode (a retry raced it) gets NONE — the fresh lifecycle owns those files.
     * Consumed by `onCancel` and `cancelAllDownloads`; `cancelARunningChapter` keeps its
     * historical whole-dir flow for RUNNING rows plus the same finalize-window handling.
     */
    fun cancelCleanup(
        state: DownloadingState?,
        encodeInFlight: Boolean,
    ): CancelCleanup =
        when {
            cancelMustRevertReadable(state) ->
                if (encodeInFlight) CancelCleanup.REVERT_ONLY else CancelCleanup.REVERT_AND_DELETE_FILES
            state == DownloadingState.QUEUED || state == DownloadingState.RUNNING ->
                if (encodeInFlight) CancelCleanup.NONE else CancelCleanup.DELETE_PARTIAL_PAGES
            else -> CancelCleanup.NONE
        }
}
