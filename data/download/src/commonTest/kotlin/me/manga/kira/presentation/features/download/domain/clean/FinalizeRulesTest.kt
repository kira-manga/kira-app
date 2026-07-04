package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure-logic regression tests for [FinalizeRules] (B1 COMPRESSING re-drive + B2-durable
 * adopt-recovery). Locks the three decisions of the iOS background engine's `launchFinalize`:
 * which rows may start a finalize, which on-disk artifact the attempt operates on, and what a
 * thrown finalize does to the chapter.
 */
class FinalizeRulesTest {

    // ---- (i) entry gate: canStartFinalize ----

    @Test
    fun finalizableStates_areExactlyDownloadedAndCompressing() {
        // Exhaustive over the enum so a future DownloadingState addition forces a deliberate decision.
        val startable = DownloadingState.entries.filter { FinalizeRules.canStartFinalize(it) }.toSet()
        assertEquals(
            setOf(DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING),
            startable,
        )
    }

    @Test
    fun compressingRow_isReDriven() {
        // B1: a row a kill left mid-encode must be accepted again — finalize is idempotent.
        assertTrue(FinalizeRules.canStartFinalize(DownloadingState.COMPRESSING))
    }

    // ---- (ii) artifact selection: selectArtifact ----

    @Test
    fun loosePagesPresent_finalizesLoosePages_withoutProbingCbz() {
        // The .cbz existence probe is I/O; the happy path must not pay it. Laziness is contract.
        var probed = false
        val artifact = FinalizeRules.selectArtifact(loosePagesPresent = true) {
            probed = true
            "/x/chapter_1.cbz"
        }
        assertEquals(FinalizeRules.Artifact.LoosePages, artifact)
        assertFalse(probed, "cbz probe must not run when loose pages are present")
    }

    @Test
    fun looseGone_publishedCbz_isAdopted() {
        // B2-durable core: kill after the rename published the .cbz but before terminal SUCCESS →
        // loose pages gone, archive present. The archive IS the finished artifact.
        val artifact = FinalizeRules.selectArtifact(loosePagesPresent = false) { "/x/chapter_1.cbz" }
        assertEquals(FinalizeRules.Artifact.AdoptCbz("/x/chapter_1.cbz"), artifact)
    }

    @Test
    fun looseGone_noCbz_isMissing() {
        val artifact = FinalizeRules.selectArtifact(loosePagesPresent = false) { null }
        assertEquals(FinalizeRules.Artifact.Missing, artifact)
    }

    // ---- (iii) failure classification: classifyFinalizeFailure ----

    @Test
    fun postSuccessError_isIgnored_regardlessOfArtifact() {
        // SUCCESS wins: a post-terminal error must never re-touch the row.
        assertEquals(
            FinalizeRules.FailureAction.IGNORE_POST_SUCCESS,
            FinalizeRules.classifyFinalizeFailure(DownloadingState.SUCCESS, artifactPresent = true),
        )
        assertEquals(
            FinalizeRules.FailureAction.IGNORE_POST_SUCCESS,
            FinalizeRules.classifyFinalizeFailure(DownloadingState.SUCCESS, artifactPresent = false),
        )
    }

    @Test
    fun readableArtifact_keepsChapterReadable_forFinalizeOwnedStates() {
        // A CBZ/IO throw over a chapter whose pages (or .cbz) are still on disk must NOT fail it —
        // the user can read it; the archive retries on the next window/foreground. Only rows this
        // finalize attempt still OWNS (DOWNLOADED/COMPRESSING) classify here.
        for (state in listOf(DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING)) {
            assertEquals(
                FinalizeRules.FailureAction.KEEP_READABLE,
                FinalizeRules.classifyFinalizeFailure(state, artifactPresent = true),
                "state=$state",
            )
        }
    }

    @Test
    fun artifactGone_fails_forFinalizeOwnedStates() {
        for (state in listOf(DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING)) {
            assertEquals(
                FinalizeRules.FailureAction.FAIL,
                FinalizeRules.classifyFinalizeFailure(state, artifactPresent = false),
                "state=$state",
            )
        }
    }

