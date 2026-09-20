package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.local.dao.ArtifactRepairSnapshot
import me.manga.kira.data.local.dao.ChapterArtifactRepairDao
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real metadata-only reopen, generated Room writes and owned files; no OS-backup claim. */
class MissingDownloadMetadataRepairTest {
    @Test
    fun absentRootClearsOnlyOfflineClaimsAndSurvivesReopenWithoutRecreatingHistory() = downloadRecoveryTest {
        val completed = seed(isDownloaded = true)
        val savedOnly = seed(isDownloaded = true)
        val failed = seed(state = DownloadingState.FAILED, isDownloaded = true)
        dao.deleteByChapterId(savedOnly.saved.id)
        val mirrors = listOf(completed, savedOnly, failed).map { offlineMirrors(it) }
        val parents = db.backupDao().getAllSavedManga()
        fs.deleteRecursively(appFileSystem.filesDir / "manga")
        reopen()
        repeat(2) {
            assertTrue(actions().reconcileInterrupted().isSuccess)
            assertMissingMetadata(completed)
            assertMissingMetadata(savedOnly, ledgerAbsent = true)
            assertMissingMetadata(failed)
            mirrors.forEach { assertMirrorsCleared(it) }
            assertEquals(parents, db.backupDao().getAllSavedManga())
            assertEquals(it + 1, restartCalls, "existing engine reconciliation still runs once")
            reopen()
        }
    }

    @Test
    fun incompleteLooseRosterRepairsOnlyWhenCanonicalFallbackIsAlsoAbsent() = downloadRecoveryTest {
        val missing = seed(isDownloaded = true)
        val fallback = seed(isDownloaded = true)
        installValidPages(missing)
        val retained = installValidPages(fallback)
        val (archive, archiveBytes) = installPreviousArchive(fallback)
        fs.delete(missing.saved.localImagePaths.last().toPath())
        fs.delete(fallback.saved.localImagePaths.last().toPath())
        assertEquals(0, repairMissingMetadata())
        assertMissingMetadata(missing)
        assertEquals(fallback.saved, saved(fallback))
        assertEquals(fallback.download, download(fallback))
        assertContentEquals(archiveBytes, fs.read(archive) { readByteArray() })
        retained.entries.first().let { assertContentEquals(it.value, fs.read(it.key) { readByteArray() }) }
    }

