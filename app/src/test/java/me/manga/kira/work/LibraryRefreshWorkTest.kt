package me.manga.kira.work

import androidx.work.ListenableWorker.Result
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Production observed-refresh runner, Result and stamp tests, not a mapper reimplementation.
 * These do NOT instantiate CoroutineWorker, validate Android DI/foreground services or prove
 * detached Updates persistence. Those still require the genuine Android dependency/platform gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshWorkTest {
    private val originalLogFloor = Logger.config.minSeverity

    // Host-JVM tests must not call Android Log through Kermit's default Android writer.
    @Before
    fun silenceAndroidLogging() {
        Logger.setMinSeverity(Severity.Assert)
    }

    @After
    fun restoreLogging() {
        Logger.setMinSeverity(originalLogFloor)
    }

    private fun TestScope.work(
        port: LibraryRefreshWorkTestFixtures,
        progress: MutableList<LibraryRefreshWorkProgress> = mutableListOf(),
        timeouts: LibraryRefreshWorkTimeouts = LibraryRefreshWorkTimeouts(),
    ) = LibraryRefreshWork(port, { progress += it }, StandardTestDispatcher(testScheduler), timeouts)

    @Test
    fun allFailedPartialAndMissingSource_neverStampOrReturnSuccess() =
        runTest {
            for (scenario in 0..2) {
                val port =
                    LibraryRefreshWorkTestFixtures().apply {
                        libraryFlow = flowOf(listOf(refreshManga(1), refreshManga(2)))
                        missingSource = scenario == 2
                        fetch = { manga ->
                            if (scenario == 1 && manga.title == "1") {
                                AppResult.Success(refreshDetails(manga))
                            } else {
                                AppResult.Failure(AppError.Network.Http(503))
                            }
                        }
                    }
                val progress = mutableListOf<LibraryRefreshWorkProgress>()
                assertEquals(Result.failure(), work(port, progress).run())
                assertEquals("old success", port.lastSuccess)
                assertFalse(progress.last().isComplete)
                assertEquals(if (scenario == 1) 1 else 0, progress.last().newChapterCount)
            }
        }

    @Test
    fun libraryAndLocalChapterReadTimeouts_areFailures_notEmptyOrSuccessfulItems() =
        runTest {
            for (libraryRead in listOf(true, false)) {
                val port =
                    LibraryRefreshWorkTestFixtures().apply {
                        if (libraryRead) {
                            libraryFlow = flow { awaitCancellation() }
                        } else {
                            chapterFlow = { flow { awaitCancellation() } }
                        }
                    }
                val progress = mutableListOf<LibraryRefreshWorkProgress>()
                assertEquals(Result.failure(), work(port, progress).run())
                assertEquals("old success", port.lastSuccess)
                assertEquals(if (libraryRead) null else 1, progress.last().snapshotSize)
                assertEquals(if (libraryRead) 0 else 1, progress.last().timedOut)
                assertTrue(port.inserts.isEmpty())
            }
        }

    @Test
    fun totalTimeout_settlesChildrenAndRetainsConfirmedActiveBatchWork() =
        runTest {
            var settled = 0
            val port =
                LibraryRefreshWorkTestFixtures().apply {
                    libraryFlow = flowOf((1L..8L).map(::refreshManga))
                    fetch = { manga ->
                        if (manga.title != "1") {
                            try {
                                awaitCancellation()
                            } finally {
                                settled++
                            }
                        }
                        AppResult.Success(refreshDetails(manga))
                    }
                }
            val progress = mutableListOf<LibraryRefreshWorkProgress>()
            assertEquals(Result.failure(), work(port, progress, LibraryRefreshWorkTimeouts(totalMs = 5_000)).run())
            val terminal = progress.last()
            assertEquals(LibraryRefreshWorkStop.TOTAL_TIMEOUT, terminal.stop)
            assertEquals(1, terminal.succeeded)
            assertEquals(4, terminal.timedOut)
            assertEquals(3, terminal.notAttempted)
            assertEquals(1, terminal.newChapterCount)
            assertEquals(4, settled)
            assertEquals("old success", port.lastSuccess)
        }

    @Test
    fun swallowedEmptyAndMalformedInsertReturns_failWithoutLaunchingNotifications() =
        runTest {
            for (ids in listOf(emptyList(), listOf(9L, 10L), listOf(0L), listOf(-2L))) {
                val port = LibraryRefreshWorkTestFixtures().apply { write = { ids } }
                assertEquals(Result.failure(), work(port).run())
                assertEquals(1, port.inserts.size)
                assertTrue(port.notifications.isEmpty())
                assertEquals("old success", port.lastSuccess)
            }
        }

    @Test
    fun validIgnoreSlots_areNotFailure_andOnlyConfirmedInsertedIdsAreCounted() =
        runTest {
            for (ids in listOf(listOf(9L, -1L), listOf(-1L, -1L))) {
                val port =
                    LibraryRefreshWorkTestFixtures().apply {
                        fetch = { AppResult.Success(refreshDetails(it, count = 2)) }
                        write = { ids }
                        cover = { error("best-effort cover failure") }
                    }
                val progress = mutableListOf<LibraryRefreshWorkProgress>()
                assertEquals(Result.success(), work(port, progress).run())
                assertEquals(ids.count { it > 0 }, progress.last().newChapterCount)
                assertEquals(1, port.stamps) // Nonempty zero-new also stamps.
                assertEquals("new success", port.lastSuccess)
                assertEquals(1, port.notifications.size) // Launch only; not persistence completion proof.
            }
        }

    @Test
    fun emptyLibrary_successDoesNotStamp() =
        runTest {
            val port = LibraryRefreshWorkTestFixtures().apply { libraryFlow = flowOf(emptyList()) }
            assertEquals(Result.success(), work(port).run())
            assertEquals("old success", port.lastSuccess)
            assertTrue(port.inserts.isEmpty())
        }

    @Test
    fun stampFailure_returnsFailure_despiteCompletedChapterWork() =
        runTest {
            val port = LibraryRefreshWorkTestFixtures().apply { stamp = { error("stamp failed") } }
            val progress = mutableListOf<LibraryRefreshWorkProgress>()
            assertEquals(Result.failure(), work(port, progress).run())
            assertEquals(LibraryRefreshWorkStop.STAMP_FAILED, progress.last().stop)
            assertEquals("old success", port.lastSuccess)
        }

    @Test
    fun cancellationDuringReadInsertOrStamp_propagates_withoutMetadataRollback() =
        runTest {
            for (stage in 0..2) {
                val reached = CompletableDeferred<Unit>()

                suspend fun pause(): Nothing {
                    reached.complete(Unit)
                    awaitCancellation()
                }

                val port = cancellingRefreshWork(stage, ::pause)
                var result: Result? = null
                val job = launch { result = work(port).run() }
                reached.await()
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
                assertNull(result)
                assertEquals(if (stage == 2) "committed" else "old success", port.lastSuccess)
            }
        }
}
