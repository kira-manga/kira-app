package me.manga.kira.data.local.dao

import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real generated Room transactions; no filesystem proof is simulated by these guard tests. */
class ChapterArtifactRepairDaoTest {
    @Test
    fun changedLedgerPathsOwnerParentOrCustodyCannotConsumeAnOldAbsenceSnapshot() = completionTest {
        for (change in RepairRace.entries) {
            val original = seed(DownloadingState.SUCCESS, listOf("/old/1.png"))
            val records = db.chapterArtifactRepairDao()
            val expected = assertNotNull(records.snapshot(original.saved.id))
            change.apply(this, original)
            val chapter = db.chapterDao().getChapterByIdSuspend(original.saved.id)
            val download = dao.getDownloadByChapter(original.saved.id)
            val artifact = db.chapterArtifactDao().get(original.saved.id)
            val parents = db.backupDao().getAllSavedManga()
            assertFalse(records.clearMissing(expected), change.name)
            assertEquals(chapter, db.chapterDao().getChapterByIdSuspend(original.saved.id), change.name)
            assertEquals(download, dao.getDownloadByChapter(original.saved.id), change.name)
            assertEquals(artifact, db.chapterArtifactDao().get(original.saved.id), change.name)
            assertEquals(parents, db.backupDao().getAllSavedManga(), change.name)
        }
    }

    @Test
    fun concurrentReadingAndLibraryEditsSurviveAValidDerivedOnlyRepair() = completionTest {
        val original = seed(DownloadingState.SUCCESS, listOf("/old/1.png"))
        val records = db.chapterArtifactRepairDao()
        val expected = assertNotNull(records.snapshot(original.saved.id))
        val reading = original.saved.copy(isBookmarked = false, isRead = false, isNew = true,
            lastReadPage = 23, lastReadDate = 456L, fetchedAt = 789L, name = "updated title")
        db.backupDao().updateChapterRow(reading)
        val parent = assertNotNull(db.mangaDao().getMangaById(reading.mangaId))
            .copy(isLiked = true, isWatchingNow = true, lastOpenTimestamp = 123L)
        db.backupDao().updateMangaRow(parent)
        assertTrue(records.clearMissing(expected))
        reopen()
        assertEquals(reading.copy(isDownloaded = false, localImagePaths = emptyList()),
            db.chapterDao().getChapterByIdSuspend(reading.id))
        assertEquals(parent, db.mangaDao().getMangaById(parent.id))
        assertEquals(original.download.copy(state = DownloadingState.FAILED, progress = 0, sizeBytes = 0, errorMsg = null),
            dao.getDownloadByChapter(reading.id))
        assertFalse(db.chapterArtifactRepairDao().clearMissing(expected))
    }

    @Test
    fun lateArtifactWriteFailureRollsBackSavedMirrorsAndLedgerAcrossReopen() = completionTest {
        val target = repairTarget()
        val expected = assertNotNull(db.chapterArtifactRepairDao().snapshot(target.download.saved.id))
        rejectLastRepairWrite(target)
        val failure = assertFails { db.chapterArtifactRepairDao().clearMissing(expected) }
        assertTrue(failure.message.orEmpty().contains("reject_artifact_repair"))
        assertRepairTargetUnchanged(target)
        reopen()
        assertRepairTargetUnchanged(target)
        executeWhileClosed("DROP TRIGGER reject_artifact_repair")
        assertTrue(db.chapterArtifactRepairDao().clearMissing(expected))
        reopen()
        assertEquals(target.download.saved.copy(isDownloaded = false, localImagePaths = emptyList()),
            db.chapterDao().getChapterByIdSuspend(target.download.saved.id))
        assertEquals(target.notification.copy(isDownloaded = false, localImagePaths = emptyList()),
            db.notificationDao().getNotificationByChapterId(target.notification.chapterId))
        assertEquals(target.history.copy(isDownloaded = false, localImagePaths = emptyList()),
            db.backupDao().getHistoryByMangaUrl(target.history.mangaUrl))
        assertEquals(target.artifact.copy(committedToken = null, committedRelativePath = null),
            db.chapterArtifactDao().get(target.artifact.chapterId))
        assertEquals(target.download.download.copy(state = DownloadingState.FAILED, progress = 0, sizeBytes = 0, errorMsg = null),
            dao.getDownloadByChapter(target.download.saved.id))
    }
}

private enum class RepairRace {
    DELETE_LEDGER, REPLACE_LEDGER, ENQUEUE, CHANGE_PATHS, CHANGE_FLAG, CHANGE_CHAPTER_URL,
    CHANGE_MANGA_ID, CHANGE_API, CHANGE_MANGA_URL, CLAIM_FILES;

