package me.manga.kira.domain.usecase.library

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryMetadataRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase

/**
 * Refresh a captured library snapshot with bounded concurrent work and a bounded total deadline.
 * Success means every observed item completed; failures/timeouts never become partial success.
 * Each ready owner commits through the typed atomic metadata/chapter/Updates writer without
 * waiting for its fetching siblings. This retains confirmed sibling writes at the total deadline.
 * A failed/uncertain persistence result stops later batches, not already completed owner commits.
 * Caller cancellation propagates; only bookkeeping, never persistence, is non-cancellable.
 */
class RefreshAllLibraryChaptersUseCase(
    private val observeLibrary: ObserveLibraryUseCase,
    private val fetchDetails: FetchMangaDetailsUseCase,
    private val persistAndNotify: PersistNewChaptersAndNotifyUseCase,
    private val dispatchers: DispatcherProvider,
    private val libraryMetadata: LibraryMetadataRepository,
) {
    suspend operator fun invoke(): AppResult<LibraryRefreshCompleted> =
        try {
            withContext(dispatchers.io) { refreshAll() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Unexpected(message = "Library refresh failed", cause = t))
        }

    private suspend fun refreshAll(): AppResult<LibraryRefreshCompleted> {
        val accounting = LibraryRefreshAccounting()
        val stop =
            try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) { refreshSnapshot(accounting) }
                    ?: LibraryRefreshStop(LibraryRefreshStopReason.TOTAL_TIMEOUT, AppError.Network.Timeout())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                LibraryRefreshStop(
                    LibraryRefreshStopReason.ABORTED,
                    AppError.Unexpected(message = "Library refresh failed", cause = t),
                )
            }
        currentCoroutineContext().ensureActive()
        val report = accounting.report(stop)
        FlowLog.log(
            "LibraryRefresh",
            "finished",
            "stop=${stop.reason} snapshot=${report.snapshotSize} attempted=${report.attempted} " +
                "failed=${report.failed} timedOut=${report.timedOut} newConfirmed=${report.newChapterCount}",
        )
        return report.completion()
    }

    private suspend fun refreshSnapshot(accounting: LibraryRefreshAccounting): LibraryRefreshStop =
        runCatchingCancellable {
            withTimeoutOrNull(LIBRARY_READ_TIMEOUT_MS) { observeLibrary().first() }
        }.fold(
            onSuccess = { library ->
                if (library == null) {
                    LibraryRefreshStop(LibraryRefreshStopReason.LIBRARY_READ_TIMEOUT, AppError.Network.Timeout())
                } else {
                    accounting.readSnapshot(library.size)
                    // Ready sub-batches must not turn a repeated snapshot owner into two writes.
                    if (library.map { it.identity.id }.distinct().size != library.size ||
                        library.map { it.identity.locator }.distinct().size != library.size
                    ) {
                        LibraryRefreshStop(
                            LibraryRefreshStopReason.ABORTED,
                            AppError.Storage.Constraint("Duplicate library refresh owner"),
                        )
                    } else {
                        refreshBatches(library, accounting)
                    }
                }
            },
            onFailure = { t ->
                LibraryRefreshStop(LibraryRefreshStopReason.LIBRARY_READ_FAILED, AppError.Storage.Io(t))
            },
        )

    private suspend fun refreshBatches(
        library: List<LibraryManga>,
        accounting: LibraryRefreshAccounting,
    ): LibraryRefreshStop {
        val batches = library.chunked(BATCH_SIZE)
        for ((index, batch) in batches.withIndex()) {
            val results = coroutineScope {
                batch.map { saved ->
                    async {
                        accounting.startItem()
                        val result = refreshOne(saved)
                        // Confirm before awaitAll: an interrupted sibling cannot erase completed
                        // work. A commit whose result never returns is not optimistically counted.
                        accounting.finishItem(result.outcome)
                        result
                    }
                }.awaitAll()
            }
            results.firstOrNull { it.stopLaterBatches }?.let { failed ->
                val error = (failed.outcome as? LibraryRefreshItemOutcome.Failed)?.error
                    ?: AppError.Network.Timeout()
                return LibraryRefreshStop(LibraryRefreshStopReason.ABORTED, error)
            }
            if (index < batches.lastIndex) delay(INTER_BATCH_DELAY_MS)
        }
        return LibraryRefreshStop(LibraryRefreshStopReason.EXHAUSTED)
    }

    private suspend fun refreshOne(saved: LibraryManga): RefreshItemResult {
        var persisting = false
        return try {
            withTimeoutOrNull(PER_MANGA_TIMEOUT_MS) {
                when (val response = fetchDetails(saved.manga)) {
                    is AppResult.Failure -> RefreshItemResult(LibraryRefreshItemOutcome.Failed(response.error))
                    is AppResult.Success -> {
                        val fetched = FetchedWorkDetails(saved.identity.locator, response.value)
                        reconcileCover(saved, fetched)
                        // Cover is optional and was attempted best-effort above. Empty tells the
                        // atomic writer to preserve it and skip cover fanout, even on cover failure.
                        val request = LibraryRefreshRequest(saved.identity, fetched.copy(details = response.value.copy(coverUrl = "")))
                        persisting = true
                        when (val persisted = persistAndNotify(listOf(request))) {
                            is AppResult.Success -> RefreshItemResult(LibraryRefreshItemOutcome.Completed(persisted.value))
                            is AppResult.Failure -> RefreshItemResult(
                                LibraryRefreshItemOutcome.Failed(persisted.error),
                                stopLaterBatches = true,
                            )
                        }
                    }
                }
            } ?: RefreshItemResult(LibraryRefreshItemOutcome.TimedOut, stopLaterBatches = persisting)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            RefreshItemResult(
                LibraryRefreshItemOutcome.Failed(AppError.Unexpected(message = "Library item refresh failed", cause = t)),
                stopLaterBatches = persisting,
            )
        }
    }

    private suspend fun reconcileCover(saved: LibraryManga, fetched: FetchedWorkDetails) {
        try {
            val result = libraryMetadata.updateCoverIfChanged(
                saved.identity,
                WorkLocator(fetched.details.api, fetched.details.url),
                fetched.details.coverUrl,
            )
            if (result is AppResult.Failure) {
                FlowLog.log("LibraryRefresh", "coverFailed", "best-effort cover reconciliation failed")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            FlowLog.log("LibraryRefresh", "coverFailed", "best-effort cover reconciliation failed")
        }
    }

    private data class RefreshItemResult(
        val outcome: LibraryRefreshItemOutcome,
        val stopLaterBatches: Boolean = false,
    )

    companion object {
        const val BATCH_SIZE = 5
        const val LIBRARY_READ_TIMEOUT_MS = 30_000L
        const val PER_MANGA_TIMEOUT_MS = 30_000L
        const val TOTAL_TIMEOUT_MS = 15L * 60 * 1000
        const val INTER_BATCH_DELAY_MS = 1_000L
    }
}
