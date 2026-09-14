package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.filesystem.folderSize
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Production reconciliation with generated Room transactions and test-owned retained files. */
class DownloadsActionRecoveryTest {
    @Test
    fun committedSuccessRepairsSavedFlagWithoutRewritingPathsOrKnownSize() =
        downloadRecoveryTest {
            val unknownSize = seed(sizeBytes = 0L)
            val knownSize = seed(sizeBytes = 913L)
            val interrupted = seed(state = DownloadingState.RUNNING)
            val actualSize =
                appFileSystem.folderSize(
                    appFileSystem.chapterDir(unknownSize.saved.mangaId, unknownSize.saved.id),
                )
            reopen()
            assertFalse(saved(unknownSize).isDownloaded)
            assertEquals(DownloadingState.SUCCESS, download(unknownSize).state)

            repeat(2) { iteration ->
                assertTrue(actions().reconcileInterrupted().isSuccess)
                assertEquals(iteration + 1, restartCalls)
                assertEquals(unknownSize.saved.copy(isDownloaded = true), saved(unknownSize))
                assertEquals(unknownSize.download.copy(sizeBytes = actualSize), download(unknownSize))
                assertEquals(knownSize.saved.copy(isDownloaded = true), saved(knownSize))
                assertEquals(knownSize.download, download(knownSize), "nonzero ledger size is not a repair target")
                assertEquals(interrupted.saved, saved(interrupted))
                assertEquals(
                    interrupted.download.copy(state = DownloadingState.QUEUED, progress = 0),
                    download(interrupted),
                )
                assertRetainedFiles(unknownSize)
                assertRetainedFiles(knownSize)
                reopen()
            }
        }

    @Test
    fun interruptedFullDeletionIsNotResurrected() =
        downloadRecoveryTest {
            val deleted = seed(isDownloaded = true)
            // The first production full-delete operation commits before files/history are removed.
            db.chapterDao().markChaptersNotDownloaded(listOf(deleted.saved.id))
            reopen()
            assertRetainedFiles(deleted)
            assertEquals(deleted.download, download(deleted))

            assertTrue(actions().reconcileInterrupted().isSuccess)
            assertEquals(deleted.saved.copy(isDownloaded = false, localImagePaths = emptyList()), saved(deleted))
            assertEquals(deleted.download, download(deleted))
            assertRetainedFiles(deleted)
        }

    @Test
    fun unreadableOrBlankPathsAreNotPromoted() =
        downloadRecoveryTest {
            val missingFile = seed()
            fs.delete(
                missingFile.saved.localImagePaths
                    .last()
                    .toPath(),
            )
            val emptyFile = seed()
            fs.write(
                emptyFile.saved.localImagePaths
                    .first()
                    .toPath(),
            ) { writeUtf8("") }
            val directory = seed()
            val directoryPath =
                directory.saved.localImagePaths
                    .first()
                    .toPath()
            fs.delete(directoryPath)
            fs.createDirectories(directoryPath)
            val blank = seed()
            val blankSaved = blank.saved.copy(localImagePaths = listOf(" "))
            db.backupDao().updateChapterRow(blankSaved)
            val valid = seed()
            assertTrue(actions().reconcileInterrupted().isSuccess)
            for (original in listOf(missingFile, emptyFile, directory)) {
                assertEquals(original.saved, saved(original))
                assertEquals(original.download, download(original))
            }
            assertEquals(blankSaved, saved(blank))
            assertEquals(blank.download, download(blank))
            assertEquals(valid.saved.copy(isDownloaded = true), saved(valid))
            assertRetainedFiles(valid)
        }

    @Test
    fun unmatchedSavedRowsAndFailedDownloadsAreNotRepairCandidates() =
        downloadRecoveryTest {
            val missingSaved = seed()
            db.chapterDao().deleteChapterById(missingSaved.saved.id)
            val wrongOwner = seed()
            val wrongOwnerSaved = wrongOwner.saved.copy(mangaId = missingSaved.saved.mangaId)
            db.backupDao().updateChapterRow(wrongOwnerSaved)
            val failed = seed(state = DownloadingState.FAILED)
            val valid = seed()

            assertTrue(actions().reconcileInterrupted().isSuccess)
            assertNull(db.chapterDao().getChapterByIdSuspend(missingSaved.saved.id))
            assertEquals(missingSaved.download, download(missingSaved))
            assertEquals(wrongOwnerSaved, saved(wrongOwner))
            assertEquals(wrongOwner.download, download(wrongOwner))
            assertEquals(failed.saved, saved(failed))
            assertEquals(failed.download, download(failed))
            assertEquals(
                valid.saved.copy(isDownloaded = true),
                saved(valid),
                "negative cases do not disable valid repair",
            )
            assertRetainedFiles(valid)
        }

    @Test
    fun fileOpenFailureDoesNotAbortOtherRepairs() =
        downloadRecoveryTest {
            val unreadable = seed()
            val valid = seed()
            val unreadablePath =
                unreadable.saved.localImagePaths
                    .first()
                    .toPath()
            var attemptedReads = 0
            val failingFileSystem =
                object : ForwardingFileSystem(fs) {
                    override fun source(file: Path): Source {
                        if (file == unreadablePath) {
                            attemptedReads++
                            throw IOException("test read failure")
                        }
                        return super.source(file)
                    }
                }
            val appFs =
                object : AppFileSystem by appFileSystem {
                    override fun fileSystem(): FileSystem = failingFileSystem
                }

            // Fault only the open of one real nonempty file; permission tests are unreliable as root.
            assertTrue(actions(fileSystem = appFs).reconcileInterrupted().isSuccess)
            assertEquals(1, attemptedReads)
            assertEquals(unreadable.saved, saved(unreadable))
            assertEquals(unreadable.download, download(unreadable))
            assertRetainedFiles(unreadable)
            assertEquals(valid.saved.copy(isDownloaded = true), saved(valid))
            assertRetainedFiles(valid)
        }

