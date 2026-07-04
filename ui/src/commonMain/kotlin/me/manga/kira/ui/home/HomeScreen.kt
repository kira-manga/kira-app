package me.manga.kira.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.feedKey
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.presentation.home.HomeEffect
import me.manga.kira.presentation.home.HomeIntent
import me.manga.kira.presentation.home.HomeState
import me.manga.kira.presentation.home.HomeViewModel
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraErrorState
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.components.KiraSiteStatusView
import me.manga.kira.ui.home.components.FeaturedCarousel
import me.manga.kira.ui.home.components.HomeFeedGridCard
import me.manga.kira.ui.home.components.HomeFeedRow
import me.manga.kira.ui.home.components.SourceTabsRow
import me.manga.kira.ui.theme.LocalBottomBarPadding
import me.manga.kira.ui.theme.LocalSpacing
import me.manga.kira.ui.util.BackHandler
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.error_auth
import me.manga.kira.ui.generated.resources.error_network
import me.manga.kira.ui.generated.resources.error_network_bad_gateway
import me.manga.kira.ui.generated.resources.error_network_bad_request
import me.manga.kira.ui.generated.resources.error_network_forbidden
import me.manga.kira.ui.generated.resources.error_network_gateway_timeout
import me.manga.kira.ui.generated.resources.error_network_no_connectivity
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.error_network_request_timeout
import me.manga.kira.ui.generated.resources.error_network_server
import me.manga.kira.ui.generated.resources.error_network_service_unavailable
import me.manga.kira.ui.generated.resources.error_network_timeout
import me.manga.kira.ui.generated.resources.error_network_unauthorized
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.error_platform
import me.manga.kira.ui.generated.resources.error_storage
import me.manga.kira.ui.generated.resources.error_validation
import me.manga.kira.ui.generated.resources.home_edit_sources
import me.manga.kira.ui.generated.resources.home_empty
import me.manga.kira.ui.generated.resources.home_pfix_failed_to_load
import me.manga.kira.ui.generated.resources.home_grid_view
import me.manga.kira.ui.generated.resources.home_list_view
import me.manga.kira.ui.generated.resources.home_new_source_badge
import me.manga.kira.ui.generated.resources.home_open_in_webview
import me.manga.kira.ui.generated.resources.home_save
import me.manga.kira.ui.generated.resources.home_saved
import me.manga.kira.ui.generated.resources.home_error_open_in_webview
import me.manga.kira.ui.generated.resources.home_help
import me.manga.kira.ui.generated.resources.home_search
import me.manga.kira.ui.generated.resources.home_source_icon
import me.manga.kira.ui.generated.resources.home_status_adult_body
import me.manga.kira.ui.generated.resources.home_status_adult_title
import me.manga.kira.ui.generated.resources.home_status_maintenance_body
import me.manga.kira.ui.generated.resources.home_status_maintenance_title
import me.manga.kira.ui.generated.resources.home_status_stopped_body
import me.manga.kira.ui.generated.resources.home_status_stopped_title
import me.manga.kira.ui.generated.resources.home_title
import me.manga.kira.ui.generated.resources.home_discover
import me.manga.kira.ui.generated.resources.home_popular
import me.manga.kira.ui.generated.resources.home_latest
import me.manga.kira.ui.generated.resources.library_cancelled
import me.manga.kira.ui.generated.resources.retry

