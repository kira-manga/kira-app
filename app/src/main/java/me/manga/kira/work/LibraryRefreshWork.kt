package me.manga.kira.work

import androidx.work.ListenableWorker.Result
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryChapterNotification
import me.manga.kira.domain.model.library.LibraryRefreshRequest

/** Observed mandatory chapter/Updates work and the worker's actual Result/stamp policy. */
internal class LibraryRefreshWork(
    private val port: LibraryRefreshWorkPort,
    private val onProgress: (LibraryRefreshWorkProgress) -> Unit,
    private val dispatcher: CoroutineDispatcher = platformIoDispatcher,
    private val timeouts: LibraryRefreshWorkTimeouts = LibraryRefreshWorkTimeouts(),
) {
    private val log = Logger.withTag("LibraryRefreshWorker")

    suspend fun run(): Result =
        withContext(dispatcher) {
            val accounting = Accounting()
            val stop =
                runCatchingCancellable {
                    withTimeoutOrNull(timeouts.totalMs) { refreshSnapshot(accounting) }
                        ?: LibraryRefreshWorkStop.TOTAL_TIMEOUT
                }.getOrElse { t ->
                    log.w(t) { "Library refresh aborted" }
                    LibraryRefreshWorkStop.ABORTED
                }
            // The timed scope has settled all children. Stamping is outside its own deadline;
            // caller cancellation still wins, without rolling back already committed metadata.
            finish(accounting.snapshot(stop))
        }

    private suspend fun finish(report: LibraryRefreshWorkProgress): Result {
        currentCoroutineContext().ensureActive()
        if (!report.isComplete) {
            reportProgress(report)
            return Result.failure()
        }
        val stampFailure =
            runCatchingCancellable {
                if (report.snapshotSize != 0) port.stampLastSuccess()
            }.exceptionOrNull()
        return if (stampFailure != null) {
            log.w(stampFailure) { "Library refresh timestamp failed" }
            reportProgress(report.copy(stop = LibraryRefreshWorkStop.STAMP_FAILED))
            Result.failure()
        } else {
            currentCoroutineContext().ensureActive()
            reportProgress(report)
            Result.success()
        }
    }

    private suspend fun refreshSnapshot(accounting: Accounting): LibraryRefreshWorkStop =
        runCatchingCancellable {
            withTimeoutOrNull(timeouts.libraryReadMs) { port.library().first() }
        }.fold(
            onSuccess = { library ->
                if (library == null) {
                    LibraryRefreshWorkStop.READ_TIMEOUT
                } else {
                    accounting.readSnapshot(library.size)
                    refreshBatches(library, accounting)
                    LibraryRefreshWorkStop.EXHAUSTED
                }
            },
            onFailure = { t ->
                log.w(t) { "Library read failed" }
                LibraryRefreshWorkStop.READ_FAILED
            },
        )

    private suspend fun refreshBatches(
        library: List<SavedMangaEntity>,
        accounting: Accounting,
    ) {
        val batches = library.chunked(BATCH_SIZE)
        batches.forEach { batch ->
            coroutineScope {
                batch
                    .map { manga ->
                        async {
                            accounting.startItem()
                            refreshManga(manga, accounting::finishItem)
                        }
                    }.awaitAll()
            }
            reportProgress(accounting.snapshot())
            delay(INTER_BATCH_DELAY_MS)
        }
    }

    /** Same operation used by batching: record mandatory work before optional display suspends. */
    internal suspend fun refreshManga(
        manga: SavedMangaEntity,
        onObserved: (ItemOutcome) -> Unit = {},
    ): Boolean {
        val outcome = refreshOne(manga)
        onObserved(outcome)
        currentCoroutineContext().ensureActive()
        if (outcome !is ItemOutcome.Completed) return false
        displayBestEffort(outcome.notifications, port, log)
        return true
    }

    private suspend fun refreshOne(manga: SavedMangaEntity): ItemOutcome =
        runCatchingCancellable {
            withTimeoutOrNull(timeouts.itemMs) {
                if (manga.id == 0L) return@withTimeoutOrNull ItemOutcome.Failed
                val owner = SavedWorkIdentity(manga.id, WorkLocator(manga.api, manga.url))
                val source = port.source(manga.api) ?: return@withTimeoutOrNull ItemOutcome.Failed
                val details =
                    withTimeoutOrNull(timeouts.detailsMs) {
                        source.details(manga.toManga())
                    } ?: return@withTimeoutOrNull ItemOutcome.TimedOut
                when (details) {
                    is AppResult.Success -> reconcile(owner, details.value)
                    is AppResult.Failure -> {
                        log.w { "Generic refresh failed: ${details.error}" }
                        ItemOutcome.Failed
                    }
                }
            } ?: ItemOutcome.TimedOut
        }.getOrElse { t ->
            log.w(t) { "Library item refresh failed" }
            ItemOutcome.Failed
        }

    private suspend fun reconcile(
        owner: SavedWorkIdentity,
        details: MangaDetails,
    ): ItemOutcome {
        reconcileCover(owner, WorkLocator(details.api, details.url), details.coverUrl)
        currentCoroutineContext().ensureActive()
        // Cover was attempted best-effort. The mandatory writer must still validate both raw
        // fetch addresses and the retained ID, even when no chapters are new or cover failed.
        val request = LibraryRefreshRequest(owner, FetchedWorkDetails(owner.locator, details.copy(coverUrl = "")))
        return when (val result = port.persistNotifications(request)) {
            is AppResult.Success -> ItemOutcome.Completed(result.value.addedChapters, result.value.notifications)
            is AppResult.Failure -> {
                log.w { "Mandatory library discovery refused: ${result.error}" }
                ItemOutcome.Failed
            }
        }
    }

    private suspend fun reconcileCover(
        owner: SavedWorkIdentity,
        fetched: WorkLocator,
        coverUrl: String,
    ) {
        if (coverUrl.isBlank()) return
        runCatchingCancellable {
            when (port.updateCover(owner, fetched, coverUrl)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> log.w { "Best-effort cover reconciliation refused" }
            }
        }.onFailure { t ->
            log.w(t) { "Best-effort cover reconciliation failed" }
        }
    }

    private fun reportProgress(progress: LibraryRefreshWorkProgress) {
        runCatchingCancellable {
            onProgress(progress)
        }.onFailure { t ->
            log.w(t) { "Optional refresh progress display failed" }
        }
    }

    internal sealed interface ItemOutcome {
        data class Completed(
            val newChapterCount: Int,
            val notifications: List<LibraryChapterNotification> = emptyList(),
        ) : ItemOutcome

        data object Failed : ItemOutcome

        data object TimedOut : ItemOutcome
    }

    private class Accounting {
        private var total: Int? = null
        private var started = 0
        private var succeeded = 0
        private var failed = 0
        private var timedOut = 0
        private var newChapters = 0

        @Synchronized
        fun readSnapshot(size: Int) {
            check(total == null && size >= 0)
            total = size
        }

        @Synchronized
        fun startItem() {
            check(started < (total ?: 0))
            started++
        }

        @Synchronized
        fun finishItem(outcome: ItemOutcome) {
            check(succeeded + failed + timedOut < started)
            when (outcome) {
                is ItemOutcome.Completed -> {
                    succeeded++
                    newChapters += outcome.newChapterCount
                }
                ItemOutcome.Failed -> failed++
                ItemOutcome.TimedOut -> timedOut++
            }
        }

        @Synchronized
        fun snapshot(stop: LibraryRefreshWorkStop? = null): LibraryRefreshWorkProgress {
            val interrupted = started - succeeded - failed - timedOut
            val expired = stop == LibraryRefreshWorkStop.TOTAL_TIMEOUT
            return LibraryRefreshWorkProgress(
                total,
                succeeded,
                failed + if (expired) 0 else interrupted,
                timedOut + if (expired) interrupted else 0,
                total?.minus(started),
                newChapters,
                stop,
            )
        }
    }

    internal companion object {
        const val BATCH_SIZE = 5
        const val INTER_BATCH_DELAY_MS = 1_000L
    }
}

private fun SavedMangaEntity.toManga(): Manga = Manga(api, language, title, url, imageUrl, null, genres)

private suspend fun displayBestEffort(
    notifications: List<LibraryChapterNotification>,
    port: LibraryRefreshWorkPort,
    log: Logger,
) {
    if (notifications.isEmpty()) return
    runCatchingCancellable {
        // Outside the item deadline, but joined within the total budget and caller ownership.
        port.displayNotifications(notifications)
    }.onFailure { t ->
        log.w(t) { "Optional chapter notification display failed" }
    }
    currentCoroutineContext().ensureActive()
}