    @Test
    fun cancelledMidFinalize_isIgnored_neverClobbersTheCancelSentinel() {
        // A user cancel that landed while the off-mutex encode ran flipped the row to FAILED with
        // the cancelled-by-user sentinel. Failing "again" would overwrite that sentinel with the
        // encode error and post a duplicate "failed" banner after the user already cancelled —
        // regardless of whether an artifact survived.
        for (artifact in listOf(true, false)) {
            assertEquals(
                FinalizeRules.FailureAction.IGNORE_STALE_ROW,
                FinalizeRules.classifyFinalizeFailure(DownloadingState.FAILED, artifactPresent = artifact),
                "artifactPresent=$artifact",
            )
        }
    }

    @Test
    fun rowDeletedMidFinalize_isIgnored() {
        // getDownloadByChapter returns null after the throw (the download was deleted while the
        // finalize coroutine ran) — writing FAILED into a gone row is a no-op and the "failed"
        // banner would announce a download that no longer exists.
        for (artifact in listOf(true, false)) {
            assertEquals(
                FinalizeRules.FailureAction.IGNORE_STALE_ROW,
                FinalizeRules.classifyFinalizeFailure(currentState = null, artifactPresent = artifact),
                "artifactPresent=$artifact",
            )
        }
    }

    @Test
    fun retryReEnqueuedMidFinalize_isIgnored() {
        // Cancel mid-encode then retry: the row is QUEUED (or already claimed RUNNING) by a FRESH
        // attempt while the stale encode is still failing. Failing it here would instantly kill the
        // retry the user just asked for.
        for (state in listOf(DownloadingState.QUEUED, DownloadingState.RUNNING)) {
            for (artifact in listOf(true, false)) {
                assertEquals(
                    FinalizeRules.FailureAction.IGNORE_STALE_ROW,
                    FinalizeRules.classifyFinalizeFailure(state, artifactPresent = artifact),
                    "state=$state artifactPresent=$artifact",
                )
            }
        }
    }

    @Test
    fun failureClassification_partitionsEveryRowState() {
        // Exhaustive over the enum + null so a future DownloadingState addition forces a deliberate
        // decision: SUCCESS is post-terminal, the two finalize-owned states decide on the artifact,
        // and EVERYTHING else is a stale row this attempt must not touch.
        val owned = setOf(DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING)
        for (state in DownloadingState.entries + null) {
            val action = FinalizeRules.classifyFinalizeFailure(state, artifactPresent = false)
            val expected = when {
                state == DownloadingState.SUCCESS -> FinalizeRules.FailureAction.IGNORE_POST_SUCCESS
                state in owned -> FinalizeRules.FailureAction.FAIL
                else -> FinalizeRules.FailureAction.IGNORE_STALE_ROW
            }
            assertEquals(expected, action, "state=$state")
        }
    }

    // --- 2026-07 audit: the SUCCESS-path abandon gate (cancel-during-finalize clobber) --------------

    @Test
    fun abandonGate_firesForCancelledOrDeletedRows_only() {
        // FAILED = a user cancel landed while the off-mutex encode ran; null = the download was
        // deleted. Writing the terminal SUCCESS bookkeeping in either case silently undoes the
        // cancel and lets the completion banner fire over deleted files.
        assertEquals(true, FinalizeRules.shouldAbandonFinalize(DownloadingState.FAILED))
        assertEquals(true, FinalizeRules.shouldAbandonFinalize(null))
    }

    @Test
    fun abandonGate_neverFiresForLiveOrTerminalSuccessStates() {
        // RUNNING is load-bearing: the Desktop coroutine engine calls finalize inline while its
        // row is RUNNING — abandoning there would break every desktop download. SUCCESS stays
        // allowed for idempotent re-runs (B1 re-drive); DOWNLOADED/COMPRESSING are the iOS
        // engine's finalize-owned states; QUEUED (retry mid-encode) is owned by job cancellation.
        for (state in DownloadingState.entries) {
            if (state == DownloadingState.FAILED) continue
            assertEquals(false, FinalizeRules.shouldAbandonFinalize(state), "state=$state")
        }
    }

