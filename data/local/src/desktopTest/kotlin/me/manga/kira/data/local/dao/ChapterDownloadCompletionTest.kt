package me.manga.kira.data.local.dao

import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Generated Room transactions against a file-backed database, not a fake DAO transaction. */
class ChapterDownloadCompletionTest {
    @Test
    fun looseAndCbzCompletionCommitBothRowsAndSurviveReopen() =
        completionTest {
            val loose = seed(DownloadingState.RUNNING, listOf("/offline/1.jpg", "/offline/2.jpg"))
            val cbz = seed(DownloadingState.COMPRESSING, listOf("/offline/chapter.cbz"))

            assertTrue(
                dao.completeDownload(loose.download, loose.saved.localImagePaths, COMPLETION_SIZE_BYTES),
            )
            // The worker's best-effort size failure uses zero (unknown), not a failed download.
            assertTrue(dao.completeDownload(cbz.download, cbz.saved.localImagePaths, 0L))
            assertCompleted(loose, COMPLETION_SIZE_BYTES)
            assertCompleted(cbz, 0L)

            reopen()
            assertCompleted(loose, COMPLETION_SIZE_BYTES)
            assertCompleted(cbz, 0L)
            assertFalse(
                dao.completeDownload(loose.download, loose.saved.localImagePaths, REPEAT_SIZE_BYTES),
            )
            assertCompleted(loose, COMPLETION_SIZE_BYTES)
        }

    @Test
    fun savedWriteFailureRollsBackLedgerAndSurvivesReopen() =
        completionTest {
            val original = seed(DownloadingState.COMPRESSING, listOf("/offline/chapter.cbz"))
            val size = ROLLBACK_SIZE_BYTES
            rejectSavedCompletionWhenLedgerWritten(original, size)

            val failure =
                assertFails {
                    dao.completeDownload(original.download, original.saved.localImagePaths, size)
                }
            assertTrue(failure.message.orEmpty().contains("reject_saved_completion"))
            assertUnchanged(original)
            reopen()
            assertUnchanged(original)

            // No-fault control uses the same persisted rows, not a different fixture or fake.
            executeWhileClosed("DROP TRIGGER reject_saved_completion")
            assertTrue(dao.completeDownload(original.download, original.saved.localImagePaths, size))
            assertCompleted(original, size)
        }

    @Test
    fun zeroSavedRowsFailAndRollBackLedger() =
        completionTest {
            val original = seed(DownloadingState.RUNNING, listOf("/offline/1.jpg"))
            executeWhileClosed(
                """
                CREATE TRIGGER ignore_saved_completion
                BEFORE UPDATE OF isDownloaded ON saved_chapters
                WHEN NEW.id = ${original.saved.id} AND NEW.isDownloaded = 1
                  AND EXISTS (
                    SELECT 1 FROM chapter_downloads
                    WHERE id = ${original.download.id} AND state = 'SUCCESS' AND sizeBytes = $IGNORED_WRITE_SIZE_BYTES
                  )
                BEGIN
                    SELECT RAISE(IGNORE);
                END
                """.trimIndent(),
            )

            // SQLite itself does not throw for IGNORE; the checked affected-row count must do so.
            assertFailsWith<IllegalStateException> {
                dao.completeDownload(original.download, original.saved.localImagePaths, IGNORED_WRITE_SIZE_BYTES)
            }
            assertUnchanged(original)
            reopen()
            assertUnchanged(original)
        }

    @Test
    fun zeroLedgerRowsDoNotMarkSavedChapterDownloaded() =
        completionTest {
            val original = seed(DownloadingState.RUNNING, listOf("/offline/1.jpg"))
            executeWhileClosed(
                """
                CREATE TRIGGER ignore_ledger_completion
                BEFORE UPDATE OF state ON chapter_downloads
                WHEN NEW.id = ${original.download.id} AND NEW.state = 'SUCCESS'
                BEGIN
                    SELECT RAISE(IGNORE);
                END
                """.trimIndent(),
            )

            assertFailsWith<IllegalStateException> {
                dao.completeDownload(original.download, original.saved.localImagePaths, IGNORED_WRITE_SIZE_BYTES)
            }
            assertUnchanged(original)
            reopen()
            assertUnchanged(original)
        }

    @Test
    fun missingOrChangedSavedChapterCannotPublishSuccess() =
        completionTest {
            val missing = seed(DownloadingState.RUNNING, listOf("/offline/missing.jpg"))
            db.chapterDao().deleteChapterById(missing.saved.id)
            // chapter_downloads has a manga FK, not a saved-chapter FK: this ledger really survives.
            assertEquals(missing.download, dao.getDownloadByChapter(missing.saved.id))
            assertInvalidSavedChapter(missing, expectedSaved = null)

            val changedUrl = seed(DownloadingState.RUNNING, listOf("/offline/url.jpg"))
            val changedManga = seed(DownloadingState.RUNNING, listOf("/offline/manga.jpg"))
            val changedPaths = seed(DownloadingState.COMPRESSING, listOf("/offline/old.cbz"))
            val changes =
                listOf(
                    changedUrl to changedUrl.saved.copy(url = "https://example.test/replaced-chapter"),
                    changedManga to changedManga.saved.copy(mangaId = missing.saved.mangaId),
                    changedPaths to changedPaths.saved.copy(localImagePaths = emptyList()),
                )
            for ((original, changed) in changes) {
                db.backupDao().updateChapterRow(changed)
                assertInvalidSavedChapter(original, expectedSaved = changed)
            }
        }

    @Test
    fun ineligibleLedgerIsNotCompletedOrRecreated() =
        completionTest {
            for (state in listOf(DownloadingState.QUEUED, DownloadingState.FAILED, DownloadingState.SUCCESS)) {
                val original = seed(DownloadingState.RUNNING, listOf("/offline/$state.jpg"))
                dao.updateStateChId(original.saved.id, state)
                assertFalse(
                    dao.completeDownload(original.download, original.saved.localImagePaths, INELIGIBLE_SIZE_BYTES),
                )
                assertEquals(original.download.copy(state = state), dao.getDownloadByChapter(original.saved.id))
                assertEquals(original.saved, db.chapterDao().getChapterByIdSuspend(original.saved.id))
            }

            val deleted = seed(DownloadingState.RUNNING, listOf("/offline/deleted.jpg"))
            dao.deleteByChapterId(deleted.saved.id)
            assertFalse(
                dao.completeDownload(deleted.download, deleted.saved.localImagePaths, INELIGIBLE_SIZE_BYTES),
            )
            assertNull(dao.getDownloadByChapter(deleted.saved.id))
            assertEquals(deleted.saved, db.chapterDao().getChapterByIdSuspend(deleted.saved.id))

            val replaced = seed(DownloadingState.RUNNING, listOf("/offline/replaced.jpg"))
            val replacement = replaced.download.copy(id = 0, progress = 12)
            val replacementId = dao.insert(replacement)
            assertTrue(replacementId != replaced.download.id)
            assertFalse(
                dao.completeDownload(replaced.download, replaced.saved.localImagePaths, INELIGIBLE_SIZE_BYTES),
            )
            assertEquals(replacement.copy(id = replacementId), dao.getDownloadByChapter(replaced.saved.id))
            assertEquals(replaced.saved, db.chapterDao().getChapterByIdSuspend(replaced.saved.id))
        }
}
