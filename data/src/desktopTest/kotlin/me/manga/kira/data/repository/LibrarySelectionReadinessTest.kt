package me.manga.kira.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LibrarySelectionReadinessTest {
    @Test
    fun membership_and_details_emit_typed_unavailability_then_recover_the_same_token() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val previous = WorkLocator(parent.api, LIBRARY_PREVIOUS_URL)
            val policy = libraryPolicy()
            f.savedDetails.observeSavedDetails(previous).test {
                val details = this
                assertEquals(parent.id, awaitItem().getOrNull()?.owner?.id)
                f.repository.observeMembership(previous).test {
                    assertEquals(AppResult.Success(parent.savedIdentity()), awaitItem())
                    f.snapshots.invalidate()
                    assertUnavailable(awaitItem())
                    assertUnavailable(details.awaitItem())
                    f.snapshots.accept(policy)
                    assertEquals(AppResult.Success(parent.savedIdentity()), awaitItem())
                    assertEquals(parent.id, details.awaitItem().getOrNull()?.owner?.id)
                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
        }
    }

    @Test
    fun initial_not_ready_is_not_missing_but_a_verified_empty_policy_keeps_exact_raw_recovery() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            f.snapshots.invalidate()
            f.repository.observeMembership(parent.savedIdentity().locator).test {
                assertUnavailable(awaitItem())
                f.snapshots.accept(SourceAliasSnapshot(libraryPolicy().token, emptyList()))
                assertEquals(AppResult.Success(parent.savedIdentity()), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
        }
    }

    @Test
    fun a_queued_library_writer_rechecks_readiness_after_acquiring_the_real_writer() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val result = withTimeout(5_000) { f.revokeWhileQueued { f.repository.toggleLiked(parent.savedIdentity()) } }
            assertUnavailable(result)
            assertFalse(f.transactions.insideWriter)
            assertEquals(1, f.snapshots.readCount)
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
        }
    }

    @Test
    fun not_ready_reads_unwind_the_writer_without_waiting_for_bootstrap() = runTest {
        LibraryIdentityFixture().use { f ->
            f.snapshots.invalidate()
            val result = withTimeout(2_000) { f.repository.get(WorkLocator(LIBRARY_TEST_API, LIBRARY_CURRENT_URL)) }
            assertUnavailable(result)
            assertFalse(f.transactions.insideWriter, "mapping happens after the owning transaction unwinds")
            assertEquals(1, f.snapshots.readCount)
            assertEquals(emptyList(), f.db.backupDao().getAllSavedManga())
        }
    }

    private fun assertUnavailable(result: AppResult<*>) {
        val error = assertIs<AppError.Storage.Io>(assertIs<AppResult.Failure>(result).error)
        assertIs<SourceSelectionUnavailable>(error.cause)
    }
}

private suspend fun <T> LibraryIdentityFixture.revokeWhileQueued(operation: suspend () -> T): T = coroutineScope {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val attempted = CompletableDeferred<Unit>()
    val blocker = launch(Dispatchers.Default) {
        RoomMangaWriteTransaction(db).write { entered.complete(Unit); release.await() }
    }
    entered.await()
    transactions.onAttempt = { attempted.complete(Unit) }
    val pending = async(Dispatchers.Default) { operation() }
    try {
        attempted.await()
        assertEquals(0, snapshots.readCount)
        snapshots.invalidate()
    } finally { release.complete(Unit) }
    pending.await().also { blocker.join() }
}
