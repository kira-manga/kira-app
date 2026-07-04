package me.manga.kira.presentation.reader

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.usecase.reader.EndReadingSessionUseCase
import me.manga.kira.domain.usecase.reader.ClearExtractedPagesUseCase
import me.manga.kira.domain.usecase.reader.ClearPageProgressUseCase
import me.manga.kira.domain.usecase.reader.FetchChapterPagesUseCase
import me.manga.kira.domain.usecase.reader.ListChaptersUseCase
import me.manga.kira.domain.usecase.reader.LoadPagePositionUseCase
import me.manga.kira.domain.usecase.reader.MarkChapterReadUseCase
import me.manga.kira.domain.usecase.reader.ObserveChapterBookmarkUseCase
import me.manga.kira.domain.usecase.reader.ObservePageProgressUseCase
import me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase
import me.manga.kira.domain.usecase.reader.RecordHistoryUseCase
import me.manga.kira.domain.usecase.reader.SavePagePositionUseCase
import me.manga.kira.domain.usecase.reader.SetReadingModeUseCase
import me.manga.kira.domain.usecase.reader.StartReadingSessionUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterBookmarkUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Reader screen ViewModel.
 *
 * Strict MVI: state lives in [ReaderState]; intents are sealed; effects are one-shot.
 * Constructor-injected use case (DIP) — never depends on the data layer directly.
 *
 * Replaces the minimal page-fetch / page-tracking slice of the legacy `:shared` `ReaderViewModel`
 * (the 601-line monolith documented in `shared/.../reader/ui/viewmodel/ReaderViewModel.kt`) with
 * a much narrower surface — see [ReaderIntent] / [ReaderState] KDocs for the explicit list of
 * deferred behaviours. The legacy VM owns seven responsibilities (page fetch, multi-chapter feed,
 * reading-mode persistence, bookmark observe/toggle, statistics session timer, CBZ cache cleanup,
 * compression state). The rework VM today owns ONE: orchestrating the page-fetch result for a
 * single chapter. Each deferred responsibility lands its own slice when its `:domain` use case
 * is ready, then extends this VM's intent surface (additive, OCP — never replaces what's here).
 *
 * Constructor dependency rationale:
 *  - [FetchChapterPagesUseCase] (Phase 6.4.1 `:domain`) is the only collaborator. Source routing,
 *    header attachment, error classification all live below the use case boundary (Phase 6.4.2
 *    `:data` impl). The VM never sees `SourcesRepository`, `BaseMangaRepository`, or
 *    `LegacyState` — that's the entire point of the rework.
 *  - No `DispatcherProvider`. The use case wraps its underlying source flow with `.flowOn(io)`
 *    in `ChapterPagesRepositoryImpl`, so by the time the VM collects the Flow the I/O work has
 *    already been dispatched. The reducer runs on the main-thread-equivalent `viewModelScope`
 *    default, which is the right surface for `updateState` calls.
 *
 * Re-entry idempotence on [ReaderIntent.OnEnter]: if the in-state `(manga, chapter)` identity
 * already matches the intent's pair, the reducer no-ops. Same posture as
 * [me.manga.kira.presentation.details.DetailsViewModel]. To force a re-fetch the view submits
 * [ReaderIntent.OnRetry] explicitly. Identity comparison uses [matches] (manga api+language+title
 * triple) and chapter `url` (which is the legacy `Chapter` primary-key surrogate and the use
 * case's effective routing key).
 *
 * Re-entrance guard on [ReaderIntent.OnRetry]: if a fetch is already in flight
 * (`state.value.isLoading == true`), the intent is dropped. This prevents concurrent fetches
 * from racing on `updateState` and producing nondeterministic order in their success/failure
 * landings — same rationale as `DetailsViewModel.onRetry`. The :ui surface that calls OnRetry
 * (Phase 7.x.reader) will disable the retry button while `isLoading`, but the VM guard closes
 * the gap for any other dispatcher (intent replay, programmatic dispatch, future pull-to-refresh).
 *
 * Streaming-source page-list semantics: [FetchChapterPagesUseCase] returns a `Flow<AppResult<List<Page>>>`.
 * For one-shot sources the flow emits one `Success(pages)` then completes; for streaming sources
 * (Prochan, see `BaseMangaRepository.fetchChapterDataF` KDoc) it emits multiple `Success(pages)`
 * each carrying the running cumulative page list. The VM treats every Success the same way —
 * replace `state.pages` with the latest snapshot. This is monotonic (the underlying source only
 * grows the list) so observers downstream see pages append, never disappear; the [currentPageIndex]
 * stays put unless the user pages forward.
 *
 * Page-index clamping on Success: when the page list shrinks (impossible in practice today but
 * possible in principle if a re-fetch returns fewer pages), the reducer clamps `currentPageIndex`
 * to the new last index. The empty case lands `currentPageIndex = 0`, which is also the safe
 * default for the initial fetch.
 *
 * Cancellation: the use case's Flow honours structured concurrency (the `:data` impl rethrows
 * [kotlin.coroutines.cancellation.CancellationException] unchanged from inside `.catch`). When
 * the VM scope cancels (host destroyed) the collector unwinds without leaking the fetch.
 *
 * Concurrent-fetch protection (Phase 7.x.reader.next): once intra-manga Next / Prev navigation
 * lands in this VM, the user can trigger an OnEnter for a NEW chapter while the previous
 * chapter's page-flow is still streaming (for streaming sources like Prochan, the flow never
 * completes until cancellation). Two concurrent collectors would race on `updateState`, with
 * the old chapter's emissions overwriting the new chapter's. So [runFetch] is fire-and-forget:
 * it launches the collector in a tracked [fetchJob] and cancels the prior job before starting
 * a new one. This shifts onEnter from "block until first emission" to "return immediately
 * after kicking off the fetch", which is also why the [onEnter] reducer eagerly sets
 * `isLoading = true` BEFORE launching — the StateFlow reader sees the loading state without
 * having to wait for the collector's first emission.
 *
 * Chapter-list fetch (Phase 7.x.reader.next): on a fresh manga (or when the list is empty),
 * [onEnter] also launches a tracked [chaptersJob] that calls [ListChaptersUseCase] and lifts
 * the result into [ReaderState.chapters]. The two fetches are independent — chapter-list
 * failure is silent (Next / Prev just stay disabled via [ReaderState.canGoNext] /
 * [ReaderState.canGoPrev]); the page-fetch error path is unchanged. Intra-manga Next / Prev
 * preserves the existing list (no refetch) — see [onEnter] KDoc for the `mangaChanged` branch.
 *
 * Session-timer pair (Phase 6.4.x.statistics): [startReadingSession] / [endReadingSession]
 * are routed by the two screen-lifecycle intents [ReaderIntent.OnScreenResumed] /
 * [ReaderIntent.OnScreenPaused]. Both use cases are direct pass-throughs — neither mutates
 * [ReaderState] (the session counter is a write-only sink for the on-disk Statistics totals;
 * the Reader UI has no reason to render the in-flight timer). The legacy reader bracketed
 * the same calls in its `onScreenResume` / `onScreenPause` host hooks; the rework moves the
 * brackets onto the MVI surface so the `:ui` host can stay stateless (DisposableEffect(Unit)
 * dispatches the two intents from `ReaderScreenContent`).
 *
 * Resume-position pair (Phase 7.x.reader.resumeposition): [loadPagePosition] / [savePagePosition]
 * persist the user's last-viewed page index per chapter so the Reader can resume there on
 * re-entry. The load happens in [onEnter] before the page-list fetch runs — `state.currentPageIndex`
 * is seeded to the saved index (or 0 if none) so the subsequent Success branch of [runFetch]
 * clamps to the right page once `pages` arrives. The save happens in [onPageChanged], fire-and-
 * forget on [viewModelScope] (the reducer does not block on the suspending repo call). This is
 * net-new functionality — the legacy reader has a `HistoryItemD.lastReadPage` field but never
 * writes a non-zero value; see [me.manga.kira.domain.repository.ReadProgressRepository]
 * class-level KDoc for the strangler-fig analysis.
 *
 * SRP: orchestrates Reader presentation state for a SINGLE chapter and nothing else. Reading
 * mode, bookmarks, statistics session bracketing, multi-chapter feed are each handled by the
 * VM via thin use-case delegations — no domain logic lives in the VM. Future slices follow
 * the same pattern.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster101.staleKdocSweep.cascade,
 * Task #557, 2026-05-28): the multi-section VM manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-first sibling of the cluster57-100 sweep — closes
 * the wave-7 `:presentation/reader/` batch alongside ReaderEffect.kt
 * plus ReaderIntent.kt plus ReaderState.kt):
 *  (a) "Replaces the minimal page-fetch / page-tracking slice of the
 *  legacy `:shared` `ReaderViewModel` ... The legacy VM owns seven
 *  responsibilities ... The rework VM today owns ONE: orchestrating
 *  the page-fetch result for a single chapter" — STALE-SUPERSEDED.
 *  The Phase 6.4.3-era "owns ONE" claim no longer holds: the LIVE VM
 *  at L121-403 injects NINE collaborators (FetchChapterPagesUseCase,
 *  ObserveReadingModeUseCase, SetReadingModeUseCase, ListChaptersUse-
 *  Case, StartReadingSessionUseCase, EndReadingSessionUseCase, Load-
 *  PagePositionUseCase, SavePagePositionUseCase, PageProgressReposit-
 *  ory) and orchestrates page-fetch PLUS reading-mode observation
 *  PLUS multi-chapter navigation PLUS statistics session bracketing
 *  PLUS resume-position load/save PLUS per-page progress observation.
 *  Each of the cited "seven legacy responsibilities" has either
 *  landed in the rework VM (multi-chapter navigation, reading-mode
 *  persistence, statistics session timer) or remains explicitly
 *  deferred (bookmark observe/toggle Task #217). The "owns ONE"
 *  snapshot is preserved as Phase 6.4.3-era audit trail.
 *  (b) "Constructor dependency rationale ... [FetchChapterPagesUse-
 *  Case] (Phase 6.4.1 `:domain`) is the only collaborator" — STALE-
 *  SUPERSEDED. The L121-133 primary constructor LIVE injects EIGHT
 *  use cases plus one repository; FetchChapterPagesUseCase is the
 *  first collaborator but not the only one. Phase 6.4.3-era single-
 *  collaborator snapshot preserved as audit trail.
 *  (c) "Re-entry idempotence on [ReaderIntent.OnEnter] ... matches
 *  [me.manga.kira.presentation.details.DetailsViewModel]" —
 *  LIVE-NOT-STALE. L207-209 realization: `if (current.manga?.matches(
 *  manga) == true && current.chapter?.url == chapter.url) return`.
 *  The `matches()` helper at L410-411 LIVE on the composite key
 *  (api plus language plus title). Identity comparison strategy
 *  holds.
 *  (d) "Re-entrance guard on [ReaderIntent.OnRetry] ... if a fetch
 *  is already in flight, the intent is dropped" — LIVE-NOT-STALE.
 *  L295 realization: `if (state.value.isLoading) return`.
 *  (e) "Streaming-source page-list semantics ... one-shot sources
 *  emit one Success(pages) then complete; streaming sources (Prochan)
 *  emit multiple Success(pages) each carrying the running cumulative
 *  page list" — LIVE-NOT-STALE. L309-340 runFetch collector replaces
 *  `state.pages` with the latest snapshot on every Success; monotonic-
 *  growth contract per the cited `BaseMangaRepository.fetchChapterDataF`
 *  KDoc cross-ref.
 *  (f) "Page-index clamping on Success" — LIVE-NOT-STALE. L313-317
 *  realization: `val nextIndex = when { pages.isEmpty() -> 0; else
 *  -> prev.currentPageIndex.coerceIn(0, pages.lastIndex) }`.
 *  (g) "Cancellation: the use case's Flow honours structured concur-
 *  rency" — LIVE-NOT-STALE. CancellationException rethrow contract in
 *  `:data` impl verified via earlier cluster sweeps; viewModelScope
 *  unwinds collectors at host destroy.
 *  (h) "Concurrent-fetch protection (Phase 7.x.reader.next) ...
 *  [runFetch] is fire-and-forget: launches the collector in a tracked
 *  [fetchJob] and cancels the prior job before starting a new one" —
 *  LIVE-NOT-STALE. L143 `fetchJob: Job?` field LIVE; L307-308
 *  realization: `fetchJob?.cancel(); fetchJob = viewModelScope.launch
 *  { ... }`. The eagerly-set `isLoading = true` in onEnter (L232)
 *  before launching ensures the StateFlow reader sees loading without
 *  waiting for the collector's first emission, as forecast.
 *  (i) "Chapter-list fetch (Phase 7.x.reader.next) ... [chaptersJob]
 *  that calls [ListChaptersUseCase]" — LIVE-NOT-STALE. L150
 *  `chaptersJob: Job?` field LIVE; L385-402 runListChapters LIVE with
 *  silent-failure semantics as forecast (L394-399 Failure branch
 *  deliberately empty — comment justifies the no-emit posture).
 *  (j) "Session-timer pair (Phase 6.4.x.statistics) ... routed by the
 *  two screen-lifecycle intents [ReaderIntent.OnScreenResumed] plus
 *  [ReaderIntent.OnScreenPaused]" — LIVE-NOT-STALE. L193-194 reducer
 *  branches LIVE: `ReaderIntent.OnScreenResumed -> startReadingSession();
 *  ReaderIntent.OnScreenPaused -> endReadingSession()`. Pure pass-
 *  through, no state mutation, as forecast.
 *  (k) "Resume-position pair (Phase 7.x.reader.resumeposition) ...
 *  [loadPagePosition] / [savePagePosition]" — LIVE-NOT-STALE. L223
 *  load LIVE (`val savedPage = loadPagePosition(chapter.url) ?: 0`);
 *  L286-287 save LIVE on `onPageChanged` (`viewModelScope.launch {
 *  savePagePosition(chapterUrl, clamped) }`).
 *  (l) "SRP: orchestrates Reader presentation state for a SINGLE
 *  chapter and nothing else. Reading mode, bookmarks, statistics
 *  session bracketing, multi-chapter feed are each handled by the
 *  VM via thin use-case delegations" — MIXED-with-inherited-stale-
 *  ness. The "SINGLE chapter ... presentation state" qualifier holds
 *  (the [chapters] field drives navigation triggers, not multi-
 *  chapter page rendering — see ReaderState [chapters] documented
 *  purpose). The embedded delegation list cites "bookmarks ...
 *  handled by the VM via thin use-case delegations" — bookmarks ARE
 *  now delegated; Task #217 HAS LANDED (constructor params
 *  observeChapterBookmark / toggleChapterBookmark, the OnToggleBookmark
 *  reducer branch, and runObserveBookmark drive [ReaderState.is-
 *  Bookmarked]). Reading-mode, statistics, plus single-chapter-list-
 *  fetch multi-chapter delegations ARE LIVE per (a) classification above.
 *  Plus three private-field per-property KDocs at L135-166 (fetchJob,
 *  chaptersJob, progressJob) STAND as LIVE-NOT-STALE on their own
 *  merits per the three Job? field declarations and the cancellation
 *  discipline realized at L227 (`progressJob?.cancel()`) plus L307
 *  (`fetchJob?.cancel()`) plus L389 (`chaptersJob?.cancel()`). The
 *  `startObservingProgress` KDoc at L343-368 (Phase 7.x.reader.
 *  modelayout.pageprogress) plus the bottom `matches()` helper KDoc
 *  at L405-409 also STAND as LIVE-NOT-STALE per recursive symbol
 *  verification.
 *  Two STALE-SUPERSEDED classifications plus nine LIVE-NOT-STALE
 *  classifications plus one MIXED-with-inherited-staleness classific-
 *  ation STAND on their own merits as a faithful Reader-ViewModel
 *  manifest. Original Phase 6.4.3-era prose preserved verbatim per
 *  the audit-trail-preservation convention.
 */