    // --- 2026-07-04 device smoke: cancel during "Finalizing…" must revert the readable bookkeeping --

    @Test
    fun cancelRevert_firesExactlyForTheFinalizeOwnedStates() {
        // The chapter is marked readable at transfer-complete — BEFORE "Finalizing…" shows — so a
        // cancel landing in DOWNLOADED (encode pending/deferred) or COMPRESSING (encode running)
        // must also revert isDownloaded/localImagePaths or the chapter stays Downloaded and opens
        // as complete after the cancel.
        assertEquals(true, FinalizeRules.cancelMustRevertReadable(DownloadingState.DOWNLOADED))
        assertEquals(true, FinalizeRules.cancelMustRevertReadable(DownloadingState.COMPRESSING))
        // NEVER for QUEUED/RUNNING: a cancel of a queued/running RETRY must not clear the
        // bookkeeping of a previous successful download of the same chapter. Terminal states and a
        // gone row have nothing to revert.
        for (state in DownloadingState.entries) {
            if (state == DownloadingState.DOWNLOADED || state == DownloadingState.COMPRESSING) continue
            assertEquals(false, FinalizeRules.cancelMustRevertReadable(state), "state=$state")
        }
        assertEquals(false, FinalizeRules.cancelMustRevertReadable(null))
    }

    @Test
    fun cancelCleanup_revertIsAlwaysImmediate_onlyFileDeletionDefersToAnInFlightEncode() {
        // 2026-07-04 device smoke round 2: after Cancel during "Finalizing…" the chapter must never
        // remain OBSERVABLY Downloaded — the bookkeeping revert happens at cancel time for BOTH
        // finalize-window states regardless of the encode; only the FILE deletion defers while an
        // encode holds the files (the post-encode cleanup finishes it). This is the decision both
        // user-cancel entry points (onCancel and cancel-all) execute.
        for (state in listOf(DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING)) {
            assertEquals(
                FinalizeRules.CancelCleanup.REVERT_ONLY,
                FinalizeRules.cancelCleanup(state, encodeInFlight = true),
                "state=$state encodeInFlight=true",
            )
            assertEquals(
                FinalizeRules.CancelCleanup.REVERT_AND_DELETE_FILES,
                FinalizeRules.cancelCleanup(state, encodeInFlight = false),
                "state=$state encodeInFlight=false",
            )
        }
    }

    @Test
    fun cancelCleanup_midTransferCancel_dropsPartialPages_neverUnderAnEncode() {
        // Mobile hardening item 1 (2026-07-04): a QUEUED/RUNNING cancel deletes THIS cycle's
        // partial page files (they used to linger as orphans) — but a published .cbz from a
        // previous completed download survives (DELETE_PARTIAL_PAGES is surgical, not whole-dir),
        // and nothing is deleted while an encode somehow owns the chapter's files (a retry that
        // raced the encode: the fresh lifecycle owns them).
        for (state in listOf(DownloadingState.QUEUED, DownloadingState.RUNNING)) {
            assertEquals(
                FinalizeRules.CancelCleanup.DELETE_PARTIAL_PAGES,
                FinalizeRules.cancelCleanup(state, encodeInFlight = false),
                "state=$state encodeInFlight=false",
            )
            assertEquals(
                FinalizeRules.CancelCleanup.NONE,
                FinalizeRules.cancelCleanup(state, encodeInFlight = true),
                "state=$state encodeInFlight=true",
            )
        }
        // Terminal/gone rows have nothing to clean in either mode.
        for (state in listOf(DownloadingState.SUCCESS, DownloadingState.FAILED, null)) {
            for (inFlight in listOf(true, false)) {
                assertEquals(
                    FinalizeRules.CancelCleanup.NONE,
                    FinalizeRules.cancelCleanup(state, encodeInFlight = inFlight),
                    "state=$state encodeInFlight=$inFlight",
                )
            }
        }
    }
}
