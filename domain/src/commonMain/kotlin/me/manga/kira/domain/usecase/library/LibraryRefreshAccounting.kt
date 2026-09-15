package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.library.LibraryRefreshCompleted

internal sealed interface LibraryRefreshItemOutcome {
    data class Completed(
        val newChapterCount: Int,
    ) : LibraryRefreshItemOutcome

    data class Failed(
        val error: AppError,
    ) : LibraryRefreshItemOutcome

    data object TimedOut : LibraryRefreshItemOutcome
}

internal enum class LibraryRefreshStopReason {
    EXHAUSTED,
    LIBRARY_READ_FAILED,
    LIBRARY_READ_TIMEOUT,
    TOTAL_TIMEOUT,
    ABORTED,
}

internal data class LibraryRefreshStop(
    val reason: LibraryRefreshStopReason,
    val error: AppError? = null,
)

internal data class LibraryRefreshReport(
    val snapshotSize: Int?,
    val succeeded: Int,
    val failed: Int,
    val timedOut: Int,
    val notAttempted: Int?,
    val newChapterCount: Int,
    val stop: LibraryRefreshStop,
    val firstError: AppError?,
) {
    val attempted: Int get() = succeeded + failed + timedOut
    private val exhaustedWithoutFailures: Boolean
        get() = stop.reason == LibraryRefreshStopReason.EXHAUSTED && failed == 0 && timedOut == 0

    init {
        require(succeeded >= 0 && failed >= 0 && timedOut >= 0 && newChapterCount >= 0)
        require(snapshotSize != null && snapshotSize > 0 || newChapterCount == 0)
        require(
            if (snapshotSize == null) {
                attempted == 0 && notAttempted == null
            } else {
                notAttempted != null && notAttempted >= 0 && snapshotSize == attempted + notAttempted
            },
        )
    }

    fun completion(): AppResult<LibraryRefreshCompleted> =
        if (
            exhaustedWithoutFailures &&
            snapshotSize != null &&
            succeeded == snapshotSize
        ) {
            AppResult.Success(LibraryRefreshCompleted(snapshotSize, newChapterCount))
        } else {
            val fallback =
                if (
                    timedOut > 0 ||
                    stop.reason == LibraryRefreshStopReason.LIBRARY_READ_TIMEOUT ||
                    stop.reason == LibraryRefreshStopReason.TOTAL_TIMEOUT
                ) {
                    AppError.Network.Timeout()
                } else {
                    AppError.Unexpected("Incomplete library refresh")
                }
            AppResult.Failure(firstError ?: stop.error ?: fallback)
        }
}

/** Synchronized bookkeeping only: no persistence is moved into a non-cancellable region. */
internal class LibraryRefreshAccounting {
    private val mutex = Mutex()
    private var snapshotSize: Int? = null
    private var started = 0
    private var succeeded = 0
    private var failed = 0
    private var timedOut = 0
    private var newChapterCount = 0
    private var firstError: AppError? = null

    suspend fun readSnapshot(size: Int) =
        mutex.withLock {
            check(snapshotSize == null && size >= 0)
            snapshotSize = size
        }

    suspend fun startItem(): Unit =
        mutex.withLock {
            check(started < (snapshotSize ?: 0))
            started++
        }

    suspend fun finishItem(outcome: LibraryRefreshItemOutcome): Unit =
        withContext(NonCancellable) {
            mutex.withLock {
                check(succeeded + failed + timedOut < started)
                when (outcome) {
                    is LibraryRefreshItemOutcome.Completed -> {
                        require(outcome.newChapterCount >= 0)
                        succeeded++
                        newChapterCount += outcome.newChapterCount
                    }
                    is LibraryRefreshItemOutcome.Failed -> {
                        failed++
                        if (firstError == null) firstError = outcome.error
                    }
                    LibraryRefreshItemOutcome.TimedOut -> timedOut++
                }
            }
        }

    /** Call only after the timed scope has joined/cancelled all children. */
    suspend fun report(stop: LibraryRefreshStop): LibraryRefreshReport =
        mutex.withLock {
            val interrupted = started - succeeded - failed - timedOut
            val totalTimeout = stop.reason == LibraryRefreshStopReason.TOTAL_TIMEOUT
            LibraryRefreshReport(
                snapshotSize = snapshotSize,
                succeeded = succeeded,
                failed = failed + if (totalTimeout) 0 else interrupted,
                timedOut = timedOut + if (totalTimeout) interrupted else 0,
                notAttempted = snapshotSize?.minus(started),
                newChapterCount = newChapterCount,
                stop = stop,
                firstError = firstError,
            )
        }
}
