package me.manga.kira.domain.usecase.library

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
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cross-platform "refresh all library" (#1): iterate every saved manga, fetch its current chapter
 * list from the source, and persist any newly-discovered chapters (flagged NEW). Returns the total
 * number of new chapters persisted.
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
 * fires per-manga notifications); both converge on the same persist semantics.
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
    suspend operator fun invoke(): AppResult<Int> = try {
        withContext(dispatchers.io) {
            val library = observeLibrary().first()
            if (library.isEmpty()) {
                AppResult.Success(0)
            } else {
                var total = 0
                val completed = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    val batches = library.chunked(BATCH_SIZE)
                    batches.forEachIndexed { i, batch ->
                        val counts = coroutineScope {
                            batch.map { lib ->
                                async { refreshOne(lib.manga) }
                            }.awaitAll()
                        }
                        total += counts.sum()
                        if (i < batches.lastIndex) delay(INTER_BATCH_DELAY_MS)
                    }
                } != null
                if (!completed) {
                    // The 15-min total timeout fired: the remaining manga were cancelled and never
                    // checked. The returned Success(total) carries only the pre-timeout chapters, so
                    // log the truncation so support/QA can tell a truncated refresh from a complete
                    // one (surfacing it in the UI would change the result contract — owner decision).
                    FlowLog.log(
                        "LibraryRefresh",
                        "totalTimeout",
                        "truncated library=${library.size} newSoFar=$total",
                    )
                }
                AppResult.Success(total)
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        AppResult.Failure(AppError.Unexpected(message = "Library refresh failed", cause = t))
    }

    private suspend fun refreshOne(
        manga: me.manga.kira.domain.model.Manga,
    ): Int = withTimeoutOrNull(PER_MANGA_TIMEOUT_MS) {
        when (val details = fetchDetails(manga)) {
            is AppResult.Success -> {
                // Repair a rotated cover URL (parity with the Android worker). No-op when unchanged
                // or not in library; a reconcile failure must not abort the chapter refresh below.
                libraryRepo.updateCoverIfChanged(
                    api = manga.api,
                    language = manga.language,
                    title = manga.title,
                    newCoverUrl = details.value.coverUrl,
                )
                (persistAndNotify(manga, details.value.chapters) as? AppResult.Success)?.value ?: 0
            }
            is AppResult.Failure -> 0 // a single source failing must not abort the whole refresh
        }
    } ?: 0

    companion object {
        const val BATCH_SIZE = 5
        const val PER_MANGA_TIMEOUT_MS = 30_000L
        const val TOTAL_TIMEOUT_MS = 15L * 60 * 1000
        const val INTER_BATCH_DELAY_MS = 1_000L
    }
}
