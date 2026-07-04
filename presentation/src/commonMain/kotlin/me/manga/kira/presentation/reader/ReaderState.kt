package me.manga.kira.presentation.reader

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.mvi.MviState

/**
 * Immutable Reader screen state.
 *
 * Strict MVI: every property is `val`. The reducer in [ReaderViewModel] is the only writer; the
 * view observes [me.manga.kira.presentation.mvi.MviViewModel.state] read-only.
 *
 * Shape design (vs. the legacy `:shared` `ReaderViewModel`'s separate StateFlows —
 * `allReaderItems`, `loadingChapters`, `readingMode`, `bookmarked`, `currentChapterIndex`;
 * the original `loadedChapterIndexes` + `compressionStates` accessors were componentpruned
 * in Phase 9.x.readervm.componentprune — see legacy VM file header for that audit):
 *  - **Single composite state, not seven parallel flows.** A reducer-driven `data class` with
 *    `.copy()` ensures readers see either the old or the new value atomically. Seven flows let
 *    readers race (page list updated but `loadingChapters` not yet, etc.). Phase 6.4.3 collapses
 *    the four flows the minimal slice owns into one record; the other three (reading-mode,
 *    bookmark, multi-chapter feed) move into their own future-slice fields without changing the
 *    rest of the shape.
 *  - **`pages: List<Page>`, not `List<ReaderItem>`.** The legacy `ReaderItem` carried per-page
 *    `BitmapPainter` + `chapterIndex` + sealed `NextChapterOverlay` / `ErrorOverlay` variants.
 *    The painter belongs in `:ui` (Phase 7.x.reader); the overlay variants are a presentation
 *    timeline concern that re-emerges when multi-chapter prefetching lands. Until then the
 *    domain [Page] (`url + headers`) is exactly what the screen needs to render a single
 *    chapter via a Coil `AsyncImage` pager / column.
 *  - **`isLoading: Boolean` + `error: AppError?` flags, not a wrapping sealed case.** Same
 *    rationale as [me.manga.kira.presentation.details.DetailsState] §6.3.3: splitting them
 *    lets the screen render the half-loaded list or a stale page list while a refresh is in
 *    flight, and pull-to-refresh is a one-line addition (flip [isLoading] back to `true`
 *    without clearing [pages]).
 *  - **`manga` + `chapter` held in state.** The legacy VM stashed `currentChaptersList` /
 *    `currentChapterIndex` as mutable fields set by `initialize(...)`. The rework keeps the
 *    identity in state so a configuration-change re-emission of the StateFlow on a fresh host
 *    shows the same screen — no out-of-band `initialize` call needed. The host triggers the
 *    fetch by submitting [ReaderIntent.OnEnter] which carries the [Manga] + [Chapter] explicitly.
 *  - **`error` is `AppError?` not `String?`.** Same rule as Details and Library — presentation
 *    never embeds user-visible text; the `:ui` layer (Phase 7.x.reader) translates the typed
 *    error to a localized string.
 *  - **`currentPageIndex: Int` tracks scroll position.** Observation-only today; future slices
 *    consume it for statistics, resume-on-last-page persistence, and the page indicator HUD.
 *    Default `0` is safe — clamping in the reducer protects against stale values once `pages`
 *    shrinks (e.g. a fresh OnEnter for a different chapter).
 *
 * **Deferred state fields** (none of these block the slice — each lands with its own use case):
 *  - Multi-chapter feed (was the legacy `loadedChapterIndexes` + `allReaderItems` pair, with
 *    the former now componentpruned in Phase 9.x.readervm.componentprune) — needs a richer
 *    `FetchChapterFeedUseCase` that aggregates a sliding window of chapters. The single-chapter
 *    fetch shipped today is the foundation; the feed accumulator builds on it.
 *  - Per-page bitmap compression (was the legacy `compressionStates` shim, now componentpruned
 *    in Phase 9.x.readervm.componentprune) — needs a cross-platform bitmap-compression
 *    pipeline. Legacy already nopped this (see legacy VM §10.3 Cluster E note #3); the rework
 *    follows the same posture.
 *
 * **Multi-chapter navigation field** (Phase 7.x.reader.next):
 *  - [chapters] holds the manga's full chapter list, fetched once per manga on first
 *    [ReaderIntent.OnEnter] via [me.manga.kira.domain.usecase.reader.ListChaptersUseCase].
 *    Stays populated across Next / Prev intra-manga navigation (in-place state mutation strategy:
 *    a new chapter is just an OnEnter for the same manga, which preserves [chapters]).
 *    Empty list is the safe initial value — Next / Prev convenience props evaluate to `false`
 *    until the first list lands, so the top-bar buttons are disabled during the initial fetch.
 *    Fetch failure is non-fatal: [chapters] stays empty, Next / Prev stay disabled, but the
 *    page-fetch happy path is unaffected (the two fetches are independent — see
 *    [ReaderViewModel.runListChapters] KDoc). Unlike [error] which surfaces page-fetch failures,
 *    chapter-list failure is silent at the MVI surface — the buttons just don't appear actionable.
 *
 * Lifecycle clean-up (CBZ extraction cache) lives in the rework VM's `onCleared`, which calls
 * `ClearExtractedPagesUseCase` for the current chapter (per-chapter dirs are also cleared on each
 * chapter change in `onEnter`) so a downloaded chapter's extracted page set doesn't accumulate.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster101.staleKdocSweep.cascade,
 * Task #557, 2026-05-28): the multi-bullet MVI-state manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-first sibling of the cluster57-100 sweep — sibling
 * of cluster101 ReaderEffect.kt plus ReaderIntent.kt):
 *  (a) "Shape design" bullets (L17-49) — LIVE-NOT-STALE. The "Single
 *  composite state, not seven parallel flows" plus "pages: List<Page>,
 *  not List<ReaderItem>" plus "isLoading plus error flags, not a
 *  wrapping sealed case" plus "manga plus chapter held in state" plus
 *  "error is AppError? not String?" plus "currentPageIndex: Int" all
 *  realized exactly as forecast in the L79-159 data class shape. The
 *  legacy `:shared` `ReaderViewModel`'s separate StateFlows shape is
 *  documented for contrast; the cited `loadedChapterIndexes` plus
 *  `compressionStates` componentprune at Phase 9.x.readervm.component-
 *  prune cross-ref verified against `shared/.../reader/ui/viewmodel/
 *  ReaderViewModel.kt` legacy file header.
 *  (b) "Deferred state fields" list:
 *    - "isBookmarked: Boolean ... needs a `LibraryRepository` use case
 *      (chapter-bookmark observe plus toggle)" — FULFILLED-PREDICTION.
 *      Task #217 (Phase 6.4.x.bookmark) HAS LANDED: the [isBookmarked]
 *      field is LIVE, driven by `ObserveChapterBookmarkUseCase` via
 *      ReaderViewModel.runObserveBookmark. The deferral bullet was
 *      removed from the manifest above; this classification records
 *      that the forecast was subsequently fulfilled.
 *    - "Multi-chapter feed ... was the legacy `loadedChapterIndexes`
 *      plus `allReaderItems` pair, with the former now componentpruned
 *      in Phase 9.x.readervm.componentprune" — LIVE-NOT-STALE. The
 *      componentprune cross-ref accurately documents legacy retire-
 *      ment; the rework feed accumulator remains deferred — distinct
 *      from the Phase 7.x.reader.next multi-chapter NAVIGATION which
 *      DID ship via in-place `onEnter` recursion (navigation, not
 *      multi-chapter page rendering).
 *    - "Per-page bitmap compression ... was the legacy `compression-
 *      States` shim, now componentpruned" — LIVE-NOT-STALE. Legacy
 *      no-op posture preserved; rework follows the same posture.
 *  (c) "Multi-chapter navigation field (Phase 7.x.reader.next)"
 *  bullet (L63-73) — FULFILLED-PREDICTION. The [chapters] field at
 *  L132 LIVE; convenience props [currentChapterIndex] (L175),
 *  [canGoNext] (L185), [canGoPrev] (L193) LIVE; behaviour matches
 *  forecast (preserved across intra-manga nav per `mangaChanged`
 *  branch at ReaderViewModel.kt:239, cleared on manga change, silent
 *  failure handling at ReaderViewModel.kt:394-399).
 *  (d) "Lifecycle clean-up (CBZ extraction cache) lives in the rework
 *  VM's `onCleared` once the downloaded-chapter local-path branch
 *  lands in `ChapterPagesRepositoryImpl` (deferred per §56.5 plus
 *  §57.8)" — FULFILLED-PREDICTION. The forecast HAS LANDED:
 *  ReaderViewModel.onCleared calls `ClearExtractedPagesUseCase` for
 *  the current chapter, and `onEnter` clears the prior chapter's dir
 *  on each chapter change. The manifest above was updated to describe
 *  the live cleanup hook.
 *  The per-field KDocs at L86-158 (isUiVisible, readingMode, chapters,
 *  pageProgress) STAND as LIVE-NOT-STALE on their own merits per
 *  recursive symbol verification against ReaderViewModel.kt's reducer
 *  branches at L187 (OnUiToggle), L174-178 (init observeReadingMode),
 *  L385-402 (runListChapters), L369-383 (startObservingProgress).
 *  One LIVE-NOT-STALE classification plus one MIXED-with-partial-
 *  fulfillment classification (one FORECAST-NOT-YET-FULFILLED plus
 *  two LIVE-NOT-STALE sub-classifications) plus one FULFILLED-
 *  PREDICTION plus one FORECAST-NOT-YET-FULFILLED STAND on their own
 *  merits as a faithful Reader-state shape manifest. Original Phase
 *  6.4.3-era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
data class ReaderState(
    val isLoading: Boolean = false,
    val manga: Manga? = null,
    val chapter: Chapter? = null,
    val pages: List<Page> = emptyList(),
    /**
     * Owning chapter URL for each entry in [pages], same length and order (#5 continuous reader).
     * For a single (non-appended) chapter every entry is [chapter]'s url. When the user scrolls to
     * the end of a continuous-scroll chapter the NEXT chapter's pages are APPENDED to [pages] (the
     * current pages are NOT removed), and their urls are appended here — so the active chapter can be
     * derived from the visible page rather than swapping the whole list. Empty until the first fetch.
     */
    val pageChapters: List<String> = emptyList(),
    /**
     * The chapter URLs currently loaded into [pages], in append order (the anchor chapter first,
     * then each appended next chapter). Used to dedupe appends and to find the tail chapter whose
     * end triggers the next append. Reset to the single anchor chapter on an explicit chapter jump.
     */
    val loadedChapterUrls: List<String> = emptyList(),
    val currentPageIndex: Int = 0,
    val error: AppError? = null,
    /**
     * Whether the top bar and page-indicator HUD are visible.
     *
     * Default `true` (chrome visible on first entry — matches legacy reader). The user toggles
     * via [ReaderIntent.OnUiToggle] (tap on the page area). Pure presentation state — not
     * persisted across screen re-entries (a fresh `OnEnter` resets to default via reducer
     * branch — see [ReaderViewModel.onEnter] KDoc).
     *
     * Conceptually a UI-chrome concern that the legacy reader tracked locally in a `remember`.
     * The rework hoists it into MVI state so toggle behavior is testable without
     * Compose-snapshot harness, and so configuration changes preserve the user's choice.
     */
    val isUiVisible: Boolean = true,
    /**
     * The user's persisted reading-mode preference.
     *
     * Fed by `ObserveReadingModeUseCase` (Phase 6.4.x.mode), started at VM `init` so the first
     * emission lands before / shortly after the first `OnEnter`. Default [ReadingMode.DEFAULT]
     * is the safe initial value while the first emission is in flight and mirrors the legacy
     * `DataStoreHelper.readingModeFlow` default.
     *
     * User picks dispatch [ReaderIntent.OnReadingModeChanged]; the VM forwards to
     * `SetReadingModeUseCase` and the observer re-emits, updating this field. We deliberately
     * do not mutate this field inline on the intent — single source of truth is the on-disk
     * value reflected back through the observer, so a write that's rejected by the store (today
     * impossible, future-proof) doesn't desync the UI from disk.
     *
     * Pure presentation state — the `:ui` reader picker reads this to highlight the current
     * choice and the page composable (future slice) will branch its layout on this value.
     */
    val readingMode: ReadingMode = ReadingMode.DEFAULT,
    /**
     * Full chapter list for the active [manga], populated by
     * [me.manga.kira.domain.usecase.reader.ListChaptersUseCase] on first OnEnter per manga.
     *
     * Empty until the fetch returns; stays populated across intra-manga Next / Prev navigation
     * (see [ReaderViewModel.onEnter] KDoc — same-manga OnEnter preserves this field). Cleared
     * only when the active manga changes.
     *
     * Order semantics: identical to legacy `BaseMangaRepository.fetchMangaChaptersF` — newest
     * first for most sources. Next / Prev arithmetic is purely positional (`curr + 1` /
     * `curr - 1`) to mirror legacy `goToNextChapter` (its `goToPreviousChapter` peer was
     * componentpruned in Phase 9.x.readervm.componentprune). The "Next" button
     * means "next-by-source-order", not "chronologically next" — matches what the chapter list
     * shows on Details.
     */
    val chapters: List<Chapter> = emptyList(),
    /**
     * Per-page download/decode progress, keyed by [Page.url].
     *
     * Populated by per-URL collectors the VM starts in `runFetch`'s Success branch (one collector
     * per page in the current chapter, subscribed to
     * [me.manga.kira.domain.repository.PageProgressRepository.observe]). The `:ui` loading slot
     * reads `pageProgress[page.url] ?: PageDownloadProgress.Idle` to render the right placeholder
     * variant (indeterminate spinner vs determinate ring with %, vs nothing because Coil has the
     * decoded bitmap, vs the error slot — see [PageDownloadProgress] KDoc for the state machine).
     *
     * Cleared back to `emptyMap()` on each [ReaderIntent.OnEnter] — a fresh chapter starts with a
     * blank slate (the previous chapter's progress entries linger in the repository's in-memory map
     * but are no longer referenced by the active state and don't drive UI).
     *
     * Memory: bounded by chapter page count (≤200 typically). Each entry is a String key + a small
     * sealed-interface reference. Negligible.
     *
     * Why not derived from the repository's whole-map flow:
     *  - Per-URL collectors only fire when *their* URL's state changes (the impl's
     *    `distinctUntilChanged` over the projected per-URL flow filters whole-map churn caused by
     *    other URLs). A whole-map observation would re-emit for every neighbor's tick.
     *  - The VM filters [PageDownloadProgress.Idle] emissions (the default value for never-reported
     *    URLs) so chapter entry doesn't trigger a flurry of spurious `updateState` calls before any
     *    real progress event arrives.
     */
    val pageProgress: Map<String, PageDownloadProgress> = emptyMap(),
    /**
     * Whether the active [chapter] is bookmarked (Phase 6.4.x.bookmark, Reader-convergence R2).
     *
     * Driven by `ObserveChapterBookmarkUseCase` for the current chapter URL — the VM launches a
     * tracked collector on each chapter establish/change and lifts every emission here. The
     * top-bar bookmark toggle dispatches [ReaderIntent.OnToggleBookmark]; the VM forwards to
     * `ToggleChapterBookmarkUseCase` and lets the observe-flow re-emission drive this field (no
     * optimistic flip — single source of truth is the on-disk bookmark column reflected back).
     * Default `false` is the safe initial value (degrades safely when the chapter is not
     * in-library: observe emits `false`, toggle no-ops).
     */
    val isBookmarked: Boolean = false,
) : MviState {

    /** Convenience: true once a successful fetch has populated [pages]. */
    val hasPages: Boolean get() = pages.isNotEmpty()

    /** Convenience: true when we're loading the first fetch (no prior pages yet). */
    val isInitialLoading: Boolean get() = isLoading && pages.isEmpty()

    /**
     * URL of the chapter the user is currently VIEWING — derived from the visible page so that, in a
     * continuous reader with appended chapters, the title/Next-Prev/HUD track the segment in view
     * rather than the anchor [chapter]. Falls back to the anchor when no per-page tags exist yet.
     */
    val activeChapterUrl: String?
        get() = pageChapters.getOrNull(currentPageIndex) ?: chapter?.url

    /** The chapter the user is currently viewing (segment-aware), or the anchor [chapter]. */
    val activeChapter: Chapter?
        get() = activeChapterUrl?.let { u -> chapters.firstOrNull { it.url == u } } ?: chapter

    /**
     * Position of the ACTIVE (currently-viewed) chapter in [chapters], or `-1` if not found.
     *
     * Derived from [activeChapterUrl] so a continuous reader with appended chapters reports the
     * segment in view. Returns `-1` when [chapters] hasn't loaded yet or the URL doesn't match
     * (defensive — degrade gracefully on inconsistent source URLs).
     */
    val currentChapterIndex: Int
        get() = activeChapterUrl?.let { u -> chapters.indexOfFirst { it.url == u } } ?: -1

    /** 1-based page number WITHIN the active chapter segment (for the HUD), or `currentPageIndex+1`. */
    val activeChapterPageNumber: Int
        get() = if (pageChapters.isEmpty()) currentPageIndex + 1
        else pageChapters.subList(0, (currentPageIndex + 1).coerceIn(0, pageChapters.size))
            .count { it == activeChapterUrl }

    /** Total page count of the active chapter segment (for the HUD), or [pages] size. */
    val activeChapterPageCount: Int
        get() = if (pageChapters.isEmpty()) pages.size else pageChapters.count { it == activeChapterUrl }

    /**
     * Absolute indices into [pages] that belong to the ACTIVE chapter segment, in order.
     *
     * This is what scopes the page slider to the current chapter: its position within this list is
     * the slider value and its size is the slider range, so the slider re-binds to the active chapter
     * as the user scrolls across an appended boundary. Mapping a slider position `r` back to a feed
     * page is `activeChapterPageIndices[r]`. Falls back to all page indices when [pageChapters] hasn't
     * been tagged yet (single chapter, pre-fetch). Contiguous in practice (appends only add a tail run).
     */
    val activeChapterPageIndices: List<Int>
        get() = if (pageChapters.isEmpty()) {
            pages.indices.toList()
        } else {
            val active = activeChapterUrl
            pageChapters.mapIndexedNotNull { i, url -> if (url == active) i else null }
        }

    /**
     * Whether the "Next chapter" affordance should be enabled.
     *
     * `true` iff the active chapter is found in [chapters] AND is not the last entry. Disabled
     * during the initial chapter-list fetch (when [chapters] is empty / `currentChapterIndex`
     * is `-1`) and at the end of the list.
     */
    val canGoNext: Boolean
        get() = currentChapterIndex in 0..<chapters.lastIndex

    /**
     * Whether the "Previous chapter" affordance should be enabled.
     *
     * `true` iff the active chapter is found in [chapters] AND is not the first entry.
     */
    val canGoPrev: Boolean
        get() = currentChapterIndex > 0
}