/**
 * Home screen — Compose entry point for the Home MVI slice (Epic H4b).
 *
 * Renders [HomeState] and dispatches [HomeIntent]. One-shot [HomeEffect]s are collected once via
 * [LaunchedEffect] and routed out via the nav callbacks or surfaced through the snackbar host.
 * Mirrors the `LibraryScreen` VM-collection + effect pattern (state via `collectAsState`, intents
 * via `viewModel::submit`, effects via a `LaunchedEffect` collector).
 *
 * Faithful to the legacy `composeApp/.../features/home/ui/screens/HomeScreen.kt`:
 *  - `Scaffold` + top bar (title + grid/list toggle + search + open-in-browser actions, gated on
 *    `siteState == WORKING` like legacy).
 *  - `PullToRefreshBox` wrapping `SourceTabsRow` + the feed body.
 *  - Grid (`LazyVerticalGrid` Adaptive) vs list (`LazyColumn` with [FeaturedCarousel] first, then
 *    [HomeFeedRow]s) on the `isGridView` toggle.
 *  - Infinite scroll → [HomeIntent.OnEndReached] driven by `snapshotFlow` over the list/grid's
 *    last-visible index.
 *  - siteState branches: WORKING renders the feed; UNDER_MAINTENANCE / STOPPED / ADULT_18_PLUS each
 *    render a [KiraSiteStatusView] (the faithful `SimpleStatusScreen` port — GAP-HOME-17) with a
 *    per-state icon/colour pair.
 *  - loading / empty / error via the shared [KiraStateViews] vocabulary.
 *
 * The Search overlay (H4c `SearchScreen`) is a sibling surface; the legacy overlay-swap on
 * `isSearching` is wired by the H5 route adapter, which observes [HomeState.isSearching] and shows
 * `SearchScreen` over Home. Here [HomeIntent.OnToggleSearch] simply flips that flag (legacy parity).
 *
 * **H5 route-adapter note:** the per-source cover request (`rememberSourceImageRequest`) lives in
 * `:composeApp`; this screen accepts a [coverModel] slot that defaults to the plain `coverUrl`
 * string. The route adapter passes the source-aware request through it (and the per-tab icon
 * through [SourceTabsRow]'s `iconForTab`).
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToDetails: (HomeEffect.NavigateToDetails) -> Unit,
    onNavigateToReader: (HomeEffect.NavigateToReader) -> Unit,
    onOpenWebView: (url: String, api: String) -> Unit,
    onNavigateToSources: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    coverModel: ((HomeFeedItem) -> Any?)? = null,
) {
    val state by viewModel.state.collectAsState()
    HomeScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateToDetails = onNavigateToDetails,
        onNavigateToReader = onNavigateToReader,
        onOpenWebView = onOpenWebView,
        onNavigateToSources = onNavigateToSources,
        onHelp = onHelp,
        modifier = modifier,
        coverModel = coverModel,
    )
}

/**
 * Stateless host — split from [HomeScreen] so previews and tests can feed canned state without a
 * real ViewModel. Same SRP split the rework `LibraryScreen` uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreenContent(
    state: HomeState,
    effects: Flow<HomeEffect>,
    onIntent: (HomeIntent) -> Unit,
    onNavigateToDetails: (HomeEffect.NavigateToDetails) -> Unit,
    onNavigateToReader: (HomeEffect.NavigateToReader) -> Unit,
    onOpenWebView: (url: String, api: String) -> Unit,
    onNavigateToSources: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    coverModel: ((HomeFeedItem) -> Any?)? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()
    val errorMessages = rememberHomeErrorMessages()

    // P1 parity (native HomeRoute.kt:289-294 `BackHandler(enabled = isSearchVisible){closeSearch()}`):
    // when the search overlay is active, system-back closes search instead of leaving Home.
    // `HomeIntent.OnToggleSearch` flips `state.isSearching` (the overlay-visible flag) off.
    BackHandler(enabled = state.isSearching) { onIntent(HomeIntent.OnToggleSearch) }

    LaunchedEffect(Unit) { onIntent(HomeIntent.OnEnter) }

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is HomeEffect.NavigateToDetails -> onNavigateToDetails(effect)
                is HomeEffect.NavigateToReader -> onNavigateToReader(effect)
                is HomeEffect.NavigateToWebView -> {
                    // Bind the smart-cast fields to locals BEFORE the log lambda: kermit's `i { }`
                    // message lambda is not inline, so `effect` captured inside it loses its
                    // NavigateToWebView smart-cast and `effect.url`/`effect.api` won't resolve on the
                    // base HomeEffect type.
                    val webUrl = effect.url
                    val webApi = effect.api
                    Logger.withTag("onOpenWebView").i { "$webUrl ,$webApi" }
                    onOpenWebView(webUrl, webApi)
                }
                HomeEffect.NavigateToSources -> onNavigateToSources()
                is HomeEffect.ShowError -> scope.launch { snackbarHostState.showSnackbar(errorMessages(effect.error)) }
                HomeEffect.ShowHelp -> onHelp()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { HomeHeader(state = state, onIntent = onIntent) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The floating bottom nav lives in the app root and overlays the content; the bottom inset
        // (system nav + capsule) reaches the feed via `LocalBottomBarPadding`, added to the list/grid
        // bottom contentPadding below. Zero the Scaffold insets so it doesn't ALSO pad the bottom
        // (double-count) — the header owns its own status-bar inset via `statusBarsPadding()`.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // P3-LOW parity (native HomeScreen.kt:455-461): native styled the pull-refresh indicator with
        // `backgroundColor = inverseSurface` + `contentColor = background`. The M3 PullToRefreshBox
        // default indicator uses the standard surface-container scheme; supply a custom `Indicator`
        // mapping `containerColor = inverseSurface` and `color = background` (the spinner/arrow tint)
        // to match. The state is hoisted so the box gesture and the custom indicator share it.
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(HomeIntent.OnRefresh) },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    color = MaterialTheme.colorScheme.background,
                )
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val sourceIconCd = stringResource(Res.string.home_source_icon)
                SourceTabsRow(
                    tabs = state.sourceTabs,
                    activeTabIndex = state.activeTabIndex,
                    onTabSelected = { onIntent(HomeIntent.OnTabSelected(it)) },
                    onEditSources = { onIntent(HomeIntent.OnEditTabs) },
                    // U2 new-source badge (native SourcesTabs.kt:144-148 `AnimatedNew` parity):
                    // `hasNewSources` is observed into state via ObserveNewSourcesBadgeUseCase
                    // (What's-New sets the cell; OnEditTabs clears it — HomeViewModel).
                    showNewBadge = state.hasNewSources,
                    newBadgeLabel = stringResource(Res.string.home_new_source_badge),
                    editContentDescription = stringResource(Res.string.home_edit_sources),
                    // U1 source-tab brand icons: SourceTabsRow resolves each pill's brand drawable
                    // itself via the app-wide LocalSourceIconResolver (RepoIconResolver ships 46
                    // per-api mappings, provided at the App root). This slot is only the FALLBACK
                    // for an api with no shipped drawable — a neutral 18.dp glyph, matching the
                    // native strip's `team_x` fallback.
                    iconForTab = { _, selected ->
                        Icon(
                            imageVector = KiraIcons.Empty,
                            contentDescription = sourceIconCd,
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                        )
                    },
                )
                // GAP-HOME-13: legacy `HomeScreen` placed an 8.dp `Spacer` under `SourcesTabs` before
                // the feed. `spacing.sm` resolves to 8.dp.
                Spacer(modifier = Modifier.height(LocalSpacing.current.sm))
                HomeFeedBody(
                    state = state,
                    onIntent = onIntent,
                    errorMessages = errorMessages,
                    coverModel = coverModel,
                )
            }
        }
    }
}

@Composable
private fun HomeFeedBody(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    errorMessages: (AppError) -> String,
    coverModel: ((HomeFeedItem) -> Any?)?,
) {
    // P3-LOW parity (native HomeScreen.kt:104-105,116-117 hoisted `listState`/`gridState`): native
    // owns BOTH scroll states above the grid/list branch and shares them across recompositions, so
    // toggling grid<->list preserves each layout's scroll offset. Hoisting them here (rather than
    // `remember`ing inside `HomeList`/`HomeGrid`, where the discarded composable lost its position on
    // every view-mode toggle) restores that — the tab-switch reset-to-top still fires via the
    // `LaunchedEffect(activeTabIndex)` inside each layout.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // siteState gate — legacy rendered a dedicated, visually-distinct maintenance/stopped/adult
    // screen per source (GAP-HOME-17: faithful `SimpleStatusScreen` port via `KiraSiteStatusView`).
    val siteName = state.activeTab?.api ?: ""
    when (state.siteState) {
        SiteState.UNDER_MAINTENANCE -> {
            KiraSiteStatusView(
                icon = Icons.Filled.Build,
                iconColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                title = stringResource(Res.string.home_status_maintenance_title),
                subtitle = siteName,
                message = stringResource(Res.string.home_status_maintenance_body),
            )
            return
        }
        SiteState.STOPPED -> {
            KiraSiteStatusView(
                icon = Icons.Filled.Error,
                iconColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                title = stringResource(Res.string.home_status_stopped_title),
                subtitle = siteName,
                message = stringResource(Res.string.home_status_stopped_body),
            )
            return
        }
        SiteState.ADULT_18_PLUS -> {
            KiraSiteStatusView(
                icon = Icons.Filled.Error,
                iconColor = MaterialTheme.colorScheme.tertiary,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                title = stringResource(Res.string.home_status_adult_title),
                subtitle = siteName,
                message = stringResource(Res.string.home_status_adult_body),
            )
            return
        }
        SiteState.WORKING -> Unit
    }

    when {
        // GAP-HOME-15: legacy `LoadingScreen` used `CircularProgressIndicator(color = inversePrimary)`;
        // the raw spinner defaulted to `primary`. Set `inversePrimary` to match native.
        state.isInitialFeedLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.inversePrimary)
        }
        // GAP-HOME-01 / GAP-HOME-02 / GAP-HOME-19: the legacy `ErrorScreen` offered three actions —
        // Retry + Open-in-WebView + Help. The Open-in-WebView action doubles as the cross-platform
        // 403/Cloudflare recovery prompt (legacy `Handle403Error` token-refresh path). Help dispatches
        // `HomeIntent.OnHelp` → `HomeEffect.ShowHelp` (the route adapter opens the help URL in WebView).
        state.feedError != null && state.feed.isEmpty() -> KiraErrorState(
            // P2 parity (native HomeScreen.kt:297-300 & 370-373:
            // `stringResource(R.string.failed_to_load, mangaItemsState.message)`, string
            // `Failed to load : %1$s`). Native interpolated the source's underlying failure text into
            // the persistent error pane so a 403/Cloudflare block reads differently from a parse
            // error. The rework pane showed only the static `home_failed_to_load`, dropping the typed
            // AppError detail (it surfaced only in the transient ShowError snackbar). Reuse the same
            // `errorMessages` vocabulary the snackbar uses to map `state.feedError` to a human-readable
            // detail, then interpolate it via the parameterized `home_pfix_failed_to_load`.
            message = stringResource(Res.string.home_pfix_failed_to_load, errorMessages(state.feedError!!)),
            retryLabel = stringResource(Res.string.retry),
            onRetry = { onIntent(HomeIntent.OnRefresh) },
            openInWebViewLabel = stringResource(Res.string.home_error_open_in_webview),
            onOpenInWebView = { onIntent(HomeIntent.OnOpenWebView) },
            helpLabel = stringResource(Res.string.home_help),
            onHelp = { onIntent(HomeIntent.OnHelp) },
        )
        // GAP-HOME-16: KMP adds an empty state where legacy showed a bare carousel + no rows. Keep the
        // empty state, BUT in list mode where the popular carousel loaded, legacy still rendered the
        // carousel; so when the feed is empty yet [featured] exists in list mode, fall through to
        // [HomeList] (which draws the carousel, then the inline empty state) rather than suppressing it.
        state.feed.isEmpty() && !(!state.isGridView && state.featured.isNotEmpty()) ->
            KiraEmptyState(title = stringResource(Res.string.home_empty))
        state.isGridView -> HomeGrid(state = state, onIntent = onIntent, coverModel = coverModel, gridState = gridState)
        else -> HomeList(state = state, onIntent = onIntent, coverModel = coverModel, listState = listState)
    }
}

@Composable
private fun HomeGrid(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    coverModel: ((HomeFeedItem) -> Any?)?,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
) {
    val spacing = LocalSpacing.current
    // GAP-HOME-20: switching source tabs resets the grid scroll to the top (legacy
    // `LaunchedEffect(activeTabIndex){ gridState.scrollToItem(0) }`), so the new source's feed starts
    // from item 0 rather than the previous tab's scroll offset. The state itself is hoisted to
    // [HomeFeedBody] (P3-LOW) so the grid<->list toggle preserves position.
    LaunchedEffect(state.activeTabIndex) { gridState.scrollToItem(0) }
    InfiniteScrollEffect(onEndReached = { onIntent(HomeIntent.OnEndReached) }) {
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = gridState.layoutInfo.totalItemsCount
        total > 0 && last >= total - 1
    }
    LazyVerticalGrid(
        state = gridState,
        // GAP-HOME-09: legacy Home grid used Adaptive(minSize = 160.dp) — match it (was 120.dp).
        columns = GridCells.Adaptive(minSize = 160.dp),
        // P3-LOW parity (native HomeScreen.kt:307-312): the native Home grid uses
        // `contentPadding = PaddingValues(8.dp)` with NO grid arrangement spacing and instead carries
        // an 8.dp padding on each card (`SearchItems` `Modifier.padding(8.dp)`). Reproduce that
        // spacing model exactly — drop the `spacedBy` arrangement and pass the per-cell 8.dp via the
        // card's `modifier` slot (the shared `HomeFeedGridCard` is out of scope to bake it into, so
        // the padding rides the modifier, matching the precedent the Search grid already set).
        contentPadding = PaddingValues(
            start = spacing.sm,
            end = spacing.sm,
            top = spacing.sm,
            bottom = spacing.sm + LocalBottomBarPadding.current,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(
            items = state.feed,
            key = { it.feedKey() },
        ) { item ->
            HomeFeedGridCard(
                item = item,
                onClick = { onIntent(HomeIntent.OnMangaClick(it)) },
                coverModel = coverModel,
                modifier = Modifier.padding(spacing.sm),
            )
        }
        if (state.isLoadingNextPage) {
            item { NextPageSpinner() }
        }
    }
}

@Composable
private fun HomeList(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    coverModel: ((HomeFeedItem) -> Any?)?,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val spacing = LocalSpacing.current
    // GAP-HOME-20: tab switch resets the list scroll to the top (legacy parity), so the new source's
    // feed (and carousel) starts from item 0 rather than retaining the previous tab's scroll offset.
    // The state itself is hoisted to [HomeFeedBody] (P3-LOW) so the grid<->list toggle preserves
    // position.
    LaunchedEffect(state.activeTabIndex) { listState.scrollToItem(0) }
    InfiniteScrollEffect(onEndReached = { onIntent(HomeIntent.OnEndReached) }) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = listState.layoutInfo.totalItemsCount
        total > 0 && last >= total - 1
    }
    LazyColumn(
        state = listState,
        // Bottom inset = the floating-nav footprint so the last row clears the capsule; the feed
        // still scrolls edge-to-edge underneath it.
        contentPadding = PaddingValues(
            top = spacing.sm,
            bottom = spacing.sm + LocalBottomBarPadding.current,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.featured.isNotEmpty()) {
            item(key = "__popular_header__") { SectionHeader(stringResource(Res.string.home_popular)) }
            item(key = "__featured__") {
                FeaturedCarousel(
                    items = state.featured,
                    onItemClick = { f -> onIntent(HomeIntent.OnMangaClick(f.toFeedItem())) },
                )
            }
        }
        if (state.feed.isNotEmpty()) {
            item(key = "__latest_header__") { SectionHeader(stringResource(Res.string.home_latest)) }
        }
        items(
            items = state.feed,
            key = { it.feedKey() },
        ) { item ->
            HomeFeedRow(
                item = item,
                isSaved = item.key() in state.savedKeys,
                isSaving = item.key() in state.savingKeys,
                onMangaClick = { onIntent(HomeIntent.OnMangaClick(it)) },
                onChapterClick = { mi, ch -> onIntent(HomeIntent.OnChapterClick(mi, ch)) },
                onSaveToggle = { onIntent(HomeIntent.OnSaveToggle(it)) },
                saveContentDescription = stringResource(Res.string.home_save),
                savedContentDescription = stringResource(Res.string.home_saved),
                coverModel = coverModel,
            )
        }
        if (state.isLoadingNextPage) {
            item(key = "__nextpage__") { NextPageSpinner() }
        }
    }
}

@Composable
private fun NextPageSpinner() {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        // GAP-HOME-15: match the legacy `inversePrimary` spinner colour (page-load spinner).
        CircularProgressIndicator(color = MaterialTheme.colorScheme.inversePrimary)
    }
}

/**
 * Fires [onEndReached] once each time [atEnd] transitions to true — the infinite-scroll trigger.
 * The VM's own pagination guard makes [HomeIntent.OnEndReached] a no-op while a page is in flight,
 * so a `distinctUntilChanged` + `filter { it }` over a `snapshotFlow` is sufficient (mirrors legacy
 * `isScrolledToTheEnd()` snapshotFlow).
 */
