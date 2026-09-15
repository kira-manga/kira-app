package me.manga.kira.domain.usecase.library

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
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cross-platform "refresh all library" (#1): iterate every saved manga, fetch its current chapter
 * list from the source, and persist any newly-discovered chapters (flagged NEW). Success means the
 * entire snapshot completed; failures/timeouts never become a successful partial chapter count.
 * Committed partial data is retained. Caller cancellation always propagates.
 *
 * Composed from existing rework use cases (so it never touches `sources_repositry/` or the source
 * engine directly): [ObserveLibraryUseCase] for the saved set, [FetchMangaDetailsUseCase] for the
 * per-manga fetch (which routes piloted/legacy sources), and [PersistNewChaptersAndNotifyUseCase]
 * for the dedup + isNew/fetchedAt insert (it also writes a Notifications-screen entry per new
 * chapter, matching the native `LibraryRefreshWorker`). Throttling mirrors that same worker:
 * batches of [BATCH_SIZE], a per-manga timeout, a total timeout, and a small inter-batch delay.
 *
 * This is the in-process refresh used on Desktop/iOS (the user-initiated pull-to-refresh runs it
 * inline while the screen is open). Android continues to run the full WorkManager worker (which also
 * fires per-manga notifications). Android's notification helper is detached: this use case's
 * observed combined persist-and-notify completion does not prove Android Updates persistence.
 */
class RefreshAllLibraryChaptersUseCase(
    private val observeLibrary: ObserveLibraryUseCase,
    private val fetchDetails: FetchMangaDetailsUseCase,
    // Refresh-all persists AND notifies (so new chapters surface in the Notifications screen on
    // every platform — Android already does this via its worker; this covers Desktop/iOS inline).
    private val persistAndNotify: PersistNewChaptersAndNotifyUseCase,
    // Reconciles a rotated cover URL across saved_manga/history/notifications — the Android worker
    // does this inline; Desktop/iOS have no worker, so the inline refresh must repair covers too.
    private val libraryRepo: LibraryRepository,
    private val dispatchers: DispatcherProvider,
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
                    refreshBatches(library, accounting)
                    LibraryRefreshStop(LibraryRefreshStopReason.EXHAUSTED)
                }
            },
            onFailure = { t ->
                LibraryRefreshStop(LibraryRefreshStopReason.LIBRARY_READ_FAILED, AppError.Storage.Io(t))
            },
        )

    private suspend fun refreshBatches(
        library: List<LibraryManga>,
        accounting: LibraryRefreshAccounting,
    ) {
        val batches = library.chunked(BATCH_SIZE)
        batches.forEachIndexed { i, batch ->
            coroutineScope {
                batch
                    .map { lib ->
                        async {
                            accounting.startItem()
                            // Record each confirmation before awaitAll: an interrupted sibling must not
                            // erase a completed child's contribution. Counts remain a lower bound if a
                            // write commits but cancellation prevents its result from returning.
                            accounting.finishItem(refreshOne(lib.manga))
                        }
                    }.awaitAll()
            }
            if (i < batches.lastIndex) delay(INTER_BATCH_DELAY_MS)
        }
    }

    private suspend fun refreshOne(manga: Manga): LibraryRefreshItemOutcome =
        try {
            withTimeoutOrNull(PER_MANGA_TIMEOUT_MS) {
                when (val details = fetchDetails(manga)) {
                    is AppResult.Failure -> LibraryRefreshItemOutcome.Failed(details.error)
                    is AppResult.Success -> {
                        reconcileCover(manga, details.value.coverUrl)
                        when (val persisted = persistAndNotify(manga, details.value.chapters)) {
                            is AppResult.Success -> LibraryRefreshItemOutcome.Completed(persisted.value)
                            is AppResult.Failure -> LibraryRefreshItemOutcome.Failed(persisted.error)
                        }
                    }
                }
            } ?: LibraryRefreshItemOutcome.TimedOut
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            LibraryRefreshItemOutcome.Failed(AppError.Unexpected(message = "Library item refresh failed", cause = t))
        }

    private suspend fun reconcileCover(
        manga: Manga,
        coverUrl: String,
    ) {
        try {
            // Best-effort only. A cover failure must not invalidate completed chapter work.
            libraryRepo.updateCoverIfChanged(manga.api, manga.language, manga.title, coverUrl)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            FlowLog.log("LibraryRefresh", "coverFailed", "best-effort cover reconciliation failed")
        }
    }

    companion object {
        const val BATCH_SIZE = 5
        const val LIBRARY_READ_TIMEOUT_MS = 30_000L
        const val PER_MANGA_TIMEOUT_MS = 30_000L
        const val TOTAL_TIMEOUT_MS = 15L * 60 * 1000
        const val INTER_BATCH_DELAY_MS = 1_000L
    }
}
