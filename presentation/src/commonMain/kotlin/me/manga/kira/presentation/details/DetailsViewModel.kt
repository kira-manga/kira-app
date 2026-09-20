package me.manga.kira.presentation.details

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.FlowLog
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.onFailure
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.model.library.SavedWorkDetails
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
import me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveCompressionDeferredUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.MarkMangaOpenedUseCase
import me.manga.kira.domain.usecase.library.ObserveInLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.reader.MarkChaptersReadUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterBookmarkUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterReadUseCase
import me.manga.kira.presentation.cloudflare.isCloudflareChallenge
import me.manga.kira.presentation.mvi.MviViewModel

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
 * matches the intent's requested work (api + raw URL), regardless of title/language changes. This means a configuration
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
 * Fetched results for a saved owner are re-resolved and persisted under the library writer before
 * rendering. A deleted/replaced owner or conflicting alias is an explicit failure, never a re-add.
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
    private val enqueueDownload: EnqueueDownloadUseCase,
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
    // On a saved-owner refresh, await newly-discovered chapters (isNew=true + fetchedAt=now)
    // and metadata commit. A missing/replaced owner fails; an unsaved fetch does not call this.
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

    private val requestFence = DetailsRequestFence()
    private var fetchedSnapshot: MangaDetails? = null
    private var savedSnapshot: SavedWorkDetails? = null
    private var libraryObservation = 0L

    /** Cancel the previous request before any new work can reuse this ViewModel. */
    private var fetchJob: Job? = null

    /**
     * Offline/local saved-details collector job (regression fix, 2026-05-31). Restarted on every
     * identity change so a saved manga renders its Room-persisted chapter list + read/downloaded/
     * bookmark marks immediately (and offline), instead of "looking fresh" while the network fetch
     * runs. Cancelled implicitly when [viewModelScope] is cancelled on `onCleared`.
     */
    private var savedDetailsJob: Job? = null

    private var downloadsJob: Job? = null
    private var downloadsGeneration = 0L
    private var cloudflareRetryJob: Job? = null

    /**
     * PFIX-DLPROGRESS (2026-06-01) + completion-freeze fix (2026-06-02): the most recent download
     * rows captured from [ObserveDownloadsUseCase] for the exact owning manga URL, then keyed by
     * chapter `url` (`DownloadedChapter.url`).
     * Carries the live [DownloadState] + 0-100 `progress` + `sizeBytes` for each QUEUED / RUNNING /
     * COMPRESSING (active) AND SUCCESS (completed) row; FAILED rows are excluded so the chapter shows
     * the idle Download button to retry.
     *
     * Keyed by `url` (not Room id) so [recomputeChapterDownloads] can join onto the url-keyed
     * displayed [Chapter] list SYNCHRONOUSLY — no per-row suspend `ChapterIdResolver` round-trip, so
     * the per-chapter status map is written in one [updateState] and the running→downloaded
     * transition is atomic (the SUCCESS row arrives in the same downloads emission that dropped
     * RUNNING). Recomputed on every downloads tick and every displayed-list change. The subscription,
     * cache and pending challenge retries are replaced together on each owner bind.
     */
    private var downloadRowsByUrl: Map<String, ChapterDownloadProgress> = emptyMap()

    /** Successful local deletions stay hidden for this visit until a later fetch rediscovers them. */
    private val chapterRetractions = DetailsChapterRetractions()

    private val challengeRecovery = DetailsChallengeRecovery()

    init {
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

    /** Capture when opening the solver; only this opaque request may retry on browser return. */
    fun cloudflareRecoveryRequestId(
        url: String,
        api: String,
    ): String? =
        challengeRecovery.requestId.takeIf { state.value.manga?.let { it.url == url && it.api == api } == true }

    override suspend fun handle(intent: DetailsIntent) {
        when (intent) {
            is DetailsIntent.OnEnter -> onEnter(intent.manga)
            is DetailsIntent.OnEnterByUrl -> onEnterByUrl(intent.api, intent.mangaUrl)
            DetailsIntent.OnRetry -> onRetry()
            is DetailsIntent.OnCloudflareSolverReturned -> onCloudflareSolverReturned(intent.requestId)
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
                            state.value.savedOwner?.locator ?: return,
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

    private suspend fun onEnter(manga: Manga) = enterWork(manga, logAnalytics = true)

    private suspend fun enterWork(manga: Manga, logAnalytics: Boolean) {
        val request = requestFence.enter(manga) ?: return
        fetchJob?.cancel()
        fetchJob = null
        savedDetailsJob?.cancel()
        fetchedSnapshot = null
        savedSnapshot = null
        chapterRetractions.clearVisit()
        updateState { it.enteringWork(manga, isAdultContent(manga)) }
        startObservingDownloads(manga)
        if (logAnalytics) logMangaOpen(api = manga.api, title = manga.title)
        withLoadingRequest(request) {
            val cached = shouldOpenFromCache(request)
            if (!requestFence.accepts(request)) return@withLoadingRequest
            startObservingSavedDetails(request)
            if (!cached) runFetch(request, manga)
        }
    }

    /** Cache-only requires a successful scoped read with chapters, not a display-title match. */
    private suspend fun shouldOpenFromCache(request: DetailsRequestToken): Boolean =
        try {
            val saved = observeSavedDetails(request.work).firstOrNull()
            currentCoroutineContext().ensureActive()
            if (saved == null || !requestFence.accepts(request)) {
                false
            } else {
                acceptSavedDetails(request, saved)
                (saved as? AppResult.Success)?.value?.details?.chapters?.isNotEmpty() == true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            libraryFailure(request, AppError.Storage.Io(failure))
            false
        }

    private fun startObservingSavedDetails(request: DetailsRequestToken) {
        savedDetailsJob?.cancel()
        savedDetailsJob = observeSavedDetails(request.work)
            .onEach {
                currentCoroutineContext().ensureActive()
                acceptSavedDetails(request, it)
            }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                libraryFailure(request, AppError.Storage.Io(failure))
            }
            .launchIn(viewModelScope)
    }

    private fun acceptSavedDetails(request: DetailsRequestToken, result: AppResult<SavedWorkDetails?>) {
        if (!requestFence.accepts(request)) return
        when (result) {
            is AppResult.Failure -> libraryFailure(request, result.error)
            is AppResult.Success -> acceptSavedSnapshot(request, result.value)
        }
    }

    private fun acceptSavedSnapshot(request: DetailsRequestToken, snapshot: SavedWorkDetails?) {
        val previous = state.value
        val previousOwner = savedSnapshot?.owner ?: previous.savedOwner
        val ownerChanged = snapshot?.owner?.id != previousOwner?.id
        if (ownerChanged) {
            fetchedSnapshot = null
            chapterRetractions.clearVisit()
        }
        // A hidden row must not remain in the flag cache and return when rediscovery releases it.
        val saved = snapshot?.let { it.copy(details = it.details.withoutChapterUrls(chapterRetractions.urls)) }
        if (saved != null && saved == savedSnapshot && previous.libraryError == null) return
        libraryObservation++
        savedSnapshot = saved
        updateState { it.withLibraryOwner(saved?.owner?.copy(locator = request.work)) }
        if (saved != null) displaySavedDetails(saved.details, previous.details.takeUnless { ownerChanged })
    }

    private fun displaySavedDetails(saved: MangaDetails, previous: MangaDetails?) {
        val network = fetchedSnapshot
        val details = when {
            network != null -> network.overlaidWith(saved)
            saved.chapters.isEmpty() && previous != null -> saved.overlaidWith(previous)
            else -> saved
        }
        displayDetails(details)
    }

    private suspend fun onEnterByUrl(api: String, mangaUrl: String) = enterWork(
        Manga(
            api = api,
            language = "",
            title = "",
            url = mangaUrl,
            coverUrl = "",
            rating = null,
            genres = emptyList(),
        ),
        logAnalytics = false,
    )

    private fun libraryFailure(request: DetailsRequestToken, failure: AppError) {
        if (!requestFence.accepts(request)) return
        libraryObservation++
        updateState { it.withLibraryFailure(failure) }
    }

    private fun displayDetails(details: MangaDetails) {
        val manga = state.value.manga ?: return
        val visible = details.withoutChapterUrls(chapterRetractions.urls)
        val adult = isAdultContent(manga.copy(genres = visible.genres))
        updateState { it.showWorkDetails(visible, adult).copy(isLoading = fetchJob?.isActive == true) }
        recomputeChapterDownloads()
    }

    private suspend fun onRetry() {
        if (state.value.isLoading || fetchJob?.isActive == true) return
        val request = requestFence.current ?: return
        val manga = state.value.manga ?: return
        withLoadingRequest(request) {
            // Reserve loading before a download retry can suspend. It does not replenish either
            // operation's solver budget, and a second refresh cannot overlap this request.
            retryCloudflareFailedDownloads(request)
            currentCoroutineContext().ensureActive()
            if (requestFence.accepts(request)) runFetch(request, manga)
        }
    }

    private suspend fun withLoadingRequest(request: DetailsRequestToken, block: suspend () -> Unit) {
        if (!requestFence.accepts(request)) return
        val job = currentCoroutineContext().job
        fetchJob = job
        updateState { it.copy(isLoading = true, error = null) }
        try {
            block()
        } finally {
            if (requestFence.accepts(request) && fetchJob == job) {
                fetchJob = null
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun runFetch(request: DetailsRequestToken, manga: Manga) {
        challengeRecovery.begin(DetailsChallengeOperation.Metadata)
        val membership = retainedOwner(request)
        currentCoroutineContext().ensureActive()
        if (!requestFence.accepts(request)) return
        val owner = when (membership) {
            is AppResult.Success -> membership.value
            is AppResult.Failure -> {
                libraryFailure(request, membership.error)
                fetchFailed(request, membership.error)
                return
            }
        }
        val retractedBeforeFetch = chapterRetractions.snapshot
        val result = fetchDetails(manga)
        currentCoroutineContext().ensureActive()
        if (!requestFence.accepts(request)) return
        // The same requested address can now belong to a different saved parent. Neither an
        // old fetch failure/solver effect nor its success may repaint that replacement.
        if (state.value.libraryError != null || state.value.savedOwner?.id != owner?.id) return
        when (result) {
            is AppResult.Success -> acceptFetchedDetails(request, owner, result.value, retractedBeforeFetch)
            is AppResult.Failure -> fetchFailed(request, result.error)
        }
    }

    private suspend fun retainedOwner(request: DetailsRequestToken): AppResult<SavedWorkIdentity?> =
        try {
            val current = state.value
            val error = current.libraryError
            val owner = current.savedOwner
            when {
                error != null -> AppResult.Failure(error)
                owner != null -> AppResult.Success(owner)
                else -> {
                    // UI membership is not authority: await an explicit scoped owner before the
                    // fetch. A newer saved observation wins over this suspended one-shot lookup.
                    val observed = libraryObservation
                    val result = observeInLibrary(request.work).first()
                    currentCoroutineContext().ensureActive()
                    if (requestFence.accepts(request)) {
                        if (observed != libraryObservation) {
                            state.value.libraryError?.let { AppResult.Failure(it) }
                                ?: AppResult.Success(state.value.savedOwner)
                        } else {
                            if (result is AppResult.Success) updateState { it.withLibraryOwner(result.value) }
                            result
                        }
                    } else {
                        result
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppResult.Failure(AppError.Storage.Io(failure))
        }

    private suspend fun acceptFetchedDetails(
        request: DetailsRequestToken,
        owner: SavedWorkIdentity?,
        fetched: MangaDetails,
        retractedBeforeFetch: Map<String, Long>,
    ) {
        currentCoroutineContext().ensureActive()
        if (!requestFence.accepts(request)) return
        if (state.value.libraryError != null || state.value.savedOwner?.id != owner?.id) return
        val prepared = chapterRetractions.previewFetch(fetched, retractedBeforeFetch)
        val refreshed = owner?.let {
            persistNewChapters(LibraryRefreshRequest(it, FetchedWorkDetails(request.work, prepared)))
        }
        currentCoroutineContext().ensureActive()
        if (!requestFence.accepts(request)) return
        // Neither old-owner outcome may alter an already-observed replacement or failure.
        if (state.value.libraryError != null || state.value.savedOwner?.id != owner?.id) return
        if (refreshed is AppResult.Failure) {
            libraryFailure(request, refreshed.error)
            fetchFailed(request, refreshed.error)
            return
        }
        // Release rediscovery only after an accepted commit. Recheck versions because a newer
        // explicit deletion may have completed while the writer was suspended.
        val details = chapterRetractions.acceptFetch(fetched, retractedBeforeFetch)
        val previous = fetchedSnapshot ?: state.value.details
        val network = if (details.chapters.isEmpty() && previous != null) details.overlaidWith(previous) else details
        fetchedSnapshot = network
        val saved = savedSnapshot?.takeIf { owner == null || it.owner.id == owner.id }?.details
        displayDetails(if (saved == null) network else network.overlaidWith(saved))
        challengeRecovery.metadataRecovered()
    }

    private suspend fun fetchFailed(request: DetailsRequestToken, error: AppError) {
        currentCoroutineContext().ensureActive()
        if (!requestFence.accepts(request)) return
        updateState { it.copy(isLoading = false, error = error) }
        val manga = state.value.manga ?: return
        if (error.isCloudflareChallenge() && challengeRecovery.request(DetailsChallengeOperation.Metadata)) {
            emit(DetailsEffect.SolveCloudflareChallenge(url = manga.url, api = manga.api))
        } else {
            emit(DetailsEffect.ShowError(error))
        }
    }

    private suspend fun onCloudflareSolverReturned(requestId: String) {
        val request = requestFence.current ?: return
        when (challengeRecovery.consume(requestId)) {
            DetailsChallengeOperation.Metadata -> {
                if (state.value.isLoading || fetchJob?.isActive == true) return
                val manga = state.value.manga ?: return
                withLoadingRequest(request) { runFetch(request, manga) }
            }
            DetailsChallengeOperation.Downloads -> retryCloudflareFailedDownloads(request)
            null -> Unit // An old owner, recovered operation or consumed return owns no work.
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
        // This drives the Library LAST_READ sort. Only a captured saved owner can be updated;
        // the writer rejects removal/replacement rather than looking up by display title.
        state.value.savedOwner?.let { owner -> launchSafely { markMangaOpened(owner) } }
        // #3 NEW-badge parity: clear the badge the instant the chapter is OPENED (native clears on
        // chapter click), not later when the reader advances past it. Does NOT mark the chapter read
        // (opening != reading). Fire-and-forget; no-op for a non-saved chapter. The saved-details
        // flow re-emits and the badge clears reactively.
        launchSafely { clearChapterNew(manga, intent.chapter.url) }
        emit(DetailsEffect.NavigateToReader(manga = manga, chapter = intent.chapter))
    }

    /**
     * "Download all" handler (legacy `HeaderSection` `action_download_all` parity). Enqueues every
     * chapter of the fetched [MangaDetails] for offline download via
     * [EnqueueAllChaptersDownloadUseCase], which composes the same per-chapter enqueue path the
     * rework Updates download button uses (Tasks #299/#300) with a `url` → Room-`chapterId`
     * resolution step (the pure-domain [me.manga.kira.domain.model.Chapter] carries `url`, not
     * the surrogate id the download subsystem keys on). Chapters with no in-library row are
     * skipped. An owner-bound completion predicate excludes effectively downloaded chapters at the
     * enqueue boundary, including live SUCCESS before the saved flag catches up. The library gate
     * remains a UI rule: `:ui` shows the button only when `state.isInLibrary`.
     *
     * No-op when `state.details` is null (the button is only reachable from the success state, but
     * the null-guard keeps the handler safe against a stray dispatch during a state transition).
     *
     * Fire-and-forget in [viewModelScope] (same posture as the Updates download button): the
     * `handle` suspend returns immediately while the use case runs the resolve+enqueue loop on its
     * own background dispatcher. The reactive Downloads list updates flow back through the existing
     * `DownloadsRepository.observeForManga()` Room flow, so no imperative state mutation is needed here.
     * A use-case-level failure surfaces via the existing [DetailsEffect.ShowError] snackbar.
     */
    private fun onDownloadAllClick() {
        val current = state.value
        val manga = current.manga ?: return
        val details = current.details ?: return
        launchSafely {
            // #4: same offline gate as the single-chapter download path.
            if (!state.value.isOnline) {
                emit(DetailsEffect.ShowError(AppError.Network.NoConnectivity()))
                return@launchSafely
            }
            enqueueAllChaptersDownload(
                manga = manga,
                details = details,
                shouldEnqueue = { chapter -> !isDownloadedForAction(current, chapter.url) },
            ).onFailure { t ->
                emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t)))
            }
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
     * lets the scoped saved-details flow re-emission drive [DetailsState.isInLibrary] —
     * matches the [me.manga.kira.presentation.library.LibraryViewModel] reactive posture.
     *
     * Race gate ([DetailsState.isTogglingBookmark], per `DetailsState` KDoc + §253 plan): the
     * retained owner goes directly to removal; an unsaved request checks membership before adding.
     * A rapid double-tap could otherwise fire concurrent invocations before the saved flow snaps
     * `isInLibrary`, racing opposite user actions against the owning writer.
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
        val request = requestFence.current ?: return
        val snapshot = state.value
        val manga = snapshot.manga ?: return
        if (snapshot.isTogglingBookmark) return
        snapshot.libraryError?.let { emit(DetailsEffect.ShowError(it)); return }
        updateState { it.copy(isTogglingBookmark = true) }
        try {
            toggleInLibrary(manga, snapshot.details, snapshot.savedOwner)
                .onFailure { error ->
                    if (requestFence.accepts(request)) emit(DetailsEffect.ShowError(error))
                }
        } finally {
            if (requestFence.accepts(request)) updateState { it.copy(isTogglingBookmark = false) }
        }
    }

    // ---- GAP-LIB-02/03 per-chapter library management ----------------------------------------

    private fun startObservingDownloads(manga: Manga) {
        downloadsJob?.cancel()
        cloudflareRetryJob?.cancel()
        val generation = ++downloadsGeneration
        downloadRowsByUrl = emptyMap()
        challengeRecovery.clearOwner()
        // Scope in Room before forming a URL map. Chapter URLs can legally repeat under another
        // saved manga; neither progress nor the direct cancel IDs may come from the global queue.
        downloadsJob =
            observeDownloads(manga)
                .onEach { rows ->
                    if (generation != downloadsGeneration || state.value.manga?.matches(manga) != true) return@onEach
                    downloadRowsByUrl = rows.toProgressByUrl()
                    recomputeChapterDownloads()
                    // FAILED stays out of the UI map, but this owner's challenge failures can request
                    // the WebView solver and a scoped retry when it returns.
                    maybeSolveCloudflareForFailedDownloads(manga, rows)
                }.catch { /* Downloads indicator is best-effort; the chapter list still works without it. */ }
                .launchIn(viewModelScope)
    }

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
     * The diff guard avoids redundant emissions when this owner's chapter progress is unchanged.
     */
    private fun recomputeChapterDownloads() {
        val current = state.value
        val generation = downloadsGeneration
        val chapters = current.details?.chapters.orEmpty()
        val rowsByUrl = downloadRowsByUrl
        val byUrl = chapterDownloadsFor(chapters, rowsByUrl)
        if (byUrl != current.chapterDownloads) {
            updateState { latest ->
                if (generation == downloadsGeneration && latest.manga?.let { current.manga?.matches(it) } == true) {
                    latest.copy(chapterDownloads = byUrl)
                } else {
                    latest
                }
            }
        }
    }

    /** Single-chapter read toggle (GAP-LIB-02). Gated on in-library; reactive flow re-renders. */
    private fun onToggleChapterRead(chapter: Chapter) {
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        launchSafely { toggleChapterRead(manga, chapter.url) }
    }

    /**
     * Single-chapter bookmark toggle (native `LibraryMangaScreen` per-chapter bookmark icon →
     * `toggleChapterBookmark`). Gated on in-library; the use case no-ops for a chapter with no saved
     * row, and the reactive saved-details flow re-emits the new flag so the row re-renders.
     */
    private fun onToggleChapterBookmark(chapter: Chapter) {
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        launchSafely { toggleChapterBookmark(manga, chapter.url) }
    }

    /** Explicit actions keep their owner on reentry, but use that owner's latest loaded completion. */
    private fun isDownloadedForAction(
        captured: DetailsState,
        chapterUrl: String,
    ): Boolean {
        val latest = state.value
        // Cached details remain loaded during refresh; a new entry's null-details shell does not.
        val snapshot =
            if (latest.manga?.let { captured.manga?.matches(it) } == true && latest.details != null) latest else captured
        return snapshot.isChapterDownloaded(chapterUrl)
    }

    /** Single-chapter download enqueue (GAP-LIB-03). Gated on in-library. */
    private fun onDownloadChapter(chapter: Chapter) {
        val current = state.value
        if (!current.isInLibrary || current.details == null) return
        val manga = current.manga ?: return
        launchSafely {
            // #4: gate the enqueue on connectivity — offline, give immediate feedback and skip the
            // enqueue (native parity: a download started offline is a no-op).
            if (!state.value.isOnline) {
                emit(DetailsEffect.ShowError(AppError.Network.NoConnectivity()))
                return@launchSafely
            }
            val chapterId = resolveChapterId(manga, chapter.url) ?: return@launchSafely
            if (isDownloadedForAction(current, chapter.url)) return@launchSafely
            enqueueDownload(chapterId = chapterId, mangaTitle = manga.title, api = manga.api)
                .onFailure { t ->
                    emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t)))
                }
        }
    }

    /** One bounded solver per unresolved owner-scoped batch; metadata success is not recovery. */
    private suspend fun maybeSolveCloudflareForFailedDownloads(
        manga: Manga,
        rows: List<DownloadedChapter>,
    ) {
        val current = state.value
        if (current.manga?.matches(manga) != true) return
        val displayed = current.details?.chapters?.mapTo(HashSet()) { it.url } ?: return
        if (!challengeRecovery.observeDownloads(rows, displayed)) return
        if (challengeRecovery.request(DetailsChallengeOperation.Downloads)) {
            emit(DetailsEffect.SolveCloudflareChallenge(url = manga.url, api = manga.api))
        } else {
            emit(DetailsEffect.ShowError(DOWNLOAD_CHALLENGE_ERROR))
        }
    }

    /**
     * Re-enqueue the downloads that failed on a Cloudflare challenge, now that a WebView solve refreshed
     * the source cookies. Re-queueing is not proof of recovery: the batch budget survives until its
     * unresolved chapters actually complete or leave this owner. Empty pending work is a no-op.
     * Recovered chapters are skipped against current state even if this retry captured an older list.
     */
    private suspend fun retryCloudflareFailedDownloads(request: DetailsRequestToken) {
        val urls = challengeRecovery.failedDownloadUrls
        if (urls.isEmpty() || !requestFence.accepts(request)) return
        val current = state.value
        val manga = current.manga ?: return
        val title = current.details?.title ?: return
        challengeRecovery.begin(DetailsChallengeOperation.Downloads)
        val generation = downloadsGeneration
        val job = currentCoroutineContext().job
        cloudflareRetryJob?.takeUnless { it == job }?.cancel()
        cloudflareRetryJob = job
        try {
            val idsByUrl = resolveChapterId(manga, urls)
            urls.forEach { url ->
                currentCoroutineContext().ensureActive()
                val latest = state.value
                if (!requestFence.accepts(request) || generation != downloadsGeneration ||
                    latest.manga?.matches(manga) != true
                ) return
                val chapterId = idsByUrl[url] ?: return@forEach
                if (latest.isChapterDownloaded(url)) {
                    challengeRecovery.downloadRecovered(url)
                    return@forEach
                }
                enqueueDownload(chapterId = chapterId, mangaTitle = title, api = manga.api)
                    .onFailure { /* best-effort; the row stays FAILED if it cannot re-queue */ }
            }
        } finally {
            if (cloudflareRetryJob == job) cloudflareRetryJob = null
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
        val manga = state.value.manga ?: return
        val row = downloadRowsByUrl[chapter.url]
        launchSafely {
            val result =
                if (row != null &&
                    (row.state == DownloadState.RUNNING || row.state == DownloadState.COMPRESSING)
                ) {
                    cancelRunningDownload(row.chapterId, row.mangaId)
                } else {
                    cancelChapterDownload(manga, chapter.url)
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
            val next =
                if (chapter.url in it.selectedChapterUrls) {
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
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        val selected = current.selectedChapterUrls.toList()
        if (selected.isNotEmpty()) {
            launchSafely { markChaptersRead(manga, selected) }
            updateState { it.copy(selectedChapterUrls = emptySet()) }
        }
    }

    /**
     * Multi-select "download" — enqueue each selected chapter, then clear selection. Mirrors the
     * sibling [onDownloadChapter] / native `onCustomDownload` (LibraryMangaRoute.kt:206-222): gates
     * on connectivity (offline → immediate feedback, no enqueue) and skips chapters that are already
     * downloaded so a range covering downloaded chapters doesn't re-fetch them.
     */
    private fun onDownloadSelected() {
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        val title = current.details?.title ?: return
        val selected = current.selectedChapterUrls
        if (selected.isEmpty()) return
        // Re-enqueuing SUCCESS demotes it to QUEUED, even before saved details catches up.
        val toDownload =
            current.displayChapters
                .filter { it.url in selected && !current.isChapterDownloaded(it.url) }
                .map { it.url }
        launchSafely {
            // #4: same offline gate as the single-chapter / download-all paths (native parity).
            if (!state.value.isOnline) {
                emit(DetailsEffect.ShowError(AppError.Network.NoConnectivity()))
                return@launchSafely
            }
            val idsByUrl = resolveChapterId(manga, toDownload)
            toDownload.forEach { url ->
                val chapterId = idsByUrl[url] ?: return@forEach
                if (isDownloadedForAction(current, url)) return@forEach
                enqueueDownload(chapterId = chapterId, mangaTitle = title, api = manga.api)
                    .onFailure { t ->
                        emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t)))
                    }
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
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        val selected = current.selectedChapterUrls.toList()
        if (selected.isNotEmpty()) {
            launchSafely {
                toggleChapterBookmark(manga, selected)
            }
            updateState { it.copy(selectedChapterUrls = emptySet()) }
        }
    }

    /**
     * Multi-select "delete downloaded" (L-4, native `ChapterSelectionActionsRow` onDeleteAll). The
     * `:ui` bar only surfaces this when every selected chapter is downloaded; the VM resolves each
     * url → Room id and FULLY deletes the download (clears isDownloaded + deletes files + drops the
     * queue row), matching native. Stray non-downloaded selections are ignored; accepted actions
     * clear selection and recheck completion before each deferred delete.
     */
    private fun onDeleteSelectedDownloads() {
        val current = state.value
        if (!current.isSelectionAllDownloaded) return
        val manga = current.manga ?: return
        val selected = current.selectedChapterUrls.toList()
        launchSafely {
            val idsByUrl = resolveChapterId(manga, selected)
            selected.forEach { url ->
                val id = idsByUrl[url] ?: return@forEach
                if (!isDownloadedForAction(current, url)) return@forEach
                deleteDownloadedChapter(id)
                    .onFailure { t ->
                        emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t)))
                    }
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
     * (a non-library manga has no row). Explicit success retracts the captured owner's chapter;
     * saved overlays alone do not remove chapters. A later source refresh/re-entry may rediscover it.
     */
    private fun onDeleteChapter(chapter: Chapter) {
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        launchSafely {
            val id = resolveChapterId(manga, chapter.url) ?: return@launchSafely
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
            if (state.value.savedOwner?.id != current.savedOwner?.id) return@launchSafely
            if (!chapterRetractions.retractIfOwned(manga, state.value.manga, chapter.url)) return@launchSafely
            fetchedSnapshot = fetchedSnapshot?.withoutChapterUrls(chapterRetractions.urls)
            savedSnapshot = savedSnapshot?.let { it.copy(details = it.details.withoutChapterUrls(chapterRetractions.urls)) }
            updateState { it.withoutDeletedChapter(chapter.url, chapterRetractions.urls) }
        }
    }

    /**
     * Multi-select "mark this and below as read", single-selection and in-library only.
     * Includes the selected chapter and every following chapter in the displayed order.
     * Dispatches the read mutation, then clears selection without awaiting completion.
     */
    private fun onMarkSelectedDownRead() {
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        val selected = current.selectedChapterUrls
        if (selected.size != 1) return
        val targetUrl = selected.first()
        val displayed = current.displayChapters
        val index = displayed.indexOfFirst { it.url == targetUrl }
        if (index < 0) return
        // Include the selected row and every following row in the displayed list.
        // Use the filtered/sorted projection so "below" follows the order the user sees.
        // Unlike the native exclusive range, this matches the inclusive KMP action label.
        val urls = displayed.subList(index, displayed.size).map { it.url }
        if (urls.isEmpty()) return
        launchSafely { markChaptersRead(manga, urls) }
        updateState { it.copy(selectedChapterUrls = emptySet()) }
    }

    /**
     * Top-bar "delete all downloaded chapters" (L-7, native `MangaTopAppBar` onDeleteDownloads).
     * FULLY deletes the download for every downloaded chapter of the fetched details (clears
     * isDownloaded + deletes files + drops the queue row), matching native. Gated on in-library.
     */
    private fun onDeleteAllDownloads() {
        val current = state.value
        if (!current.isInLibrary) return
        val manga = current.manga ?: return
        val downloaded = current.details?.chapters?.filter { current.isChapterDownloaded(it.url) } ?: return
        if (downloaded.isEmpty()) return
        launchSafely {
            val idsByUrl = resolveChapterId(manga, downloaded.map { it.url })
            downloaded.forEach { chapter ->
                val id = idsByUrl[chapter.url] ?: return@forEach
                if (!isDownloadedForAction(current, chapter.url)) return@forEach
                deleteDownloadedChapter(id)
                    .onFailure { t ->
                        emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t)))
                    }
            }
        }
    }

    /**
     * Top-bar "cancel all downloads" (L-7, native `MangaTopAppBar` Stop icon → cancelAllDownloads).
     * Enabled by this manga's scoped active rows, but deliberately invokes the existing GLOBAL
     * worker stop. Per-row cancellation above is manga-scoped; this global contract is unchanged.
     */
    private fun onCancelAllDownloads() {
        if (state.value.downloadingChapterUrls.isEmpty()) return
        launchSafely {
            // Single bulk-cancel that stops the worker + marks all in-flight rows FAILED. The
            // previous per-chapter prune loop never interrupted the active worker (audit
            // details-cancel-all-does-not-stop-worker).
            cancelAllDownloads()
                .onFailure { t ->
                    emit(DetailsEffect.ShowError(AppError.Unexpected(message = t.message ?: "action failed", cause = t)))
                }
        }
    }
}

private fun List<DownloadedChapter>.toProgressByUrl(): Map<String, ChapterDownloadProgress> =
    filter { it.state != DownloadState.FAILED }
        .associate {
            it.url to
                ChapterDownloadProgress(
                    state = it.state,
                    progress = it.progress,
                    sizeBytes = it.sizeBytes,
                    chapterId = it.chapterId,
                    mangaId = it.mangaId,
                )
        }

private fun chapterDownloadsFor(
    chapters: List<Chapter>,
    rowsByUrl: Map<String, ChapterDownloadProgress>,
): Map<String, ChapterDownloadProgress> =
    if (rowsByUrl.isEmpty()) {
        emptyMap()
    } else {
        chapters
            .mapNotNull { chapter ->
                val progress = rowsByUrl[chapter.url] ?: return@mapNotNull null
                chapter.url to progress
            }.toMap()
    }

/**
 * Requested owner identity; title/language are metadata, and aliases require data-layer proof.
 */
private fun Manga.matches(other: Manga): Boolean =
    api == other.api && url == other.url
