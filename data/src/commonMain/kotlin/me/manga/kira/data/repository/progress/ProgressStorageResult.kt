package me.manga.kira.data.repository.progress

import kotlin.coroutines.cancellation.CancellationException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.identity.WorkOwnerResolution
import me.manga.kira.domain.model.progress.ProgressWriteResult

internal enum class ProgressRejection {
    SAVED_WORK_CONFLICT,
    SAVED_CHAPTER_CONFLICT,
    WORK_ANCHOR_CONFLICT,
    CHAPTER_ANCHOR_CONFLICT,
    WRITE_COUNT,
    NEGATIVE_PAGE,
}

internal class ProgressIdentityException(
    val rejection: ProgressRejection,
    val conflict: WorkOwnerResolution.Conflict? = null,
) : IllegalStateException(rejection.name)

internal class StaleProgressHandle : IllegalStateException("Stale progress handle")

internal fun requireProgress(condition: Boolean, rejection: ProgressRejection) {
    if (!condition) throw ProgressIdentityException(rejection)
}

/** Exceptions escape the entire writer before they become boundary values. */
internal suspend fun <T> progressStorageResult(block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ProgressIdentityException) {
        AppResult.Failure(AppError.Storage.Constraint(failure.rejection.name, failure))
    } catch (failure: Exception) {
        AppResult.Failure(AppError.Storage.Io(failure))
    }

internal suspend fun progressSaveResult(block: suspend () -> ProgressWriteResult): AppResult<ProgressWriteResult> =
    progressStorageResult {
        try {
            block()
        } catch (_: StaleProgressHandle) {
            ProgressWriteResult.STALE
        }
    }
