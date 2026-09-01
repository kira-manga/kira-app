package me.manga.kira.presentation.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.flatMap
import me.manga.kira.core.result.onFailure
import me.manga.kira.core.result.onSuccess
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.model.home.feedKey
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.home.FetchFeaturedUseCase
import me.manga.kira.domain.usecase.home.FetchHomeFeedUseCase
import me.manga.kira.domain.usecase.home.FetchMoreHomeFeedUseCase
import me.manga.kira.domain.usecase.home.ObserveActiveTabIndexUseCase
import me.manga.kira.domain.usecase.home.ObserveSiteStateUseCase
import me.manga.kira.domain.usecase.home.ObserveSourceTabsUseCase
import me.manga.kira.domain.usecase.home.SelectSourceTabUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.sources.ClearNewSourcesBadgeUseCase
import me.manga.kira.domain.usecase.sources.ObserveNewSourcesBadgeUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Home screen ViewModel (Epic H3a).
 *
 * Strict MVI: state lives in [HomeState]; intents are sealed; effects are one-shot. Depends only on
 * H1 `:domain` use cases + the rework library heart-sync use cases — never on the data layer (DIP).
 * Compose-free.
 *
 * Concurrency posture (mirrors `DetailsViewModel` / `LibraryViewModel`):
 *  - [homeFetchJob] / [featuredFetchJob] are single-flight, cancel-and-replace tracked jobs. A new
 *    fetch (tab switch, refresh) cancels the prior one so a slow previous source can't land its
 *    payload over the new tab's feed.
 *  - The tab list and active index are combined into one selection stream in [onEnter]. The active
 *    tab's site state gates requests before a client is resolved, so maintenance/stopped sources
 *    render their status view without producing a validation error.
 *  - [libraryKeysJob] observes the WHOLE library key set once (a single `ObserveLibraryUseCase`
 *    flow) for the VM lifetime, projected into [HomeState.libraryKeys]; the per-card heart state
 *    ([HomeState.savedKeys]) is then a derived intersection with the visible feed keys — no
 *    per-feed-item Room flow, and no restart on every feed change (was an N-flows N+1 pattern).
 */