    @Test
    fun exactMissingRestoredGenerationDoesNotAdoptOldCanonicalAndAllowsExplicitImport() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val (oldArchive, bytes) = installPreviousArchive(original)
        val relative = ChapterArtifactReference.restored(RESTORED_TOKEN)
        val record = ChapterArtifactEntity(original.saved.id, original.saved.mangaId, original.saved.url,
            committedToken = RESTORED_TOKEN, committedRelativePath = relative)
        artifactRuntime.dao.insert(record)
        assertNull(artifactRuntime.ownership.beginRestore(original.saved, bytes.size.toLong()))
        assertEquals(0, repairMissingMetadata())
        assertMissingMetadata(original)
        assertEquals(record.copy(committedToken = null, committedRelativePath = null), artifactRuntime.dao.get(original.saved.id))
        assertContentEquals(bytes, fs.read(oldArchive) { readByteArray() })
        assertRetainedFiles(original)
        assertNotNull(artifactRuntime.ownership.beginRestore(saved(original), bytes.size.toLong()))
    }

    @Test
    fun existingCorruptOrEmptyBytesAreNotNegativeEvidence() = downloadRecoveryTest {
        val corruptArchive = seed(isDownloaded = true)
        val emptyPage = seed(isDownloaded = true)
        val archive = appFileSystem.chapterDir(corruptArchive.saved.mangaId, corruptArchive.saved.id) /
            "chapter_${corruptArchive.saved.id}.cbz"
        fs.write(archive) { writeUtf8("not an archive") }
        fs.delete(corruptArchive.saved.localImagePaths.last().toPath())
        fs.write(emptyPage.saved.localImagePaths.first().toPath()) { }
        assertEquals(0, repairMissingMetadata())
        for (original in listOf(corruptArchive, emptyPage)) {
            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
        }
        assertEquals("not an archive", fs.read(archive) { readUtf8() })
    }

    @Test
    fun mirrorsWithDifferentOwnerIdentityRemainUntouched() = downloadRecoveryTest {
        val originals = (0..2).map { seed(isDownloaded = true) }
        val sentinels = originals.mapIndexed { index, original ->
            val (notification, history) = offlineMirrors(original)
            val foreignNotification = when (index) {
                0 -> notification.copy(api = "other")
                1 -> notification.copy(mangaId = notification.mangaId + 100)
                else -> notification.copy(chapterUrl = "${notification.chapterUrl}/other")
            }
            val foreignHistory = when (index) {
                0 -> history.copy(api = "other")
                1 -> history.copy(mangaUrl = "${history.mangaUrl}/other")
                else -> history.copy(chapterUrl = "${history.chapterUrl}/other")
            }
            db.notificationDao().updateNotification(foreignNotification)
            db.historyDao().updateHistory(foreignHistory)
            foreignNotification to foreignHistory
        }
        fs.deleteRecursively(appFileSystem.filesDir / "manga")
        assertEquals(0, repairMissingMetadata())
        originals.forEach { assertMissingMetadata(it) }
        sentinels.forEach { (notification, history) ->
            assertEquals(notification, db.notificationDao().getNotificationByChapterId(notification.chapterId))
            assertEquals(history, db.backupDao().getAllHistoryOnce().single { it.id == history.id })
        }
    }

    @Test
    fun failedRepairIsReportedAfterUnrelatedRowsFinishWithoutExposingPrivateDetails() = downloadRecoveryTest {
        val blocked = seed(isDownloaded = true)
        val other = seed(isDownloaded = true)
        fs.deleteRecursively(appFileSystem.filesDir / "manga")
        val real = db.chapterArtifactRepairDao()
        val failing = object : ChapterArtifactRepairDao by real {
            override suspend fun clearMissing(expected: ArtifactRepairSnapshot): Boolean {
                if (expected.chapter.id == blocked.saved.id) throw IOException("private database detail")
                return real.clearMissing(expected)
            }
        }
        val result = actions(repairDao = failing).reconcileInterrupted()
        assertFalse(result.isSuccess)
        assertEquals("Download maintenance could not be completed", result.exceptionOrNull()?.message)
        assertEquals(blocked.saved, saved(blocked))
        assertEquals(blocked.download, download(blocked))
        assertMissingMetadata(other)
        assertEquals(1, restartCalls)
    }

    @Test
    fun unknownFilesystemFailureDefersAndProbeCancellationPropagatesWithoutMutation() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val relative = ChapterArtifactReference.restored(RESTORED_TOKEN)
        val missing = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / relative
        fs.createDirectories(assertNotNull(missing.parent))
        val receipt = ChapterArtifactEntity(original.saved.id, original.saved.mangaId, original.saved.url,
            committedToken = RESTORED_TOKEN, committedRelativePath = relative)
        artifactRuntime.dao.insert(receipt)
        var cancel = false
        var observations = 0
        val failing = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                if (path == missing) {
                    observations++
                    if (cancel) throw CancellationException("cancel missing-file probe")
                    throw IOException("protected-data or unknown IO")
                }
                return super.metadataOrNull(path)
            }
        }
        val appFs = object : AppFileSystem by appFileSystem { override fun fileSystem(): FileSystem = failing }
        assertEquals(0, repairMissingMetadata(files = appFs))
        cancel = true
        assertFailsWith<CancellationException> { repairMissingMetadata(files = appFs) }
        assertEquals(2, observations)
        assertEquals(original.saved, saved(original))
        assertEquals(original.download, download(original))
        assertEquals(receipt, artifactRuntime.dao.get(original.saved.id))
        assertRetainedFiles(original)
    }

    @Test
    fun activeStatesAndEveryUnsettledCustodyFieldAreExcluded() = downloadRecoveryTest {
        // Finish ordinary runtime recovery first; this test measures repair, not startup settlement.
        artifactRuntime.ownership.read(0) { }
        val active = listOf(DownloadingState.QUEUED, DownloadingState.RUNNING,
            DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING).map { seed(it, isDownloaded = true) }
        val unsettled = (0..8).map { seed(isDownloaded = true) }
        val records = unsettled.mapIndexed { index, original ->
            val base = ChapterArtifactEntity(original.saved.id, original.saved.mangaId, original.saved.url)
            when (index) {
                0 -> base.copy(token = RESTORED_TOKEN)
                1 -> base.copy(operation = "restore")
                2 -> base.copy(retiring = true)
                3 -> base.copy(downloadId = original.download.id)
                4 -> base.copy(pendingRelativePath = ChapterArtifactReference.restored(RESTORED_TOKEN))
                5 -> base.copy(pendingSizeBytes = 10)
                6 -> base.copy(ownsPendingPath = true)
                7 -> base.copy(retiredRelativePath = ChapterArtifactReference.restored(RESTORED_TOKEN))
                else -> base.copy(conversionSourceRoster = """["/old/1.jpg"]""")
            }.also { artifactRuntime.dao.insert(it) }
        }
        fs.deleteRecursively(appFileSystem.filesDir / "manga")
        assertEquals(0, repairMissingMetadata())
        for (original in active + unsettled) {
            assertEquals(original.saved, saved(original))
            assertEquals(original.download, download(original))
        }
        records.forEach { assertEquals(it, artifactRuntime.dao.get(it.chapterId)) }
    }
}

private const val RESTORED_TOKEN = "12345678-1234-4234-8234-123456789abc"