@Composable
private fun InfiniteScrollEffect(onEndReached: () -> Unit, atEnd: () -> Boolean) {
    LaunchedEffect(Unit) {
        snapshotFlow(atEnd)
            .distinctUntilChanged()
            .filter { it }
            .collect { onEndReached() }
    }
}

private fun HomeFeedItem.key() = me.manga.kira.domain.repository.MangaKey(
    api = api,
    language = language,
    title = title,
)

private fun FeaturedManga.toFeedItem(): HomeFeedItem = HomeFeedItem(
    api = api,
    language = language,
    title = title,
    url = url,
    coverUrl = coverUrl,
    rating = null,
    genres = emptyList(),
    recentChapters = emptyList(),
)

/**
 * Pre-resolves [AppError] messages in composable scope so the snackbar collector (a coroutine,
 * where `stringResource` can't run) can map a typed error to a string. Same posture as the
 * Library screen's `rememberAppErrorMessages`.
 */
@Composable
private fun rememberHomeErrorMessages(): (AppError) -> String {
    val network = stringResource(Res.string.error_network)
    val net = rememberNetworkErrorMessages()
    val storage = stringResource(Res.string.error_storage)
    val validation = stringResource(Res.string.error_validation)
    val auth = stringResource(Res.string.error_auth)
    val platform = stringResource(Res.string.error_platform)
    val cancelled = stringResource(Res.string.library_cancelled)
    val unexpected = stringResource(Res.string.error_occurred)
    return { error ->
        when (error) {
            is AppError.Network -> net.messageFor(error, fallback = network)
            is AppError.Storage -> storage
            is AppError.Validation -> validation
            is AppError.Auth -> auth
            is AppError.Platform -> platform
            is AppError.Cancelled -> cancelled
            is AppError.Unexpected -> unexpected
        }
    }
}

