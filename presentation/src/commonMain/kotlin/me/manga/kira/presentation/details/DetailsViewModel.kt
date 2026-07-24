package me.manga.kira.presentation.details

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.onFailure
import me.manga.kira.core.result.onSuccess
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.usecase.analytics.LogMangaOpenUseCase
import me.manga.kira.domain.usecase.connectivity.ObserveConnectivityUseCase
import me.manga.kira.domain.usecase.details.ClearChapterNewUseCase
import me.manga.kira.domain.usecase.details.DeleteChapterUseCase
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.IsAdultContentUseCase
import me.manga.kira.domain.usecase.details.ObserveSavedMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.ResolveChapterIdUseCase
import me.manga.kira.domain.usecase.downloads.CancelAllDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.CancelChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase
import me.manga.kira.domain.usecase.downloads.DeleteDownloadedChapterUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueAllChaptersDownloadUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveCompressionDeferredUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.MarkMangaOpenedUseCase
import me.manga.kira.domain.usecase.library.ObserveInLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.reader.MarkChaptersReadUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterBookmarkUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterReadUseCase
import me.manga.kira.presentation.mvi.MviViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Details screen ViewModel.
 *
 * Strict MVI: state lives in [DetailsState]; intents are sealed; effects are one-shot.
 * Constructor-injected use case (DIP) — never depends on the data layer directly.
 *
 * Replaces the legacy `:shared` `MangaDerailsViewModel` with two structural deltas:
 *  - **No `SourcesRepository` dependency**. The legacy VM held a direct reference to the source
 *    registry to pick the right repo and to read `blackListGenres` directly. The rework VM
 *    depends on two domain use cases instead: [FetchMangaDetailsUseCase] for source routing +
 *    fetch, and [IsAdultContentUseCase] for the blacklist check. Both hide the legacy registry
 *    behind their `:data` impls; the VM never sees `SourcesRepository`.
 *  - **No `initialize(...)` side door**. The legacy VM exposed a mutable `currentUrl` field
 *    that the navigation host wrote to once. The rework VM stores the manga identity inside
 *    [DetailsState] and the host submits [DetailsIntent.OnEnter] with the [Manga] — same trigger,
 *    but routed through the MVI intent channel so configuration-change replay logic stays in
 *    the framework, not in the VM.
 *
 * Adult-content classification (legacy parity, see [DetailsState.isAdult] KDoc):
 *  - On [DetailsIntent.OnEnter] the VM classifies using the *nav-arg* `manga.genres`. These may
 *    be empty when navigated from a search result that doesn't pre-populate genres — in which
 *    case [IsAdultContentUseCase] returns `false` (no genre overlap possible), which is the
 *    correct conservative default until the fetch lands.
 *  - On fetch success the VM re-classifies using the *fetched* [MangaDetails.genres] (the
 *    authoritative source — matches legacy, which called `isPlus18(info.genres, api)` against
 *    the fetched `MangaInfo`). The flag flips from `false` to `true` when the fetched details
 *    reveal adult genres that the nav-arg manga didn't carry.
 *  - The classification is synchronous (in-memory `Set.contains` per
 *    `AdultContentClassifier` KDoc), so it folds into the same `updateState` block as the
 *    success transition — no extra suspension, no additional MVI intent.
 *
 * SRP: orchestrates Details presentation state and nothing else. Bookmarking, downloading,
 * reading-progress writes are handled by their own future ViewModels.
 *
 * Re-entry idempotence: [DetailsIntent.OnEnter] is a no-op when the in-state manga already
 * matches the intent's identity (api + language + title triple). This means a configuration
 * change re-attaching the screen to a fresh host does NOT re-trigger the network fetch —
 * the StateFlow re-emission to the new host already carries the previously-fetched details.
 * To force a re-fetch, the view submits [DetailsIntent.OnRetry] explicitly.
 *
 * Cache-first open (native parity, 2026-06-01): on [DetailsIntent.OnEnter] the VM does NOT
 * unconditionally hit the network. Native reads an in-library manga's chapters straight from Room
 * (`LibraryDetailsViewModel.getChaptersByMangaId`) and only fetches on an explicit pull-to-refresh.
 * The rework mirrors this via [shouldOpenFromCache]: if the manga is in the library and already has
 * a saved chapter list, the open renders purely from the offline saved-details flow and fires no
 * network fetch; a not-in-library manga (or an in-library one with no cached chapters yet) still
 * fetches. [DetailsIntent.OnRetry] always fetches regardless of membership (pull-to-refresh parity).
 * Limitation: the in-library-but-no-cached-chapters case fetches but does NOT persist the fetched
 * list back to Room on open (that needs a dedicated persist-chapters-for-saved-manga use case +
 * repo method + cross-module DI wiring — out of scope for this minimal fix); such a manga re-fetches
 * on each open until its chapters are persisted by another path (e.g. a re-add or a library refresh).
 *
 * Re-entrance guard on [DetailsIntent.OnRetry]: if a fetch is already in flight
 * (`state.value.isLoading == true`), the intent is dropped. This prevents concurrent fetches
 * from racing on `updateState` and producing nondeterministic order in their success/failure
 * landings — the visible symptom would be a brief flicker if the second call returns first.
 * The :ui top-bar refresh button (Phase 7.x.refresh §49.5) is already disabled while a fetch
 * is in flight, so the VM guard closes the gap for any other dispatcher (intent replay,
 * programmatic dispatch, future pull-to-refresh) that lacks that surface.
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade, Task #446,
 * 2026-05-28): the "Replaces the legacy `:shared` `MangaDerailsViewModel`" paragraph above
 * (lines 23-33) describes the structural deltas relative to a legacy VM that has since
 * been retired in Phase 9.x.mangadetails.retire (§430, Slice 5 of the Phase 7.x.details.parity
 * campaign). The legacy `MangaDerailsViewModel.kt` + the legacy `MangaDetailsScreenRoute.kt`
 * route adapter + the legacy `MangaDetailsScreen.kt` + components were all deleted; the
 * `Screen.MangaDetails` route key remains in `Screen.kt` (per ADR-7/§253) and is now bound
 * to the rework adapter `MangaDetailsReworkByUrlScreenRoute` via `OnEnterByUrl`. The
 * structural-delta narrative stands as historical record of how this rework VM was designed
 * relative to its now-retired predecessor; the "no `SourcesRepository` dependency" + "no
 * `initialize(...)` side door" + "two domain use cases instead" design rules continue to
 * apply to this VM's current shape. Verified by Glob search for `MangaDerailsViewModel.kt`
 * returning zero hits. Original prose preserved verbatim per §253.
 */