    @Test
    fun fileValidationCancellationPropagates() =
        downloadRecoveryTest {
            val original = seed()
            val cancelledFileSystem =
                object : ForwardingFileSystem(fs) {
                    override fun source(file: Path): Source = throw CancellationException("cancel repair")
                }
            val appFs =
                object : AppFileSystem by appFileSystem {
                    override fun fileSystem(): FileSystem = cancelledFileSystem
                }

            assertFailsWith<CancellationException> {
                actions(fileSystem = appFs).reconcileInterrupted()
            }
            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
            assertRetainedFiles(original)
        }

    @Test
    fun repairRechecksPathsAndSavedIdentityAfterFileReads() =
        downloadRecoveryTest {
            val clearedPaths = seed()
            val changedPaths = seed()
            val changedSaved = seed()
            val valid = seed()
            val newPaths = changedPaths.saved.localImagePaths.reversed()
            val replacementSaved = changedSaved.saved.copy(url = "https://example.test/replaced-after-read")
            val results =
                reconcileAfterFileReads { expected ->
                    when (expected.chapterId) {
                        clearedPaths.saved.id -> db.chapterDao().markChaptersNotDownloaded(listOf(expected.chapterId))
                        changedPaths.saved.id ->
                            db.backupDao().updateChapterRow(changedPaths.saved.copy(localImagePaths = newPaths))
                        changedSaved.saved.id -> db.backupDao().updateChapterRow(replacementSaved)
                        else -> Unit
                    }
                }
            assertEquals(
                mapOf(
                    clearedPaths.saved.id to false,
                    changedPaths.saved.id to false,
                    changedSaved.saved.id to false,
                    valid.saved.id to true,
                ),
                results,
            )
            assertEquals(clearedPaths.saved.copy(localImagePaths = emptyList()), saved(clearedPaths))
            assertEquals(changedPaths.saved.copy(localImagePaths = newPaths), saved(changedPaths))
            assertEquals(replacementSaved, saved(changedSaved))
            assertEquals(valid.saved.copy(isDownloaded = true), saved(valid))
            for (original in listOf(clearedPaths, changedPaths, changedSaved, valid)) {
                assertEquals(original.download, download(original))
                assertRetainedFiles(original)
            }
        }

    @Test
    fun repairRechecksCancelledDeletedAndReplacedLedgers() =
        downloadRecoveryTest {
            val cancelled = seed()
            val deleted = seed()
            val replaced = seed()
            val valid = seed()
            var replacement: ChapterDownloadEntity? = null
            val results =
                reconcileAfterFileReads { expected ->
                    when (expected.chapterId) {
                        cancelled.saved.id -> dao.updateFailure(expected.chapterId, "cancelled during read")
                        deleted.saved.id -> dao.deleteByChapterId(expected.chapterId)
                        replaced.saved.id -> {
                            val next = replaced.download.copy(id = 0, sizeBytes = 777L)
                            replacement = next.copy(id = dao.insert(next))
                        }
                        else -> Unit
                    }
                }
            assertEquals(
                mapOf(
                    cancelled.saved.id to false,
                    deleted.saved.id to false,
                    replaced.saved.id to false,
                    valid.saved.id to true,
                ),
                results,
            )
            assertEquals(
                cancelled.download.copy(state = DownloadingState.FAILED, errorMsg = "cancelled during read"),
                download(cancelled),
            )
            assertNull(dao.getDownloadByChapter(deleted.saved.id))
            assertEquals(assertNotNull(replacement), download(replaced))
            assertEquals(valid.saved.copy(isDownloaded = true), saved(valid))
            for (original in listOf(cancelled, deleted, replaced)) {
                assertEquals(original.saved, saved(original))
                assertRetainedFiles(original)
            }
        }

    @Test
    fun historyOnlyAndFullDeleteRemainDistinctAfterReconcile() =
        downloadRecoveryTest {
            val historyOnly = seed(isDownloaded = true)
            val fullDelete = seed(isDownloaded = true)

            assertTrue(actions().deleteDownload(historyOnly.saved.id).isSuccess)
            assertTrue(actions().deleteDownloadedChapter(fullDelete.saved.id).isSuccess)
            reopen()
            assertTrue(actions().reconcileInterrupted().isSuccess)

            assertNull(dao.getDownloadByChapter(historyOnly.saved.id))
            assertEquals(historyOnly.saved, saved(historyOnly), "saved-only offline fallback is intentional")
            assertRetainedFiles(historyOnly)
            assertNull(dao.getDownloadByChapter(fullDelete.saved.id))
            assertEquals(fullDelete.saved.copy(isDownloaded = false, localImagePaths = emptyList()), saved(fullDelete))
            assertFalse(fs.exists(appFileSystem.chapterDir(fullDelete.saved.mangaId, fullDelete.saved.id)))
        }
}
