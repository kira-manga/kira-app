package me.manga.kira.domain.usecase.downloads

import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.ChapterIdResolver

/**
 * Use case: enqueue a download for **every** chapter of [details] — the rework Details
 * "Download all" action (legacy `HeaderSection` `action_download_all` parity).
 *
 * Phase 7.x.details.downloadall. Composed entirely from proven building blocks — no new
 * repository mutation surface:
 *  - [ChapterIdResolver] resolves each chapter's canonical `url` to its Room
 *    `saved_chapters.id` (the pure-domain [me.manga.kira.domain.model.Chapter] is `url`-keyed
 *    and carries no surrogate id; the download subsystem keys on the `Long` id).
 *  - [EnqueueDownloadUseCase] enqueues each resolved chapter — the *same* per-chapter enqueue
 *    path the rework Updates download button uses (Tasks #299/#300), keyed on
 *    `(chapterId, mangaTitle, api)`. [details]'s `title` + `api` supply the denormalised
 *    metadata the enqueue path needs (the legacy `enqueueChapterDownload` carries them on the
 *    manga record, not the chapter).
 *
 * **Skip semantics (parity with the single-enqueue path)**:
 *  - A chapter whose `url` resolves to `null` has no `saved_chapters` row (its manga is not in
 *    the library) — it is skipped. The single-enqueue path treats a missing row as a failure
 *    because it has nowhere to read the `SavedChapterEntity` from; here, with potentially many
 *    chapters, skipping the un-resolvable ones and enqueuing the rest is the legacy "download
 *    all" behaviour (only library-known chapters were enqueued). The `:ui` already gates the
 *    "Download all" button on `state.isInLibrary`, so in practice every chapter resolves.
 *  - By default, already-downloaded chapters are skipped, preserving native's "download all"
 *    outcome. Re-enqueuing a SUCCESS chapter would replace its ledger row with QUEUED and fetch
 *    its pages again. Callers may supply a live eligibility predicate instead of the captured
 *    saved flag; it is authoritative and checked after resolution, immediately before each enqueue.
 *
 * **Result semantics**: returns [Result.success] when the enqueue loop completes (per-chapter
 * enqueue failures are absorbed so one source/Room hiccup doesn't abort the whole batch —
 * matches the legacy fire-and-forget "download all", which enqueued each chapter independently);
 * returns [Result.failure] only if the resolve/enqueue orchestration itself throws (e.g. the
 * resolver fails catastrophically). The caller surfaces a failure via the existing error
 * snackbar effect.
 *
 * **Threading**: the whole resolve + enqueue loop runs on [DispatcherProvider.io] via
 * `withContext`, off the UI thread — the chapter list can be large and each chapter incurs a
 * Room read plus a legacy enqueue. The VM invokes this from a fire-and-forget
 * `viewModelScope.launch`, same as the Updates download button.
 *
 * Contract §6 SRP: one rule — "enqueue every resolvable chapter of a manga for download".
 * Contract §6 DIP: depends only on `:domain` seams ([ChapterIdResolver], the
 * [EnqueueDownloadUseCase] composition root) and the [DispatcherProvider] indirection — never on
 * `:data` impls, the legacy `:shared` facade, or any Room DAO.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `detailsReworkModule` (stateless, cheap to construct).
 */
class EnqueueAllChaptersDownloadUseCase(
    private val chapterIdResolver: ChapterIdResolver,
    private val enqueueDownload: EnqueueDownloadUseCase,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Resolves the full captured roster, then checks [shouldEnqueue] for each resolvable chapter.
     * The predicate runs synchronously on [DispatcherProvider.io], including after any previous
     * enqueue suspension; callers supplying live policy must use thread-safe snapshot reads.
     */
    suspend operator fun invoke(
        manga: Manga,
        details: MangaDetails,
        shouldEnqueue: (Chapter) -> Boolean = { !it.isDownloaded },
    ): Result<Unit> =
        runCatchingCancellable {
            withContext(dispatchers.io) {
                // Resolve every chapter url -> id in one chunked query instead of N per-chapter Room
                // round-trips. Urls with no in-library row are absent from the map (skipped), same as
                // the prior per-chapter `resolveChapterId(...) ?: return@forEach` skip.
                val idsByUrl = chapterIdResolver.resolveChapterIds(manga, details.chapters.map { it.url })
                details.chapters.forEach { chapter ->
                    val chapterId = idsByUrl[chapter.url] ?: return@forEach
                    if (!shouldEnqueue(chapter)) return@forEach
                    enqueueDownload(
                        chapterId = chapterId,
                        mangaTitle = details.title,
                        api = details.api,
                    )
                }
            }
        }
}
