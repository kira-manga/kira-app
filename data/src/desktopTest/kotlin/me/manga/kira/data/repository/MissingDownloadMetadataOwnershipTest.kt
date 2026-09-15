package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.manga.kira.data.local.dao.ArtifactRepairSnapshot
import me.manga.kira.data.local.dao.ChapterArtifactRepairDao
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Both real producer/file-pin orderings; the injected pause never substitutes a Room outcome. */
class MissingDownloadMetadataOwnershipTest {
    @Test
    fun enqueueDuringPinnedProbeInvalidatesRepairBeforeNewPublisherCanTouchFiles() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        fs.deleteRecursively(appFileSystem.chapterDir(original.saved.mangaId, original.saved.id))
        val paused = PausedRepairDao(db.chapterArtifactRepairDao())
        coroutineScope {
            val repair = async { repairMissingMetadata(records = paused) }
            paused.entered.await()
            try {
                val claim = assertNotNull(artifactRuntime.downloads.enqueue(original.saved,
                    original.download.copy(state = DownloadingState.QUEUED)))
                val filesEntered = CompletableDeferred<Unit>()
                val attempted = CompletableDeferred<Unit>()
                val publisher = async { publishValidPages(original, claim, filesEntered, attempted = attempted) }
                attempted.await()
                assertFalse(filesEntered.isCompleted, "repair still owns the real file pin")
                paused.release.complete(Unit)
                assertEquals(0, repair.await())
                assertTrue(publisher.await())
            } finally {
                paused.release.complete(Unit)
            }
        }
        assertEquals(listOf(false), paused.outcomes, "new custody makes the old absence proof stale")
        assertTrue(saved(original).isDownloaded)
        assertEquals(DownloadingState.SUCCESS, download(original).state)
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
        assertEquals(0, restartCalls)
    }

    @Test
    fun producerHoldingFilePinCompletesBeforeRepairCanObserveOrChangeItsMetadata() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        fs.deleteRecursively(appFileSystem.chapterDir(original.saved.mangaId, original.saved.id))
        val claim = assertNotNull(artifactRuntime.downloads.enqueue(original.saved,
            original.download.copy(state = DownloadingState.QUEUED)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val candidatesRead = CompletableDeferred<Unit>()
        val real = db.chapterArtifactRepairDao()
        val observed = object : ChapterArtifactRepairDao by real {
            override suspend fun candidateIds(): List<Long> = real.candidateIds().also { candidatesRead.complete(Unit) }
        }
        coroutineScope {
            val publisher = async { publishValidPages(original, claim, entered, release) }
            entered.await()
            try {
                val repair = async { repairMissingMetadata(records = observed) }
                candidatesRead.await()
                assertFalse(repair.isCompleted, "the producer holds the shared file lock")
                release.complete(Unit)
                assertTrue(publisher.await())
                assertEquals(0, repair.await())
            } finally { release.complete(Unit) }
        }
        assertEquals(original.saved, saved(original))
        assertEquals(DownloadingState.SUCCESS, download(original).state)
        assertTrue(download(original).sizeBytes > 0)
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
    }
}

private class PausedRepairDao(private val real: ChapterArtifactRepairDao) : ChapterArtifactRepairDao by real {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val outcomes = mutableListOf<Boolean>()

    override suspend fun clearMissing(expected: ArtifactRepairSnapshot): Boolean {
        entered.complete(Unit)
        release.await()
        return real.clearMissing(expected).also { outcomes += it }
    }
}

private suspend fun DownloadRecoveryFixture.publishValidPages(
    original: RetainedDownload,
    claim: ChapterArtifactClaim,
    entered: CompletableDeferred<Unit>? = null,
    release: CompletableDeferred<Unit>? = null,
    attempted: CompletableDeferred<Unit>? = null,
): Boolean {
    val row = download(original)
    attempted?.complete(Unit)
    assertTrue(artifactRuntime.ownership.files(claim) {
        entered?.complete(Unit)
        release?.await()
        fs.createDirectories(appFileSystem.chapterDir(original.saved.mangaId, original.saved.id))
        installValidPages(original)
        artifactRuntime.downloads.complete(claim, row, original.saved.localImagePaths)
    } == true)
    return artifactRuntime.downloads.settle(claim)
}
