package me.manga.kira.platform.cbz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fault policy/ownership complements the existing real ZIP/native validation suite. */
class DefaultCbzReaderFailurePolicyTest {
    @Test
    fun nonIoExceptionsKeepLegacySentinelsWithoutTouchingExistingArtifacts() =
        readerFailureTest { fixture ->
            val reader = fixture.reader(access = { throw IllegalStateException("filesystem unavailable") })
            assertEquals(0, reader.pageCount(fixture.archive))
            assertTrue(reader.extractImages(fixture.archive, 1, 2).isEmpty())
            assertFalse(reader.deleteCbz(1, 2))
            reader.cleanupExtractedCache(1, 2)
            fixture.assertOnlyPreviousGeneration()
        }

    @Test
    fun noSentinelBoundarySwallowsCancellationOrAFatalThrowable() =
        readerFailureTest { fixture ->
            for (failure in listOf(ReaderCancellation("reader cancelled"), ReaderFatal("fatal filesystem"))) {
                val reader = fixture.reader(access = { throw failure })
                assertSame(failure, assertFailsWith<Throwable> { reader.pageCount(fixture.archive) })
                assertSame(failure, assertFailsWith<Throwable> { reader.extractImages(fixture.archive, 1, 2) })
                assertSame(failure, assertFailsWith<Throwable> { reader.deleteCbz(1, 2) })
                assertSame(failure, assertFailsWith<Throwable> { reader.cleanupExtractedCache(1, 2) })
            }
            fixture.assertOnlyPreviousGeneration()
        }

    @Test
    fun fatalSecondPageFailureStillRemovesTheEarlierOwnedPage() =
        readerFailureTest { fixture ->
            val failure = ReaderFatal("fatal second page")
            val reader = fixture.reader(media = fixture.failSecondInspection(failure))
            assertSame(failure, assertFailsWith<AssertionError> { reader.extractImages(fixture.archive, 1, 2) })
            fixture.assertOnlyPreviousGeneration()
        }

    @Test
    fun cancellationAfterSuccessfulMoveDeletesOnlyTheUnacceptedNewGeneration() =
        readerFailureTest { fixture ->
            val failure = ReaderCancellation("cancel after publication move")
            lateinit var conversionJob: Job
            val files = AfterReaderMoveFileSystem(fixture.system) { conversionJob.cancel(failure) }
            val reader = fixture.reader(access = { files })
            val conversion =
                async {
                    conversionJob = currentCoroutineContext().job
                    reader.extractImages(fixture.archive, 1, 2)
                }
            try {
                assertSame(failure, assertFailsWith<CancellationException> { conversion.await() })
            } finally {
                conversion.cancelAndJoin()
            }
            assertFalse(fixture.system.exists(assertNotNull(files.destination)))
            fixture.assertOnlyPreviousGeneration()
        }

    @Test
    fun cleanupFailureIsSuppressedOnTheOriginalCancellation() = assertSuppressedRollback(
        ReaderCancellation("cancel second page"),
    )

    @Test
    fun cleanupFailureIsSuppressedOnTheOriginalFatalThrowable() = assertSuppressedRollback(ReaderFatal("fatal page"))

    private fun assertSuppressedRollback(primary: Throwable) =
        readerFailureTest { fixture ->
            val cleanup = AssertionError("fatal rollback failure")
            val files = ReaderRollbackFailureFileSystem(fixture.system, cleanup)
            val reader = fixture.reader(access = { files }, media = fixture.failSecondInspection(primary))
            assertSame(primary, assertFailsWith<Throwable> { reader.extractImages(fixture.archive, 1, 2) })
            assertSame(cleanup, primary.suppressedExceptions.single())
            assertTrue(
                files.deletions
                    .single()
                    .segments
                    .any { it.startsWith(".partial-") },
            )
            fixture.assertOriginalsRetained()
        }
}

/** Keep operation-bearing signals identifiable even with JVM coroutine stacktrace recovery. */
private class ReaderCancellation(
    val operation: String,
) : CancellationException(operation)

private class ReaderFatal(
    val operation: String,
) : AssertionError(operation)
