package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.Page

/**
 * Source of [Page]s for a given [Chapter].
 *
 * Contract §6 SRP: owns ONE rule — "given a manga + chapter, hand back the page sequence as it
 * becomes available, or a typed failure". The data-layer impl picks the right route (downloaded
 * CBZ extraction, downloaded image directory, or live source fetch) and the right per-source
 * repository — none of that routing logic leaks past this interface.
 *
 * Why [Flow] rather than `suspend fun ... : AppResult<List<Page>>`:
 *  - **Streaming sources are first-class**. Prochan-style sources emit pages incrementally as
 *    the source page is scrolled server-side (see legacy `ReaderViewModel.loadChapterStreaming`).
 *    The non-streaming case folds naturally into the same shape — a Flow that emits exactly once
 *    with the full list. Forcing the contract to `suspend AppResult` would push the streaming
 *    branch into a side-channel that the UI would have to special-case per source.
 *  - **Incremental delivery without intent-channel noise**. Each emission carries the cumulative
 *    page list so the presentation layer can `update { it.copy(pages = emission) }` directly. No
 *    delta protocol, no merge logic in the VM.
 *
 * Routing contract for the `:data` impl:
 *  1. If `chapter.isDownloaded` AND the future downloads facility supplies a local path list:
 *     emit `AppResult.Success(localPaths.map { Page(url = it, headers = emptyMap()) })` once and
 *     complete. If the only local path is a `.cbz`, the impl extracts it via `CbzReader` and emits
 *     the extracted paths.
 *  2. Otherwise: delegate to the per-source repository via `SourcesRepository.getRepoByName(manga.api)`
 *     and translate its `Flow<State<List<String>>>` to `Flow<AppResult<List<Page>>>` by attaching
 *     `BaseMangaRepository.defaultHeaders` to each URL.
 *
 * Downloaded-chapter local read (live):
 *  - The `:data` impl serves downloaded chapters from local storage first. For
 *    `chapter.isDownloaded` with a single `.cbz`, it extracts the archive via `CbzReader` (including
 *    the iOS stale-sandbox-path re-derivation under the live filesDir); for loose image files it
 *    emits the stored paths as `file://` URLs; otherwise it falls back to the source fetch.
 *  - This repo IS consumed by the rework reader — `ReaderViewModel` /
 *    `ChapterImagesReworkScreenRoute` drive reading via `FetchChapterPagesUseCase`.
 *
 * DIP (contract §6): consumers (`FetchChapterPagesUseCase`, the Reader VM) depend on this
 * interface, never on a concrete `:data` impl. Koin binds the impl at the composition root.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster139.staleKdocSweep.cascade,
 * Task #595, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-fourth sibling of the cluster57-138
 * sweep — second file of the wave-25 first-cluster 5-leaf-repository
 * batch alongside MangaDetailsRepository):
 *  (a) "Source-of-Pages-for-a-given-Chapter + Contract-§6-SRP-owns-ONE-
 *  rule-given-a-manga-plus-chapter-hand-back-the-page-sequence-as-it-
 *  becomes-available-or-a-typed-failure + the-data-layer-impl-picks-
 *  the-right-route-downloaded-CBZ-extraction-downloaded-image-
 *  directory-or-live-source-fetch-and-the-right-per-source-repository
 *  + Why-Flow-rather-than-suspend-fun-AppResult-List-Page + Streaming-
 *  sources-are-first-class + Prochan-style-sources-emit-pages-
 *  incrementally-as-the-source-page-is-scrolled-server-side + The-
 *  non-streaming-case-folds-naturally-into-the-same-shape-a-Flow-that-
 *  emits-exactly-once-with-the-full-list + Forcing-the-contract-to-
 *  suspend-AppResult-would-push-the-streaming-branch-into-a-side-
 *  channel-that-the-UI-would-have-to-special-case-per-source +
 *  Incremental-delivery-without-intent-channel-noise + Each-emission-
 *  carries-the-cumulative-page-list" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION. Verified via recursive grep: ChapterPagesRepository is
 *  consumed by FetchChapterPagesUseCase (the :domain caller) plus
 *  ReaderViewModel plus ReaderState plus ChapterImagesReworkScreen-
 *  Route plus ReaderReworkModule plus ChapterPagesRepositoryImpl. The
 *  interface still declares exactly ONE `fetchPages(manga, chapter):
 *  Flow<AppResult<List<Page>>>` method. ReaderViewModel collects each
 *  emission as the predicted `state.copy(pages = emission)` cumulative
 *  update — no delta protocol, no merge logic in the VM. Streaming +
 *  one-shot sources both surface through the same Flow shape.
 *  (b) "Routing-contract-for-the-:data-impl + If-chapter.isDownloaded-
 *  AND-the-future-downloads-facility-supplies-a-local-path-list-emit-
 *  AppResult.Success-localPaths.map-Page-url-it-headers-emptyMap-once-
 *  and-complete + If-the-only-local-path-is-a-.cbz-the-impl-extracts-
 *  it-via-CbzReader-and-emits-the-extracted-paths + Otherwise-delegate-
 *  to-the-per-source-repository-via-SourcesRepository.getRepoByName-
 *  manga.api-and-translate-its-Flow-State-List-String-to-Flow-
 *  AppResult-List-Page-by-attaching-BaseMangaRepository.defaultHeaders-
 *  to-each-URL + Deferred-lands-in-Phase-6.4.2-Downloaded-chapter-
 *  local-path-lookup + The-rework-has-no-DownloadsRepository-yet-
 *  until-that-lands-the-:data-impl-falls-back-to-source-fetch-even-
 *  for-chapter.isDownloaded-true + User-visible-impact-offline-reading-
 *  of-downloaded-chapters-re-fetches-from-source-through-the-rework-
 *  reader-path + Acceptable-for-Phase-6.4.x-because-no-rework-caller-
 *  invokes-this-repo-yet" — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-
 *  FORECAST plus FORECAST-NOT-YET-FULFILLED-(:data-ChapterPagesRepo-
 *  Impl-isDownloaded-branch-wiring-into-DownloadsRepository). Verified
 *  via recursive grep: DownloadsRepository EXISTS in :domain/
 *  repository/ since Phase 7.x.downloads.foundation (§276). However,
 *  ChapterPagesRepositoryImpl.kt at L44-48 still notes "The rework has
 *  no DownloadsRepository [wired into this impl] yet; until that
 *  lands, this impl will branch on chapter.isDownloaded first" — i.e.
 *  the DownloadsRepository SYMBOL exists but the predicted `if
 *  chapter.isDownloaded → emit localImagePaths` branch in
 *  ChapterPagesRepositoryImpl has NOT been wired in. The "no rework
 *  caller invokes this repo yet" sub-clause is now STALE (Reader-
 *  ViewModel + ChapterImagesReworkScreenRoute consume it post-Phase
 *  6.4.x.reader §215+§216), so the deferral rationale ("acceptable
 *  because no caller") no longer holds — but the wiring gap itself
 *  persists. The "future downloads facility lands as a :data-impl
 *  branch" remains the next productive step on this seam.
 *  (c) "DIP-contract-§6-consumers-FetchChapterPagesUseCase-the-future-
 *  Reader-VM-depend-on-this-interface-never-on-a-concrete-:data-impl
 *  + Koin-binds-the-impl-at-the-composition-root + Coroutine-context-
 *  the-:data-impl-flows-on-the-I/O-dispatcher-callers-do-not-need-to-
 *  switch-contexts + Cancellation-propagates-to-the-underlying-
 *  network-or-file-read + For-one-shot-sources-exactly-one-AppResult.
 *  Success-pages-then-completion + For-streaming-sources-a-sequence-
 *  of-AppResult.Success-cumulativePages-emissions-each-carrying-the-
 *  running-page-list-then-completion-when-the-source-signals-done +
 *  On-failure-a-single-AppResult.Failure-error-followed-by-completion
 *  + The-Flow-does-not-interleave-Success-and-Failure-within-the-same-
 *  chapter-load" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified:
 *  FetchChapterPagesUseCase.kt depends only on this interface — no
 *  :data import. ReaderReworkModule wires ChapterPagesRepositoryImpl
 *  as the single Koin binding. The "future Reader VM" is now LIVE
 *  (ReaderViewModel.kt — Phase 6.4.3 §214). The Success-then-completion
 *  vs Failure-then-completion non-interleaving contract holds in the
 *  :data impl per its cluster23 §479 postscript.
 *  Three classifications STAND on their own merits. Original Phase
 *  6.4.1-era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
interface ChapterPagesRepository {

    /**
     * Stream of page-list snapshots for [chapter] (which is one entry from
     * [manga.url]'s chapter list).
     *
     * Emissions:
     *  - For one-shot sources: exactly one `AppResult.Success(pages)`, then completion.
     *  - For streaming sources: a sequence of `AppResult.Success(cumulativePages)` emissions, each
     *    carrying the running page list (later emissions are supersets of earlier ones), then
     *    completion when the source signals done.
     *  - On failure: a single `AppResult.Failure(error)` followed by completion. The Flow does not
     *    interleave Success and Failure within the same chapter load.
     *
     * Coroutine context: the `:data` impl flows on the I/O dispatcher; callers do not need to
     * switch contexts. Cancellation propagates to the underlying network/file read.
     */
    fun fetchPages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>>

    /**
     * Best-effort, fire-and-forget cleanup of the temporary images extracted from a downloaded
     * chapter's CBZ archive (see the local-read branch of [fetchPages]). Reading a downloaded `.cbz`
     * extracts its pages into a per-chapter cache dir; without this the dirs accumulate unbounded.
     *
     * Non-suspend: the impl runs the file deletion on its own app-lifetime scope so it can be
     * called safely from a ViewModel's `onCleared()` (where `viewModelScope` is already cancelled).
     * A no-op for chapters that were not downloaded / not CBZ-extracted.
     */
    fun clearExtractedPages(chapter: Chapter)
}
