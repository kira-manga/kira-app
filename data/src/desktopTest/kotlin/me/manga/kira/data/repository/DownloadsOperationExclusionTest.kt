package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.manga.kira.data.download.selection.DownloadCatalogNotReady
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.download.DownloadOperationBusy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Real Room capture with a controlled downstream consumer; not a platform engine witness. */
class DownloadsOperationExclusionTest {
    @Test
    fun enqueueWaitsBeforeRoomCaptureAndRetainsOwnershipThroughConsumerReturn() = downloadRecoveryTest {
        val retained = seed()
        val captured = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<SavedChapterEntity>()
        val finishConsumer = CompletableDeferred<Unit>()
        val realChapters = db.chapterDao()
        val chapters = object : ChapterDao by realChapters {
            override suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity? {
                captured.complete(Unit)
                return realChapters.getChapterByIdSuspend(chapterId)
            }
        }
        val consumer = object : FakeDownloadRepository() {
            override suspend fun enqueueChapterDownload(chapter: SavedChapterEntity, title: String, mangaApi: String) {
                delivered.complete(chapter)
                finishConsumer.await()
            }
        }
        val actions = DownloadsActionRepositoryImpl(
            consumer,
            DownloadsActionStorage(dao, chapters, appFileSystem, artifactRuntime.ownership, db.chapterArtifactRepairDao()),
            downloadOperations,
            TestDownloadCatalogAdmission(downloadOperations),        )
        coroutineScope {
            // This scope predates the exclusive context: the action is an independent entrant,
            // never an illegal exclusive-to-operation upgrade in the test coroutine.
            val entrants = this
            val pending = downloadOperations.withExclusive {
                entrants.async(start = CoroutineStart.UNDISPATCHED) {
                    actions.enqueueDownload(retained.saved.id, "retained", "source")
                }.also {
                    assertFalse(captured.isCompleted)
                    assertFalse(it.isCompleted)
                }
            }
            try {
                assertEquals(retained.saved, delivered.await())
                assertTrue(captured.isCompleted)
                assertFailsWith<DownloadOperationBusy> { downloadOperations.withExclusive {} }
            } finally {
                finishConsumer.complete(Unit)
            }
            assertTrue(pending.await().isSuccess)
            downloadOperations.withExclusive { assertTrue(pending.isCompleted) }
        }
    }

    @Test
    fun unreadyNewWorkPreparesOutsideOwnershipAndDoesNotCaptureOrBlockCancellation() = downloadRecoveryTest {
        val retained = seed()
        val witness = UnreadyDownloadActions(this)
        val actions = witness.actions
        assertIs<DownloadCatalogNotReady>(actions.enqueueDownload(retained.saved.id, "retained", "source").exceptionOrNull())
        assertIs<DownloadCatalogNotReady>(actions.retryDownload(retained.saved.id).exceptionOrNull())
        assertIs<DownloadCatalogNotReady>(actions.reconcileInterrupted().exceptionOrNull())
        assertEquals(3, witness.preparations)
        assertEquals(0, witness.captures)
        assertEquals(retained.download, dao.getDownloadByChapter(retained.saved.id))
        assertTrue(actions.cancelDownload(retained.saved.id).isSuccess)
        assertEquals(1, witness.cancelled)
        assertEquals(3, witness.preparations, "original cleanup does not bootstrap or reacquire readiness")
    }

}

private class UnreadyDownloadActions(fixture: DownloadRecoveryFixture) {
    var preparations = 0
    var captures = 0
    var cancelled = 0
    val chapters = object : ChapterDao by fixture.db.chapterDao() {
        override suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity? {
            captures++
            return fixture.db.chapterDao().getChapterByIdSuspend(chapterId)
        }
    }
    val downloads = object : ChapterDownloadDao by fixture.dao {
        override suspend fun getDownloadByChapter(chapterId: Long): me.manga.kira.data.local.entity.ChapterDownloadEntity? {
            captures++
            return fixture.dao.getDownloadByChapter(chapterId)
        }
    }
    val actions = DownloadsActionRepositoryImpl(
        object : FakeDownloadRepository() {
            override suspend fun reconcileInterruptedDownloads() = error("unready restart")
            override suspend fun onCancel(chapterId: Long) { cancelled++ }
        },
        DownloadsActionStorage(downloads, chapters, fixture.appFileSystem, fixture.artifactRuntime.ownership, fixture.db.chapterArtifactRepairDao()),
        fixture.downloadOperations,
        TestDownloadCatalogAdmission(
            fixture.downloadOperations,
            prepare = { fixture.downloadOperations.withExclusive { preparations++ } },
            checkReady = { throw DownloadCatalogNotReady() },
        ),
    )
}
