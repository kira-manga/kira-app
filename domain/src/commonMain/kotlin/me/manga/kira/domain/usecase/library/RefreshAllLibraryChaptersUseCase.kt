package me.manga.kira.domain.usecase.library

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase

/**
 * Fetch in bounded batches, then atomically persist each successful-fetch batch. Network/source
 * failures remain isolated; identity/storage failures are explicit and roll back the affected
 * batch. The immutable library snapshot retains every pre-fetch parent ID through the writer.
 */
class RefreshAllLibraryChaptersUseCase(
    private val observeLibrary: ObserveLibraryUseCase,
    private val fetchDetails: FetchMangaDetailsUseCase,
    private val persistAndNotify: PersistNewChaptersAndNotifyUseCase,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(): AppResult<Int> =
        try {
            withContext(dispatchers.io) { refreshLibrary(observeLibrary().first()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppResult.Failure(AppError.Storage.Io(failure))
        }

    private suspend fun refreshLibrary(library: List<LibraryManga>): AppResult<Int> {
        var total = 0
        val completed = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val batches = library.chunked(BATCH_SIZE)
            for ((index, batch) in batches.withIndex()) {
                when (val result = refreshBatch(batch)) {
                    is AppResult.Success -> total += result.value
                    is AppResult.Failure -> return@withTimeoutOrNull result
                }
                if (index < batches.lastIndex) delay(INTER_BATCH_DELAY_MS)
            }
            AppResult.Success(total)
        }
        if (completed != null) return completed
        FlowLog.log("LibraryRefresh", "totalTimeout", "truncated library=${library.size} newSoFar=$total")
        return AppResult.Success(total)
    }

    private suspend fun refreshBatch(batch: List<LibraryManga>): AppResult<Int> {
        val fetched = coroutineScope { batch.map { async { fetchOne(it) } }.awaitAll().filterNotNull() }
        return if (fetched.isEmpty()) AppResult.Success(0) else persistAndNotify(fetched)
    }

    private suspend fun fetchOne(manga: LibraryManga): LibraryRefreshRequest? =
        withTimeoutOrNull(PER_MANGA_TIMEOUT_MS) {
            when (val response = fetchDetails(manga.manga)) {
                is AppResult.Success -> LibraryRefreshRequest(
                    owner = manga.identity,
                    fetched = FetchedWorkDetails(manga.identity.locator, response.value),
                )
                is AppResult.Failure -> null
            }
        }

    companion object {
        const val BATCH_SIZE = 5
        const val PER_MANGA_TIMEOUT_MS = 30_000L
        const val TOTAL_TIMEOUT_MS = 15L * 60 * 1000
        const val INTER_BATCH_DELAY_MS = 1_000L
    }
}
