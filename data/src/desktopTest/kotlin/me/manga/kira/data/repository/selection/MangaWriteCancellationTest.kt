package me.manga.kira.data.repository.selection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.RoomMangaWriteTransaction
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual SQL/Room settlement checks, not just a cancelled Deferred returned to its caller. */
class MangaWriteCancellationTest {
    @Test
    fun cancelledCallerRollsBackSqlBeforeWriterReturn() = runTest { assertCancelledSqlRollsBack(nested = false) }

    @Test
    fun nestedWriterRetainsTheCancelledOuterCaller() = runTest { assertCancelledSqlRollsBack(nested = true) }
}

private suspend fun assertCancelledSqlRollsBack(nested: Boolean) {
    StrictSelectionMigrationFixture().use { fixture ->
        fixture.saved()
        val before = fixture.snapshot()
        val observed = cancelAfterSql(fixture, nested)
        fixture.reopen()
        val persistedUnchanged = before == fixture.snapshot()
        assertTrue(observed.bodyReachedCancellation, "The real SQL/body cancellation boundary must be reached")
        if (nested) assertFalse(observed.nestedReturnedNormally, "The inner writer must observe the outer caller")
        assertTrue(persistedUnchanged, "Cancelled writer SQL must roll back before reopening (nested=$nested)")
    }
}

private suspend fun cancelAfterSql(
    fixture: StrictSelectionMigrationFixture,
    nested: Boolean,
): MangaWriteCancellationObservation =
    coroutineScope {
        var bodyReachedCancellation = false
        var nestedReturnedNormally = false
        val attempt =
            async {
                val originalCaller = currentCoroutineContext().job
                val writer = RoomMangaWriteTransaction(fixture.db)
                val writeThenCancel: suspend () -> Unit = {
                    fixture.execute("UPDATE saved_manga SET url = 'https://new.test/cancelled'")
                    originalCaller.cancel()
                    bodyReachedCancellation = true
                }
                writer.write {
                    if (nested) {
                        writer.write(writeThenCancel)
                        nestedReturnedNormally = true
                    } else {
                        writeThenCancel()
                    }
                }
            }
        assertFailsWith<CancellationException> { attempt.await() }
        attempt.join()
        MangaWriteCancellationObservation(bodyReachedCancellation, nestedReturnedNormally)
    }

private data class MangaWriteCancellationObservation(
    val bodyReachedCancellation: Boolean,
    val nestedReturnedNormally: Boolean,
)
