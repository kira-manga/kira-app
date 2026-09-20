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
import me.manga.kira.core.result.map
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Production observed-refresh runner, Result and stamp tests, not a mapper reimplementation.
 * Port doubles do not prove Room IDs, CoroutineWorker or Android DI/foreground services.
 * The separate real Worker/Room regressions retain those assertions and their own platform gate.
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
    fun libraryReadAndMandatoryPersistenceTimeouts_areFailures_notEmptyOrSuccessfulItems() =
        runTest {
            for (libraryRead in listOf(true, false)) {
                val port =
                    LibraryRefreshWorkTestFixtures().apply {
                        if (libraryRead) {
                            libraryFlow = flow { awaitCancellation() }
                        } else {
                            persist = { awaitCancellation() }
                        }
                    }
                val progress = mutableListOf<LibraryRefreshWorkProgress>()
                assertEquals(Result.failure(), work(port, progress).run())
                assertEquals("old success", port.lastSuccess)
                assertEquals(if (libraryRead) null else 1, progress.last().snapshotSize)
                assertEquals(if (libraryRead) 0 else 1, progress.last().timedOut)
                assertEquals(if (libraryRead) 0 else 1, port.persistenceCalls.size)
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
    fun atomicPersistenceFailure_failsWithoutRetryDisplayOrStamp() =
        runTest {
            for (typed in listOf(false, true)) {
                val port = LibraryRefreshWorkTestFixtures().apply {
                    persist = {
                        if (typed) AppResult.Failure(AppError.Storage.Constraint("fixture_owner_changed"))
                        else error("fixture_atomic_discovery_failure")
                    }
                }
                assertEquals(Result.failure(), work(port).run())
                assertEquals(1, port.persistenceCalls.size)
                assertTrue(port.persistedNotifications.isEmpty())
                assertTrue(port.displayCalls.isEmpty())
                assertEquals("old success", port.lastSuccess)
            }
        }

    @Test
    fun lostDiscoveryRace_isSuccess_andOnlyCommittedNotificationsAreCounted() =
        runTest {
            for (committedCount in listOf(1, 0)) {
                val port = LibraryRefreshWorkTestFixtures().apply {
                    fetch = { AppResult.Success(refreshDetails(it, count = 2)) }
                    val normalPersist = persist
                    persist = { request ->
                        normalPersist(request).map {
                            it.copy(addedChapters = committedCount, notifications = it.notifications.take(committedCount))
                        }
                    }
                    cover = {
                        if (committedCount == 0) AppResult.Failure(AppError.Storage.Io())
                        else error("best-effort cover failure")
                    }
                }
                val progress = mutableListOf<LibraryRefreshWorkProgress>()
                assertEquals(Result.success(), work(port, progress).run())
                assertEquals(1, port.coverCalls.size)
                assertEquals(committedCount, progress.last().newChapterCount)
                assertEquals(1, port.stamps) // Nonempty zero-new also stamps.
                assertEquals("new success", port.lastSuccess)
                assertEquals(1, port.persistedNotifications.size) // Port result, not Room proof.
                assertEquals(if (committedCount == 0) 0 else 1, port.displayCalls.size)
            }
        }

    @Test
    fun equalOrBlankCoverWithEmptyFetch_stillRequiresCapturedOwnerAndActualDetails() =
        runTest {
            for (remoteCover in listOf("old", " ")) {
                val captured = refreshManga(1)
                val owner = SavedWorkIdentity(captured.id, WorkLocator(captured.api, captured.url))
                val fetched = WorkLocator("fetched-source", "m/redirected")
                val port =
                    LibraryRefreshWorkTestFixtures().apply {
                        libraryFlow = flowOf(listOf(captured))
                        fetch = {
                            // A reread after fetch would select a different owner. The actual
                            // response locator is deliberately distinct; the writer must vet it.
                            libraryFlow = flowOf(listOf(captured.copy(id = 2, api = "replacement", url = "m/replaced")))
                            AppResult.Success(refreshDetails(it, count = 0).copy(api = fetched.api, url = fetched.url, coverUrl = remoteCover))
                        }
                    }
                val progress = mutableListOf<LibraryRefreshWorkProgress>()
                assertEquals(Result.success(), work(port, progress).run())
                assertEquals(if (remoteCover.isBlank()) emptyList() else listOf(Triple(owner, fetched, remoteCover)), port.coverCalls)
                assertEquals(0, progress.last().newChapterCount)
                val request = port.persistenceCalls.single()
                assertEquals(owner, request.owner)
                assertEquals(owner.locator, request.fetched.requested)
                assertEquals(fetched, WorkLocator(request.fetched.details.api, request.fetched.details.url))
                assertEquals("", request.fetched.details.coverUrl)
                assertTrue(request.fetched.details.chapters.isEmpty())
                assertEquals(1, port.stamps)
            }
        }

    @Test
    fun emptyLibrary_successDoesNotStamp() =
        runTest {
            val port = LibraryRefreshWorkTestFixtures().apply { libraryFlow = flowOf(emptyList()) }
            assertEquals(Result.success(), work(port).run())
            assertEquals("old success", port.lastSuccess)
            assertTrue(port.persistenceCalls.isEmpty())
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
    fun cancellationDuringReadCoverInsertOrStamp_propagates_withoutMetadataRollback() =
        runTest {
            for (stage in listOf(0, 1, 2, 5)) {
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
                if (stage == 5) {
                    assertEquals(1, port.coverCalls.size)
                    assertTrue(port.persistenceCalls.isEmpty() && port.displayCalls.isEmpty())
                }
            }
        }

    @Test
    fun updatesPersistenceRejectionOrItemTimeout_preventsCompletionDisplayAndStamp() =
        runTest {
            for (expires in listOf(false, true)) {
                val settled = CompletableDeferred<Unit>()
                val port = failingUpdatesRefreshWork(expires, settled)
                val progress = mutableListOf<LibraryRefreshWorkProgress>()
                val started = testScheduler.currentTime
                assertEquals(Result.failure(), work(port, progress).run())
                val terminal = progress.last()
                assertEquals(LibraryRefreshWorkStop.EXHAUSTED, terminal.stop)
                assertEquals(0, terminal.succeeded)
                assertEquals(if (expires) 0 else 1, terminal.failed)
                assertEquals(if (expires) 1 else 0, terminal.timedOut)
                assertEquals(0, terminal.newChapterCount)
                assertEquals(if (expires) 31_000L else 26_000L, testScheduler.currentTime - started)
                assertEquals(1, port.persistenceCalls.size)
                assertTrue(port.persistedNotifications.isEmpty())
                assertTrue(port.displayCalls.isEmpty())
                assertTrue(settled.isCompleted)
                assertEquals("old success", port.lastSuccess)
            }
        }

    @Test
    fun totalTimeoutDuringDisplay_retainsObservedAccountingAndSettlesDisplay() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val settled = CompletableDeferred<Unit>()
            val port =
                LibraryRefreshWorkTestFixtures().apply {
                    display = { awaitRefreshCancellation(entered, settled) }
                }
            val progress = mutableListOf<LibraryRefreshWorkProgress>()
            assertEquals(Result.failure(), work(port, progress, LibraryRefreshWorkTimeouts(totalMs = 5_000)).run())
            val terminal = progress.last()
            assertEquals(LibraryRefreshWorkStop.TOTAL_TIMEOUT, terminal.stop)
            assertEquals(1, terminal.succeeded)
            assertEquals(0, terminal.failed)
            assertEquals(0, terminal.timedOut)
            assertEquals(0, terminal.notAttempted)
            assertEquals(1, terminal.newChapterCount)
            assertFalse(terminal.isComplete)
            assertTrue(entered.isCompleted && settled.isCompleted)
            assertEquals(1, port.persistedNotifications.size)
            assertEquals("old success", port.lastSuccess)
        }

    @Test
    fun cancellationDuringUpdatesPersistenceOrDisplay_propagatesAndSettlesOwnedWork() =
        runTest {
            for (stage in 3..4) {
                val entered = CompletableDeferred<Unit>()
                val settled = CompletableDeferred<Unit>()
                val port = cancellingRefreshWork(stage) { awaitRefreshCancellation(entered, settled) }
                var result: Result? = null
                val job = launch { result = work(port).run() }
                entered.await()
                job.cancel()
                job.join()
                assertTrue(job.isCancelled && settled.isCompleted)
                assertNull(result)
                assertEquals(1, port.persistenceCalls.size)
                assertEquals(if (stage == 4) 1 else 0, port.persistedNotifications.size)
                assertEquals(if (stage == 4) 1 else 0, port.displayCalls.size)
                assertEquals("old success", port.lastSuccess)
            }
        }

    @Test
    fun displayAndProgressFailures_preserveSuccessAndCapturedPayload() =
        runTest {
            val port = LibraryRefreshWorkTestFixtures().apply { display = { error("fixture_display_rejected") } }
            val progress = mutableListOf<LibraryRefreshWorkProgress>()
            val runner =
                LibraryRefreshWork(
                    port,
                    {
                        progress += it
                        error("fixture_progress_format")
                    },
                    StandardTestDispatcher(testScheduler),
                )
            assertEquals(Result.success(), runner.run())
            assertSame(port.persistedNotifications.single(), port.displayCalls.single())
            assertEquals(2, progress.size)
            assertTrue(progress.last().isComplete)
            assertEquals(1, progress.last().newChapterCount)
            assertEquals(1, port.stamps)
            assertEquals("new success", port.lastSuccess)
        }
}