class DetailsViewModel(
    private val fetchDetails: FetchMangaDetailsUseCase,
    private val isAdultContent: IsAdultContentUseCase,
    private val observeInLibrary: ObserveInLibraryUseCase,
    private val observeSavedDetails: ObserveSavedMangaDetailsUseCase,
    private val toggleInLibrary: ToggleInLibraryUseCase,
    private val enqueueAllChaptersDownload: EnqueueAllChaptersDownloadUseCase,
    // GAP-LIB-02/03 per-chapter library management (read-toggle/mark-read + download/cancel).
    private val toggleChapterRead: ToggleChapterReadUseCase,
    // Per-chapter bookmark toggle (native LibraryChapterItem bookmark icon).
    private val toggleChapterBookmark: ToggleChapterBookmarkUseCase,
    private val markChaptersRead: MarkChaptersReadUseCase,
    private val enqueueChapterDownload: EnqueueChapterDownloadUseCase,
    private val cancelChapterDownload: CancelChapterDownloadUseCase,
    // Interrupt the in-flight worker/coroutine for a RUNNING/COMPRESSING chapter (deletes partials
    // + re-enqueues the rest). The queue-prune cancelChapterDownload above is for QUEUED rows only.
    private val cancelRunningDownload: CancelRunningDownloadUseCase,
    // Top-bar "Stop" — bulk-cancel that actually stops the worker (not a per-chapter prune loop).
    private val cancelAllDownloads: CancelAllDownloadsUseCase,
    // L-4 / L-7: FULLY delete a chapter's download (clear isDownloaded + delete files + drop the
    // queue row), native parity with LibraryRepository.deleteDownloadedChapters. Used by the
    // multi-select delete, top-bar delete-all-downloaded, and per-row delete-chapter actions.
    // Id-keyed; the VM resolves chapter url → Room id via [resolveChapterId].
    private val deleteDownloadedChapter: DeleteDownloadedChapterUseCase,
    private val observeDownloads: ObserveDownloadsUseCase,
    private val resolveChapterId: ResolveChapterIdUseCase,
    // Bumps the manga's last-open timestamp on Details open (native parity), driving the Library
    // LAST_READ sort. No-op when the manga isn't in the library.
    private val markMangaOpened: MarkMangaOpenedUseCase,
    // #3: on a refresh of an in-library manga, persist newly-discovered chapters (isNew=true +
    // fetchedAt=now) so they survive nav-away and gain the NEW badge. No-op when not in library.
    private val persistNewChapters: PersistNewChaptersUseCase,
    // #3: clear a chapter's NEW badge the moment it is opened (without marking it read).
    private val clearChapterNew: ClearChapterNewUseCase,
    // Per-chapter "delete from database" button — deletes the saved_chapters row (after its download).
    private val deleteChapter: DeleteChapterUseCase,
    // #4: observe device reachability so the download gates can block + give immediate feedback
    // while offline (native parity: download actions are no-ops without a connection).
    private val observeConnectivity: ObserveConnectivityUseCase,
    // #11: native-parity manga_open analytics event, fired once per opened identity.
    private val logMangaOpen: LogMangaOpenUseCase,
    // iOS Low Power Mode compression deferral, exposed through a domain use case so the VM stays
    // independent of the platform signal and settings implementation.
    private val observeCompressionDeferred: ObserveCompressionDeferredUseCase,
) : MviViewModel<DetailsState, DetailsIntent, DetailsEffect>(
    initialState = DetailsState(),
) {

    /**
     * Library-membership flow collector job. Restarted on every identity change in [onEnter] so
     * the heart in the top bar reflects the *current* manga's bookmark state, not a stale one
     * from the previous screen visit. Cancelled implicitly when [viewModelScope] is cancelled on
     * `onCleared`. Same posture as `LibraryViewModel.observeJob` (Phase 6.2).
     */
    private var libraryMembershipJob: Job? = null

    /**
     * Offline/local saved-details collector job (regression fix, 2026-05-31). Restarted on every
     * identity change so a saved manga renders its Room-persisted chapter list + read/downloaded/
     * bookmark marks immediately (and offline), instead of "looking fresh" while the network fetch
     * runs. Cancelled implicitly when [viewModelScope] is cancelled on `onCleared`.
     */
    private var savedDetailsJob: Job? = null

    /**
     * PFIX-DLPROGRESS (2026-06-01) + completion-freeze fix (2026-06-02): the most recent download
     * rows captured from [ObserveDownloadsUseCase], keyed by chapter `url` (`DownloadedChapter.url`).
     * Carries the live [DownloadState] + 0-100 `progress` + `sizeBytes` for each QUEUED / RUNNING /
     * COMPRESSING (active) AND SUCCESS (completed) row; FAILED rows are excluded so the chapter shows
     * the idle Download button to retry.
     *
     * Keyed by `url` (not Room id) so [recomputeChapterDownloads] can join onto the url-keyed
     * displayed [Chapter] list SYNCHRONOUSLY — no per-row suspend `ChapterIdResolver` round-trip, so
     * the per-chapter status map is written in one [updateState] and the running→downloaded
     * transition is atomic (the SUCCESS row arrives in the same downloads emission that dropped
     * RUNNING). Recomputed on every downloads tick and every displayed-list change. Started once in
     * [init] for the screen's lifetime.
     */
    private var downloadRowsByUrl: Map<String, ChapterDownloadProgress> = emptyMap()

    /** Consecutive Cloudflare-solve round-trips for the current screen; bounded by
     *  [MAX_CLOUDFLARE_ATTEMPTS] and reset on any successful fetch. */
    private var cloudflareAttempts = 0

    /** Chapter `url`s whose DOWNLOAD failed on a Cloudflare challenge (engine stamped the
     *  [DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL]). Doubles as the solver-emit de-dup (one
     *  WebView open per batch, not per chapter) and the pending-retry list that [onRetry] re-enqueues
     *  once the WebView solve refreshed the source cookies. */
    private val cloudflareFailedDownloadUrls = mutableSetOf<String>()

    init {
        // Observe the global downloads queue; project each active/completed row (state + live
        // progress + size) into a url-keyed cache, then re-derive the per-chapter
        // [DetailsState.chapterDownloads] map for the currently-displayed chapters. Joining by `url`
        // keeps the whole recompute synchronous (no id-resolve), which is what makes the
        // running→downloaded flip land in a single state snapshot (no leave/re-enter flash).
        observeDownloads()
            .onEach { rows ->
                downloadRowsByUrl = rows
                    .filter { it.state != DownloadState.FAILED }
                    .associate {
                        it.url to ChapterDownloadProgress(
                            state = it.state,
                            progress = it.progress,
                            sizeBytes = it.sizeBytes,
                            chapterId = it.chapterId,
                            mangaId = it.mangaId,
                        )
                    }
                recomputeChapterDownloads()
                // A download whose page-resolution hit a Cloudflare challenge is FAILED with the engine's
                // sentinel — auto-route to the same WebView solver the reading path uses, then re-enqueue
                // on return (see onRetry). FAILED stays out of the UI map above (idle Download button).
                maybeSolveCloudflareForFailedDownloads(rows)
            }
            .catch { /* Downloads indicator is best-effort; the chapter list still works without it. */ }
            .launchIn(viewModelScope)

        // #4: track reachability into state so the download gates can block + give immediate
        // feedback while offline. Defaults to online (DetailsState.isOnline = true) so the absence
        // of an emission never blocks a download (no regression to the existing enqueue flows).
        observeConnectivity()
            .onEach { online -> updateState { it.copy(isOnline = online) } }
            .catch { /* connectivity is a best-effort gate; on observer failure stay optimistic. */ }
            .launchIn(viewModelScope)

        // Keep the chapter rows honest while iOS Low Power Mode is active. This is a global
        // signal rather than a manga-specific observation, so it starts once for the VM lifetime.
        observeCompressionDeferred()
            .onEach { deferred -> updateState { it.copy(compressionDeferred = deferred) } }
            .catch { /* deferral is advisory; retain the safe default false on observer failure. */ }
            .launchIn(viewModelScope)
    }

    private companion object {
        /**
         * HTTP statuses that a Cloudflare / anti-bot interstitial uses and that the user can clear
         * in a WebView (bug #2). 403 is the classic Cloudflare challenge; 503 ("checking your
         * browser"), 429 (rate-limit interstitial), and 520-524 (CF origin/edge errors) are also
         * routinely transient WebView-solvable states. The `:data` layer additionally re-surfaces
         * code-0 challenge-bodied throws as 403 (see MangaDetailsRepositoryImpl.isChallengeMessage),
         * so genuine 404/500 app errors keep falling to the generic ShowError snackbar.
         */
        val CHALLENGE_STATUSES = setOf(403, 429, 503, 520, 521, 522, 523, 524)

        /**
         * Max consecutive Cloudflare-solve round-trips before we stop auto-routing to the WebView
         * and surface the error normally. Inspired by native `Handle403Error`'s `maxDismissals`
         * cap (native defaulted to 1 re-show); we deliberately allow 2 — an initial solve plus one
         * retry — more forgiving while still bounded. Without this, an unsolvable/persistent
         * challenge re-emits the solver effect on every retry, trapping the user in an infinite
         * WebView re-route loop. Reset to 0 on any successful fetch (see [runFetch]).
         */
        const val MAX_CLOUDFLARE_ATTEMPTS = 2
    }

    override suspend fun handle(intent: DetailsIntent) {
        when (intent) {
            is DetailsIntent.OnEnter -> onEnter(intent.manga)
            is DetailsIntent.OnEnterByUrl -> onEnterByUrl(intent.api, intent.mangaUrl)
            DetailsIntent.OnRetry -> onRetry()
            is DetailsIntent.OnChapterClick -> onChapterClick(intent)
            DetailsIntent.OnBackClick -> emit(DetailsEffect.NavigateBack)
            // P0-ADULT hard-block gate (native parity). Advance steps; every dismiss path
            // back-navigates and NO path clears the gate to None, so content is never revealed.
            DetailsIntent.OnAdultWarningContinue ->
                updateState { it.copy(adultGateStep = AdultGateStep.MStep1) }
            DetailsIntent.OnAdultStep1Continue ->
                updateState { it.copy(adultGateStep = AdultGateStep.MStep2) }
            DetailsIntent.OnAdultStep2Dismiss -> emit(DetailsEffect.NavigateBack)
            DetailsIntent.OnAdultGateBack -> emit(DetailsEffect.NavigateBack)
            DetailsIntent.OnToggleInLibrary -> onToggleInLibrary()
            DetailsIntent.OnDownloadClick -> emit(DetailsEffect.NavigateToDownloads)
            DetailsIntent.OnExportManga -> {
                // feature/backup: scoped export — gated on membership (an unsaved manga has no
                // local rows to back up; the :ui action is hidden then too, this is the VM guard).
                val manga = state.value.manga
                if (state.value.isInLibrary && manga != null) {
                    emit(
                        DetailsEffect.NavigateToBackupExport(
                            MangaKey(api = manga.api, language = manga.language, title = manga.title),
                        ),
                    )
                }
            }
            DetailsIntent.OnDownloadAllClick -> onDownloadAllClick()
            DetailsIntent.OnOpenInWebView -> onOpenInWebView()
            is DetailsIntent.OnToggleChapterRead -> onToggleChapterRead(intent.chapter)
            is DetailsIntent.OnToggleChapterBookmark -> onToggleChapterBookmark(intent.chapter)
            is DetailsIntent.OnDownloadChapter -> onDownloadChapter(intent.chapter)
            is DetailsIntent.OnCancelChapterDownload -> onCancelChapterDownload(intent.chapter)
            is DetailsIntent.OnDeleteChapter -> onDeleteChapter(intent.chapter)
            is DetailsIntent.OnSetChapterFilter -> updateState { it.copy(chapterFilter = intent.filter) }
            is DetailsIntent.OnSetChapterSort -> updateState { it.copy(chapterSort = intent.sort) }
            DetailsIntent.OnToggleSortDirection -> updateState { it.copy(sortAscending = !it.sortAscending) }
            is DetailsIntent.OnChapterLongClick -> onChapterLongClick(intent.chapter)
            is DetailsIntent.OnSelectionToggle -> onSelectionToggle(intent.chapter)
            DetailsIntent.OnSelectionClear -> updateState { it.copy(selectedChapterUrls = emptySet()) }
            DetailsIntent.OnMarkSelectedRead -> onMarkSelectedRead()
            DetailsIntent.OnDownloadSelected -> onDownloadSelected()
            DetailsIntent.OnBookmarkSelected -> onBookmarkSelected()
            DetailsIntent.OnDeleteSelectedDownloads -> onDeleteSelectedDownloads()
            DetailsIntent.OnMarkSelectedDownRead -> onMarkSelectedDownRead()
            DetailsIntent.OnDeleteAllDownloads -> onDeleteAllDownloads()
            DetailsIntent.OnCancelAllDownloads -> onCancelAllDownloads()
        }
    }

    private suspend fun onEnter(manga: Manga) {
        if (state.value.manga?.matches(manga) == true) return
        FlowLog.log("Details", "open", "title=${manga.title} api=${manga.api} lang=${manga.language}")
        // #11: native manga_open — fired once per opened identity (this method early-returns on a
        // same-identity re-enter). Full-tuple entry (Home/Library/Search/Details) has the title; the
        // URL-only onEnterByUrl deep-link path has no title at entry, so it does not fire here.
        logMangaOpen(api = manga.api, title = manga.title)
        // Tentative classification from nav-arg genres (may be empty → false). The fetched
        // details refine this in runFetch().onSuccess.
        val tentativeAdult = isAdultContent(manga)
        updateState {
            it.copy(
                manga = manga,
                isLoading = true,
                details = null,
                error = null,
                isAdult = tentativeAdult,
                // P0-ADULT: arm the hard-block gate at AdultWarning the moment the manga classifies
                // adult; otherwise None. Re-derived on the fetched genres in runFetch.
                //
                // INTENTIONALLY-DIFFERENT (not native parity): native gated adult content only on the
                // search/home MangaDetailsScreen, NOT on the library MangaDetailsScreen — an in-library
                // adult title opened from the Library rendered ungated there. The rework folds both
                // entry points into one DetailsScreen and gates ALL of them. This deliberate
                // over-block (more restrictive, aligned with Play-policy / cultural guidelines) is
                // kept on purpose; we do NOT suppress the gate for in-library adult manga, because
                // doing so would re-expose explicit content the gate is meant to block.
                adultGateStep = if (tentativeAdult) AdultGateStep.AdultWarning else AdultGateStep.None,
                // Reset to the conservative default until the new identity's first flow
                // emission lands — prevents a stale "in library" heart from the previous manga
                // flashing on the new screen between OnEnter and the first ObserveInLibrary
                // re-emission (typically same frame, but the reset guarantees correctness even
                // when the Room query coldstart takes a tick).
                isInLibrary = false,
            )
        }
        startObservingLibraryMembership(manga)
        startObservingSavedDetails(manga)
        // Native parity (LibraryDetailsViewModel reads chapters from Room; the source is only hit on
        // an explicit pull-to-refresh): decide cache-vs-network for THIS open based on library
        // membership + whether a saved chapter list already exists.
        if (!shouldOpenFromCache(manga)) {
            FlowLog.log("Details", "openMode", "title=${manga.title} mode=network")
            runFetch(manga)
        } else {
            FlowLog.log("Details", "openMode", "title=${manga.title} mode=cache-only (no fetch on open)")
        }
        // else: the saved (Room) details flow above already rendered the cached chapter list and
        // cleared the spinner; no network fetch is fired on open. The user can force a refresh via
        // OnRetry (pull-to-refresh parity), which always fetches regardless of membership.
    }

    /**
     * Cache-first open decision (native parity). Returns `true` when this open should render purely
     * from the local Room store WITHOUT a network fetch — i.e. the manga is in the library AND it
     * already has a saved chapter list. Performs a one-shot read of the saved-details flow keyed on
     * the manga identity (the same flow the reactive [startObservingSavedDetails] observer drives):
     *  - saved + non-empty chapters → cache-only (no fetch on open). Mirrors native's
     *    `LibraryMangaScreen`, which reads `getChaptersByMangaId` and never fetches on open.
     *  - saved + empty chapters (e.g. added before this fix, or via an empty-chapter entry point) →
     *    fetch once so the list isn't empty. (Persist-on-fetch for this case is NOT implemented —
     *    see the class KDoc note; the reactive overlay still merges read-state.)
     *  - not saved (`null`) → fetch (fresh network open, as before).
     *
     * Best-effort: any failure resolving the local store falls back to fetching (returns `false`),
     * so a flaky storage read never strands the screen on an empty cache. [CancellationException] is
     * rethrown so structured concurrency / re-entry cancellation works.
     */
    private suspend fun shouldOpenFromCache(manga: Manga): Boolean {
        if (manga.title.isBlank()) return false
        return try {
            val saved = observeSavedDetails(api = manga.api, title = manga.title).firstOrNull()
            saved != null && saved.chapters.isNotEmpty()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Offline-first local Details path (regression fix, 2026-05-31). Subscribes to
     * [ObserveSavedMangaDetailsUseCase] keyed on `(api, title)`. The first non-null emission means
     * the manga is saved: render its chapter list (with read/downloaded/bookmark marks) and clear
     * the spinner IMMEDIATELY — before, and independently of, the network fetch. This is what makes
     * a Library-opened manga show as the saved/local manga rather than "looking fresh", and it
     * keeps the chapter list visible even when the source fetch fails or the device is offline.
     *
     * Once network details land ([runFetch]), the saved read-state is overlaid onto the fresh list
     * (see [MangaDetails.overlaidWith]) so a refresh never wipes read progress; subsequent local
     * writes re-emit here and re-overlay onto whatever details are currently shown.
     *
     * No-op for URL-only entries (blank title) — the `(api, title)` key isn't resolvable yet; the
     * deferred subscription starts in [runFetch]'s success path once the title is enriched.
     */
    private fun startObservingSavedDetails(manga: Manga) {
        savedDetailsJob?.cancel()
        if (manga.title.isBlank()) return
        savedDetailsJob =
            observeSavedDetails(api = manga.api, title = manga.title)
                .onEach { saved ->
                    if (saved == null) return@onEach
                    // Cancellation of the previous Room collector and the next identity's OnEnter can
                    // race by one emission. Never let a saved payload from the previous screen make a
                    // newly Home-opened manga look like that library entry.
                    if (state.value.manga?.matches(manga) != true) return@onEach
                    FlowLog.log(
                        "Details",
                        "savedLoaded",
                        "title=${manga.title} chapters=${saved.chapters.size} new=${saved.chapters.count { it.isNew }}",
                    )
                    updateState { current ->
                        val net = current.details
                        val merged =
                            (if (net != null) net.overlaidWith(saved) else saved)
                                .expireNewBadges(nowMs())
                        // P0-ADULT (compliance): a cache-first open suppresses runFetch, so this is the
                        // only place the gate gets re-classified for an in-library manga. Classify from
                        // the saved/merged genres (the nav-arg genres may be empty — History/Updates
                        // pass `genres = emptyList()`) and arm the hard-block gate exactly as
                        // runFetch.onSuccess does: arm at AdultWarning when adult (preserving an
                        // already-advanced step), else clear to None. Without this, an adult title opened
                        // from cache would render fully ungated.
                        val refreshedAdult = isAdultContent(manga.copy(genres = merged.genres))
                        current.copy(
                            details = merged,
                            isLoading = false,
                            error = null,
                            isAdult = refreshedAdult,
                            adultGateStep =
                                when {
                                    !refreshedAdult -> AdultGateStep.None
                                    current.adultGateStep == AdultGateStep.None -> AdultGateStep.AdultWarning
                                    else -> current.adultGateStep
                                },
                        )
                    }
                    // A saved manga's chapter list just landed/changed — re-derive each chapter's live
                    // download status+progress (PFIX-DLPROGRESS).
                    recomputeChapterDownloads()
                }.catch { /* Offline read is best-effort; the network refresh path still runs. */ }
                .launchIn(viewModelScope)
    }

    /**
     * URL-only entry handler (Phase 9.x.mangadetails.swap Slice 4 — ADR-6).
     *
     * Built a *tentative* [Manga] with the two known nav-arg fields ([api] + [mangaUrl]) and
     * sentinel placeholders for the rest. The existing [runFetch] path uses only those two fields
     * for routing — the source repositories look up by `(api, url)`, never by language/title —
     * so the tentative manga is enough to dispatch a successful fetch.
     *
     * **Re-entry guard** is keyed on `(api, mangaUrl)` rather than the (api, language, title)
     * triple used by [onEnter], because at OnEnterByUrl-time the language/title are not yet
     * known. A config-change replay or a `LaunchedEffect` re-fire for the same URL → no-op; once
     * the fetch lands and [runFetch] enriches the in-state manga's language/title from the
     * fetched [me.manga.kira.domain.model.MangaDetails], a subsequent OnEnterByUrl with the
     * same URL still no-ops (the enriched state's manga.url still matches).
     *
     * **Library subscription deferral**: [startObservingLibraryMembership] is keyed on the
     * (api, language, title) triple, which is incomplete at OnEnterByUrl-time. The
     * `wasUrlOnly`-gated branch inside [runFetch].onSuccess subscribes after the fetch lands and
     * enriches the identity — until then, [DetailsState.isInLibrary] stays `false`, and the
     * `:ui` bookmark IconButton's `bookmarkEnabled` gate already binds on
     * `state.manga?.title?.isNotBlank() == true` to prevent the user from toggling a junk
     * `("", "", api)` Library row before identity resolves.
     */
    private suspend fun onEnterByUrl(api: String, mangaUrl: String) {
        val current = state.value.manga
        if (current?.api == api && current.url == mangaUrl) return
        val tentative = Manga(
            api = api,
            language = "",
            title = "",
            url = mangaUrl,
            coverUrl = "",
            rating = null,
            genres = emptyList(),
        )
        // Tentative classification — no genres yet, so isAdult is false. Re-classified in
        // runFetch.onSuccess on the fetched details (matches the onEnter posture).
        val tentativeAdult = isAdultContent(tentative)
        // Cancel any pre-existing library-membership subscription from a previous manga visit —
        // OnEnterByUrl can't restart it here (the identity isn't known yet); the gap is closed
        // by runFetch.onSuccess on the first successful fetch (see the wasUrlOnly branch).
        libraryMembershipJob?.cancel()
        // Cancel any saved-details observer from a previous visit; OnEnterByUrl can't restart it
        // (no title yet) — the runFetch success path re-attaches it once the title is enriched.
        savedDetailsJob?.cancel()
        updateState {
            it.copy(
                manga = tentative,
                isLoading = true,
                details = null,
                error = null,
                isAdult = tentativeAdult,
                // P0-ADULT: tentative manga carries no genres → not adult yet; gate stays None
                // and is re-armed in runFetch.onSuccess once the fetched genres classify adult.
                adultGateStep = if (tentativeAdult) AdultGateStep.AdultWarning else AdultGateStep.None,
                isInLibrary = false,
            )
        }
        runFetch(tentative)
    }

    /**
     * Subscribe to [ObserveInLibraryUseCase] keyed on the active manga identity. Cancels any
     * previously-running collector so a screen revisit to a different title doesn't leak its
     * predecessor's emissions onto the new state. SRP-clean: this method owns ONE rule —
     * "the [DetailsState.isInLibrary] flag mirrors the reactive `EXISTS` query for the
     * currently-displayed manga".
     *
     * No `emit(ShowError)` on flow failure: library membership is a *secondary* affordance on
     * the Details screen — the cover, chapter list, and bookmark action all work fine when
     * the flag is `false`. A toast every time the user opens Details on a flaky storage host
     * would be noise, not signal. The catch swallows the throw so it can't crash the scope; an
     * upstream throw still completes the flow (Flow.catch does not resubscribe), so on failure the
     * collector stops and the flag keeps its last value until the screen is re-entered.
     */
    private fun startObservingLibraryMembership(manga: Manga) {
        libraryMembershipJob?.cancel()
        libraryMembershipJob =
            observeInLibrary(
                api = manga.api,
                language = manga.language,
                title = manga.title,
            ).onEach { inLibrary ->
                // A cancelled membership flow may already have one Room emission queued. Scope it
                // to the identity that started this collector before touching the shared VM state.
                updateState { current ->
                    if (current.manga?.matches(manga) == true) {
                        current.copy(isInLibrary = inLibrary)
                    } else {
                        current
                    }
                }
            }.catch { /* See KDoc — secondary affordance, defaulting to false is safe. */ }
                .launchIn(viewModelScope)
    }

    private suspend fun onRetry() {
        // Re-entrance guard: if a fetch is already in flight, drop this OnRetry. Two concurrent
        // runFetch coroutines for the same identity would race on `updateState`, and the second
        // one to land would overwrite the first's payload — flicker risk for the user, no
        // benefit. The :ui top-bar refresh button is also gated by `refreshEnabled = !state.isLoading`
        // (§49.5), but any other dispatcher (intent replay, programmatic OnRetry, future
        // pull-to-refresh, etc.) lacks that surface — the guard here closes the gap at the VM
        // boundary. Same reasoning would apply to OnEnter, but OnEnter has its own idempotence
        // guard on the in-state manga identity (see `onEnter`), so the loading flag isn't the
        // right discriminator there.
        if (state.value.isLoading) return
        val manga = state.value.manga ?: return
        // Cloudflare-solver return path: re-enqueue any downloads that failed on the challenge now that
        // the WebView solve refreshed the source cookies. No-op for a plain fetch retry (empty set).
        retryCloudflareFailedDownloads()
        updateState { it.copy(isLoading = true, error = null) }
        runFetch(manga)
    }

    private suspend fun runFetch(manga: Manga) {
        FlowLog.log("Details", "refresh", "title=${manga.title} api=${manga.api}")
        // Capture the stable identity this fetch was started for. `(api, url)` survives the
        // onSuccess enrichment (unlike language/title, which onEnterByUrl fills only afterwards),
        // so we can detect a stale landing: if a different identity has taken over the in-state
        // manga while this fetch was in flight (same-VM OnEnter/OnEnterByUrl for another manga),
        // its onSuccess/onFailure must NOT write over the newer identity's state.
        val fetchApi = manga.api
        val fetchUrl = manga.url
        fetchDetails(manga)
            .onSuccess { details ->
                val active = state.value.manga
                if (active == null || active.api != fetchApi || active.url != fetchUrl) {
                    FlowLog.log("Details", "refreshStale", "dropped stale fetch for api=$fetchApi url=$fetchUrl")
                    return@onSuccess
                }
                FlowLog.log("Details", "refreshOk", "title=${details.title} chapters=${details.chapters.size}")
                // Re-classify with the authoritative genres from the fetched details — matches
                // legacy isPlus18(info.genres, api). manga.copy() keeps the original api +
                // language + title; only the genres are replaced.
                val classifierInput = manga.copy(genres = details.genres)
                val refreshedAdult = isAdultContent(classifierInput)
                // Detect URL-only entry: title was blank pre-enrichment (set by onEnterByUrl
                // with sentinel placeholders). After the state update below, this branch is
                // also the trigger for the deferred library-membership subscription.
                val wasUrlOnly = state.value.manga?.title.isNullOrBlank()
                // Overlay the locally-persisted read/downloaded/bookmark state (currently shown
                // from the saved projection, if any) onto the fresh network chapter list so a
                // refresh never wipes the user's read marks (regression fix, 2026-05-31). Idempotent
                // when there's no saved snapshot or on a re-fetch.
                val savedSnapshot = state.value.details
                updateState { current ->
                    // Enrich the in-state Manga with the authoritative identity fields from the
                    // fetched details. For full-tuple OnEnter entries this overwrites identical
                    // fields with the freshly-fetched values (cover/genres may have been refreshed
                    // server-side since the user last loaded the parent list). For OnEnterByUrl
                    // entries this fills the sentinel placeholders (`language=""`, `title=""`,
                    // `coverUrl=""`, `genres=[]`) with the real values for the first time. Skip
                    // `rating` — Manga.rating is Int? while MangaDetails.rating is String
                    // (heterogeneous source formats kept opaque per MangaDetails KDoc); the screen
                    // reads details.rating directly anyway.
                    val enrichedManga = current.manga?.copy(
                        language = details.language,
                        title = details.title,
                        coverUrl = details.coverUrl,
                        genres = details.genres,
                    )
                    current.copy(
                        isLoading = false,
                        manga = enrichedManga,
                        details = (if (savedSnapshot != null) details.overlaidWith(savedSnapshot) else details)
                            .expireNewBadges(nowMs()),
                        error = null,
                        isAdult = refreshedAdult,
                        // P0-ADULT: arm/keep the hard-block gate from the AUTHORITATIVE fetched
                        // genres (mirrors native isPlus18(info.genres, api) on the fetched info).
                        // This is the path that catches URL-only / search-result entries whose
                        // nav-arg carried no genres. If adult, ensure the gate is active —
                        // preserve an already-advanced step (e.g. the user is mid-chain when a
                        // refresh lands) and otherwise arm at AdultWarning; if not adult, clear to
                        // None. Compliance-critical: a fetch that reveals adult genres can never
                        // leave the gate at None, so the body stays blocked.
                        adultGateStep = when {
                            !refreshedAdult -> AdultGateStep.None
                            current.adultGateStep == AdultGateStep.None -> AdultGateStep.AdultWarning
                            else -> current.adultGateStep
                        },
                    )
                }
                // Library-membership subscription deferral close-out (URL-only entry path). The
                // OnEnterByUrl handler can't subscribe up-front because the (api, language, title)
                // triple isn't known until the fetch lands. This branch fires exactly once per
                // URL-only entry — the first successful fetch — and starts the same flow collector
                // OnEnter already starts up-front. Full-tuple OnEnter entries skip this branch
                // because their pre-fetch title was non-blank.
                if (wasUrlOnly) {
                    val enriched = state.value.manga
                    if (enriched != null && enriched.title.isNotBlank()) {
                        startObservingLibraryMembership(enriched)
                        // Now that the title is known, attach the offline saved-details observer too
                        // (URL-only History/Updates entries also benefit from local read-state merge).
                        startObservingSavedDetails(enriched)
                    }
                }
                // Network chapter list landed — re-derive per-chapter download status (PFIX-DLPROGRESS).
                recomputeChapterDownloads()
                // #3: persist refresh-discovered chapters for an in-library manga so they survive
                // nav-away (written to Room, not just VM state) and gain the NEW badge. Gated on
                // membership — a not-in-library Details open must not create saved rows. Diff/dedup +
                // isNew/fetchedAt stamping live in the use case; fire-and-forget (the saved-details
                // flow re-emits and re-overlays the new rows). Uses the authoritative fetched identity.
                if (state.value.isInLibrary) {
                    launchSafely {
                        persistNewChapters(details.api, details.language, details.title, details.chapters)
                    }
                }
                // A successful fetch clears the Cloudflare-solve budget so a later genuine challenge
                // gets its full allowance again (not starved by earlier attempts this session).
                cloudflareAttempts = 0
            }
            .onFailure { error ->
                val active = state.value.manga
                if (active == null || active.api != fetchApi || active.url != fetchUrl) {
                    FlowLog.log("Details", "refreshStale", "dropped stale failure for api=$fetchApi url=$fetchUrl")
                    return@onFailure
                }
                FlowLog.log("Details", "refreshError", "title=${manga.title} error=${error::class.simpleName}")
                updateState { it.copy(isLoading = false, error = error) }
                // Legacy `Handle403Error` parity (bug #2): a 403 is a Cloudflare / anti-bot
                // interstitial, not a hard failure. Route the user to the WebView to solve the
                // challenge (which primes the per-source cookie/header store) instead of surfacing
                // a dead-end "failed to load" snackbar. The `:ui` layer auto-retries the fetch when
                // it returns from the WebView, mirroring the legacy auto-retry-on-dismiss. Any other
                // error keeps the existing generic ShowError snackbar behaviour.
                val manga = state.value.manga
                if (error is AppError.Network.Http && error.statusCode in CHALLENGE_STATUSES &&
                    manga != null && cloudflareAttempts < MAX_CLOUDFLARE_ATTEMPTS
                ) {
                    // Bounded auto-recovery: route to the WebView solver, but cap consecutive
                    // round-trips so a persistent/unsolvable challenge can't loop forever.
                    cloudflareAttempts++
                    emit(DetailsEffect.SolveCloudflareChallenge(url = manga.url, api = manga.api))
                } else {
                    // Either not a challenge, or the solve budget is exhausted — surface the error
                    // (the user can still retry manually) instead of re-entering the WebView loop.
                    emit(DetailsEffect.ShowError(error))
                }
            }
    }

    private suspend fun onChapterClick(intent: DetailsIntent.OnChapterClick) {
        val manga = state.value.manga ?: return
        FlowLog.log(
            "Details",
            "chapterClick",
            "chapter=${intent.chapter.url} num=${intent.chapter.number} downloaded=${intent.chapter.isDownloaded} new=${intent.chapter.isNew}",
        )
        // Native parity (LibraryMangaRoute.onChapterClick → updateLastOpen): bump the manga's
        // last-open timestamp when the user opens a chapter to read — NOT on mere Details viewing.
        // This drives the Library LAST_READ sort. Fire-and-forget; markOpened no-ops when the manga
        // isn't in the library (no saved_manga row to bump).
        launchSafely { markMangaOpened(manga.api, manga.language, manga.title) }
        // #3 NEW-badge parity: clear the badge the instant the chapter is OPENED (native clears on
        // chapter click), not later when the reader advances past it. Does NOT mark the chapter read
        // (opening != reading). Fire-and-forget; no-op for a non-saved chapter. The saved-details
        // flow re-emits and the badge clears reactively.
        launchSafely { clearChapterNew(intent.chapter.url) }
        emit(DetailsEffect.NavigateToReader(manga = manga, chapter = intent.chapter))
    }

    /**
     * "Download all" handler (legacy `HeaderSection` `action_download_all` parity). Enqueues every
     * chapter of the fetched [MangaDetails] for offline download via
     * [EnqueueAllChaptersDownloadUseCase], which composes the same per-chapter enqueue path the
     * rework Updates download button uses (Tasks #299/#300) with a `url` → Room-`chapterId`
     * resolution step (the pure-domain [me.manga.kira.domain.model.Chapter] carries `url`, not
     * the surrogate id the download subsystem keys on). Chapters with no in-library row are
     * skipped; already-downloaded chapters are re-enqueued idempotently — same posture as the
     * single-enqueue path (the gate is a UI rule: `:ui` shows the button only when
     * `state.isInLibrary`).
     *
     * No-op when `state.details` is null (the button is only reachable from the success state, but
     * the null-guard keeps the handler safe against a stray dispatch during a state transition).
     *
     * Fire-and-forget in [viewModelScope] (same posture as the Updates download button): the
     * `handle` suspend returns immediately while the use case runs the resolve+enqueue loop on its
     * own background dispatcher. The reactive Downloads list updates flow back through the existing
     * `DownloadsRepository.observeAll()` Room flow, so no imperative state mutation is needed here.
     * A use-case-level failure surfaces via the existing [DetailsEffect.ShowError] snackbar.
     */
    private fun onDownloadAllClick() {
        val details = state.value.details ?: return
        launchSafely {
            // #4: same offline gate as the single-chapter download path.
            if (!state.value.isOnline) {
                emit(DetailsEffect.ShowError(AppError.Network.NoConnectivity()))
                return@launchSafely
            }
            enqueueAllChaptersDownload(details)
                .onFailure { t -> emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t))) }
        }
    }

    /**
     * WebView intent handler. Reads identity from `state.manga`, emits a
     * [DetailsEffect.NavigateToWebView] carrying the manga URL + source api in domain terms.
     * The `:composeApp` route adapter is the only layer that translates this into
     * `Screen.WebView(url, api)` — `:presentation` and `:ui` stay route-name-agnostic per the
     * campaign clean-architecture guardrail.
     *
     * Legacy parity: legacy `onOpenInWebViewError` defaulted to `viewModel.currentUrl` and
     * returned early when empty (`MangaDetailsScreenRoute.kt:94-101`). The rework reads
     * `state.value.manga?.url` and returns early on null — same observable behaviour. Both the
     * top-bar ↗ button (success state) and the error-pane "Open in WebView" fallback dispatch
     * the same intent because they carry the same payload and route to the same destination
     * (ADR-5: one intent, both buttons — SRP, one rule, one handler).
     */
    private suspend fun onOpenInWebView() {
        val manga = state.value.manga ?: return
        emit(DetailsEffect.NavigateToWebView(url = manga.url, api = manga.api))
    }

    /**
     * Bookmark toggle handler. Reads identity from `state.manga`, calls [ToggleInLibraryUseCase],
     * lets the [ObserveInLibraryUseCase] flow re-emission drive [DetailsState.isInLibrary] —
     * matches the [me.manga.kira.presentation.library.LibraryViewModel] reactive posture.
     *
     * Race gate ([DetailsState.isTogglingBookmark], per `DetailsState` KDoc + §253 plan): the
     * use case is non-atomic at the domain boundary (read `repository.get` THEN add/remove). A
     * rapid double-tap could fire two concurrent invocations before the reactive flow snaps
     * `isInLibrary`, with the second call observing the post-first-call state and toggling BACK.
     * The flag is raised before the call and reset in a `finally` so a use-case failure (or any
     * thrown exception in `.onSuccess`/`.onFailure`) doesn't leave the flag permanently stuck.
     * The guard is VM-side only — the handler drops a re-entrant intent synchronously (the
     * `isTogglingBookmark` early-return above) before the first suspension; no `:ui` affordance
     * currently binds `enabled` to the flag.
     *
     * No payload on the intent — identity is derived from `state.manga`, the same field
     * `OnEnter` populated. If `state.manga` is null (race against `OnEnter` landing, or the user
     * tapping in a state transition window where state was reset), the intent is a no-op.
     */
    private suspend fun onToggleInLibrary() {
        val manga = state.value.manga ?: return
        if (state.value.isTogglingBookmark) return
        updateState { it.copy(isTogglingBookmark = true) }
        try {
            // Native parity: persist the manga WITH its fetched chapter list at add-time
            // (saveMangaWithChapters) so an in-library manga renders from Room on subsequent opens
            // without a network re-fetch. The chapters are ignored on the removal branch.
            toggleInLibrary(manga, state.value.details?.chapters ?: emptyList())
                .onFailure { error -> emit(DetailsEffect.ShowError(error)) }
                .onSuccess { /* Flow re-emission flips isInLibrary; no extra state work. */ }
        } finally {
            updateState { it.copy(isTogglingBookmark = false) }
        }
    }

    // ---- GAP-LIB-02/03 per-chapter library management ----------------------------------------

    /**
     * Recompute [DetailsState.chapterDownloads] from the latest download rows ([downloadRowsByUrl])
     * and the currently-displayed chapter list (PFIX-DLPROGRESS / completion-freeze fix). For each
     * displayed chapter whose `url` has an active or completed download row, writes a url-keyed
     * [ChapterDownloadProgress] carrying the live state + 0-100 progress + size.
     *
     * Synchronous — joins by `url` directly (no suspend id-resolve), so the whole map is written in
     * ONE [updateState] and the row's active/downloaded/idle branches always move together from a
     * single state snapshot. This is what removes the old race where, on completion, the row briefly
     * read "not downloading AND not yet downloaded" and flashed the idle Download button until the
     * screen was re-entered: the SUCCESS row now arrives in the same downloads emission that dropped
     * the RUNNING state, so the flip is atomic.
     *
     * Runs on BOTH triggers: every downloads-queue emission (so a RUNNING progress *tick* refreshes
     * the percent) and every saved/network chapter-list change (so a list refresh re-keys the map).
     * The diff guard avoids redundant emissions when a tick concerns some other manga's download.
     */
    private fun recomputeChapterDownloads() {
        val chapters = state.value.details?.chapters
        val rowsByUrl = downloadRowsByUrl
        if (chapters.isNullOrEmpty() || rowsByUrl.isEmpty()) {
            if (state.value.chapterDownloads.isNotEmpty()) {
                updateState { it.copy(chapterDownloads = emptyMap()) }
            }
            return
        }
        val byUrl = chapters.mapNotNull { chapter ->
            val progress = rowsByUrl[chapter.url] ?: return@mapNotNull null
            chapter.url to progress
        }.toMap()
        if (byUrl != state.value.chapterDownloads) {
            updateState { it.copy(chapterDownloads = byUrl) }
        }
    }

    /** Single-chapter read toggle (GAP-LIB-02). Gated on in-library; reactive flow re-renders. */
    private fun onToggleChapterRead(chapter: Chapter) {
        if (!state.value.isInLibrary) return
        launchSafely { toggleChapterRead(chapter.url) }
    }

    /**
     * Single-chapter bookmark toggle (native `LibraryMangaScreen` per-chapter bookmark icon →
     * `toggleChapterBookmark`). Gated on in-library; the use case no-ops for a chapter with no saved
     * row, and the reactive saved-details flow re-emits the new flag so the row re-renders.
     */
    private fun onToggleChapterBookmark(chapter: Chapter) {
        if (!state.value.isInLibrary) return
        launchSafely { toggleChapterBookmark(chapter.url) }
    }

    /** Single-chapter download enqueue (GAP-LIB-03). Gated on in-library. */
    private fun onDownloadChapter(chapter: Chapter) {
        if (!state.value.isInLibrary) return
        val title = state.value.details?.title ?: return
        val api = state.value.manga?.api ?: return
        launchSafely {
            // #4: gate the enqueue on connectivity — offline, give immediate feedback and skip the
            // enqueue (native parity: a download started offline is a no-op).
            if (!state.value.isOnline) {
                emit(DetailsEffect.ShowError(AppError.Network.NoConnectivity()))
                return@launchSafely
            }
            enqueueChapterDownload(chapterUrl = chapter.url, mangaTitle = title, api = api)
                .onFailure { t -> emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t))) }
        }
    }

    /**
     * Auto-route a Cloudflare-failed DOWNLOAD to the existing WebView solver (downloads parity with the
     * reading path). The engine stamps [DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL] on a
     * challenge-class resolve failure; here we collect those FAILED chapter urls (for the displayed
     * manga) and emit the SAME [DetailsEffect.SolveCloudflareChallenge] effect, reusing the bounded
     * [cloudflareAttempts] guard. One emit per batch (de-duped via [cloudflareFailedDownloadUrls]):
     * solving once refreshes the source cookies, so the remaining queued chapters self-heal on their
     * next resolve. The set self-prunes (retainAll) as rows leave FAILED, and [onRetry] re-enqueues it
     * on WebView dismiss.
     */
    private suspend fun maybeSolveCloudflareForFailedDownloads(rows: List<DownloadedChapter>) {
        val manga = state.value.manga ?: return
        val displayed = state.value.details?.chapters?.mapTo(HashSet()) { it.url } ?: return
        val failedUrls = rows.asSequence()
            .filter { it.state == DownloadState.FAILED && it.errorMsg == DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL }
            .map { it.url }
            .filter { it in displayed }
            .toList()
        // Self-prune: drop urls that recovered / are no longer Cloudflare-failed so a later genuine
        // failure can re-trigger (and so a re-enqueued row isn't re-counted).
        cloudflareFailedDownloadUrls.retainAll(failedUrls.toHashSet())
        val fresh = failedUrls.filter { it !in cloudflareFailedDownloadUrls }
        if (fresh.isEmpty()) return
        val wasEmpty = cloudflareFailedDownloadUrls.isEmpty()
        cloudflareFailedDownloadUrls.addAll(fresh)
        // Open the solver once per batch, bounded exactly like the fetch path (no WebView loop on a
        // persistent/unsolvable challenge); reset on a successful fetch via runFetch.
        if (wasEmpty && cloudflareAttempts < MAX_CLOUDFLARE_ATTEMPTS) {
            cloudflareAttempts++
            emit(DetailsEffect.SolveCloudflareChallenge(url = manga.url, api = manga.api))
        }
    }

    /**
     * Re-enqueue the downloads that failed on a Cloudflare challenge, now that a WebView solve refreshed
     * the source cookies (the download analogue of [onRetry]'s re-fetch). [enqueueChapterDownload] is
     * idempotent — it drops the stale manifest, resets attempt counts, and re-queues — and the engine
     * then resolves with the fresh cookies. The pending set is NOT cleared here; it self-prunes via
     * [maybeSolveCloudflareForFailedDownloads] once each row leaves FAILED, which avoids a re-emit race.
     * No-op when nothing is pending, so calling it from the shared [onRetry] is safe for plain retries.
     */
    private fun retryCloudflareFailedDownloads() {
        if (cloudflareFailedDownloadUrls.isEmpty()) return
        val title = state.value.details?.title ?: return
        val api = state.value.manga?.api ?: return
        val urls = cloudflareFailedDownloadUrls.toList()
        launchSafely {
            urls.forEach { url ->
                enqueueChapterDownload(chapterUrl = url, mangaTitle = title, api = api)
                    .onFailure { /* best-effort; the row stays FAILED (and pending) if it can't re-queue */ }
            }
        }
    }

    /**
     * Single-chapter download cancel (GAP-LIB-03). A RUNNING/COMPRESSING chapter holds the active
     * worker/coroutine, so it must be INTERRUPTED via [cancelRunningDownload] (which stops the
     * worker, deletes partial files, and re-enqueues the rest) — the queue-prune
     * [cancelChapterDownload] only marks the row FAILED and would leave the worker running. A QUEUED
     * chapter isn't active, so pruning it is correct (the worker simply skips the failed row).
     */
    private fun onCancelChapterDownload(chapter: Chapter) {
        launchSafely {
            val row = downloadRowsByUrl[chapter.url]
            val result =
                if (row != null &&
                    (row.state == DownloadState.RUNNING || row.state == DownloadState.COMPRESSING)
                ) {
                    cancelRunningDownload(row.chapterId, row.mangaId)
                } else {
                    cancelChapterDownload(chapter.url)
                }
            result.onFailure { t -> emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t))) }
        }
    }

    /** Long-press a chapter → enter multi-select with this chapter selected (GAP-LIB-10). */
    private fun onChapterLongClick(chapter: Chapter) {
        updateState { it.copy(selectedChapterUrls = it.selectedChapterUrls + chapter.url) }
    }

    /** Toggle a chapter's membership in the selection set (GAP-LIB-10). */
    private fun onSelectionToggle(chapter: Chapter) {
        updateState {
            val next = if (chapter.url in it.selectedChapterUrls) {
                it.selectedChapterUrls - chapter.url
            } else {
                it.selectedChapterUrls + chapter.url
            }
            it.copy(selectedChapterUrls = next)
        }
    }

    /**
     * Multi-select "mark read" — bulk-mark the selected set read, then clear selection.
     *
     * P3-LOW parity note (native `ChapterSelectionActionsRow.onMarkAllRead` →
     * `LibraryDetailsViewModel.toggleChaptersRead`): native's bulk action *toggles* the read flag of
     * each selected chapter (flips read↔unread). The rework deliberately makes this a one-way
     * "mark all read" via [MarkChaptersReadUseCase] instead. Rationale (Intentionally-different):
     *  - The icon + content description on the `:ui` selection bar is RemoveRedEye / "mark all read"
     *    (matching native's glyph) — a label that reads as a one-way action, not a toggle, so a
     *    toggle would surprise the user who selected a mixed read/unread set expecting "make these
     *    read".
     *  - Per-chapter un-reading is still available through the per-row RemoveRedEye toggle
     *    ([ToggleChapterReadUseCase] on the `:ui` ChapterRow), so no capability is lost.
     *  - A true bulk *toggle* would need a new `toggleRead(List<String>)` repository/use-case path
     *    in `:domain`/`:data` (only `markRead(List<String>)` and single-url `toggleRead` exist);
     *    looping the single-url toggle over a mixed selection would yield an inconsistent
     *    half-read/half-unread result — strictly worse than both native and this one-way mark.
     * The one-way mark is idempotent and predictable; the divergence is recorded here as the
     * deliberate decision.
     */
    private fun onMarkSelectedRead() {
        if (!state.value.isInLibrary) return
        val selected = state.value.selectedChapterUrls.toList()
        if (selected.isEmpty()) return
        launchSafely { markChaptersRead(selected) }
        updateState { it.copy(selectedChapterUrls = emptySet()) }
    }

    /**
     * Multi-select "download" — enqueue each selected chapter, then clear selection. Mirrors the
     * sibling [onDownloadChapter] / native `onCustomDownload` (LibraryMangaRoute.kt:206-222): gates
     * on connectivity (offline → immediate feedback, no enqueue) and skips chapters that are already
     * downloaded so a range covering downloaded chapters doesn't re-fetch them.
     */
    private fun onDownloadSelected() {
        if (!state.value.isInLibrary) return
        val title = state.value.details?.title ?: return
        val api = state.value.manga?.api ?: return
        val selected = state.value.selectedChapterUrls
        if (selected.isEmpty()) return
        // Drop already-downloaded chapters (native filters `chapters.filter { !it.isDownloaded }`
        // before enqueueing — re-enqueuing a SUCCESS row demotes it to QUEUED and re-fetches files).
        val toDownload = state.value.displayChapters
            .filter { it.url in selected && !it.isDownloaded }
            .map { it.url }
        launchSafely {
            // #4: same offline gate as the single-chapter / download-all paths (native parity).
            if (!state.value.isOnline) {
                emit(DetailsEffect.ShowError(AppError.Network.NoConnectivity()))
                return@launchSafely
            }
            toDownload.forEach { url ->
                enqueueChapterDownload(chapterUrl = url, mangaTitle = title, api = api)
                    .onFailure { t -> emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t))) }
            }
        }
        updateState { it.copy(selectedChapterUrls = emptySet()) }
    }

    /**
     * Multi-select "bookmark all" (L-4, native `ChapterSelectionActionsRow` onBookmarkAll). Toggles
     * the bookmark flag on each selected chapter via [ToggleChapterBookmarkUseCase], then clears the
     * selection. Gated on in-library; the use case no-ops for a chapter with no saved row.
     */
    private fun onBookmarkSelected() {
        if (!state.value.isInLibrary) return
        val selected = state.value.selectedChapterUrls.toList()
        if (selected.isEmpty()) return
        launchSafely {
            selected.forEach { url -> toggleChapterBookmark(url) }
        }
        updateState { it.copy(selectedChapterUrls = emptySet()) }
    }

    /**
     * Multi-select "delete downloaded" (L-4, native `ChapterSelectionActionsRow` onDeleteAll). The
     * `:ui` bar only surfaces this when every selected chapter is downloaded; the VM resolves each
     * url → Room id and FULLY deletes the download (clears isDownloaded + deletes files + drops the
     * queue row), matching native. Clears the selection.
     */
    private fun onDeleteSelectedDownloads() {
        val selected = state.value.selectedChapterUrls.toList()
        if (selected.isEmpty()) return
        launchSafely {
            selected.forEach { url ->
                val id = resolveChapterId(url) ?: return@forEach
                deleteDownloadedChapter(id)
                    .onFailure { t -> emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t))) }
            }
        }
        updateState { it.copy(selectedChapterUrls = emptySet()) }
    }

    /**
     * Per-row "delete chapter from the database" (the row's trash button, user-requested). Removes
     * the chapter's `saved_chapters` record AND its download. Order matters: clean the download
     * FIRST ([deleteDownloadedChapter] reads the chapter row's mangaId to locate the on-disk files),
     * THEN delete the chapter row ([deleteChapter]) — but only if the cleanup succeeded, so a failed
     * file/flag cleanup can't orphan files under a deleted `saved_chapters` row. Gated on in-library
     * (a non-library manga has no row); the reactive saved-details flow re-emits without the chapter,
     * so it drops out of the list. For a source-backed manga a later refresh may re-discover it.
     */
    private fun onDeleteChapter(chapter: Chapter) {
        if (!state.value.isInLibrary) return
        launchSafely {
            val id = resolveChapterId(chapter.url) ?: return@launchSafely
            // 1) Clean the download (clear isDownloaded + files + chapter_downloads row); no-op if not
            //    downloaded. Abort before deleting the saved_chapters row if cleanup failed, so files
            //    are never orphaned under a missing referencing row.
            deleteDownloadedChapter(id)
                .onFailure { t ->
                    emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t)))
                    return@launchSafely
                }
            // 2) Delete the saved_chapters record itself.
            deleteChapter(id)
        }
    }

    /**
     * Multi-select "mark this and below as read" (L-4, native `ChapterSelectionActionsRow`
     * onMarkAllDownRead, single-selection only). Marks the selected chapter and every chapter below
     * it in the *displayed* reading order as read, then clears the selection. Gated on in-library.
     */
    private fun onMarkSelectedDownRead() {
        if (!state.value.isInLibrary) return
        val selected = state.value.selectedChapterUrls
        if (selected.size != 1) return
        val targetUrl = selected.first()
        val displayed = state.value.displayChapters
        val index = displayed.indexOfFirst { it.url == targetUrl }
        if (index < 0) return
        // "This and below" marks everything AFTER the selected chapter in displayed order, EXCLUSIVE
        // of the tapped chapter itself — matching native (onMarkAllDownRead marks subList(0, idx) over
        // the reversed list, i.e. the suffix-after-selected; it does not mark the tapped chapter).
        val urls = displayed.subList(index + 1, displayed.size).map { it.url }
        if (urls.isEmpty()) return
        launchSafely { markChaptersRead(urls) }
        updateState { it.copy(selectedChapterUrls = emptySet()) }
    }

    /**
     * Top-bar "delete all downloaded chapters" (L-7, native `MangaTopAppBar` onDeleteDownloads).
     * FULLY deletes the download for every downloaded chapter of the fetched details (clears
     * isDownloaded + deletes files + drops the queue row), matching native. Gated on in-library.
     */
    private fun onDeleteAllDownloads() {
        if (!state.value.isInLibrary) return
        val downloaded = state.value.details?.chapters?.filter { it.isDownloaded } ?: return
        if (downloaded.isEmpty()) return
        launchSafely {
            downloaded.forEach { chapter ->
                val id = resolveChapterId(chapter.url) ?: return@forEach
                deleteDownloadedChapter(id)
                    .onFailure { t -> emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t))) }
            }
        }
    }

    /**
     * Top-bar "cancel all downloads" (L-7, native `MangaTopAppBar` Stop icon → cancelAllDownloads).
     * Cancels every currently-active download among this manga's chapters (the url set the
     * downloads queue projects into [DetailsState.downloadingChapterUrls]).
     */
    private fun onCancelAllDownloads() {
        if (state.value.downloadingChapterUrls.isEmpty()) return
        launchSafely {
            // Single bulk-cancel that stops the worker + marks all in-flight rows FAILED. The
            // previous per-chapter prune loop never interrupted the active worker (audit
            // details-cancel-all-does-not-stop-worker).
            cancelAllDownloads()
                .onFailure { t -> emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t))) }
        }
    }
}

