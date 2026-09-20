package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.ArtifactRepairSnapshot
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Interpose only before the genuine generated Room transaction; never substitute its result. */
class IosRestoredDownloadSnapshotTest {
    @Test
    fun replacingOwnerLedgerPathsParentOrArtifactAfterAbsenceProbeRefusesTheStaleRepair() = runTest {
        for (change in listOf("owner", "ledger", "paths", "parent", "artifact")) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.downloadNamed(fixture.seed())
                fixture.prepareAttempt(chapter, DownloadingState.RUNNING, pageCount = 2)
                fixture.system.deleteRecursively(fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id))
                val mirrors = fixture.offlineMirrors(chapter)
                fixture.reopen()
                var expectedSaved = fixture.saved(chapter)
                var expectedRow = fixture.download(chapter)
                var expectedRecord = assertNotNull(fixture.db.chapterArtifactDao().get(chapter.saved.id))
                var expectedParent = assertNotNull(fixture.db.mangaDao().getMangaById(chapter.saved.mangaId))
                val outcome = CompletableDeferred<Boolean>()
                val real = fixture.db.chapterArtifactCommitDao()
                val commits = object : ChapterArtifactCommitDao by real {
                    override suspend fun failMissingRestoredDownload(claim: ChapterArtifactClaim, expected: ArtifactRepairSnapshot): Boolean {
                        assertFalse(outcome.isCompleted, "Only the single stale proof may reach this writer")
                        when (change) {
                            "owner" -> {
                                expectedSaved = expectedSaved.copy(url = "https://example.test/replacement-chapter")
                                fixture.db.backupDao().updateChapterRow(expectedSaved)
                            }
                            "ledger" -> {
                                val id = fixture.dao.insert(expectedRow.copy(id = 0, sizeBytes = 987L))
                                expectedRow = expectedRow.copy(id = id, sizeBytes = 987L)
                            }
                            "paths" -> {
                                val restored = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "restored.png"
                                fixture.system.createDirectories(assertNotNull(restored.parent))
                                fixture.system.write(restored) { write(chapter.pages.values.last()) }
                                expectedSaved = expectedSaved.copy(localImagePaths = listOf(restored.toString()))
                                fixture.db.backupDao().updateChapterRow(expectedSaved)
                            }
                            "parent" -> {
                                expectedParent = expectedParent.copy(url = "https://example.test/replacement-manga")
                                fixture.db.backupDao().updateMangaRow(expectedParent)
                            }
                            "artifact" -> {
                                expectedRecord = expectedRecord.copy(token = "replacement-artifact-token")
                                fixture.db.chapterArtifactDao().update(expectedRecord)
                            }
                        }
                        return real.failMissingRestoredDownload(claim, expected).also { outcome.complete(it) }
                    }
                }
                val runtime = ArtifactTestRuntime(fixture.db.chapterArtifactDao(), commits, fixture.appFileSystem,
                    IosPageMediaInspector(system = fixture.system))
                val transport = ArtifactTestTransport(fixture.operations, ready = true)
                fixture.engine(CoroutineScope(coroutineContext + host), transport, downloadArtifacts = runtime.downloads)
                assertFalse(outcome.await(), change)
                host.cancelAndJoin()
                assertEquals(expectedSaved, fixture.saved(chapter), change)
                assertEquals(expectedRow, fixture.download(chapter), change)
                assertEquals(expectedRecord, fixture.db.chapterArtifactDao().get(chapter.saved.id), change)
                assertEquals(expectedParent, fixture.db.mangaDao().getMangaById(chapter.saved.mangaId), change)
                assertEquals(mirrors.first, fixture.db.notificationDao().getNotificationByChapterId(chapter.saved.id))
                assertEquals(mirrors.second, fixture.db.backupDao().getAllHistoryOnce().single())
                assertTrue(transport.enqueued.isEmpty())
                assertTrue(transport.cancelled.isEmpty())
            } finally {
                host.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun concurrentReadingProgressIsPreservedByTheSuccessfulPartialUpdates() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.downloadNamed(fixture.seed())
            fixture.prepareAttempt(chapter, DownloadingState.RUNNING, pageCount = 2)
            fixture.system.deleteRecursively(fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id))
            fixture.reopen()
            val outcome = CompletableDeferred<Boolean>()
            val changed = chapter.saved.copy(lastReadPage = 19, lastReadDate = 456L, isRead = true)
            val real = fixture.db.chapterArtifactCommitDao()
            val commits = object : ChapterArtifactCommitDao by real {
                override suspend fun failMissingRestoredDownload(claim: ChapterArtifactClaim, expected: ArtifactRepairSnapshot): Boolean {
                    fixture.db.backupDao().updateChapterRow(changed)
                    return real.failMissingRestoredDownload(claim, expected).also { outcome.complete(it) }
                }
            }
            val runtime = ArtifactTestRuntime(fixture.db.chapterArtifactDao(), commits, fixture.appFileSystem,
                IosPageMediaInspector(system = fixture.system))
            val transport = ArtifactTestTransport(fixture.operations, ready = true)
            fixture.engine(CoroutineScope(coroutineContext + host), transport, downloadArtifacts = runtime.downloads)
            assertTrue(outcome.await())
            host.cancelAndJoin()
            assertEquals(changed.copy(isDownloaded = false, localImagePaths = emptyList()), fixture.saved(chapter))
            assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
            assertTrue(transport.enqueued.isEmpty())
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }
}
