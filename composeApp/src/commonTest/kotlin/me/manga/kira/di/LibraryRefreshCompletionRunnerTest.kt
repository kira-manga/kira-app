package me.manga.kira.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Common policy tests only: not Kotlin/Native compilation or BGTask expiry execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshCompletionRunnerTest {
    @Test
    fun fullAndEmptySuccess_settleOnce_withDifferentStampPolicy() =
        runTest {
            for (size in listOf(0, 2)) {
                var stamps = 0
                val completions = mutableListOf<Boolean>()
                val job =
                    launchLibraryRefreshCompletion(
                        this,
                        { AppResult.Success(LibraryRefreshCompleted(size, 0)) },
                        { stamps++ },
                        completions::add,
                    )
                job.join()
                job.cancel()
                assertEquals(listOf(true), completions)
                assertEquals(if (size == 0) 0 else 1, stamps)
            }
        }

    @Test
    fun failureOrResolutionException_settlesFalseWithoutStamp() =
        runTest {
            for (throws in listOf(false, true)) {
                val completions = mutableListOf<Boolean>()
                val job =
                    launchLibraryRefreshCompletion(
                        this,
                        { if (throws) error("DI unavailable") else AppResult.Failure(AppError.Network.Timeout()) },
                        { error("must not stamp") },
                        completions::add,
                    )
                job.join()
                assertEquals(listOf(false), completions)
            }
        }

    @Test
    fun expiryDuringWork_cancelsAndSettlesFalseOnce() =
        runTest {
            val reached = CompletableDeferred<Unit>()
            val completions = mutableListOf<Boolean>()
            val job =
                launchLibraryRefreshCompletion(
                    this,
                    {
                        reached.complete(Unit)
                        awaitCancellation()
                    },
                    { error("must not stamp") },
                    completions::add,
                )
            reached.await()
            repeat(2) { job.cancel() }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf(false), completions)
        }

    @Test
    fun cancellationBeforeStart_stillSettlesFalse() =
        runTest {
            val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            owner.cancel()
            val completions = mutableListOf<Boolean>()
            launchLibraryRefreshCompletion(
                owner,
                { error("must not run") },
                { error("must not stamp") },
                completions::add,
            ).join()
            assertEquals(listOf(false), completions)
        }

    @Test
    fun stampFailure_settlesFalse_andCancellationDoesNotRollbackCommittedMetadata() =
        runTest {
            val failureCompletions = mutableListOf<Boolean>()
            launchNonemptySuccess({ error("stamp failed") }, failureCompletions).join()
            assertEquals(listOf(false), failureCompletions)

            var committed = false
            val cancelCompletions = mutableListOf<Boolean>()
            val job =
                launchNonemptySuccess(
                    {
                        committed = true
                        awaitCancellation()
                    },
                    cancelCompletions,
                )
            runCurrent()
            assertTrue(committed)
            job.cancel()
            job.join()
            assertTrue(committed)
            assertEquals(listOf(false), cancelCompletions)
        }

    private fun CoroutineScope.launchNonemptySuccess(
        stampLastSuccess: suspend () -> Unit,
        completions: MutableList<Boolean>,
    ): Job =
        launchLibraryRefreshCompletion(
            this,
            { AppResult.Success(LibraryRefreshCompleted(1, 1)) },
            stampLastSuccess,
            completions::add,
        )
}
