package me.manga.kira.data.local.dao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.local.entity.ChapterArtifactFile
import me.manga.kira.data.local.entity.claimOrNull
import me.manga.kira.presentation.features.download.data.DownloadingState

/** Real Room transactions reuse the completion fixture rather than a second mock SQL model. */
class ChapterArtifactDaoTest {
    @Test
    fun everyActiveEngineStateExcludesRestore() = completionTest {
        for (state in listOf(DownloadingState.QUEUED, DownloadingState.RUNNING, DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING)) {
            val original = seed(state, emptyList())
            assertNull(db.chapterArtifactDao().claimRestore(original.saved, FIRST, pending()))
            assertUnchanged(original)
        }
    }

    @Test
    fun restoreReservationExcludesBothEnqueueAndAnotherRestore() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val artifacts = db.chapterArtifactDao()
        assertNotNull(artifacts.claimRestore(original.saved, FIRST, pending()))
        assertNull(artifacts.enqueue(original.saved, original.download.copy(state = DownloadingState.QUEUED), SECOND))
        assertNull(artifacts.claimRestore(original.saved, SECOND, pending(SECOND)))
        assertUnchanged(original)
    }

    @Test
    fun enqueueReservesANewLedgerIdentityAndRejectsOldAttempt() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val artifacts = db.chapterArtifactDao()
        val claim = assertNotNull(artifacts.enqueue(original.saved, original.download.copy(state = DownloadingState.QUEUED), FIRST))
        assertTrue(claim.downloadId != original.download.id)
        assertTrue(artifacts.canPublish(claim))
        assertNull(artifacts.claimExistingDownload(original.download, SECOND))
        assertNull(artifacts.claimRestore(original.saved, SECOND, pending(SECOND)))
    }

    @Test
    fun revokedFailedProducerStillKeepsCustodyAcrossReopen() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val claim = assertNotNull(db.chapterArtifactDao().enqueue(original.saved, original.download.copy(state = DownloadingState.QUEUED), FIRST))
        db.chapterArtifactDao().revoke(original.saved.id, claim.token)
        dao.updateFailure(original.saved.id, "cancelled")
        reopen()
        assertFalse(db.chapterArtifactDao().canPublish(claim))
        assertEquals(claim, db.chapterArtifactDao().get(original.saved.id)?.claimOrNull())
        assertNull(db.chapterArtifactDao().claimRestore(original.saved, SECOND, pending(SECOND)))
    }

    @Test
    fun committedReceiptSurvivesSuccessHistoryDeletionAndRootDrift() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val claim = assertNotNull(db.chapterArtifactDao().claimRestore(original.saved, FIRST, pending()))
        assertEquals(1, db.chapterArtifactDao().confirmPendingPathOwnership(original.saved.id, FIRST))
        val path = "/old-sandbox/${pending().relativePath}"
        assertTrue(db.chapterArtifactCommitDao().commitRestore(claim, original.saved, path, original.download.copy(sizeBytes = SIZE)))
        assertEquals(original.download.id, dao.getDownloadByChapter(original.saved.id)?.id)
        dao.deleteByChapterId(original.saved.id)
        reopen()
        assertEquals(
            ChapterRestoreOutcome.COMMITTED,
            db.chapterArtifactCommitDao().readRestoreOutcome(claim, "/new-sandbox/${pending().relativePath}", SIZE),
        )
        assertEquals(FIRST, db.chapterArtifactDao().get(original.saved.id)?.committedToken)
        assertEquals(original.saved.copy(isDownloaded = true, localImagePaths = listOf(path)), db.chapterDao().getChapterByIdSuspend(original.saved.id))
    }

    @Test
    fun ignoredSavedUpdateCannotPublishLedgerOrReceipt() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val claim = assertNotNull(db.chapterArtifactDao().claimRestore(original.saved, FIRST, pending()))
        db.chapterArtifactDao().confirmPendingPathOwnership(original.saved.id, FIRST)
        executeWhileClosed(
            "CREATE TRIGGER ignore_restore BEFORE UPDATE OF isDownloaded ON saved_chapters " +
                "WHEN NEW.id = ${original.saved.id} BEGIN SELECT RAISE(IGNORE); END",
        )
        assertFailsWith<IllegalStateException> {
            db.chapterArtifactCommitDao().commitRestore(claim, original.saved, absolute(), original.download.copy(sizeBytes = SIZE))
        }
        assertUnchanged(original)
        assertNull(db.chapterArtifactDao().get(original.saved.id)?.committedToken)
        assertEquals(ChapterRestoreOutcome.NOT_COMMITTED, db.chapterArtifactCommitDao().readRestoreOutcome(claim, absolute(), SIZE))
    }

    @Test
    fun failureAfterSavedWriteRollsItBackWithLedgerAndReceipt() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val claim = assertNotNull(db.chapterArtifactDao().claimRestore(original.saved, FIRST, pending()))
        db.chapterArtifactDao().confirmPendingPathOwnership(original.saved.id, FIRST)
        executeWhileClosed(
            "CREATE TRIGGER reject_restore BEFORE INSERT ON chapter_downloads WHEN NEW.sizeBytes = $SIZE " +
                "BEGIN SELECT RAISE(ABORT, 'reject_restore'); END",
        )
        assertFailsWith<Exception> {
            db.chapterArtifactCommitDao().commitRestore(claim, original.saved, absolute(), original.download.copy(sizeBytes = SIZE))
        }
        assertUnchanged(original)
        assertNull(db.chapterArtifactDao().get(original.saved.id)?.committedToken)
    }

    @Test
    fun changedLedgerAfterPromotionIsUnknownNotCleanupAuthority() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val claim = assertNotNull(db.chapterArtifactDao().claimRestore(original.saved, FIRST, pending()))
        db.chapterArtifactDao().confirmPendingPathOwnership(original.saved.id, FIRST)
        // A pre-protocol writer deliberately bypasses admission: fail closed, never delete its files.
        dao.insert(original.download.copy(id = 0, state = DownloadingState.QUEUED))
        assertFalse(db.chapterArtifactCommitDao().commitRestore(claim, original.saved, absolute(), original.download.copy(sizeBytes = SIZE)))
        assertEquals(ChapterRestoreOutcome.UNKNOWN, db.chapterArtifactCommitDao().readRestoreOutcome(claim, absolute(), SIZE))
    }

    @Test
    fun capturedRetryCannotResurrectDeletedOrReplaceNewerHistory() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val artifacts = db.chapterArtifactDao()
        dao.deleteHistoryAttempt(original.saved.id, original.download.id)
        assertNull(artifacts.retry(original.download, FIRST))
        assertNull(dao.getDownloadByChapter(original.saved.id))
        val replacementId = dao.insert(original.download.copy(id = 0))
        assertNull(artifacts.retry(original.download, FIRST))
        assertEquals(replacementId, dao.getDownloadByChapter(original.saved.id)?.id)
        assertNull(artifacts.get(original.saved.id))
    }

    @Test
    fun admittedRetryGetsFreshIdentityAndSurvivesOldHistoryDelete() = completionTest {
        val original = seed(DownloadingState.FAILED, listOf("/retained/page_0.png"))
        val artifacts = db.chapterArtifactDao()
        val claim = assertNotNull(artifacts.retry(original.download, FIRST))
        dao.deleteHistoryAttempt(original.saved.id, original.download.id)
        val current = assertNotNull(dao.getDownloadByChapter(original.saved.id))
        assertTrue(current.id != original.download.id)
        assertEquals(claim.downloadId, current.id)
        assertEquals(original.download.copy(
            id = current.id, state = DownloadingState.QUEUED, progress = 0, errorMsg = null, sizeBytes = 0,
        ), current)
        assertEquals(original.saved, db.chapterDao().getChapterByIdSuspend(original.saved.id))
        assertNull(artifacts.retry(original.download, SECOND))
        assertTrue(artifacts.canPublish(claim))
    }

    @Test
    fun retryRefusesChangedIdentityStateAndUnsettledCustody() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        val artifacts = db.chapterArtifactDao()
        for (stale in listOf(
            original.download.copy(api = "other"), original.download.copy(url = "https://other.test/chapter"),
            original.download.copy(mangaId = original.saved.mangaId + 100),
            original.download.copy(state = DownloadingState.SUCCESS),
        )) assertNull(artifacts.retry(stale, FIRST))
        dao.updateStateChId(original.saved.id, DownloadingState.SUCCESS)
        assertNull(artifacts.retry(original.download, FIRST))
        dao.updateStateChId(original.saved.id, DownloadingState.FAILED)
        val restore = assertNotNull(artifacts.claimRestore(original.saved, FIRST, pending()))
        assertNull(artifacts.retry(original.download, SECOND))
        artifacts.revoke(original.saved.id, FIRST)
        assertNull(artifacts.retry(original.download, SECOND), "Revocation is not custody release")
        assertEquals(restore, artifacts.get(original.saved.id)?.claimOrNull())
        assertUnchanged(original)
    }

    @Test
    fun retryReservationFailureRollsBackQueueReplacement() = completionTest {
        val original = seed(DownloadingState.FAILED, emptyList())
        executeWhileClosed(
            "CREATE TRIGGER reject_retry BEFORE INSERT ON chapter_artifacts " +
                "BEGIN SELECT RAISE(ABORT, 'reject_retry'); END",
        )
        assertFailsWith<Exception> { db.chapterArtifactDao().retry(original.download, FIRST) }
        assertUnchanged(original)
        assertNull(db.chapterArtifactDao().get(original.saved.id))
    }

    private fun pending(token: String = FIRST) = ChapterArtifactFile("_restored/$token/chapter.cbz", SIZE)
    private fun absolute() = "/sandbox/${pending().relativePath}"

    private companion object {
        const val FIRST = "11111111-1111-4111-8111-111111111111"
        const val SECOND = "22222222-2222-4222-8222-222222222222"
        const val SIZE = 123L
    }
}