class ReaderViewModel(
    private val fetchPages: FetchChapterPagesUseCase,
    private val observeReadingMode: ObserveReadingModeUseCase,
    private val setReadingMode: SetReadingModeUseCase,
    private val listChapters: ListChaptersUseCase,
    private val startReadingSession: StartReadingSessionUseCase,
    private val endReadingSession: EndReadingSessionUseCase,
    private val loadPagePosition: LoadPagePositionUseCase,
    private val savePagePosition: SavePagePositionUseCase,
    private val observePageProgress: ObservePageProgressUseCase,
    private val observeChapterBookmark: ObserveChapterBookmarkUseCase,
    private val toggleChapterBookmark: ToggleChapterBookmarkUseCase,
    private val recordHistory: RecordHistoryUseCase,
    private val markChapterRead: MarkChapterReadUseCase,
    // Best-effort cleanup of a downloaded chapter's extracted-CBZ temp dir (fire-and-forget).
    private val clearExtractedPages: ClearExtractedPagesUseCase,
    // Prunes the per-page progress entries from the process-singleton map on teardown so it does
    // not grow without bound across long sessions.
    private val clearPageProgress: ClearPageProgressUseCase,
) : MviViewModel<ReaderState, ReaderIntent, ReaderEffect>(
    initialState = ReaderState(),
) {

    /**
     * Tracked page-fetch coroutine. Cancelled at the start of every new [runFetch] so a prior
     * streaming fetch (Prochan) cannot land emissions on top of a fresh chapter's state.
     * `null` before the first fetch and after the VM's scope cancels. Mutation is single-threaded
     * via [MviViewModel.submit]'s per-intent `viewModelScope.launch` (each intent runs in its
     * own coroutine, but state transitions go through the same VM-owned reducer thread because
     * `updateState` is a CAS loop — `fetchJob` is touched only from the reducer's perspective).
     */
    private var fetchJob: Job? = null

    /**
     * Tracked APPEND coroutine (#5 continuous reader). Distinct from [fetchJob] so appending the next
     * chapter's pages does NOT cancel the current chapter's fetch (and vice-versa). Cancelled on an
     * explicit chapter jump / fresh OnEnter. A single append runs at a time (guarded in
     * [onAppendNextChapter]).
     */
    private var appendJob: Job? = null

    /**
     * Tracked chapter-list-fetch coroutine. Cancelled at the start of every new [runListChapters]
     * so a stale fetch for the previous manga cannot land emissions on top of the new manga's
     * state. Same mutation discipline as [fetchJob].
     */
    private var chaptersJob: Job? = null

    /**
     * Tracked per-page progress-observer coroutine (Phase 7.x.reader.modelayout.pageprogress).
     * Parent job that supervises N per-URL child collectors (one per page in the active chapter,
     * launched by [startObservingProgress]). Cancelled in [onEnter] before the new chapter's
     * state lands and re-launched in [runFetch]'s Success branch once the new page list arrives.
     *
     * Why cancel in onEnter rather than relying on [startObservingProgress]'s own cancel: between
     * onEnter's state reset and the new fetch's first Success, the prior chapter's collectors are
     * still observing the repository. If a neighbor URL (page from the prior chapter) emitted a
     * late tick, those collectors would re-add entries to the freshly-cleared `pageProgress` map.
     * Cancelling early closes that race window.
     *
     * Same mutation discipline as [fetchJob] / [chaptersJob].
     */
    private var progressJob: Job? = null

    /** Consecutive Cloudflare-solve round-trips; bounded by [MAX_CLOUDFLARE_ATTEMPTS], reset on a
     *  successful page fetch. */
    private var cloudflareAttempts = 0

    /**
     * Tracked chapter-bookmark-observer coroutine (Phase 6.4.x.bookmark, Reader-convergence R2).
     * Cancelled and re-launched in [onEnter] on every chapter establish/change so the collector
     * always observes the CURRENT chapter's bookmark state — a stale collector for the prior
     * chapter's URL would otherwise keep writing into `state.isBookmarked` after a chapter swap.
     * Same mutation discipline as [fetchJob] / [chaptersJob] / [progressJob].
     */
    private var bookmarkJob: Job? = null

    init {
        // Reading-mode preference is hot for the VM's lifetime — every emission lifts the new
        // value into state. Launched in `init` (not in `onEnter`) because the preference is
        // independent of which chapter is active, and we want the first emission to land as
        // soon as possible so the first frame of the reader picks up the right mode. The
        // collector cancels with `viewModelScope` when the host is destroyed.
        launchSafely {
            observeReadingMode().collect { mode ->
                updateState { it.copy(readingMode = mode) }
            }
        }
    }

    override suspend fun handle(intent: ReaderIntent) {
        when (intent) {
            // Initialize-once per VM instance: a fresh entry-scoped VM has `chapter == null` and
            // runs the full fetch; once a chapter is established, a second intent-level OnEnter can
            // only be a stale re-composition replaying the ANCHOR nav-args (rotation, pop-back from
            // the WebView / Cloudflare solver). Honouring it would reset the reader to the chapter
            // originally opened, discarding any Next/Prev navigation. The internal onEnter() calls
            // from onNextChapter/onPrevChapter bypass this guard (they don't go through the intent
            // path), so chapter navigation is unaffected; process-death restore also works (the
            // restored VM has chapter == null and proceeds). OnRetry forces an explicit re-fetch.
            is ReaderIntent.OnEnter ->
                if (state.value.chapter == null) onEnter(intent.manga, intent.chapter)
            is ReaderIntent.OnPageChanged -> onPageChanged(intent.pageIndex)
            ReaderIntent.OnRetry -> onRetry()
            ReaderIntent.OnBackClick -> emit(ReaderEffect.NavigateBack)
            ReaderIntent.OnUiToggle -> updateState { it.copy(isUiVisible = !it.isUiVisible) }
            is ReaderIntent.OnReadingModeChanged -> onReadingModeChanged(intent.mode)
            is ReaderIntent.OnOpenInWebView ->
                emit(ReaderEffect.OpenChapterInWebView(intent.url, intent.api))
            ReaderIntent.OnNextChapter -> onNextChapter()
            ReaderIntent.OnPrevChapter -> onPrevChapter()
            ReaderIntent.OnAppendNextChapter -> onAppendNextChapter()
            ReaderIntent.OnScreenResumed -> startReadingSession()
            ReaderIntent.OnScreenPaused -> endReadingSession()
            ReaderIntent.OnToggleBookmark -> onToggleBookmark()
            ReaderIntent.OnShareCurrentPage -> onShareCurrentPage()
        }
    }

    private suspend fun onShareCurrentPage() {
        // Reader parity item #5: capture + share the current page. The VM owns no capture/encode
        // machinery — it just emits the trigger; the `:ui` screen records the page into a
        // GraphicsLayer and the route adapter encodes + hands the bytes to the `:platform`
        // ScreenshotProvider SPI. Dropped when no pages are loaded yet (nothing to capture);
        // mirrors the legacy reader, whose Share button was only reachable once a page was on
        // screen.
        if (!state.value.hasPages) return
        emit(ReaderEffect.ShareCurrentPage)
    }

    private fun onToggleBookmark() {
        // Fire-and-forget toggle — the per-chapter observe collector launched in [onEnter]
        // re-emits with the new value, which updates `state.isBookmarked`. We deliberately do
        // not flip state inline so the on-disk bookmark column is the single source of truth (a
        // write rejected by the store stays consistent with what the UI shows). Ignored when no
        // chapter is active (impossible after onEnter, defensive). The use case degrades safely:
        // toggling a not-in-library chapter no-ops, so the always-rendered button stays inert.
        //
        // #5 continuous reader: bookmark the chapter the user is CURRENTLY VIEWING ([activeChapterUrl],
        // the appended segment in view), not the anchor [chapter] — so in a multi-chapter feed the star
        // toggles the chapter on screen. Falls back to the anchor before any pages tag the feed.
        val chapterUrl = state.value.activeChapterUrl ?: state.value.chapter?.url ?: return
        launchSafely {
            // #15 — the toggle no-ops when the manga isn't in the library (no saved_chapters row).
            // Surface that as the native "add to Library first" hint instead of silently doing
            // nothing; an in-library toggle returns true and the observe collector updates the star.
            val toggled = toggleChapterBookmark(chapterUrl)
            if (!toggled) emit(ReaderEffect.ShowNotInLibrary)
        }
    }

    private suspend fun onReadingModeChanged(mode: ReadingMode) {
        // Fire-and-write — the observer launched in init re-emits with the new value, which
        // updates `state.readingMode`. We deliberately do not `updateState` inline here so
        // the on-disk write is the single source of truth (if the store ever rejected a
        // write, the UI would not show a stale optimistic value).
        if (state.value.readingMode == mode) return
        setReadingMode(mode)
    }

    private suspend fun onEnter(manga: Manga, chapter: Chapter) {
        val current = state.value
        if (current.manga?.matches(manga) == true && current.chapter?.url == chapter.url) return
        FlowLog.log("Reader", "enter", "chapter=${chapter.url} num=${chapter.number} api=${manga.api}")
        // Leaving a chapter (incl. intra-manga Next/Prev on this reused VM): drop the temp images
        // extracted from the previous downloaded chapter's CBZ so they don't accumulate. In a
        // continuous feed several chapters may have been appended, so clear EVERY loaded chapter
        // (excluding the one being entered, whose pages are about to load) rather than only the
        // anchor. Their page file:// paths are no longer referenced once state.pages is replaced below.
        clearLoadedExtractedPages(current, exclude = chapter.url)
        // Fresh chapter (incl. intra-manga Next/Prev, which reuse this single VM instance) gets its
        // own Cloudflare-solve budget — otherwise a chapter that exhausted the budget would starve
        // the auto-recovery for the next, genuinely-solvable chapter. Mirrors native's per-route
        // `remember` scoping of the dismissal count.
        cloudflareAttempts = 0
        // Whether the user is moving to a different manga vs. navigating between chapters of
        // the same manga (intra-manga Next / Prev). On manga change we clear `chapters` and
        // refetch; on intra-manga nav we preserve the existing list (same source = same list,
        // no reason to refetch). Phase 7.x.reader.next.
        val mangaChanged = current.manga?.matches(manga) != true
        // Resume-position seed (Phase 7.x.reader.resumeposition): load the persisted page index
        // BEFORE the state update so the new chapter's state lands with the right seed in one
        // atomic transition. Null means no saved position — start at page 0. The load is suspend
        // but cheap (a single ObservableSettings read); we accept the brief block here rather
        // than fanning out to a separate coroutine because the in-flight value is needed
        // synchronously to populate the state. The subsequent `runFetch` Success branch clamps
        // the seed to `pages.lastIndex` once the page list arrives — handles the "chapter
        // shrank since last open" edge case without an extra check here.
        val savedPage = loadPagePosition(chapter.url) ?: 0
        FlowLog.log("Reader", "resume", "chapter=${chapter.url} savedPage=$savedPage")
        // Cancel the prior chapter's progress observers before resetting state. Closes the race
        // window where a late emission from the OLD chapter's URLs would write into the NEW
        // chapter's freshly-cleared `pageProgress` map — see [progressJob] KDoc.
        progressJob?.cancel()
        // #5: an explicit jump / fresh entry CLEARS the continuous feed — cancel any in-flight append
        // and reset the page→chapter tags + loaded-chapter list (the replace fetch re-seeds them).
        appendJob?.cancel()
        updateState {
            it.copy(
                manga = manga,
                chapter = chapter,
                isLoading = true,
                pages = emptyList(),
                pageChapters = emptyList(),
                loadedChapterUrls = emptyList(),
                currentPageIndex = savedPage,
                error = null,
                // Reset UI chrome to visible on a fresh chapter — the user's previous
                // chapter's hide/show choice doesn't carry across, matches legacy posture.
                isUiVisible = true,
                chapters = if (mangaChanged) emptyList() else it.chapters,
                // Reset per-page progress to a blank slate. The new chapter's collectors start in
                // [runFetch]'s Success branch and populate this map as ticks arrive. Stale entries
                // for prior-chapter URLs would be harmless to the UI (it only reads URLs from the
                // current pages list) but would clutter memory across long sessions.
                pageProgress = emptyMap(),
            )
        }
        if (mangaChanged || state.value.chapters.isEmpty()) {
            runListChapters(manga)
        }
        runObserveBookmark(chapter.url)
        // Reading-history record (Reader-convergence R3a). Fire on every chapter establish/change
        // (open + Next/Prev) so the History screen reflects the user's progress — matching the
        // legacy reader, which recorded history on chapter open. Fire-and-forget on viewModelScope:
        // the reducer must not block on the suspending settings-read + DB upsert, and there is no
        // error path the UI could act on. The incognito gate lives inside [RecordHistoryUseCase]
        // (no-op when incognito is ON), so the call is unconditional here.
        launchSafely { recordHistory(manga, chapter) }
        runFetch(manga, chapter)
    }

    private fun runObserveBookmark(chapterUrl: String) {
        // Cancel the prior chapter's bookmark observer before starting a new one. Without this an
        // intra-manga Next / Prev would leave the previous chapter's collector observing and
        // writing into `state.isBookmarked` — same cancel-previous-on-change discipline as
        // [runFetch] / [runListChapters]. Every emission lifts the latest bookmark state into
        // [ReaderState.isBookmarked] (no `filter` / `distinctUntilChanged` here — the use case's
        // backing flow already projects a `distinctUntilChanged` Boolean per chapter).
        bookmarkJob?.cancel()
        bookmarkJob = launchSafely {
            observeChapterBookmark(chapterUrl).collect { bookmarked ->
                updateState { it.copy(isBookmarked = bookmarked) }
            }
        }
    }

    private suspend fun onNextChapter() {
        val current = state.value
        // Re-entrance guard (matches [ReaderIntent.OnNextChapter] KDoc): drop while the initial
        // fetch is in flight so a rapid double-dispatch can't advance twice and skip a chapter.
        if (current.isLoading) return
        if (!current.canGoNext) return
        val manga = current.manga ?: return
        val nextIdx = current.currentChapterIndex + 1
        // Mark-read trigger (b) (Reader-convergence R3b): advancing to the next chapter means the
        // user is done with the CURRENT one, so mark the chapter being LEFT as read — matching the
        // legacy reader, which marked-read on next-chapter advance. Fire-and-forget on
        // viewModelScope BEFORE onEnter swaps `state.chapter` to the next chapter. In-library-only
        // + idempotent (UPDATE isRead=1) + not incognito-gated; the use case no-ops when the
        // chapter has no saved row. See [MarkChapterReadUseCase]. Uses the ACTIVE (currently-viewed)
        // chapter so a continuous-feed advance marks the appended segment in view, not the anchor.
        val leavingUrl = current.activeChapterUrl ?: current.chapter?.url
        if (leavingUrl != null) {
            launchSafely { markChapterRead(leavingUrl) }
        }
        onEnter(manga, current.chapters[nextIdx])
    }

    private suspend fun onPrevChapter() {
        val current = state.value
        // Re-entrance guard (matches [ReaderIntent.OnPrevChapter] KDoc): drop while the initial
        // fetch is in flight — same posture as [onNextChapter].
        if (current.isLoading) return
        if (!current.canGoPrev) return
        val manga = current.manga ?: return
        val prevIdx = current.currentChapterIndex - 1
        // Native parity (ReaderScreen onPrevious marks the current chapter read): a chapter advance —
        // next OR previous — marks the chapter being LEFT as read. Same fire-and-forget,
        // in-library-only, idempotent posture as [onNextChapter]. See [MarkChapterReadUseCase].
        val leavingUrl = current.activeChapterUrl ?: current.chapter?.url
        if (leavingUrl != null) {
            launchSafely { markChapterRead(leavingUrl) }
        }
        onEnter(manga, current.chapters[prevIdx])
    }

    /**
     * #5 continuous reader: append the next chapter's pages BELOW the current feed (do NOT clear /
     * replace), so the user can scroll freely between the chapter they just finished and the next.
     * Triggered by a scroll-to-end in non-paged modes (the `:ui` routes paged modes to [onNextChapter]
     * instead). Idempotent: dropped while an append is in flight, before any pages load, when the tail
     * chapter is the last one, or when the next chapter is already loaded.
     */
    private fun onAppendNextChapter() {
        val current = state.value
        val manga = current.manga ?: return
        if (current.pages.isEmpty()) return // initial fetch not done yet — nothing to append onto
        if (appendJob?.isActive == true) {
            FlowLog.log("Reader", "appendNext", "skipped=append-in-flight")
            return // one append at a time
        }
        // The chapter whose end we reached is the tail of the loaded feed.
        val tailUrl = current.loadedChapterUrls.lastOrNull() ?: current.chapter?.url ?: return
        val tailIdx = current.chapters.indexOfFirst { it.url == tailUrl }
        if (tailIdx !in 0..<current.chapters.lastIndex) {
            FlowLog.log("Reader", "appendNext", "skipped=no-next-chapter tail=$tailUrl tailIdx=$tailIdx total=${current.chapters.size}")
            return // no next chapter
        }
        val next = current.chapters[tailIdx + 1]
        if (next.url in current.loadedChapterUrls) {
            FlowLog.log("Reader", "appendNext", "skipped=already-loaded next=${next.url}")
            return // already appended
        }
        FlowLog.log("Reader", "appendNext", "tail=$tailUrl next=${next.url} loaded=${current.loadedChapterUrls.size}")
        // Finishing the tail chapter marks it read (native marks-read on advance). Fire-and-forget,
        // in-library-only + idempotent — see [MarkChapterReadUseCase].
        launchSafely { markChapterRead(tailUrl) }
        appendChapterPages(manga, next)
    }

    /**
     * Fetch [chapter]'s pages and APPEND them to the current feed (no clear, no `fetchJob` cancel).
     * Handles streaming sources by replacing this chapter's prior (cumulative) snapshot rather than
     * re-appending. An append failure surfaces a non-blocking [ReaderEffect.ShowError] and leaves the
     * visible current chapter intact (never sets `state.error`, which would hide the page area).
     */
    private fun appendChapterPages(manga: Manga, chapter: Chapter) {
        appendJob?.cancel()
        appendJob = launchSafely {
            fetchPages(manga, chapter).collect { result ->
                when (result) {
                    is AppResult.Success -> {
                        val newPages = result.value
                        if (newPages.isEmpty()) {
                            // #4 (append path): an appended chapter that RESOLVED to zero pages must
                            // not silently dead-end the feed. Record it as loaded (with no page tags)
                            // so the NEXT append targets the chapter after it (tailIdx+2) instead of
                            // re-attempting this one forever and re-marking it read on every retrigger,
                            // and surface a non-blocking error so the user knows the chapter was empty.
                            FlowLog.log("Reader", "appendNext", "chapter=${chapter.url} skipped=empty-next-chapter")
                            updateState { prev ->
                                if (chapter.url in prev.loadedChapterUrls) prev
                                else prev.copy(loadedChapterUrls = prev.loadedChapterUrls + chapter.url)
                            }
                            emit(ReaderEffect.ShowError(AppError.Unexpected("This chapter returned no pages.")))
                            return@collect
                        }
                        updateState { prev ->
                            // Drop any prior (streaming) snapshot of THIS chapter, then append the
                            // latest full snapshot — so cumulative emissions don't duplicate pages.
                            val segStart = prev.pageChapters.indexOfFirst { it == chapter.url }
                            val keep = if (segStart < 0) prev.pages.size else segStart
                            prev.copy(
                                pages = prev.pages.take(keep) + newPages,
                                pageChapters = prev.pageChapters.take(keep) + List(newPages.size) { chapter.url },
                                loadedChapterUrls =
                                    if (chapter.url in prev.loadedChapterUrls) prev.loadedChapterUrls
                                    else prev.loadedChapterUrls + chapter.url,
                            )
                        }
                        // Re-observe progress across the full (current + appended) page set.
                        startObservingProgress(state.value.pages.map { it.url })
                        FlowLog.log(
                            "Reader",
                            "appended",
                            "chapter=${chapter.url} newPages=${newPages.size} feedTotal=${state.value.pages.size} chapters=${state.value.loadedChapterUrls.size}",
                        )
                    }
                    is AppResult.Failure -> {
                        FlowLog.log("Reader", "appendError", "chapter=${chapter.url} error=${result.error::class.simpleName}")
                        // #6 (append path): a Cloudflare / anti-bot interstitial on a continuous-mode
                        // chapter advance must get the same AUTO 403→WebView recovery as the replace
                        // path (runFetch) — otherwise advancing past a CF-walled next chapter dead-ends
                        // with a generic snackbar. Same CHALLENGE_STATUSES + bounded cloudflareAttempts
                        // gate; on return the route adapter re-dispatches OnRetry (re-fetches the anchor
                        // with the freshly-minted cookies, so a subsequent scroll re-appends). Any other
                        // error keeps the existing non-blocking ShowError.
                        val error = result.error
                        if (error is AppError.Network.Http && error.statusCode in CHALLENGE_STATUSES &&
                            cloudflareAttempts < MAX_CLOUDFLARE_ATTEMPTS
                        ) {
                            cloudflareAttempts++
                            emit(
                                ReaderEffect.SolveCloudflareChallenge(
                                    url = chapter.url,
                                    api = manga.api,
                                ),
                            )
                        } else {
                            emit(ReaderEffect.ShowError(error))
                        }
                    }
                }
            }
        }
    }

    private fun onPageChanged(pageIndex: Int) {
        val current = state.value
        // Drop out-of-range or unchanged indices — protects state churn while pages are still
        // streaming in (a UI Pager that snaps to an index past the current list length should
        // settle once the list catches up, not flicker).
        if (current.pages.isEmpty()) return
        val clamped = pageIndex.coerceIn(0, current.pages.lastIndex)
        if (clamped == current.currentPageIndex) return
        updateState { it.copy(currentPageIndex = clamped) }
        // Resume-position write (Phase 7.x.reader.resumeposition): persist the new page as the
        // chapter's last-viewed position. Fire-and-forget on viewModelScope — the reducer must
        // not block on the suspending settings write, and there's no error path the UI could
        // act on if the write failed (see ReadProgressRepository class-level KDoc for the
        // no-AppResult rationale). The chapter URL is the persistence key; if it's null
        // (impossible after onEnter ran, since the chapter was just set) we silently drop the
        // save. Repeated saves for the same `(chapterUrl, pageIndex)` are no-ops at the
        // ObservableSettings layer.
        // #5: save the resume position against the chapter ACTUALLY in view (the appended segment, if
        // the user scrolled past a boundary) using the WITHIN-chapter page index, not the flat feed
        // index — so resume lands on the right page of the right chapter. For a single (non-appended)
        // chapter this reduces to (anchor url, clamped) exactly as before.
        val activeUrl = current.pageChapters.getOrNull(clamped) ?: current.chapter?.url ?: return
        val withinIdx = if (current.pageChapters.isEmpty()) clamped
        else current.pageChapters.subList(0, clamped + 1).count { it == activeUrl } - 1
        FlowLog.log(
            "Reader",
            "page",
            "flatIndex=$clamped/${current.pages.lastIndex} activeChapter=$activeUrl chapterPage=${withinIdx + 1} chapterIdx=${current.currentChapterIndex}",
        )
        launchSafely { savePagePosition(activeUrl, withinIdx) }
        // #5 continuous reader: when the ACTIVE (viewed) chapter actually changes — the user scrolled
        // across a boundary into an appended segment — follow it so the rest of the reader tracks the
        // chapter on screen, not the anchor:
        //   - re-point the bookmark observer at the now-visible chapter (so the star reflects it), and
        //   - record reading history for it (so History reflects what's actually being read).
        // GUARDED on a real URL change (`activeUrl != current.activeChapterUrl`, the pre-update active
        // chapter) so an ordinary page scroll WITHIN the same chapter does NOT re-subscribe the
        // observer or re-record history on every page — only the once-per-chapter crossing does.
        // Resume above already keys off the active chapter; this brings bookmark + history in line.
        if (activeUrl != current.activeChapterUrl) {
            runObserveBookmark(activeUrl)
            val manga = current.manga
            val activeChapter = current.chapters.firstOrNull { it.url == activeUrl }
            if (manga != null && activeChapter != null) {
                launchSafely { recordHistory(manga, activeChapter) }
            }
            FlowLog.log("Reader", "activeChapterChange", "active=$activeUrl idx=${state.value.currentChapterIndex}")
        }
        // Native parity (mark-read): native marks a chapter read ONLY on an explicit chapter advance
        // (next/prev button, or a swipe/scroll PAST the last page → onReachedEnd → OnNextChapter),
        // never on merely landing on the last image page in paged modes. So the chapter being left is
        // marked read in [onNextChapter] (and the previous-chapter path), not here. Marking eagerly on
        // last-page dwell here also compounded the resume-at-last-page case, so it's intentionally
        // dropped — reaching the end no longer marks read; advancing past it does.
    }

    /**
     * Audit P1 follow-through: an absorbed throw (via [launchSafely] / the `submit` net) skips the
     * fetch's Success/Failure branches — the ONLY places that reset `isLoading`. Left alone, the
     * spinner sticks forever and [onRetry]'s re-entrance guard dead-locks the screen (its
     * `isLoading` check would drop every retry). Restore UI consistency by routing recovery
     * through the exact error+retry path a typed Failure uses: clear the spinner always, and show
     * the error pane only when there are no visible pages (append-path throws keep the readable
     * chapter on screen, matching [appendChapterPages]' non-blocking failure posture).
     */
    override fun onUnhandledError(
        throwable: Throwable,
        intent: ReaderIntent?,
    ) {
        super.onUnhandledError(throwable, intent)
        val error = AppError.Unexpected(message = throwable.message ?: "reader fetch failed", cause = throwable)
        updateState {
            it.copy(
                isLoading = false,
                error = if (it.pages.isEmpty()) error else it.error,
            )
        }
    }

    private fun onRetry() {
        // Re-entrance guard: drop if a fetch is already in flight. Two concurrent fetches for
        // the same chapter would race on `updateState`, and the second one to land would
        // overwrite the first's payload — flicker risk for the user, no benefit. Mirrors
        // `DetailsViewModel.onRetry` (§6.3.5).
        if (state.value.isLoading) return
        val manga = state.value.manga ?: return
        val chapter = state.value.chapter ?: return
        updateState { it.copy(isLoading = true, error = null) }
        runFetch(manga, chapter)
    }

    private fun runFetch(manga: Manga, chapter: Chapter) {
        // Cancel the previous fetch before starting a new one. Critical for streaming sources
        // (Prochan): without this, an intra-manga Next / Prev OnEnter would leave the prior
        // chapter's flow still streaming pages and overwriting the new chapter's state. See
        // class-level "Concurrent-fetch protection" KDoc.
        fetchJob?.cancel()
        fetchJob = launchSafely {
            fetchPages(manga, chapter).collect { result ->
                when (result) {
                    is AppResult.Success -> {
                        val pages = result.value
                        if (pages.isEmpty()) {
                            // #4: a chapter that RESOLVED to zero pages is a failure, not a silent
                            // blank screen. Every source path delivers an empty list only as a
                            // TERMINAL Success (piloted single-emit; legacy single-terminal; streaming
                            // accumulate-after-add, whose first Success is already non-empty for a
                            // non-empty chapter) — so classifying empty-as-error at this single point
                            // where both engines converge is safe and needs no streaming gate. Routes
                            // through the exact error+retry path a Failure uses, so the reader can
                            // never render nothing.
                            FlowLog.log("Reader", "emptyPages", "chapter=${chapter.url} -> error (no pages)")
                            val error = AppError.Unexpected("This chapter returned no pages.")
                            updateState { it.copy(isLoading = false, error = error) }
                            emit(ReaderEffect.ShowError(error))
                        } else {
                            FlowLog.log("Reader", "pages", "chapter=${chapter.url} count=${pages.size}")
                            updateState { prev ->
                                val nextIndex = prev.currentPageIndex.coerceIn(0, pages.lastIndex)
                                prev.copy(
                                    isLoading = false,
                                    pages = pages,
                                    // #5: this is the (replace) initial/explicit-jump load, so the
                                    // page→chapter tags and the loaded-chapter list reset to this one
                                    // chapter. Appends (onAppendNextChapter) extend them instead.
                                    pageChapters = List(pages.size) { chapter.url },
                                    loadedChapterUrls = listOf(chapter.url),
                                    currentPageIndex = nextIndex,
                                    error = null,
                                )
                            }
                            // Start per-page progress observers for this chapter's URLs. For one-shot
                            // sources this fires once and the collectors run for the chapter's lifetime;
                            // for streaming sources (Prochan) this fires on every cumulative Success,
                            // restarting collectors with the growing URL set — acceptable churn given
                            // Prochan chapters are ≤5 pages typically. Restart vs incremental-add: keep
                            // the contract simple (current URL set = current observers) and accept the
                            // wasteful re-subscription.
                            startObservingProgress(pages.map { it.url })
                            // Successful fetch clears the Cloudflare-solve budget for this reader.
                            cloudflareAttempts = 0
                        }
                    }
                    is AppResult.Failure -> {
                        FlowLog.log("Reader", "error", "chapter=${chapter.url} error=${result.error::class.simpleName}")
                        updateState { it.copy(isLoading = false, error = result.error) }
                        // Reader parity item #6 (legacy auto-403→WebView recovery): a Cloudflare /
                        // anti-bot interstitial is not a hard failure. AUTO-route the user to the
                        // WebView to solve the challenge (which primes the per-source cookie/header
                        // store) instead of leaving a dead-end "failed to load" pane. The
                        // `:composeApp` route adapter auto-re-dispatches OnRetry when it returns
                        // from the WebView, mirroring the legacy auto-reload-on-return and the
                        // proven Details `SolveCloudflareChallenge` pattern. Any other error keeps
                        // the existing generic ShowError snackbar behaviour.
                        //
                        // GAP-RDR-01: the detected status set now matches Details'
                        // [CHALLENGE_STATUSES] ({403, 429, 503, 520-524}) rather than 403-only, so a
                        // Cloudflare 503 ("checking your browser") / 429 (rate-limit interstitial)
                        // on a reader page auto-routes to the WebView the same way Details does —
                        // closing the recovery asymmetry between the two surfaces.
                        val error = result.error
                        if (error is AppError.Network.Http && error.statusCode in CHALLENGE_STATUSES &&
                            cloudflareAttempts < MAX_CLOUDFLARE_ATTEMPTS
                        ) {
                            // Bounded auto-recovery — cap consecutive solver round-trips so an
                            // unsolvable challenge can't loop the user back into the WebView forever.
                            cloudflareAttempts++
                            emit(
                                ReaderEffect.SolveCloudflareChallenge(
                                    url = chapter.url,
                                    api = manga.api,
                                ),
                            )
                        } else {
                            // Not a challenge, or solve budget exhausted — surface the error instead
                            // of re-entering the WebView loop (the user can still retry manually).
                            emit(ReaderEffect.ShowError(error))
                        }
                    }
                }
            }
        }
    }

    /**
     * Start (or restart) per-page progress observers for the active chapter's [urls]
     * (Phase 7.x.reader.modelayout.pageprogress).
     *
     * Cancels any prior [progressJob] then launches a supervisor parent coroutine that hosts N
     * child collectors (one per URL). Each collector observes
     * [ObservePageProgressUseCase] for its URL and lifts non-Idle emissions into
     * `state.pageProgress`. The [filter] drop on [PageDownloadProgress.Idle] is critical — the
     * repository's `observe()` projection emits [PageDownloadProgress.Idle] for never-reported
     * URLs on subscription, so without the filter chapter entry would trigger N spurious
     * `updateState` calls before any real platform tick lands.
     *
     * Why N per-URL collectors rather than one whole-map collector:
     *  - The repository's per-URL projection includes `distinctUntilChanged`, so a collector for
     *    URL X only re-emits when X's state actually changes. A whole-map collector would re-fire
     *    on every neighbor URL's tick (the underlying `MutableStateFlow<Map>` emits the whole map
     *    on any entry change), forcing the VM to diff against the prior map to know which URL
     *    actually changed. The per-URL fan-out moves that distinct-checking into the repository
     *    where it belongs.
     *  - Memory cost is bounded by chapter page count (≤200 typical). Each child coroutine has
     *    O(KB) overhead; 200 × few KB = sub-MB. Negligible.
     *
     * Cancellation: cancelling [progressJob] cancels all N children (structured concurrency
     * propagates through the parent). Called by [onEnter] before a chapter swap and by this
     * method itself before restarting.
     */
    private fun startObservingProgress(urls: List<String>) {
        progressJob?.cancel()
        if (urls.isEmpty()) return
        progressJob = launchSafely {
            urls.forEach { url ->
                // A child coroutine's failure bypasses launchSafely's try/catch (it propagates
                // through the Job tree, not the parent's body), so each per-URL collector carries
                // its own absorption — onEach BEFORE catch, so a state-update throw is also routed
                // to onUnhandledError instead of killing the process. launchIn(this) keeps the
                // one-child-per-URL structure (all cancelled with [progressJob]).
                observePageProgress(url)
                    .filter { it !is PageDownloadProgress.Idle }
                    .onEach { status ->
                        updateState { it.copy(pageProgress = it.pageProgress + (url to status)) }
                    }.catch { t -> onUnhandledError(t) }
                    .launchIn(this)
            }
        }
    }

    private fun runListChapters(manga: Manga) {
        // Cancel the previous chapter-list fetch before starting a new one. Protects against
        // a stale fetch (e.g. previous manga's slow source) landing emissions on top of a new
        // manga's state. Symmetric with [runFetch].
        chaptersJob?.cancel()
        chaptersJob = launchSafely {
            when (val result = listChapters(manga)) {
                is AppResult.Success -> {
                    updateState { it.copy(chapters = result.value) }
                    val s = state.value
                    FlowLog.log(
                        "Reader",
                        "chapterNav",
                        "count=${s.chapters.size} activeIdx=${s.currentChapterIndex} " +
                            "canNext=${s.canGoNext} canPrev=${s.canGoPrev} active=${s.activeChapterUrl}",
                    )
                }
                is AppResult.Failure -> {
                    // Silent failure — chapter-list fetch failure must NOT mask the page-fetch
                    // happy path. The user can still read the chapter they opened; Next / Prev
                    // just stay disabled via [ReaderState.canGoNext] / [ReaderState.canGoPrev].
                    // Emitting [ReaderEffect.ShowError] here would surface a snackbar over a
                    // successfully-rendered page, which is worse UX than silent disablement.
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Reader closing: drop the extracted-CBZ temp dirs of every chapter still loaded into the
        // feed (the anchor plus any chapters appended in continuous mode). clearExtractedPages is
        // fire-and-forget on the repository's app-lifetime scope, so it completes even though
        // viewModelScope is now cancelled.
        clearLoadedExtractedPages(state.value, exclude = null)
        // Prune this session's per-page progress entries from the process-singleton map so it does
        // not accumulate across reader sessions. clear() is a synchronous map update, safe to call
        // even though viewModelScope is now cancelled.
        state.value.pageProgress.keys.forEach { clearPageProgress(it) }
    }

    /**
     * Clear the extracted-CBZ temp dir for every chapter currently loaded into the feed
     * ([ReaderState.loadedChapterUrls], resolved against [ReaderState.chapters]), optionally
     * skipping [exclude] (the chapter being entered, whose pages are about to load). Falls back to
     * the anchor [ReaderState.chapter] when no chapters are loaded / resolvable yet. No-op for
     * chapters that were not downloaded as a CBZ.
     */
    private fun clearLoadedExtractedPages(s: ReaderState, exclude: String?) {
        val loaded = s.loadedChapterUrls
            .filter { it != exclude }
            .mapNotNull { url -> s.chapters.firstOrNull { it.url == url } }
        if (loaded.isEmpty()) {
            s.chapter?.takeIf { it.url != exclude }?.let { clearExtractedPages(it) }
        } else {
            loaded.forEach { clearExtractedPages(it) }
        }
    }

    private companion object {
        /**
         * HTTP statuses that a Cloudflare / anti-bot interstitial uses and that the user can clear
         * in a WebView. Mirrors
         * [me.manga.kira.presentation.details.DetailsViewModel]'s `CHALLENGE_STATUSES`
         * verbatim (GAP-RDR-01): 403 is the classic Cloudflare challenge; 503 ("checking your
         * browser"), 429 (rate-limit interstitial), and 520-524 (CF origin/edge errors) are also
         * routinely transient WebView-solvable states. The `:data` layer additionally re-surfaces
         * code-0 challenge-bodied throws as 403, so genuine 404/500 app errors keep falling to the
         * generic ShowError snackbar. Keeping the two surfaces' status sets identical closes the
         * recovery asymmetry the audit flagged (Reader was previously 403-only).
         */
        val CHALLENGE_STATUSES = setOf(403, 429, 503, 520, 521, 522, 523, 524)

        /**
         * Max consecutive Cloudflare-solve round-trips before the reader stops auto-routing to the
         * WebView and surfaces the error. Inspired by native `Handle403Error`'s `maxDismissals`
         * (native defaulted to 1 re-show); we allow 2 — an initial solve plus one retry. Prevents
         * an unsolvable challenge from trapping the user in an infinite WebView re-route loop.
         * Reset to 0 on any successful page fetch AND on each chapter change (see [onEnter]).
         */
        const val MAX_CLOUDFLARE_ATTEMPTS = 2
    }
}

/**
 * Identity comparison on the rework's composite key (api + language + title). Mirrors the
 * legacy `SavedMangaEntity` primary-key composition documented on [Manga] and the matcher used
 * by [me.manga.kira.presentation.details.DetailsViewModel].
 */
private fun Manga.matches(other: Manga): Boolean =
    api == other.api && language == other.language && title == other.title