/**
 * P1 parity: native distinguishes network failures by HTTP status code / transport failure
 * (`State.kt` `httpStatusMessage` + `fromException`) rather than collapsing every
 * [AppError.Network] into one string. Pre-resolves the per-code messages in composable scope and
 * returns a holder whose [NetworkErrorMessages.messageFor] is a plain `when` callable from the
 * snackbar collector (where `stringResource` cannot run). Codes native does not name individually
 * fall back to the caller's generic network string.
 */
@Composable
private fun rememberNetworkErrorMessages(): NetworkErrorMessages = NetworkErrorMessages(
    noConnectivity = stringResource(Res.string.error_network_no_connectivity),
    timeout = stringResource(Res.string.error_network_timeout),
    badRequest = stringResource(Res.string.error_network_bad_request),
    unauthorized = stringResource(Res.string.error_network_unauthorized),
    forbidden = stringResource(Res.string.error_network_forbidden),
    notFound = stringResource(Res.string.error_network_not_found),
    requestTimeout = stringResource(Res.string.error_network_request_timeout),
    server = stringResource(Res.string.error_network_server),
    badGateway = stringResource(Res.string.error_network_bad_gateway),
    serviceUnavailable = stringResource(Res.string.error_network_service_unavailable),
    gatewayTimeout = stringResource(Res.string.error_network_gateway_timeout),
)

