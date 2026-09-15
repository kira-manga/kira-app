package me.manga.kira.data.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.claimOrNull
import me.manga.kira.presentation.features.download.data.DownloadingState
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
    fun interruptedRestorePartIsCompensatedAfterReopenWithoutTouchingOtherOwners() = restoredDownloadTest {
        val controls = seedRecoveryControls()
        val before = snapshot(saved.id)
        assertFalse(before.saved.isDownloaded)
        assertNull(before.download)
        val partial = RestoredDownloadFixture.CONTENT.copyOf(RestoredDownloadFixture.CONTENT.size / 2)
        val (claim, path) = stageRestorePart(partial)
        val interrupted = snapshot(saved.id, listOf(partPath(path)))
        val receipt = assertNotNull(interrupted.artifact)
        assertEquals(claim, receipt.claimOrNull())
        assertTrue(receipt.ownsPendingPath)
        assertFalse(receipt.retiring)
        assertNull(receipt.committedToken)
        assertNull(receipt.committedRelativePath)
        assertTrue(partial.isNotEmpty() && partial.size.toLong() < source.sizeBytes)
        assertContentEquals(partial, fs.read(partPath(path)) { readByteArray() })
        assertFalse(fs.exists(path))
        reopen()
        assertEquals(interrupted, snapshot(saved.id, interrupted.files.keys))
        artifacts.read(saved.id) { assertEquals(released(receipt), it) } // Normal first-use recovery.
        assertFalse(fs.exists(assertNotNull(path.parent)))
        assertEquals(before.saved, db.chapterDao().getChapterByIdSuspend(saved.id))
        assertEquals(before.download, db.chapterArtifactDao().download(saved.id))
        assertFalse(db.chapterArtifactDao().canPublish(claim))
        assertPreservedControls(controls)
        val next = assertNotNull(artifacts.enqueue(saved, row))
        assertNotEquals(claim.token, next.token)
    }

    @Test
    fun committedRestoreBeforeSettlementSurvivesReopenAndReleasesOnlyItsCustody() = restoredDownloadTest {
        val controls = seedRecoveryControls()
        val (claim, path) = stageRestorePart(RestoredDownloadFixture.CONTENT)
        fs.atomicMove(partPath(path), path)
        val terminal = row.copy(
            mangaTitle = "Test", state = DownloadingState.SUCCESS, progress = 100, sizeBytes = source.sizeBytes,
        )
        // Real Room commit, deliberately without the publisher's ordinary finally settlement.
        assertTrue(db.chapterArtifactCommitDao().commitRestore(claim, saved, path.toString(), terminal))
        val committed = snapshot(saved.id, listOf(path))
        val receipt = assertNotNull(committed.artifact)
        assertTrue(committed.saved.isDownloaded)
        assertEquals(listOf(path.toString()), committed.saved.localImagePaths)
        assertEquals(terminal.copy(id = assertNotNull(committed.download).id), committed.download)
        assertEquals(claim, receipt.claimOrNull())
        assertTrue(receipt.ownsPendingPath)
        assertEquals(claim.token, receipt.committedToken)
        assertEquals(claim.relativePath, receipt.committedRelativePath)
        reopen()
        assertEquals(committed, snapshot(saved.id, committed.files.keys))
        artifacts.read(saved.id) { assertEquals(released(receipt), it) }
        assertEquals(committed.copy(artifact = released(receipt)), snapshot(saved.id, committed.files.keys))
        val outcome = db.chapterArtifactCommitDao().readRestoreOutcome(claim, path.toString(), source.sizeBytes)
        assertEquals(ChapterRestoreOutcome.COMMITTED, outcome)
        assertContentEquals(RestoredDownloadFixture.CONTENT, fs.read(path) { readByteArray() })
        assertFalse(fs.exists(partPath(path)))
        assertFalse(db.chapterArtifactDao().canPublish(claim))
        assertPreservedControls(controls)
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

    private fun released(record: ChapterArtifactEntity): ChapterArtifactEntity = record.copy(
        token = null, operation = null, retiring = false, downloadId = null,
        pendingRelativePath = null, pendingSizeBytes = null, ownsPendingPath = false,
    )

    private suspend fun RestoredDownloadFixture.assertPreservedControls(controls: List<RestoreChapterSnapshot>) {
        controls.forEach { assertEquals(it, snapshot(it.saved.id, it.files.keys)) }
        val readable = controls.first()
        assertTrue(readable.saved.isDownloaded)
        assertEquals(DownloadingState.SUCCESS, readable.download?.state)
        assertNotNull(readable.artifact?.committedRelativePath)
        assertNull(readable.artifact?.token)
        val queued = controls.last()
        assertEquals(DownloadingState.QUEUED, queued.download?.state)
        assertTrue(db.chapterArtifactDao().canPublish(assertNotNull(queued.artifact?.claimOrNull())))
        assertEquals("incumbent-canonical", fs.read(canonical) { readUtf8() })
    }
}
