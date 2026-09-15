package me.manga.kira.data.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import okio.IOException
import okio.Path.Companion.toPath

class RestoredDownloadPublisherTest {
    @Test
    fun ownerClaimedBeforeCopyRefusesRestoreWithoutLiveFileChanges() = restoredDownloadTest {
        assertNotNull(artifacts.enqueue(saved, row))
        assertEquals(ChapterRestoreOutcome.NOT_COMMITTED, publish())
        assertEquals("incumbent-canonical", fs.read(canonical) { readUtf8() })
        assertFalse(db.chapterDao().getChapterByIdSuspend(saved.id)?.isDownloaded == true)
    }

    @Test
    fun ownerCannotClaimDuringCopy() = restoredDownloadTest {
        fs.duringCopy = { runBlocking { assertNull(db.chapterArtifactDao().enqueue(saved, row, "racing-attempt")) } }
        assertEquals(ChapterRestoreOutcome.COMMITTED, publish())
        assertEquals("incumbent-canonical", fs.read(canonical) { readUtf8() })
    }

    @Test
    fun ownerCannotClaimAfterPromotionBeforeRoomPublication() = restoredDownloadTest {
        fs.afterPromotion = { runBlocking { assertNull(db.chapterArtifactDao().enqueue(saved, row, "racing-attempt")) } }
        assertEquals(ChapterRestoreOutcome.COMMITTED, publish())
        val record = assertNotNull(db.chapterArtifactDao().get(saved.id))
        val path = ChapterArtifactReference.resolve(appFs, ChapterArtifactOwner.of(saved), assertNotNull(record.committedRelativePath))
        assertContentEquals(RestoredDownloadFixture.CONTENT, fs.read(path) { readByteArray() })
        assertEquals("incumbent-canonical", fs.read(canonical) { readUtf8() })
    }

    @Test
    fun knownNoncommitDeletesOnlyTheExclusiveGeneration() = restoredDownloadTest {
        decorateCommits { RestoreCommitFault(it, before = { throw IOException("before commit") }) }
        assertEquals(ChapterRestoreOutcome.NOT_COMMITTED, publish())
        val record = assertNotNull(db.chapterArtifactDao().get(saved.id))
        assertNull(record.token)
        assertNull(record.committedRelativePath)
        assertEquals("incumbent-canonical", fs.read(canonical) { readUtf8() })
        assertNotNull(artifacts.enqueue(saved, row))
    }

    @Test
    fun exceptionAfterRealCommitCannotCompensateOrReportFalse() = restoredDownloadTest {
        decorateCommits { RestoreCommitFault(it, after = { throw IOException("after commit") }) }
        assertEquals(ChapterRestoreOutcome.COMMITTED, publish())
        val savedAfter = assertNotNull(db.chapterDao().getChapterByIdSuspend(saved.id))
        assertTrue(savedAfter.isDownloaded)
        assertTrue(fs.exists(savedAfter.localImagePaths.single().toPath()))
    }

    @Test
    fun cancelledReturnAfterRealCommitPreservesTheCommittedArchive() = restoredDownloadTest {
        decorateCommits { RestoreCommitFault(it, after = { throw CancellationException("cancelled return") }) }
        assertFailsWith<CancellationException> { publish() }
        val record = assertNotNull(db.chapterArtifactDao().get(saved.id))
        val path = ChapterArtifactReference.resolve(appFs, ChapterArtifactOwner.of(saved), assertNotNull(record.committedRelativePath))
        assertContentEquals(RestoredDownloadFixture.CONTENT, fs.read(path) { readByteArray() })
        assertNull(record.token)
    }

    @Test
    fun unavailableCommitReadbackRetainsBytesAndDurableCustody() = restoredDownloadTest {
        decorateCommits { RestoreCommitFault(it, before = { throw IOException("commit unavailable") }, unreadableOutcome = true) }
        assertEquals(ChapterRestoreOutcome.UNKNOWN, publish())
        val record = assertNotNull(db.chapterArtifactDao().get(saved.id))
        assertNotNull(record.token)
        assertTrue(record.retiring)
        val path = ChapterArtifactReference.resolve(appFs, ChapterArtifactOwner.of(saved), assertNotNull(record.pendingRelativePath))
        assertContentEquals(RestoredDownloadFixture.CONTENT, fs.read(path) { readByteArray() })
        assertNull(artifacts.enqueue(saved, row))
    }

    @Test
    fun interruptedPromotedGenerationIsSettledAfterRealRoomReopen() = restoredDownloadTest {
        val claim = assertNotNull(artifacts.beginRestore(saved, source.sizeBytes))
        val path = ChapterArtifactReference.resolve(appFs, claim.owner, assertNotNull(claim.relativePath))
        fs.createDirectories(assertNotNull(path.parent))
        db.chapterArtifactDao().confirmPendingPathOwnership(saved.id, claim.token)
        fs.write(path) { write(RestoredDownloadFixture.CONTENT) }
        reopen()
        assertNotNull(artifacts.enqueue(saved, row)) // Admission first reconciles the abandoned restore.
        assertFalse(fs.exists(path))
        assertEquals("incumbent-canonical", fs.read(canonical) { readUtf8() })
    }

    @Test
    fun revokedProducerRetainsCustodyUntilItsRealFinallyUnwinds() = restoredDownloadTest {
        val claim = assertNotNull(artifacts.enqueue(saved, row))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        kotlinx.coroutines.coroutineScope {
            val producer = async {
                artifacts.producing(claim) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                }
            }
            entered.await()
            assertTrue(artifacts.revoke(claim))
            db.chapterDownloadingDao().updateFailure(saved.id, "cancelled")
            producer.cancel()
            assertNull(artifacts.beginRestore(saved, source.sizeBytes))
            val settlement = async { artifacts.settle(claim) { true } }
            yield()
            assertFalse(settlement.isCompleted)
            finish.complete(Unit)
            producer.join()
            assertTrue(settlement.await())
            assertNotNull(artifacts.beginRestore(saved, source.sizeBytes))
        }
    }
}
