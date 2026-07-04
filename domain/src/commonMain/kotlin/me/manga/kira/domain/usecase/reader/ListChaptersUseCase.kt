package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.flow.first
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * List the chapters of a given [Manga].
 *
 * Phase 7.x.reader.next foundation: the rework Reader needs the manga's chapter list to expose
 * Next / Previous chapter navigation. Today the chapters are already fetched by Details
 * ([me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase]) but the Reader has no
 * way to read them — `Screen.ChapterImagesRework` nav args carry only the single target chapter,
 * and `ReaderViewModel` never reaches the Details VM.
 *
 * Why a thin use case over [MangaDetailsRepository] rather than a new `ChaptersRepository`:
 *  - Legacy `BaseMangaRepository.fetchMangaChaptersF(url)` is the single endpoint that delivers
 *    both the manga metadata and its chapter list — they ship in one `MangaInfo` payload.
 *    There is no "chapters-only" endpoint on the source contract. So a `ChaptersRepository`
 *    would either (a) duplicate [MangaDetailsRepositoryImpl]'s fetch logic, or (b) delegate to
 *    [MangaDetailsRepository] internally — at which point the extra interface is dead weight.
 *  - Folding the projection (`MangaDetails` → `List<Chapter>`) into a presentation-friendly
 *    use case keeps the VM signature narrow: the Reader VM gets the chapter list as a
 *    first-class `AppResult<List<Chapter>>` instead of having to thread the whole `MangaDetails`
 *    through state just to read one field.
 *  - The `MangaDetails.chapters` field is the source of truth in the rework — single source of
 *    truth (contract §6 SRP). Reader's chapter list IS Details' chapter list; there's no
 *    independent observation pathway and no field-by-field divergence to manage.
 *
 * Cost vs. caching:
 *  - Cache-first: for an in-library manga the chapter list is read from the Room-saved details
 *    via [SavedMangaDetailsRepository] (the same list Details shows), so no network/source hit is
 *    incurred and the clicked chapter's URL is guaranteed present. Only a NOT-in-library manga
 *    (opened from search/home, no saved list yet) falls back to a full `MangaDetails` network
 *    fetch via [MangaDetailsRepository]; the description / cover / genres in that payload are
 *    discarded — the Reader only needs the chapter list.
 *  - The Reader VM also caches the result in `ReaderState.chapters` after the first successful
 *    fetch (see [me.manga.kira.presentation.reader.ReaderViewModel.onEnter] re-entrance
 *    guard) — subsequent Next / Prev navigation does NOT re-invoke the use case.
 *
 * Error semantics:
 *  - Source unknown / network failure / parse failure all surface as `AppResult.Failure` — same
 *    classifier the Details slice uses ([me.manga.kira.data.repository.MangaDetailsRepositoryImpl]
 *    `LegacyState.Error.toAppError()`).
 *  - An empty list (`AppResult.Success(emptyList())`) is a valid outcome — a manga with no
 *    chapters yet. The VM treats `chapters.isEmpty()` as "Next / Prev disabled".
 *
 * Cancellation: propagates through the underlying repository call.
 *
 * Constructor-injected [MangaDetailsRepository] (network fetch) and [SavedMangaDetailsRepository]
 * (Room cache-first read) per contract §6 DIP — Koin binds the same `:data` singletons declared
 * in `detailsReworkModule` to both Details and Reader graphs.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster125.staleKdocSweep.cascade,
 * Task #581, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-second sibling of the cluster57-124 sweep —
 * fifth and closing file of the wave-22 `:domain/usecase/reader/` 5-
 * file batch alongside StartReadingSession plus EndReadingSession plus
 * LoadPagePosition plus SavePagePosition; closing 3-file batch (Fetch-
 * ChapterPages plus ObserveReadingMode plus SetReadingMode) deferred
 * to cluster126 follow-up):
 *  (a) "Phase 7.x.reader.next foundation Reader-VM-chaptersJob-load +
 *  Next/Prev-navigation-enabler" — LIVE-NOT-STALE. ReaderViewModel.kt
 *  L15 import, L229 ctor `private val listChapters: ListChaptersUseCase`,
 *  L495 realization `when (val result = listChapters(manga))` inside
 *  the chaptersJob's launched coroutine; ReaderViewModel L91 KDoc
 *  references the chaptersJob tracking. ReaderState.chapters first-
 *  successful-fetch caching upheld — subsequent Next/Prev navigation
 *  reuses the cached list per the original prose's re-entrance guard
 *  invariant.
 *  (b) "Why-thin-use-case-over-MangaDetailsRepository-not-new-Chapters-
 *  Repository — legacy fetchMangaChaptersF delivers MangaInfo with
 *  both metadata + chapter list; no chapters-only endpoint; folding
 *  the projection (MangaDetails -> List<Chapter>) into a use case
 *  keeps VM signature narrow; single-source-of-truth for chapters
 *  (Reader's chapter list IS Details' chapter list)" — LIVE-NOT-STALE.
 *  Single-source-of-truth invariant verified by the use case's L58-62
 *  projection logic — `AppResult.Success(result.value.chapters)`
 *  extracts only the chapters field; the description / cover / genres
 *  / rating are discarded per the original prose's cost-vs-caching
 *  framing. Future Flow-based cache-first-with-network-refresh
 *  forecast — FORECAST-NOT-YET-FULFILLED. Recursive search for Flow-
 *  shaped chapter-observe returns zero matches; suspend AppResult
 *  shape unchanged.
 *  (c) "§6 SRP + §6 DIP + same-:data-singleton-shared-by-detailsRework-
 *  Module-and-readerReworkModule" — LIVE-NOT-STALE. ReaderReworkModule.
 *  kt L108 `factory { ListChaptersUseCase(get()) }` realization; the
 *  MangaDetailsRepository single binding lives in detailsReworkModule
 *  (per the readerReworkModule L44-49 KDoc referenced at the module
 *  level); Koin's cross-module single graph resolves the same instance
 *  to both consumers — the dead-weight-ChaptersRepository-interface
 *  avoidance rationale stands. Closes wave-22 `:domain/usecase/reader/`
 *  5-file batch; cluster126 follow-up (3 files) will close reader/ as
 *  FULLY SWEPT (8 of 8 files); ≤5-file-cap-with-followup convention
 *  upheld for the third consecutive wave after wave-20 downloads/ 5+1
 *  and wave-21 complaint/ 5+4 splits. Three classifications STAND on
 *  their own merits. Original Phase 7.x.reader.next-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ListChaptersUseCase(
    private val repository: MangaDetailsRepository,
    private val savedDetails: SavedMangaDetailsRepository,
) {

    suspend operator fun invoke(manga: Manga): AppResult<List<Chapter>> {
        // CACHE-FIRST: for an in-library manga the chapter list is already persisted in Room — it
        // IS the list the Details screen shows (same `getChaptersByMangaId` query) and the list the
        // user clicked a chapter from. Reuse it directly so:
        //  (a) the Reader's chapter list is byte-identical to Details' — the clicked chapter's URL is
        //      guaranteed present, so `currentChapterIndex` resolves correctly and Next/Prev and the
        //      "last chapter" detection are right (a network list with even slightly different URLs
        //      yields index -1 → both nav disabled → a non-last chapter wrongly reads as the last);
        //  (b) we don't re-hit the network/source for a chapter list we already hold offline.
        // Network fetch is reserved for a NOT-in-library manga (opened from search/home) which has no
        // saved list yet. Insertion order (`id ASC`) equals the source fetch order, so reading
        // direction is preserved either way.
        val saved = try {
            savedDetails.observeSavedDetails(manga.api, manga.title).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A local-cache read failure degrades to the network path instead of escaping the
            // AppResult contract this use case declares.
            null
        }
        if (saved != null && saved.chapters.isNotEmpty()) {
            FlowLog.log("Reader", "chapterList", "source=room count=${saved.chapters.size}")
            return AppResult.Success(saved.chapters)
        }
        return when (val result = repository.fetchDetails(manga)) {
            is AppResult.Success -> {
                FlowLog.log("Reader", "chapterList", "source=network count=${result.value.chapters.size}")
                AppResult.Success(result.value.chapters)
            }
            is AppResult.Failure -> {
                FlowLog.log("Reader", "chapterList", "source=network result=failure")
                result
            }
        }
    }
}