/**
 * Identity comparison on the rework's composite key (api + language + title). Mirrors the
 * legacy `SavedMangaEntity` primary-key composition documented on [Manga].
 */
private fun Manga.matches(other: Manga): Boolean =
    api == other.api && language == other.language && title == other.title

/**
 * Overlay the locally-persisted chapter state from [saved] onto this (network) [MangaDetails],
 * matching chapters by [me.manga.kira.domain.model.Chapter.url]. The network list stays the
 * base (its ordering + any newly-published chapters win); for each network chapter that also
 * exists in [saved], the user-state flags (`isRead` / `isDownloaded` / `isBookmarked` / `isNew`)
 * are taken **directly** from the saved Room row, which is the source of truth. Chapters absent
 * from [saved] keep their network defaults. Used so a network refresh never discards local read
 * progress (regression fix, 2026-05-31).
 *
 * These flags are assigned directly (NOT OR-ed with the base). The collector overlays each saved
 * re-emission onto `current.details` — which is itself the *previously-overlaid* result — so OR-ing
 * made `isDownloaded` / `isBookmarked` / `isNew` "sticky": once true they could never go back to
 * false on a later re-emission, so deleting a download (or un-bookmarking, or opening a NEW chapter)
 * didn't update the row until the screen was left and re-entered with a fresh fetch. Saved Room
 * state is authoritative for all four flags (the network DTO always leaves them false), so a direct
 * assignment is both correct and reactive (regression fix, 2026-06-02).
 */
