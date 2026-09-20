package me.manga.kira.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.repository.library.DownloadLibraryRemovalGuard
import me.manga.kira.data.repository.library.LibraryRemovalGuard
import me.manga.kira.data.repository.library.LibraryRemovalStorage
import me.manga.kira.data.repository.library.LibraryRemovalWriter
import me.manga.kira.data.repository.library.LibraryWriteException
import me.manga.kira.data.repository.library.LibraryWriteRejection
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Room, removal writer and exclusion; controlled cancel/child handles are not native drain proof. */
class DownloadLibraryRemovalGuardTest {
    @Test
    fun theLastStaleOwnerRefusesTheWholeSelectionBeforeAnyCancellationOrFileWork() = runTest {
        LibraryIdentityFixture().use { f ->
            val first = f.removalSeed(state = DownloadingState.RUNNING)
            val second = f.removalSeed(libraryParent("https://current.test/work/two"), DownloadingState.QUEUED)
            val cancelled = mutableListOf<Long>()
            val operations = DownloadOperationExclusion()
            val guard = f.downloadGuard(operations) { cancelled += it }
            var deletes = 0
            f.files.beforeDelete = { deletes++ }
            val stale = second.owner.copy(id = second.owner.id + 1_000L)

            val failure = assertFailsWith<LibraryWriteException> {
                f.removalWriter(guard).remove(listOf(first.owner, stale))
            }

            assertEquals(LibraryWriteRejection.OWNER_CONFLICT, failure.rejection)
            assertTrue(cancelled.isEmpty(), "Even the earlier valid owner's cancellation is forbidden")
            assertEquals(0, deletes)
            f.assertUnremoved(first, DownloadingState.RUNNING)
            f.assertUnremoved(second, DownloadingState.QUEUED)
            operations.withExclusive {} // The failed read-only preflight released its operation.
        }
    }

    @Test
    fun returnedCancellationCannotReleaseAChildAndTheSameOwnerRetriesAfterActualRelease() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed(state = DownloadingState.RUNNING)
            val operations = DownloadOperationExclusion()
            val child = operations.withOperation { it.retain() }
            val cancelled = mutableListOf<Long>()
            val guard = ObservedRemovalGuard(f, f.downloadGuard(operations) {
                assertFalse(f.transactions.insideWriter, "Cancellation must run outside the owner writer")
                assertNotNull(currentCoroutineContext()[DownloadOperationExclusion.Operation])
                cancelled += it
                f.db.chapterDownloadingDao().updateStateChId(it, DownloadingState.FAILED)
            })
            val removal = f.removalWriter(guard)
            try {
                repeat(2) { assertActiveRefusal { removal.remove(listOf(seed.owner)) } }
                assertEquals(listOf(seed.chapter.id), cancelled, "An inactive row does not request a second cancel")
                assertEquals(0, guard.checks, "The removal block must not start while the child owns the gate")
                f.assertUnremoved(seed, DownloadingState.FAILED)
            } finally { child.release() }

            assertEquals(1, removal.remove(listOf(seed.owner)))
            assertEquals(2, guard.checks, "Both the complete preflight and final writer retain the actual exclusive")
            assertNull(f.db.mangaDao().getMangaById(seed.owner.id))
            assertFalse(f.files.exists(seed.owner))
            assertFalse(f.db.readerProgressDao().savePosition(seed.progress, 8))
            operations.withExclusive {}
        }
    }

    @Test
    fun activeRowsStillRefuseInsideTheWriterEvenWhenExclusiveAdmissionSucceeds() = runTest {
        for (state in listOf(DownloadingState.QUEUED, DownloadingState.RUNNING, DownloadingState.COMPRESSING)) {
            LibraryIdentityFixture().use { f ->
                val seed = f.removalSeed(state = state)
                val operations = DownloadOperationExclusion()
                val cancelled = mutableListOf<Long>()
                val guard = f.downloadGuard(operations) { cancelled += it } // Request returned; row is still active.
                var entered = false
                operations.withExclusive {}
                assertActiveRefusal {
                    guard.withQuiescentWorks(listOf(seed.owner)) {
                        entered = true
                        f.owners.write { guard.checkInTransaction(listOf(seed.owner)) }
                        error("An active queue must not pass the real in-writer guard")
                    }
                }
                assertTrue(entered, "This refusal is a fresh queue check, not a busy operation counter")
                assertEquals(listOf(seed.chapter.id), cancelled)
                f.assertUnremoved(seed, state)
                operations.withExclusive {}
            }
        }
    }

    @Test
    fun theRoomCheckRejectsUnheldSharedAndForeignExclusiveContexts() = runTest {
        LibraryIdentityFixture().use { f ->
            val seed = f.removalSeed()
            val operations = DownloadOperationExclusion()
            val guard = f.downloadGuard(operations) { error("Inactive history cannot request cancellation") }
            val checkOwner: suspend () -> Unit = {
                f.owners.write {
                    assertTrue(f.transactions.insideWriter)
                    guard.checkInTransaction(listOf(seed.owner))
                }
            }
            assertFailsWith<IllegalStateException> { checkOwner() }
            operations.withOperation { assertFailsWith<IllegalStateException> { checkOwner() } }
            DownloadOperationExclusion().withExclusive {
                assertFailsWith<IllegalStateException> { checkOwner() }
            }
            operations.withExclusive { checkOwner() }
            f.assertUnremoved(seed, DownloadingState.SUCCESS)
        }
    }

    private suspend fun assertActiveRefusal(block: suspend () -> Unit) {
        assertEquals(LibraryWriteRejection.ACTIVE_DOWNLOAD, assertFailsWith<LibraryWriteException> { block() }.rejection)
    }

    private fun LibraryIdentityFixture.downloadGuard(
        operations: DownloadOperationExclusion,
        cancel: suspend (Long) -> Unit,
    ) = DownloadLibraryRemovalGuard(owners, db.chapterDownloadingDao(), object : FakeDownloadRepository() {
        override suspend fun onCancel(chapterId: Long) = cancel(chapterId)
        override suspend fun cancelAllDownloads() = error("Removal must cancel only its retained owners' chapters")
    }, operations)

    private fun LibraryIdentityFixture.removalWriter(guard: LibraryRemovalGuard) = LibraryRemovalWriter(
        owners,
        LibraryRemovalStorage(db.libraryDeo(),
            ProgressStorage(db.readerProgressDao(), db.readerLegacyCleanupDao(), db.mangaDao(), db.chapterDao(), db.backupDao()),
            db.chapterDownloadingDao()),
        guard, FileService(files), artifacts,
    )

    private suspend fun LibraryIdentityFixture.assertUnremoved(seed: LibraryRemovalSeed, state: DownloadingState) {
        assertEquals(seed.parent, db.mangaDao().getMangaById(seed.owner.id))
        assertEquals(seed.chapter, db.chapterDao().getChapterByIdSuspend(seed.chapter.id))
        assertEquals(state, db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id)?.state)
        assertEquals(seed.progress, db.readerProgressDao().findSnapshot(seed.parent.api, seed.parent.url, seed.chapter.url))
        assertTrue(files.exists(seed.owner))
    }

    /** Observes the real guard, without supplying or simulating permission. */
    private class ObservedRemovalGuard(
        private val fixture: LibraryIdentityFixture,
        private val delegate: LibraryRemovalGuard,
    ) : LibraryRemovalGuard by delegate {
        var checks = 0
            private set

        override suspend fun checkInTransaction(owners: List<SavedWorkIdentity>) {
            assertTrue(fixture.transactions.insideWriter)
            delegate.checkInTransaction(owners)
            checks++
        }
    }
}
