package me.manga.kira.data.repository.library

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.identity.WorkOwnerResolution

internal enum class LibraryWriteRejection {
    OWNER_CONFLICT,
    FETCHED_WORK_CHANGED,
    INSERT_CONFLICT,
    WRITE_COUNT,
    ACTIVE_DOWNLOAD,
    DUPLICATE_REQUEST,
    RELATED_OWNER_CHANGED,
    CHAPTER_ALIAS_REQUIRES_RECONCILIATION,
}

internal class LibraryWriteException(
    val rejection: LibraryWriteRejection,
    val conflict: WorkOwnerResolution.Conflict? = null,
) : IllegalStateException(rejection.name)

internal fun requireLibraryWrite(condition: Boolean, rejection: LibraryWriteRejection) {
    if (!condition) throw LibraryWriteException(rejection)
}

/** Map failures only outside the owning transaction, never between its statements. */
internal suspend fun <T> libraryStorageResult(block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LibraryWriteException) {
        AppResult.Failure(AppError.Storage.Constraint(failure.rejection.name, failure))
    } catch (failure: Exception) {
        AppResult.Failure(AppError.Storage.Io(failure))
    }

/** DAO/token invalidation failures are boundary failures too, not silent collector termination. */
internal fun <T> Flow<AppResult<T>>.libraryStorageFailures(): Flow<AppResult<T>> = catch { failure ->
    if (failure is CancellationException || failure !is Exception) throw failure
    emit(AppResult.Failure(AppError.Storage.Io(failure)))
}