private class NetworkErrorMessages(
    val noConnectivity: String,
    val timeout: String,
    val badRequest: String,
    val unauthorized: String,
    val forbidden: String,
    val notFound: String,
    val requestTimeout: String,
    val server: String,
    val badGateway: String,
    val serviceUnavailable: String,
    val gatewayTimeout: String,
) {
    fun messageFor(error: AppError.Network, fallback: String): String = when (error) {
        is AppError.Network.NoConnectivity -> noConnectivity
        is AppError.Network.Timeout -> timeout
        is AppError.Network.Serialization -> fallback
        is AppError.Network.Http -> when (error.statusCode) {
            400 -> badRequest
            401 -> unauthorized
            403 -> forbidden
            404 -> notFound
            408 -> requestTimeout
            500 -> server
            502 -> badGateway
            503 -> serviceUnavailable
            504 -> gatewayTimeout
            else -> fallback
        }
    }
}

/**
 * Redesign 2026-06 header: brand eyebrow + large "Discover" title + circular action buttons
 * (grid/list toggle, search, open-in-browser) gated on `siteState == WORKING` (legacy parity).
 * Owns its own status-bar inset (mirrors the M3 TopAppBar it replaced).
 */
@Composable
private fun HomeHeader(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "KIRA",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
            Text(
                text = stringResource(Res.string.home_discover),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
            )
        }
        if (state.siteState == SiteState.WORKING) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderAction(
                    icon = if (state.isGridView) KiraIcons.ViewList else KiraIcons.GridView,
                    contentDescription = if (state.isGridView) {
                        stringResource(Res.string.home_list_view)
                    } else {
                        stringResource(Res.string.home_grid_view)
                    },
                    onClick = { onIntent(HomeIntent.OnToggleGridView) },
                )
                HeaderAction(
                    icon = KiraIcons.Search,
                    contentDescription = stringResource(Res.string.home_search),
                    onClick = { onIntent(HomeIntent.OnToggleSearch) },
                )
                HeaderAction(
                    icon = Icons.Filled.Web,
                    contentDescription = stringResource(Res.string.home_open_in_webview),
                    onClick = { onIntent(HomeIntent.OnOpenWebView) },
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Redesign 2026-06: section label above the carousel / feed ("Popular now" / "Latest updates"). */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
    )
}

