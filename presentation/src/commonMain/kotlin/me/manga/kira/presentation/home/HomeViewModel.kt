package me.manga.kira.presentation.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.core.result.flatMap
import me.manga.kira.core.result.onFailure
import me.manga.kira.core.result.onSuccess
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.feedKey
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.usecase.home.FetchFeaturedUseCase
import me.manga.kira.domain.usecase.home.FetchHomeFeedUseCase
import me.manga.kira.domain.usecase.home.FetchMoreHomeFeedUseCase
import me.manga.kira.domain.usecase.home.ObserveActiveTabIndexUseCase
import me.manga.kira.domain.usecase.home.ObserveSiteStateUseCase
import me.manga.kira.domain.usecase.home.ObserveSourceTabsUseCase
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.home.SelectSourceTabUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.sources.ClearNewSourcesBadgeUseCase
import me.manga.kira.domain.usecase.sources.ObserveNewSourcesBadgeUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
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
 *  - The tab-strip / active-index / siteState collectors launch once in [onEnter] and live for the
 *    VM lifetime (cancelled with `viewModelScope`). The siteState collector is keyed on the active
 *    source api, so it is restarted ([siteStateJob]) when the active tab changes.
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

    /**
     * The source api the currently-displayed feed was fetched for. Tracked so the [observeSourceTabs]
     * collector can detect when toggling a source in the Sources screen shifted the active tab onto a
     * DIFFERENT source under the same (or now out-of-range) index, and re-sync the feed (aNum 11).
     */
    private var feedApi: String? = null

    override suspend fun handle(intent: HomeIntent) {
        when (intent) {
            HomeIntent.OnEnter -> onEnter()
            HomeIntent.OnRefresh -> onRefresh()
            HomeIntent.OnEndReached -> onEndReached()
            is HomeIntent.OnTabSelected -> onTabSelected(intent.index)
            HomeIntent.OnToggleGridView -> updateState { it.copy(isGridView = !it.isGridView) }
            HomeIntent.OnToggleSearch -> updateState { it.copy(isSearching = !it.isSearching) }
            is HomeIntent.OnMangaClick -> emit(
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
            is HomeIntent.OnChapterClick -> emit(
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

        observeSourceTabs()
            .onEach { tabs ->
                updateState { it.copy(sourceTabs = tabs) }
                restartSiteStateObservation()
                // aNum 11: a source toggle in the Sources screen can shrink/grow the enabled set
                // under a fixed activeTabIndex, leaving the highlighted tab, the displayed feed, and
                // the siteState gate pointing at the wrong (or a now-removed) source. Clamp the index
                // and re-fetch the feed when the active source no longer matches what the feed shows.
                resyncFeedForActiveTab()
            }
            .launchIn(viewModelScope)

        observeActiveTabIndex()
            .onEach { index ->
                updateState { it.copy(activeTabIndex = index) }
                restartSiteStateObservation()
            }
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
        libraryKeysJob = observeLibrary()
            .onEach { library ->
                val keys = library.map { it.manga.key() }.toSet()
                updateState { it.copy(libraryKeys = keys) }
            }
            .catch { /* secondary affordance — empty libraryKeys renders empty hearts. */ }
            .launchIn(viewModelScope)

        fetchHome(reset = true)
        fetchFeaturedFeed()
    }

    /**
     * Re-sync the feed when the enabled-source set shifted under the active index (aNum 11). Clamps
     * [HomeState.activeTabIndex] into range, persists the clamp via [selectSourceTab], and — when the
     * resulting active source differs from the source the current feed was fetched for ([feedApi]),
     * or there is no active tab — resets the feed/featured and refetches, exactly like a tab switch.
     */
    private suspend fun resyncFeedForActiveTab() {
        // Skip until the initial feed fetch has been kicked off (onEnter fetches AFTER wiring this
        // collector). Without this guard the first tab-strip emission would trigger a redundant
        // second fetch racing onEnter's own. Once a fetch has run, [feedApi] is non-null and a later
        // Sources-toggle round-trip can re-sync.
        if (feedApi == null) return
        val tabs = state.value.sourceTabs
        if (tabs.isEmpty()) {
            // No enabled sources at all: clear the feed so a now-disabled source's items don't linger.
            // Keep [feedApi] as the last-fetched source (don't null it) so that when a source is
            // re-enabled the next emission detects the active-source mismatch and refetches.
            if (state.value.feed.isNotEmpty() || state.value.featured.isNotEmpty()) {
                updateState { it.copy(feed = emptyList(), featured = emptyList(), feedError = null) }
            }
            return
        }
        val clampedIndex = state.value.activeTabIndex.coerceIn(0, tabs.lastIndex)
        if (clampedIndex != state.value.activeTabIndex) {
            selectSourceTab(clampedIndex)
            updateState { it.copy(activeTabIndex = clampedIndex) }
            restartSiteStateObservation()
        }
        val activeApi = state.value.activeTab?.api
        // Only re-fetch when the active source actually changed relative to the displayed feed —
        // an ordinary tab-list re-emit (same active source) must not wipe and re-load the feed.
        if (activeApi != feedApi) {
            updateState {
                it.copy(
                    feed = emptyList(),
                    featured = emptyList(),
                    feedError = null,
                    page = 1,
                    hasMorePages = true,
                    isLoadingNextPage = false,
                )
            }
            fetchHome(reset = true)
            fetchFeaturedFeed()
        }
    }

    private fun onRefresh() {
        updateState { it.copy(isRefreshing = true) }
        fetchHome(reset = true)
        fetchFeaturedFeed()
    }

    private fun onEndReached() {
        val s = state.value
        // Pagination guard: don't double-load while a page is in flight, an initial/refresh fetch
        // is running, or there are no more pages.
        if (s.isLoadingNextPage || s.isFeedLoading || s.isRefreshing || !s.hasMorePages) return
        val nextPage = s.page + 1
        updateState { it.copy(isLoadingNextPage = true) }
        homeFetchJob?.cancel()
        homeFetchJob = launchSafely {
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
                }
                .onFailure { error ->
                    updateState { it.copy(isLoadingNextPage = false) }
                    emit(HomeEffect.ShowError(error))
                }
        }
    }

    private suspend fun onTabSelected(index: Int) {
        if (index == state.value.activeTabIndex) return
        selectSourceTab(index)
        // Tab-switch reset: clear the current feed/pagination so the new source starts clean
        // (legacy ManhastroDadosStore.clear() on tab switch is replicated in the :data impl; here
        // we reset the presentation-side feed + page cursor).
        updateState {
            it.copy(
                activeTabIndex = index,
                feed = emptyList(),
                // #23 — clear the featured/popular carousel too so the previous source's
                // carousel doesn't flash over the new feed before fetchFeaturedFeed() refills it
                // (native getPopularManga posts Success(emptyList()) on tab switch).
                featured = emptyList(),
                feedError = null,
                page = 1,
                hasMorePages = true,
                isLoadingNextPage = false,
            )
        }
        restartSiteStateObservation()
        fetchHome(reset = true)
        fetchFeaturedFeed()
    }

    private fun fetchHome(reset: Boolean) {
        // Record the source this feed is being fetched for so the tabs collector can detect an
        // active-source shift (aNum 11). Captured here (not on success) so a slow/failed fetch still
        // marks the feed as belonging to the current source.
        feedApi = state.value.activeTab?.api
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
        homeFetchJob = launchSafely {
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
                }
                .onFailure { error ->
                    updateState {
                        it.copy(isFeedLoading = false, isRefreshing = false, feedError = error)
                    }
                    emit(HomeEffect.ShowError(error))
                }
        }
    }

    private fun fetchFeaturedFeed() {
        featuredFetchJob?.cancel()
        featuredFetchJob = launchSafely {
            fetchFeatured()
                // De-dup the carousel by the same key its LazyRow uses (FeaturedManga.feedKey) — a
                // source's "popular" list can repeat a manga or carry a null title, which would crash
                // the carousel with a duplicate Compose key.
                .onSuccess { featured -> updateState { it.copy(featured = featured.distinctBy(FeaturedManga::feedKey)) } }
                .onFailure { error -> emit(HomeEffect.ShowError(error)) }
        }
    }

    private fun restartSiteStateObservation() {
        val api = state.value.activeTab?.api ?: return
        siteStateJob?.cancel()
        siteStateJob = observeSiteState(api)
            .onEach { siteState -> updateState { it.copy(siteState = siteState) } }
            .catch { /* secondary gate — default WORKING is safe; don't toast. */ }
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
                    .flatMap { details -> toggleInLibrary(manga, details.chapters) }
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

private fun HomeFeedItem.toManga(): Manga = Manga(
    api = api,
    language = language,
    title = title,
    url = url,
    coverUrl = coverUrl,
    rating = rating,
    genres = genres,
)
