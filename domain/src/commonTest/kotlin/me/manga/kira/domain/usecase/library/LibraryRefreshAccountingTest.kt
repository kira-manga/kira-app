package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LibraryRefreshAccountingTest {
    @Test
    fun totalTimeout_conservesDisjointOutcomesAndConfirmedWorkFromAnInterruptedBatch() =
        runTest {
            val accounting = LibraryRefreshAccounting()
            accounting.readSnapshot(8)
            repeat(5) { accounting.startItem() }
            accounting.finishItem(LibraryRefreshItemOutcome.Completed(4))
            val error = AppError.Storage.Io()
            accounting.finishItem(LibraryRefreshItemOutcome.Failed(error))
            accounting.finishItem(LibraryRefreshItemOutcome.TimedOut)
            val report = accounting.report(LibraryRefreshStop(LibraryRefreshStopReason.TOTAL_TIMEOUT))

            assertEquals(1, report.succeeded)
            assertEquals(1, report.failed)
            assertEquals(3, report.timedOut) // one item deadline plus two interrupted children
            assertEquals(5, report.attempted)
            assertEquals(3, report.notAttempted)
            assertEquals(4, report.newChapterCount)
            assertEquals(error, assertIs<AppResult.Failure>(report.completion()).error)
        }

    @Test
    fun unreadSnapshot_isNotTheEmptySuccessfulNoOp() =
        runTest {
            val accounting = LibraryRefreshAccounting()
            val unread = accounting.report(LibraryRefreshStop(LibraryRefreshStopReason.LIBRARY_READ_TIMEOUT))
            assertNull(unread.snapshotSize)
            assertNull(unread.notAttempted)
            assertIs<AppResult.Failure>(unread.completion())

            accounting.readSnapshot(0)
            val empty = accounting.report(LibraryRefreshStop(LibraryRefreshStopReason.EXHAUSTED))
            assertIs<AppResult.Success<*>>(empty.completion())
        }
}
