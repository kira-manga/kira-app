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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import kotlin.time.Clock

/** Observed chapter work and the actual WorkManager Result/stamp policy used by the worker. */
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
            onProgress(report)
            return Result.failure()
        }
        val stampFailure =
            runCatchingCancellable {
                if (report.snapshotSize != 0) port.stampLastSuccess()
            }.exceptionOrNull()
        return if (stampFailure != null) {
            log.w(stampFailure) { "Library refresh timestamp failed" }
            onProgress(report.copy(stop = LibraryRefreshWorkStop.STAMP_FAILED))
            Result.failure()
        } else {
            currentCoroutineContext().ensureActive()
            onProgress(report)
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
                            accounting.finishItem(refreshOne(manga))
                        }
                    }.awaitAll()
            }
            onProgress(accounting.snapshot())
            delay(INTER_BATCH_DELAY_MS)
        }
    }

    private suspend fun refreshOne(manga: SavedMangaEntity): ItemOutcome =
        runCatchingCancellable {
            withTimeoutOrNull(timeouts.itemMs) {
                if (manga.id == 0L) return@withTimeoutOrNull ItemOutcome.Failed
                val source = port.source(manga.api) ?: return@withTimeoutOrNull ItemOutcome.Failed
                val details =
                    withTimeoutOrNull(timeouts.detailsMs) {
                        source.details(manga.toManga())
                    } ?: return@withTimeoutOrNull ItemOutcome.TimedOut
                when (details) {
                    is AppResult.Success -> reconcile(manga, details.value)
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

    private fun SavedMangaEntity.toManga(): Manga = Manga(api, language, title, url, imageUrl, null, genres)

    private suspend fun reconcile(
        manga: SavedMangaEntity,
        details: MangaDetails,
    ): ItemOutcome {
        reconcileCover(manga, details.coverUrl)
        val local =
            withTimeoutOrNull(timeouts.localReadMs) { port.chapters(manga.id).first() }
                ?: return ItemOutcome.TimedOut
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val fetchedAt = Clock.System.now().toEpochMilliseconds()
        val chapters =
            details.chapters
                .filterNot { remote -> local.any { it.url == remote.url } }
                .map { remote ->
                    SavedChapterEntity(
                        mangaId = manga.id,
                        name = remote.name,
                        number = remote.number,
                        url = remote.url,
                        date = remote.date ?: today,
                        isNew = true,
                        fetchedAt = fetchedAt,
                    )
                }.reversed()
        return if (chapters.isEmpty()) ItemOutcome.Completed(0) else insertChapters(manga, chapters)
    }

    private suspend fun insertChapters(
        manga: SavedMangaEntity,
        chapters: List<SavedChapterEntity>,
    ): ItemOutcome {
        val ids = port.insert(chapters)
        // The legacy facade catches write errors and returns emptyList, not a thrown failure.
        // Room IGNORE returns one slot per candidate; -1 is valid, but missing/invalid slots aren't.
        if (ids.size != chapters.size || ids.any { it != -1L && it <= 0L }) return ItemOutcome.Failed
        notifyBestEffort(manga, chapters)
        return ItemOutcome.Completed(ids.count { it > 0L })
    }

    private suspend fun reconcileCover(
        manga: SavedMangaEntity,
        coverUrl: String,
    ) {
        if (coverUrl.isBlank() || coverUrl == manga.imageUrl) return
        runCatchingCancellable {
            port.updateCover(manga.id, coverUrl)
        }.onFailure { t ->
            log.w(t) { "Best-effort cover reconciliation failed" }
        }
    }

    private fun notifyBestEffort(
        manga: SavedMangaEntity,
        chapters: List<SavedChapterEntity>,
    ) {
        runCatchingCancellable {
            // App6's helper launches detached work and swallows failures. Returning here is NOT
            // evidence of completed Updates persistence; chapter insertion above is observed.
            port.notify(manga, chapters)
        }.onFailure { t ->
            log.w(t) { "Detached chapter notification could not be launched" }
        }
    }

    private sealed interface ItemOutcome {
        data class Completed(
            val newChapterCount: Int,
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