class HomeViewModel(
    private val observeSourceTabs: ObserveSourceTabsUseCase,
    private val observeActiveTabIndex: ObserveActiveTabIndexUseCase,
    private val observeSiteState: ObserveSiteStateUseCase,
    private val selectSourceTab: SelectSourceTabUseCase,
    private val fetchHomeFeed: FetchHomeFeedUseCase,
    private val fetchMoreHomeFeed: FetchMoreHomeFeedUseCase,
    private val fetchFeatured: FetchFeaturedUseCase,
    private val observeLibrary: ObserveLibraryUseCase,
    private val toggleInLibrary: ToggleInLibraryUseCase,
    // #2: on the Home ADD path, fetch the full chapter list before persisting (same use case
    // Details uses), so a manga saved from Home isn't a 0-chapter row (which caused the Android
    // LibraryRefreshWorker to fire a false "new chapter" notification per existing chapter).
    private val fetchDetails: FetchMangaDetailsUseCase,
    // U2 (new-sources badge): drives the tab strip's "NEW" chip; cleared when the user opens
    // the source-edit surface (OnEditTabs), mirroring native HomeRoute's setNewSources(false).
    private val observeNewSourcesBadge: ObserveNewSourcesBadgeUseCase,
    private val clearNewSourcesBadge: ClearNewSourcesBadgeUseCase,
) : MviViewModel<HomeState, HomeIntent, HomeEffect>(
        initialState = HomeState(),
    ) {
    private var started = false
    private var siteStateJob: Job? = null
    private var homeFetchJob: Job? = null
    private var featuredFetchJob: Job? = null
    private var libraryKeysJob: Job? = null
    private var hasResolvedSourceSelection = false

    override suspend fun handle(intent: HomeIntent) {
        when (intent) {
            HomeIntent.OnEnter -> onEnter()
            HomeIntent.OnRefresh -> onRefresh()
            HomeIntent.OnEndReached -> onEndReached()
            is HomeIntent.OnTabSelected -> onTabSelected(intent.index)
            HomeIntent.OnToggleGridView -> updateState { it.copy(isGridView = !it.isGridView) }
            HomeIntent.OnToggleSearch -> updateState { it.copy(isSearching = !it.isSearching) }
            is HomeIntent.OnMangaClick ->
                emit(
                    HomeEffect.NavigateToDetails(
                        api = intent.item.api,
                        language = intent.item.language,
                        title = intent.item.title,
                        mangaUrl = intent.item.url,
                        coverUrl = intent.item.coverUrl,
                        rating = intent.item.rating,
                        genres = intent.item.genres,
                    ),
                )
            is HomeIntent.OnChapterClick ->
                emit(
                    HomeEffect.NavigateToReader(
                        api = intent.item.api,
                        language = intent.item.language,
                        title = intent.item.title,
                        mangaUrl = intent.item.url,
                        coverUrl = intent.item.coverUrl,
                        chapterNumber = intent.chapterRef.number,
                        chapterUrl = intent.chapterRef.url,
                        isDownloaded = intent.chapterRef.isDownloaded,
                    ),
                )
            is HomeIntent.OnSaveToggle -> onSaveToggle(intent.item)
            HomeIntent.OnOpenWebView -> onOpenWebView()
            HomeIntent.OnEditTabs -> {
                // U2: opening the edit surface acknowledges the new sources — clear the chip
                // (fire-and-forget; the badge collector re-emits false) before navigating.
                launchSafely { clearNewSourcesBadge() }
                emit(HomeEffect.NavigateToSources)
            }
            HomeIntent.OnHelp -> emit(HomeEffect.ShowHelp)
        }
    }

    private fun onEnter() {
        // Idempotent: a config-change re-attach re-submits OnEnter, but the collectors + first
        // fetch must run only once (same guard posture as LibraryViewModel.startObserving).
        if (started) return
        started = true

        // Resolve the tab list and active index together. A SourceTab already carries the Room-backed
        // operational state, so this gives Home one atomic source selection instead of briefly
        // treating a maintenance source as WORKING while two independent collectors catch up.
        combine(observeSourceTabs(), observeActiveTabIndex()) { tabs, index -> tabs to index }
            .onEach { (tabs, index) -> syncActiveSource(tabs, index) }
            .launchIn(viewModelScope)

        // U2 (new-sources badge): lifetime collector — the "NEW" chip on the tab strip's edit
        // action tracks the prefs cell reactively (set by What's-New, cleared by OnEditTabs).
        observeNewSourcesBadge()
            .onEach { hasNew -> updateState { it.copy(hasNewSources = hasNew) } }
            .launchIn(viewModelScope)

        // Heart-sync (#code-as-5): observe the WHOLE library key set ONCE for the VM lifetime instead
        // of one Room flow per visible feed item. Each emission lifts the full set into state; the
        // per-card [HomeState.savedKeys] is derived by intersecting it with the visible feed keys, so
        // a feed refresh / pagination append needs no re-subscription.
        libraryKeysJob =
            observeLibrary()
                .onEach { library ->
                    val keys = library.map { it.manga.key() }.toSet()
                    updateState { it.copy(libraryKeys = keys) }
                }.catch { /* secondary affordance — empty libraryKeys renders empty hearts. */ }
                .launchIn(viewModelScope)
    }

    /**
     * Applies one coherent source selection. Non-working sources remain visible as tabs, but their
     * status screen is entered without resolving a client or starting network work. A live
     * maintenance -> working transition automatically reloads the source; the reverse transition
     * cancels in-flight work and clears content that belongs to the old operational state.
     */
    private fun syncActiveSource(
        tabs: List<SourceTab>,
        requestedIndex: Int,
    ) {
        val previous = state.value.activeTab
        val previousSiteState = state.value.siteState
        val activeIndex = if (tabs.isEmpty()) 0 else requestedIndex.coerceIn(0, tabs.lastIndex)
        val active = tabs.getOrNull(activeIndex)
        val firstSelection = !hasResolvedSourceSelection
        val sourceChanged = previous?.api != active?.api
        val siteStateChanged = previousSiteState != (active?.siteState ?: SiteState.WORKING)

        updateState {
            it.copy(
                sourceTabs = tabs,
                activeTabIndex = activeIndex,
                siteState = active?.siteState ?: SiteState.WORKING,
            )
        }

        if (firstSelection || sourceChanged || siteStateChanged) {
            hasResolvedSourceSelection = true
            activateSource(active)
        }
        if (firstSelection || sourceChanged) restartSiteStateObservation(active?.api)
    }

    private fun activateSource(source: SourceTab?) {
        homeFetchJob?.cancel()
        featuredFetchJob?.cancel()
        updateState {
            it.copy(
                feed = emptyList(),
                featured = emptyList(),
                feedError = null,
                isFeedLoading = false,
                isRefreshing = false,
                isLoadingNextPage = false,
                page = 1,
                hasMorePages = source?.siteState == SiteState.WORKING,
            )
        }

        when {
            source == null -> fetchHome(reset = true)
            source.siteState == SiteState.WORKING -> {
                fetchHome(reset = true)
                fetchFeaturedFeed()
            }
        }
    }

    private fun onRefresh() {
        if (state.value.siteState != SiteState.WORKING) {
            updateState { it.copy(isRefreshing = false) }
            return
        }
        updateState { it.copy(isRefreshing = true) }
        fetchHome(reset = true)
        fetchFeaturedFeed()
    }

    private fun onEndReached() {
        val s = state.value
        if (s.siteState != SiteState.WORKING) return
        // Pagination guard: don't double-load while a page is in flight, an initial/refresh fetch
        // is running, or there are no more pages.
        if (
            s.isLoadingNextPage ||
            s.isFeedLoading ||
            s.isRefreshing ||
            !s.hasMorePages
        ) {
            return
        }
        val nextPage = s.page + 1
        updateState { it.copy(isLoadingNextPage = true) }
        homeFetchJob?.cancel()
        homeFetchJob =
            launchSafely {
                fetchMoreHomeFeed(nextPage)
                    .onSuccess { more ->
                        updateState {
                            // De-dup across the page boundary: sources ordered by "last chapter added"
                            // re-surface the same manga on consecutive pages, which would otherwise
                            // produce a duplicate LazyGrid/LazyColumn key (Compose crash). distinctBy the
                            // shared feedKey() so the appended page only contributes genuinely-new rows;
                            // hasMorePages is gated on whether anything new was actually added, so an
                            // all-duplicate page ends pagination instead of looping forever.
                            //
                            // P2-PAGINATION parity note: native `MangaViewModel.getMoreManga` REPLACED
                            // state wholesale because the legacy per-source `fetchMoreManga(page,
                            // currentItems)` returns the FULL accumulated list (prior + new). VERIFIED
                            // 2026-07-02 (review P10): `HomeFeedRepositoryImpl.fetchMore` DOES write the
                            // returned list back into its `accumulated` snapshot (generation-guarded), and
                            // the generic path dedups by url — the `more` payload is the full running list
                            // (native contract), not a delta. `feed + more` + distinctBy stays as the merge
                            // anyway: it is contract-safe under both full-list and delta payloads (a full
                            // list collapses back to itself), costing one linear pass per page.
                            val merged = (it.feed + more).distinctBy(HomeFeedItem::feedKey)
                            it.copy(
                                feed = merged,
                                page = nextPage,
                                isLoadingNextPage = false,
                                hasMorePages = merged.size > it.feed.size,
                            )
                        }
                    }.onFailure { error ->
                        updateState { it.copy(isLoadingNextPage = false) }
                        if (error !is AppError.Validation.SourceUnavailable) {
                            emit(HomeEffect.ShowError(error))
                        }
                    }
            }
    }

    private suspend fun onTabSelected(index: Int) {
        if (index == state.value.activeTabIndex || index !in state.value.sourceTabs.indices) return
        selectSourceTab(index)
    }

    private fun fetchHome(reset: Boolean) {
        if (state.value.activeTab != null && state.value.siteState != SiteState.WORKING) {
            return
        }
        if (reset) {
            // Clear any stuck pagination flag. A reset fetch cancels the shared `homeFetchJob`
            // below — which may currently be an in-flight `onEndReached` page-load. Cancellation
            // skips that coroutine's onSuccess/onFailure, the ONLY places that reset
            // `isLoadingNextPage` to false. Without clearing it here the bottom spinner sticks and
            // `onEndReached`'s `isLoadingNextPage` guard dead-locks pagination for the rest of the
            // screen's life (reachable via pull-to-refresh or a tab switch mid-page-load).
            updateState { it.copy(isFeedLoading = true, feedError = null, isLoadingNextPage = false) }
        }
        homeFetchJob?.cancel()
        homeFetchJob =
            launchSafely {
                fetchHomeFeed(reset = reset)
                    .onSuccess { items ->
                        // De-dup the first page too — a source can return the same manga twice within a
                        // single response; distinctBy the shared feedKey() keeps the LazyGrid/LazyColumn
                        // keys unique (Compose crashes on a duplicate key).
                        val deduped = items.distinctBy(HomeFeedItem::feedKey)
                        updateState {
                            it.copy(
                                feed = deduped,
                                isFeedLoading = false,
                                isRefreshing = false,
                                feedError = null,
                                page = 1,
                                hasMorePages = deduped.isNotEmpty(),
                            )
                        }
                    }.onFailure { error ->
                        val sourceUnavailable = error is AppError.Validation.SourceUnavailable
                        updateState {
                            it.copy(
                                isFeedLoading = false,
                                isRefreshing = false,
                                // SourceUnavailable is an operational-state race, not bad user input.
                                // The catalog/Room flow immediately supplies the status screen.
                                feedError = if (sourceUnavailable) null else error,
                            )
                        }
                        if (error !is AppError.Validation.NoEnabledSources && !sourceUnavailable) {
                            emit(HomeEffect.ShowError(error))
                        }
                    }
            }
    }

    private fun fetchFeaturedFeed() {
        if (state.value.activeTab == null || state.value.siteState != SiteState.WORKING) return
        featuredFetchJob?.cancel()
        featuredFetchJob =
            launchSafely {
                fetchFeatured()
                    // De-dup the carousel by the same key its LazyRow uses (FeaturedManga.feedKey) — a
                    // source's "popular" list can repeat a manga or carry a null title, which would crash
                    // the carousel with a duplicate Compose key.
                    .onSuccess { featured -> updateState { it.copy(featured = featured.distinctBy(FeaturedManga::feedKey)) } }
                    .onFailure { error ->
                        if (
                            error !is AppError.Validation.NoEnabledSources &&
                            error !is AppError.Validation.SourceUnavailable
                        ) {
                            emit(HomeEffect.ShowError(error))
                        }
                    }
            }
    }

    private fun restartSiteStateObservation(api: String?) {
        siteStateJob?.cancel()
        if (api == null) return
        siteStateJob =
            observeSiteState(api)
                .onEach { observedState ->
                    val active = state.value.activeTab
                    if (active?.api != api || state.value.siteState == observedState) return@onEach
                    updateState { it.copy(siteState = observedState) }
                    activateSource(active.copy(siteState = observedState))
                }.catch { /* SourceTab remains the fallback status snapshot; don't toast. */ }
                .launchIn(viewModelScope)
    }

    private suspend fun onSaveToggle(item: HomeFeedItem) {
        // GAP-HOME-24 / #2: only ADD shows the inline spinner (it does a real network round-trip);
        // REMOVE is an instant DB delete (no prefetch), so the heart just flips.
        val key = item.key()
        // Re-entry guard: ToggleInLibraryUseCase is read-then-write (non-atomic), so a double-tap
        // delivered before the membership flow re-emits would fire two concurrent toggles that undo
        // each other (a double-tap on REMOVE even RE-ADDs the manga). Drop the second invocation
        // while one is in flight — savingKeys (already keyed per item) tracks the in-flight set, and
        // is cleared in the finally so a failure never strands the guard.
        if (key in state.value.savingKeys) return
        val isAdd = key !in state.value.savedKeys
        val manga = item.toManga()
        updateState { it.copy(savingKeys = it.savingKeys + key) }
        try {
            if (!isAdd) {
                // REMOVE — instant, no fetch.
                toggleInLibrary(manga).onFailure { emit(HomeEffect.ShowError(it)) }
            } else {
                // ADD — fetch the FULL chapter list first (same FetchMangaDetailsUseCase Details
                // uses), then persist the manga WITH its chapters. flatMap short-circuits on a fetch
                // failure so toggleInLibrary never runs on a failed fetch (no half-saved 0-chapter
                // row).
                fetchDetails(manga)
                    .flatMap { details -> toggleInLibrary(manga, details) }
                    .onFailure { emit(HomeEffect.ShowError(it)) }
                    .onSuccess { /* membership flow re-emits and snaps savedKeys; no extra state work. */ }
            }
        } finally {
            // Cleared unconditionally so a fetch OR toggle failure never strands the guard/spinner.
            updateState { it.copy(savingKeys = it.savingKeys - key) }
        }
    }

    private suspend fun onOpenWebView() {
        val tab = state.value.activeTab ?: return
        // Open the active source's base URL (native parity: HomeViewModel.getCurrentBaseUrl()). The
        // url was previously empty here, so the WebView opened a blank page — the "route layer maps
        // the api to the URL" mapping was never implemented. The api is still sent so the WebView
        // persists the captured Cloudflare cookie / User-Agent against THIS source
        // (saveHeaders(headers, api) -> refreshHeaders).
        emit(HomeEffect.NavigateToWebView(url = tab.baseUrl, api = tab.api))
    }
}

private fun HomeFeedItem.key(): MangaKey = MangaKey(api = api, language = language, title = title)

private fun Manga.key(): MangaKey = MangaKey(api = api, language = language, title = title)

private fun HomeFeedItem.toManga(): Manga =
    Manga(
        api = api,
        language = language,
        title = title,
        url = url,
        coverUrl = coverUrl,
        rating = rating,
        genres = genres,
    )