private fun MangaDetails.overlaidWith(saved: MangaDetails): MangaDetails {
    if (saved.chapters.isEmpty()) return this
    // An empty successful transport payload must not erase a non-empty last-known-good list on
    // screen. Azora exposed this when chapters became opt-in: refresh showed zero chapters, while
    // reopening restored the Room list. Keep fresh metadata, but retain prior chapters until a
    // verified non-empty list arrives. Explicit local chapter deletion remains a separate action.
    val networkOrLastKnownGood =
        if (chapters.isEmpty()) saved.chapters else chapters
    val savedByUrl = saved.chapters.associateBy { it.url }
    return copy(
        chapters =
            networkOrLastKnownGood.map { chapter ->
                val s = savedByUrl[chapter.url] ?: return@map chapter
                chapter.copy(
                    isRead = s.isRead,
                    isDownloaded = s.isDownloaded,
                    isBookmarked = s.isBookmarked,
                    // Persisted NEW-chapter flag from Room (the network chapter is always isNew=false).
                    // The library-refresh worker sets it; ChapterDao.markChapterIsNew clears it on open.
                    // (native LibraryDetails likewise shows NEW from the saved row.)
                    isNew = s.isNew,
                    // Discovery timestamp from the saved row, driving the 4-day badge expiry below.
                    fetchedAt = s.fetchedAt,
                )
            },
    )
}

