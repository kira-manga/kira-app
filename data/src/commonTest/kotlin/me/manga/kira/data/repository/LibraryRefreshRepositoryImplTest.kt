package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
import me.manga.kira.platform.jobs.BackgroundJob
import me.manga.kira.platform.jobs.BackgroundJobScheduler
import me.manga.kira.platform.jobs.JobState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshRepositoryImplTest {
    @Test
    fun nonemptyZeroNewSuccess_isPublishedOnlyAfterStamp_andDoubleGestureDoesNotDoubleRun() =
        runTest {
            val stampGate = CompletableDeferred<Unit>()
            var calls = 0
            val events = mutableListOf<String>()
            val repository =
                LibraryRefreshRepositoryImpl(
                    NoWorkerScheduler,
                    {
                        calls++
                        AppResult.Success(LibraryRefreshCompleted(2, 0))
                    },
                    { recordStamp(stampGate, events) },
                    this,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeLastRefreshResult().collect { if (it != null) events += "result:$it" }
            }
            repository.refresh()
            repository.refresh()
            runCurrent()
            assertEquals(1, calls)
            assertEquals(listOf("stamp started"), events)
            assertTrue(repository.observeIsRefreshing().first())
            stampGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("stamp started", "stamped", "result:${AppResult.Success(0)}"), events)
            assertFalse(repository.observeIsRefreshing().first())
        }

    @Test
    fun incompleteAndEmptyRuns_doNotStamp_andPreserveThePublicFlowShape() =
        runTest {
            val failure = AppResult.Failure(AppError.Network.Timeout())
            for (outcome in listOf(failure, AppResult.Success(LibraryRefreshCompleted(0, 0)))) {
                var stamps = 0
                val repository = LibraryRefreshRepositoryImpl(NoWorkerScheduler, { outcome }, { stamps++ }, this)
                repository.refresh()
                runCurrent()
                assertEquals(0, stamps)
                assertEquals(
                    if (outcome is AppResult.Failure) failure else AppResult.Success(0),
                    repository.observeLastRefreshResult().first(),
                )
                assertFalse(repository.observeIsRefreshing().first())
            }
        }

    @Test
    fun repeatedEqualFailures_reachTheActualCollectorForBothAttempts() =
        runTest {
            val failure = AppResult.Failure(AppError.Network.Http(503))
            val repository =
                LibraryRefreshRepositoryImpl(NoWorkerScheduler, { failure }, { error("must not stamp") }, this)
            val received = mutableListOf<AppResult<Int>?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeLastRefreshResult().collect { received += it }
            }
            repeat(2) {
                repository.refresh()
                runCurrent()
            }
            assertEquals(listOf<AppResult<Int>?>(null, failure, failure), received)
        }

    @Test
    fun stampException_publishesTypedFailure_neverAPrematureSuccess() =
        runTest {
            val cause = IllegalStateException("write failed")
            val repository =
                LibraryRefreshRepositoryImpl(
                    NoWorkerScheduler,
                    { AppResult.Success(LibraryRefreshCompleted(1, 3)) },
                    { throw cause },
                    this,
                )
            val received = mutableListOf<AppResult<Int>?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeLastRefreshResult().collect { received += it }
            }
            repository.refresh()
            runCurrent()
            assertEquals(listOf<AppResult<Int>?>(null, AppResult.Failure(AppError.Storage.Io(cause))), received)
            assertFalse(repository.observeIsRefreshing().first())
        }

    @Test
    fun cancellationAfterMetadataCommit_doesNotPublishSuccessOrRollback_andClearsSpinner() =
        runTest {
            val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val stampGate = CompletableDeferred<Unit>()
            var committed = false
            val repository =
                LibraryRefreshRepositoryImpl(
                    NoWorkerScheduler,
                    { AppResult.Success(LibraryRefreshCompleted(1, 1)) },
                    {
                        committed = true
                        stampGate.await()
                    },
                    owner,
                )
            repository.refresh()
            runCurrent()
            assertTrue(committed)
            owner.cancel()
            runCurrent()
            assertTrue(committed) // Cancellation is not permission to undo committed metadata.
            assertEquals(null, repository.observeLastRefreshResult().first())
            assertFalse(repository.observeIsRefreshing().first())
        }

    @Test
    fun cancelledScopeBeforeLaunch_doesNotLeaveSpinnerClaimed() =
        runTest {
            val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            owner.cancel()
            val repository =
                LibraryRefreshRepositoryImpl(
                    NoWorkerScheduler,
                    { error("must not run") },
                    { error("must not stamp") },
                    owner,
                )
            repository.refresh()
            runCurrent()
            assertFalse(repository.observeIsRefreshing().first())
            assertEquals(null, repository.observeLastRefreshResult().first())
        }

    private suspend fun recordStamp(
        stampGate: CompletableDeferred<Unit>,
        events: MutableList<String>,
    ) {
        events += "stamp started"
        stampGate.await()
        events += "stamped"
    }

    private object NoWorkerScheduler : BackgroundJobScheduler {
        override fun scheduleOneOff(job: BackgroundJob): String = error("inline only")

        override fun schedulePeriodic(
            job: BackgroundJob,
            intervalMinutes: Long,
        ): String = error("inline only")

        override fun cancel(jobId: String) = Unit

        override fun cancelAll() = Unit

        override fun observeJobState(jobId: String) = flowOf(JobState.Idle)
    }
}