// region Previews

private val previewTabs = listOf(
    SourceTab(api = "MangaDex", language = "en", iconKey = null, siteState = SiteState.WORKING),
    SourceTab(api = "Comick", language = "en", iconKey = null, siteState = SiteState.WORKING),
    SourceTab(api = "Team-X", language = "ar", iconKey = null, siteState = SiteState.WORKING),
)

private fun previewFeed(n: Int) = (1..n).map { i ->
    HomeFeedItem(
        api = "MangaDex",
        language = "en",
        title = "Sample Manga $i",
        url = "https://example/$i",
        coverUrl = "",
        rating = 8,
        genres = listOf("Action"),
        recentChapters = listOf(
            HomeChapterRef(number = "${i + 2}", url = "c/$i/3", isDownloaded = false),
            HomeChapterRef(number = "${i + 1}", url = "c/$i/2", isDownloaded = true),
            HomeChapterRef(number = "$i", url = "c/$i/1", isDownloaded = false),
        ),
    )
}

private val previewFeatured = (1..6).map { i ->
    FeaturedManga(api = "MangaDex", language = "en", title = "Popular $i", url = "p/$i", coverUrl = "")
}

@Preview
@Composable
private fun HomeScreenListPreview() {
    HomeScreenContent(
        state = HomeState(
            sourceTabs = previewTabs,
            activeTabIndex = 0,
            isGridView = false,
            feed = previewFeed(6),
            featured = previewFeatured,
        ),
        effects = kotlinx.coroutines.flow.emptyFlow(),
        onIntent = {},
        onNavigateToDetails = { },
        onNavigateToReader = {},
        onOpenWebView = { _, _ -> },
        onNavigateToSources = {},
        onHelp = {},
    )
}

@Preview
@Composable
private fun HomeScreenGridPreview() {
    HomeScreenContent(
        state = HomeState(
            sourceTabs = previewTabs,
            activeTabIndex = 1,
            isGridView = true,
            feed = previewFeed(9),
        ),
        effects = kotlinx.coroutines.flow.emptyFlow(),
        onIntent = {},
        onNavigateToDetails = { },
        onNavigateToReader = {},
        onOpenWebView = { _, _ -> },
        onNavigateToSources = {},
        onHelp = {},
    )
}

@Preview
@Composable
private fun HomeScreenMaintenancePreview() {
    HomeScreenContent(
        state = HomeState(
            sourceTabs = previewTabs,
            activeTabIndex = 0,
            siteState = SiteState.UNDER_MAINTENANCE,
        ),
        effects = kotlinx.coroutines.flow.emptyFlow(),
        onIntent = {},
        onNavigateToDetails = { },
        onNavigateToReader = {},
        onOpenWebView = { _, _ -> },
        onNavigateToSources = {},
        onHelp = {},
    )
}

// endregion