    suspend fun apply(fixture: ChapterDownloadCompletionFixture, original: SeededDownload) = with(fixture) {
        val saved = original.saved
        when (this@RepairRace) {
            DELETE_LEDGER -> dao.deleteByChapterId(saved.id)
            REPLACE_LEDGER -> dao.insert(original.download.copy(id = 0))
            ENQUEUE -> dao.updateStateChId(saved.id, DownloadingState.QUEUED)
            CHANGE_PATHS -> db.backupDao().updateChapterRow(saved.copy(localImagePaths = listOf("/new/2.png")))
            CHANGE_FLAG -> db.backupDao().updateChapterRow(saved.copy(isDownloaded = true))
            CHANGE_CHAPTER_URL -> db.backupDao().updateChapterRow(saved.copy(url = "${saved.url}/replacement"))
            CHANGE_MANGA_ID -> db.backupDao().updateChapterRow(saved.copy(mangaId = seed(DownloadingState.FAILED, emptyList()).saved.mangaId))
            CHANGE_API, CHANGE_MANGA_URL -> {
                val parent = assertNotNull(db.mangaDao().getMangaById(saved.mangaId))
                db.backupDao().updateMangaRow(if (this@RepairRace == CHANGE_API) parent.copy(api = "replacement")
                    else parent.copy(url = "${parent.url}/replacement"))
            }
            CLAIM_FILES -> db.chapterArtifactDao().insert(ChapterArtifactEntity(saved.id, saved.mangaId, saved.url,
                token = REPAIR_TOKEN, operation = "restore"))
        }
        Unit
    }
}

private data class RepairTarget(
    val download: SeededDownload,
    val notification: ChapterNotification,
    val history: HistoryItemD,
    val artifact: ChapterArtifactEntity,
)

private suspend fun ChapterDownloadCompletionFixture.repairTarget(): RepairTarget {
    val initial = seed(DownloadingState.SUCCESS, listOf("/old/_restored/$REPAIR_TOKEN/chapter.cbz"))
    val original = initial.copy(saved = initial.saved.copy(isDownloaded = true))
    db.backupDao().updateChapterRow(original.saved)
    val chapter = original.saved
    val parent = assertNotNull(db.mangaDao().getMangaById(chapter.mangaId))
    val notification = ChapterNotification(api = parent.api, language = parent.language,
        mangaId = parent.id, mangaTitle = parent.title, mangaImageUrl = parent.imageUrl, mangaUrl = parent.url,
        chapterId = chapter.id, chapterNumber = chapter.number, chapterUrl = chapter.url, isRead = true,
        isDownloaded = true, localImagePaths = chapter.localImagePaths)
    val notificationId = db.notificationDao().insertNotificationsList(listOf(notification)).single()
    val history = HistoryItemD(api = parent.api, language = parent.language, mangaUrl = parent.url,
        mangaTitle = parent.title, mangaImageUrl = parent.imageUrl, chapterUrl = chapter.url,
        chapterTitle = chapter.name, isDownloaded = true, localImagePaths = chapter.localImagePaths,
        lastReadPage = 6, totalPages = 20)
    db.backupDao().insertHistoryRow(history)
    val artifact = ChapterArtifactEntity(chapter.id, chapter.mangaId, chapter.url,
        committedToken = REPAIR_TOKEN, committedRelativePath = "_restored/$REPAIR_TOKEN/chapter.cbz")
    db.chapterArtifactDao().insert(artifact)
    return RepairTarget(original, notification.copy(id = notificationId),
        assertNotNull(db.backupDao().getHistoryByMangaUrl(parent.url)), artifact)
}

private fun ChapterDownloadCompletionFixture.rejectLastRepairWrite(target: RepairTarget) {
    executeWhileClosed(
        """
        CREATE TRIGGER reject_artifact_repair BEFORE UPDATE OF committedRelativePath ON chapter_artifacts
        WHEN NEW.chapterId = ${target.artifact.chapterId} AND NEW.committedRelativePath IS NULL
          AND EXISTS (SELECT 1 FROM saved_chapters WHERE id = NEW.chapterId AND isDownloaded = 0 AND localImagePaths = '[]')
          AND EXISTS (SELECT 1 FROM notifications WHERE id = ${target.notification.id} AND isDownloaded = 0 AND localImagePaths = '[]')
          AND EXISTS (SELECT 1 FROM history_items WHERE id = ${target.history.id} AND isDownloaded = 0 AND localImagePaths = '[]')
          AND EXISTS (SELECT 1 FROM chapter_downloads WHERE id = ${target.download.download.id}
              AND state = 'FAILED' AND progress = 0 AND sizeBytes = 0 AND errorMsg IS NULL)
        BEGIN SELECT RAISE(ABORT, 'reject_artifact_repair'); END
        """.trimIndent(),
    )
}

private suspend fun ChapterDownloadCompletionFixture.assertRepairTargetUnchanged(target: RepairTarget) {
    assertUnchanged(target.download)
    assertEquals(target.notification, db.notificationDao().getNotificationByChapterId(target.notification.chapterId))
    assertEquals(target.history, db.backupDao().getHistoryByMangaUrl(target.history.mangaUrl))
    assertEquals(target.artifact, db.chapterArtifactDao().get(target.artifact.chapterId))
}

private const val REPAIR_TOKEN = "12345678-1234-4234-8234-123456789abc"