/** 4 days in milliseconds — the window the NEW badge stays visible after discovery if unopened. */
internal const val NEW_BADGE_WINDOW_MS: Long = 4L * 24 * 60 * 60 * 1000

/**
 * Read-time NEW-badge expiry (#3, deliberate deviation from native, which has no expiry). Forces
 * `isNew = false` on any chapter whose discovery timestamp ([Chapter.fetchedAt]) is older than
 * [NEW_BADGE_WINDOW_MS] (or unknown, i.e. `0`), so the badge auto-disappears 4 days after discovery
 * even if the chapter was never opened. The persisted `isNew` flag is untouched (the badge is
 * re-evaluated against the clock on every emission); an explicit clear-on-open still wins immediately.
 */
internal fun MangaDetails.expireNewBadges(nowMs: Long): MangaDetails {
    if (chapters.none { it.isNew }) return this
    return copy(
        chapters = chapters.map { c ->
            if (c.isNew && !isWithinNewWindow(c.fetchedAt, nowMs)) c.copy(isNew = false) else c
        },
    )
}

private fun isWithinNewWindow(fetchedAt: Long, nowMs: Long): Boolean =
    fetchedAt > 0L && (nowMs - fetchedAt) in 0L until NEW_BADGE_WINDOW_MS

/** Current wall-clock in epoch-millis for the read-time badge-expiry evaluation. */
@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
