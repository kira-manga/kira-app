package me.manga.kira.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.feedKey
import me.manga.kira.presentation.mvi.UiState
import me.manga.kira.presentation.search.SearchEffect
import me.manga.kira.presentation.search.SearchIntent
import me.manga.kira.presentation.search.SearchModeTab
import me.manga.kira.presentation.search.SearchState
import me.manga.kira.presentation.search.SearchViewModel
import me.manga.kira.ui.components.KiraCoverImage
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraErrorState
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.home.components.HomeFeedGridCard
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
import me.manga.kira.ui.generated.resources.home_error_open_in_webview
import me.manga.kira.ui.generated.resources.home_help
import me.manga.kira.ui.generated.resources.library_cancelled
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.search_clear
import me.manga.kira.ui.generated.resources.search_close
import me.manga.kira.ui.generated.resources.search_empty
import me.manga.kira.ui.generated.resources.search_filters
import me.manga.kira.ui.generated.resources.search_hint
import me.manga.kira.ui.generated.resources.search_pfix_tab_multi
import me.manga.kira.ui.generated.resources.search_tab_single

/**
 * Search overlay screen — Compose entry point for the Search MVI slice (Epic H4c).
 *
 * Renders [SearchState] and dispatches [SearchIntent]; routes [SearchEffect]s via a
 * [LaunchedEffect] collector. Same VM-collection + effect pattern as the rework `LibraryScreen` /
 * `HomeScreen`.
 *
 * Faithful to the legacy `composeApp/.../features/home/ui/screens/search/SearchScreen.kt`:
 *  - A search app bar: a close action + an inline query field + a filter action (legacy used a
 *    `SearchAppBar` + a settings `IconButton` opening the filter sheet).
 *  - A single/multi `TabRow` (legacy `ChipsRow`) above a [HorizontalPager] of 2 pages.
 *  - Page 0: single-source results in a `LazyVerticalGrid` of [HomeFeedGridCard]s (legacy
 *    `MangaSearchItems` grid). Page 1: the multi-repo aggregation — a `LazyColumn` of per-source
 *    sections, each a title + a horizontal `LazyRow`, with per-section Loading / Error / Success
 *    drawn from the `Map<String, UiState<...>>` (legacy `MultiRepoResults` / `RepoSection`).
 *  - The [SearchFilterSheet] (`ModalBottomSheet`) opens from the filter action: a genre
 *    `FilterChip` FlowRow + a sort dropdown + an Apply button (legacy `SearchBottomSheet`).
 *
 * **H5 route-adapter note:** per-source cover requests stay in `:composeApp`; this screen takes a
 * [coverModel] slot (default plain `coverUrl`).
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onNavigateToDetails: (SearchEffect.NavigateToDetails) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    coverModel: ((HomeFeedItem) -> Any?)? = null,
    onOpenInWebView: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    SearchScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateToDetails = onNavigateToDetails,
        onClose = onClose,
        modifier = modifier,
        coverModel = coverModel,
        onOpenInWebView = onOpenInWebView,
        onHelp = onHelp,
    )
}

/** Stateless host — split for previews/tests (same SRP split as the sibling rework screens). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreenContent(
    state: SearchState,
    effects: Flow<SearchEffect>,
    onIntent: (SearchIntent) -> Unit,
    onNavigateToDetails: (SearchEffect.NavigateToDetails) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    coverModel: ((HomeFeedItem) -> Any?)? = null,
    onOpenInWebView: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()
    val errorMessages = rememberSearchErrorMessages()
    var showFilters by remember { mutableStateOf(false) }

    // Search is an overlay-swap on the Home back-stack entry, so system-back must close the overlay
    // here — the BackHandler in HomeScreen is unreachable while SearchScreen is composed. Routes via
    // the same OnClose intent as the close button (SearchEffect.Close -> onClose()).
    BackHandler { onIntent(SearchIntent.OnClose) }

    LaunchedEffect(Unit) { onIntent(SearchIntent.OnLoadFilters) }

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is SearchEffect.NavigateToDetails -> onNavigateToDetails(effect)
                SearchEffect.Close -> onClose()
                is SearchEffect.ShowError -> scope.launch { snackbarHostState.showSnackbar(errorMessages(effect.error)) }
            }
        }
    }

    val pagerState = rememberPagerState(initialPage = state.mode.ordinal, pageCount = { 2 })
    // Keep the pager and the MVI mode in sync (page swipe → intent; mode change → page scroll).
    LaunchedEffect(pagerState.currentPage) {
        val mode = SearchModeTab.entries[pagerState.currentPage]
        if (mode != state.mode) onIntent(SearchIntent.OnModeTabChange(mode))
    }
    LaunchedEffect(state.mode) {
        if (pagerState.currentPage != state.mode.ordinal) pagerState.scrollToPage(state.mode.ordinal)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SearchTopBar(
                query = state.query,
                // #16 submit-driven: commit the query text then run the search on the IME search
                // action / clear (OnQueryChange no longer searches on its own).
                onSearch = {
                    onIntent(SearchIntent.OnQueryChange(it))
                    onIntent(SearchIntent.OnSubmit)
                },
                onClose = { onIntent(SearchIntent.OnClose) },
                onOpenFilters = { showFilters = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The redesigned [SearchTopBar] owns its own status-bar inset via `statusBarsPadding()` (it
        // replaced an M3 `TopAppBar`, which had supplied that inset). Zero the Scaffold insets so the
        // top status bar isn't padded twice; the result lists carry their own `LocalBottomBarPadding`
        // bottom inset for the floating nav.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // GAP-SRCH-03: legacy used a two-segment filter-chip `ChipsRow` (Search / Multi Search)
            // with a Check leading icon on the selected chip — NOT an M3 underline TabRow.
            SearchModeChipsRow(
                mode = state.mode,
                onModeChange = { onIntent(SearchIntent.OnModeTabChange(it)) },
            )
            // P3-SRCH-Low (native parity): native showed NO empty-state message before the first
            // query — an idle Search overlay rendered just the banner + an empty grid/list
            // (`MangaSearchItems.kt:54-72` / `MultiRepoResults.kt:59-77`). Distinguish the pristine
            // idle state (no search has run) so the "No results found" panel is suppressed until the
            // user actually searches; an explicit empty state AFTER a real zero-result search is
            // retained as an intentional UX/a11y improvement over native's silent empty grid. Derived
            // from `hasSearched` (not `query.isBlank()`) because a genre/sort browse runs with a
            // deliberately blank query yet must still surface the zero-result empty state.
            val isIdle = !state.hasSearched
            HorizontalPager(state = pagerState, pageSpacing = 16.dp) { page ->
                when (page) {
                    0 -> SingleResults(
                        state = state.single,
                        isIdle = isIdle,
                        onMangaClick = { onIntent(SearchIntent.OnMangaClick(it)) },
                        // Retry the failed single search with its exact recorded params (genre/sort
                        // browse runs blank-query; OnSubmit would hit the blank guard and clear to idle).
                        onRetry = { onIntent(SearchIntent.OnRetrySingle) },
                        onOpenInWebView = onOpenInWebView,
                        onHelp = onHelp,
                        coverModel = coverModel,
                    )
                    else -> MultiRepoResults(
                        results = state.multi,
                        isIdle = isIdle,
                        onMangaClick = { onIntent(SearchIntent.OnMangaClick(it)) },
                        coverModel = coverModel,
                    )
                }
            }
        }
    }

    if (showFilters) {
        SearchFilterSheet(
            filters = state.filters,
            selections = state.selections,
            // F1 (native parity): filter changes fire an IMMEDIATE search and the sheet stays OPEN
            // so results update live behind it (legacy `SearchBottomSheet` wired the chip/dropdown
            // straight to `MangaViewModel.onGenreClicked` / `onSortClick`; the generic renderer
            // generalizes that to every control type). `showFilters` is only flipped off by the
            // bottom button (= `onDismiss`) or a sheet swipe-down.
            onFilterChange = { id, values -> onIntent(SearchIntent.OnFilterChange(id, values)) },
            onResetFilters = { onIntent(SearchIntent.OnResetFilters) },
            onDismiss = { showFilters = false },
        )
    }
}

/**
 * GAP-SRCH-03: the native Search mode selector is a two-segment filter-chip `ChipsRow`
 * (Search / Multi Search) with an [Icons.Default.Done] leading icon on the selected chip —
 * reproduced here as a `FilterChip` Row, NOT an M3 underline `TabRow`. Tapping a chip dispatches the
 * mode change (the pager stays in sync via the host's `LaunchedEffect(state.mode)`). See
 * [SearchModeChip] for the S-5 native chip styling (primary fill + primary border + `Done` tint).
 *
 * Redesign 2026-06: the row picks up the mockup's `.pills` metrics (horizontal-20 inset, a small
 * inter-pill gap) and the chips are fully-rounded coral pills — the selection semantics, the `Done`
 * leading check, and the mode-change dispatch are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchModeChipsRow(
    mode: SearchModeTab,
    onModeChange: (SearchModeTab) -> Unit,
) {
    val spacing = LocalSpacing.current
    // Redesign mockup `.pills`: `padding:2px 20px` with a ~9px inter-pill gap. The per-chip
    // `padding(horizontal = 4.dp)` from native is retained on [SearchModeChip], so the visible gap is
    // the chip padding (8dp combined) — close to the mockup — without a double `spacedBy` here.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        SearchModeChip(
            label = stringResource(Res.string.search_tab_single),
            selected = mode == SearchModeTab.SINGLE,
            onClick = { onModeChange(SearchModeTab.SINGLE) },
        )
        SearchModeChip(
            // P3-SRCH-Low (native parity): native label is 'Multi Search' (native multi_search); the
            // base search_tab_multi value was 'Multi search'. Use the cased pfix key to match native.
            label = stringResource(Res.string.search_pfix_tab_multi),
            selected = mode == SearchModeTab.MULTI,
            onClick = { onModeChange(SearchModeTab.MULTI) },
        )
    }
}

/**
 * S-5 (native parity): faithful port of native `ChipItemRow` (`ChipsRow.kt:43-83`) — a solid
 * primary-colored selected chip with an [Icons.Default.Done] check tinted `onPrimary`, a primary
 * `filterChipBorder` when selected, and `defaultMinSize(minHeight = 32.dp)` + `padding(horizontal =
 * 4.dp)`. (Native's mode selector is the primary-filled chip; this is distinct from the genre chips
 * in the filter sheet, which keep the tonal `primaryContainer` + `Check` styling.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            null
        },
        modifier = Modifier
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = 4.dp),
        // Redesign mockup: fully-rounded coral pills (`.pill` border-radius:999px).
        shape = RoundedCornerShape(50),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/**
 * Redesign 2026-06 search bar — matches the approved Search mockup (`design/redesign/screens/
 * search.html`, the RIGHT "Redesign" phone): a rounded-14 ghost back/close button + a full-width
 * rounded-16 pill search field (leading search glyph, placeholder, inline clear-X) with the
 * filter/sort affordance pinned to the field's trailing edge. Owns its own status-bar inset (it
 * replaced an M3 `TopAppBar` in the `Scaffold` `topBar` slot, which previously supplied that inset).
 *
 * Behaviour is byte-identical to the prior `TopAppBar`-based bar (no MVI changes):
 *  - GAP-SRCH-05: submit-driven — typing updates local state only; the search fires on the IME
 *    Search action (then the keyboard hides), not on every keystroke.
 *  - P3-SRCH-Low (intentionally-different): the clear-X resets the query AND re-runs an empty search
 *    so clearing the box also clears stale results (a deliberate prior decision over native, which
 *    reset only the text field; unchanged here).
 *  - The filter affordance keeps the `search_filters` ("Filters") a11y label — it opens the
 *    filter/sort sheet, so "Filters" is the accurate description.
 */
@Composable
private fun SearchTopBar(
    query: String,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var localQuery by rememberSaveable(query) { mutableStateOf(query) }
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Rounded ghost back/close button (mockup: 42dp, rounded-14, surfaceVariant ghost fill) —
        // mirrors the HomeScreen `HeaderAction` circular-button look.
        Surface(
            onClick = onClose,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = KiraIcons.Close,
                    contentDescription = stringResource(Res.string.search_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // Full-width rounded pill field: leading search glyph + text field + inline clear-X + the
        // filter/sort affordance pinned to the trailing edge (mockup: 48dp, rounded-16, card surface
        // with a hairline border).
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = KiraIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = localQuery,
                onValueChange = { localQuery = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelLarge.merge(
                    TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onSearch(localQuery)
                        keyboardController?.hide()
                    },
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    // Box so the placeholder sits BEHIND the input (overlay), not stacked above it.
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (localQuery.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.search_hint),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (localQuery.isNotEmpty()) {
                KiraIconButton(
                    icon = KiraIcons.Close,
                    contentDescription = stringResource(Res.string.search_clear),
                    onClick = {
                        localQuery = ""
                        onSearch("")
                    },
                )
            }
            // Filter/sort affordance pinned to the field's trailing edge (mockup). Tinted coral
            // (`primary`) to read as the brand accent, matching the mockup's accent-colored glyph.
            Icon(
                imageVector = KiraIcons.Tune,
                contentDescription = stringResource(Res.string.search_filters),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onOpenFilters),
            )
        }
    }
}

@Composable
private fun SingleResults(
    state: UiState<List<HomeFeedItem>>,
    isIdle: Boolean,
    onMangaClick: (HomeFeedItem) -> Unit,
    onRetry: () -> Unit,
    onOpenInWebView: () -> Unit,
    onHelp: () -> Unit,
    coverModel: ((HomeFeedItem) -> Any?)?,
) {
    when (state) {
        UiState.Loading -> CenteredSpinner()
        // F3 (native parity): the legacy single-source error screen offered THREE recovery actions —
        // Retry + Open-in-WebView + Help (legacy `ErrorScreen` with `onOpenInBrowser` + `onHelp`).
        // Open-in-WebView is the primary path past 403/Cloudflare blocks; Help opens the help video.
        // Use the multi-action `KiraErrorState` overload, mirroring the Home feed error surface.
        is UiState.Error -> KiraErrorState(
            message = rememberSearchErrorMessages()(state.error),
            retryLabel = stringResource(Res.string.retry),
            onRetry = onRetry,
            openInWebViewLabel = stringResource(Res.string.home_error_open_in_webview),
            onOpenInWebView = onOpenInWebView,
            helpLabel = stringResource(Res.string.home_help),
            onHelp = onHelp,
        )
        is UiState.Success -> {
            if (state.data.isEmpty()) {
                // P3-SRCH-Low (native parity): suppress the empty-state panel on the pristine idle
                // state (no query yet) — native rendered just an empty grid there. Keep the explicit
                // "No results found" message only after a real zero-result search.
                if (!isIdle) {
                    KiraEmptyState(title = stringResource(Res.string.search_empty))
                }
            } else {
                LazyVerticalGrid(
                    // GAP-SRCH-01: match legacy `MangaSearchItems` Adaptive(minSize = 160.dp).
                    // P3-SRCH-Low (native parity): native `MangaSearchItems` grid uses asymmetric
                    // content padding (horizontal = 8.dp, vertical = 4.dp) with NO grid arrangement
                    // spacing, and instead carries an 8.dp padding on each card
                    // (`MangaSearchItems.kt:55-56,90`). Reproduce that spacing model exactly: drop the
                    // `spacedBy` arrangement and apply the per-cell 8.dp padding at the call site (the
                    // shared `HomeFeedGridCard` is out of this screen's edit scope, so the padding is
                    // passed via its `modifier` slot rather than baked into the card).
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    // Bottom inset clears the floating nav (when shown over Home) so the last result
                    // stays reachable; harmless (0) when the nav is hidden.
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 4.dp,
                        bottom = 4.dp + LocalBottomBarPadding.current,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(
                        items = state.data,
                        key = { it.feedKey() },
                    ) { item ->
                        HomeFeedGridCard(
                            item = item,
                            onClick = onMangaClick,
                            coverModel = coverModel,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Multi-repo aggregated results: one section per source api, each a title + a horizontal [LazyRow]
 * of cover cards, with per-section Loading / Error / Success drawn from the source's own
 * [UiState] (legacy `MultiRepoResults` / `RepoSection`).
 */
@Composable
private fun MultiRepoResults(
    results: Map<String, UiState<List<HomeFeedItem>>>,
    isIdle: Boolean,
    onMangaClick: (HomeFeedItem) -> Unit,
    coverModel: ((HomeFeedItem) -> Any?)?,
) {
    val spacing = LocalSpacing.current
    if (results.isEmpty()) {
        // P3-SRCH-Low (native parity): native rendered nothing (just the banner + an empty list)
        // when the multi-repo map was empty (`MultiRepoResults.kt:59-77`). Suppress the empty-state
        // panel on the pristine idle state (no query yet); keep it only after a real fan-out that
        // returned nothing, as an intentional UX/a11y improvement over native's silent empty list.
        if (!isIdle) {
            KiraEmptyState(title = stringResource(Res.string.search_empty))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // GAP-MULT-01: legacy `MultiRepoResults` `LazyColumn` used 16.dp padding + 24.dp section
        // spacing. Moved to contentPadding (was a Modifier.padding) so sections can scroll under the
        // floating nav; bottom inset adds the nav footprint so the last section clears it.
        contentPadding = PaddingValues(
            start = spacing.lg,
            end = spacing.lg,
            top = spacing.lg,
            bottom = spacing.lg + LocalBottomBarPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        results.forEach { (api, state) ->
            item(key = api) {
                RepoSection(
                    api = api,
                    state = state,
                    onMangaClick = onMangaClick,
                    coverModel = coverModel,
                )
            }
        }
    }
}

@Composable
private fun RepoSection(
    api: String,
    state: UiState<List<HomeFeedItem>>,
    onMangaClick: (HomeFeedItem) -> Unit,
    coverModel: ((HomeFeedItem) -> Any?)?,
) {
    val spacing = LocalSpacing.current
    Column {
        Text(
            text = api,
            // GAP-MULT-01: legacy section header was `titleLarge` primary (was `titleMedium`).
            // P3-SRCH-Low (native parity): native uses `titleLarge`'s DEFAULT weight with no weight
            // override (`MultiRepoResults.kt:93-98`); the prior explicit bold override made the header
            // heavier than native. Drop the override to match native exactly.
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        when (state) {
            UiState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is UiState.Error -> Text(
                text = rememberSearchErrorMessages()(state.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.search_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // P3-SRCH-Low (native parity): native `MultiRepoResults` `LazyRow` uses 12.dp item
                    // spacing + a horizontal 4.dp content padding (`MultiRepoResults.kt:116-118`); the
                    // prior 8.dp `spacing.sm` spacing with no content padding ran the cards tighter and
                    // flush to the edge. Match native's metrics exactly.
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        items(
                            items = state.data,
                            key = { it.feedKey() },
                        ) { item ->
                            MultiRepoCard(item = item, onClick = onMangaClick, coverModel = coverModel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * GAP-MULT-01: faithful port of the legacy `MultiSearchItem` card — a `Card(140×200.dp, rounded 8,
 * elevation 4)` with a full-bleed Crop cover and a **top-start** translucent (`Black@.6`, rounded
 * bottomEnd 4) title label, `bodySmall` white 1-line ellipsis. (Distinct from the bottom-banded
 * `HomeFeedGridCard` — the multi-repo card labels the cover at the top-start corner.)
 */
@Composable
private fun MultiRepoCard(
    item: HomeFeedItem,
    onClick: (HomeFeedItem) -> Unit,
    coverModel: ((HomeFeedItem) -> Any?)?,
) {
    // #32: thread the source-aware Coil model (ImageRequest or null) through to KiraCoverImage.
    val coverModelValue = coverModel?.invoke(item)
    Card(
        modifier = Modifier
            .width(140.dp)
            .height(200.dp)
            .clickable { onClick(item) },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            KiraCoverImage(
                coverUrl = item.coverUrl,
                model = coverModelValue,
                contentDescription = item.title,
                aspectRatio = null,
                shape = RoundedCornerShape(14.dp),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(bottomEnd = 4.dp),
                    )
                    // P3-SRCH-Low (native parity): native `MultiSearchItem` title label uses
                    // `padding(horizontal = 6.dp, vertical = 4.dp)` (`MultiRepoResults.kt:178`); the
                    // prior 2.dp vertical padding ran the label tighter. Match native's 4.dp.
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun rememberSearchErrorMessages(): (AppError) -> String {
    val network = stringResource(Res.string.error_network)
    // P1 parity: native distinguishes network failures by HTTP status code / transport failure
    // (`State.kt` `httpStatusMessage` + `fromException`); codes native does not name individually
    // fall back to the generic `error_network`.
    val noConnectivity = stringResource(Res.string.error_network_no_connectivity)
    val timeout = stringResource(Res.string.error_network_timeout)
    val badRequest = stringResource(Res.string.error_network_bad_request)
    val unauthorized = stringResource(Res.string.error_network_unauthorized)
    val forbidden = stringResource(Res.string.error_network_forbidden)
    val notFound = stringResource(Res.string.error_network_not_found)
    val requestTimeout = stringResource(Res.string.error_network_request_timeout)
    val server = stringResource(Res.string.error_network_server)
    val badGateway = stringResource(Res.string.error_network_bad_gateway)
    val serviceUnavailable = stringResource(Res.string.error_network_service_unavailable)
    val gatewayTimeout = stringResource(Res.string.error_network_gateway_timeout)
    val storage = stringResource(Res.string.error_storage)
    val validation = stringResource(Res.string.error_validation)
    val auth = stringResource(Res.string.error_auth)
    val platform = stringResource(Res.string.error_platform)
    val cancelled = stringResource(Res.string.library_cancelled)
    val unexpected = stringResource(Res.string.error_occurred)
    return { error ->
        when (error) {
            is AppError.Network -> when (error) {
                is AppError.Network.NoConnectivity -> noConnectivity
                is AppError.Network.Timeout -> timeout
                is AppError.Network.Serialization -> network
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
                    else -> network
                }
            }
            is AppError.Storage -> storage
            is AppError.Validation -> validation
            is AppError.Auth -> auth
            is AppError.Platform -> platform
            is AppError.Cancelled -> cancelled
            is AppError.Unexpected -> unexpected
        }
    }
}

// region Previews

private fun previewResults(n: Int) = (1..n).map { i ->
    HomeFeedItem(
        api = "MangaDex",
        language = "en",
        title = "Result $i",
        url = "u/$i",
        coverUrl = "",
        rating = null,
        genres = emptyList(),
        recentChapters = emptyList(),
    )
}

@Preview
@Composable
private fun SearchScreenSinglePreview() {
    SearchScreenContent(
        state = SearchState(
            query = "naruto",
            mode = SearchModeTab.SINGLE,
            single = UiState.Success(previewResults(8)),
            filters = listOf(
                SourceFilter(
                    id = "genres",
                    label = "genres",
                    type = FilterControlType.MULTISELECT,
                    options = listOf("Action", "Romance").map { FilterOption(it, it) },
                ),
                SourceFilter(
                    id = "sort",
                    label = "sort",
                    type = FilterControlType.SELECT,
                    options = listOf("Latest", "Popular").map { FilterOption(it, it) },
                ),
            ),
        ),
        effects = kotlinx.coroutines.flow.emptyFlow(),
        onIntent = {},
        onNavigateToDetails = { },
        onClose = {},
    )
}

@Preview
@Composable
private fun SearchScreenMultiPreview() {
    SearchScreenContent(
        state = SearchState(
            query = "one piece",
            mode = SearchModeTab.MULTI,
            multi = mapOf(
                "MangaDex" to UiState.Success(previewResults(4)),
                "Comick" to UiState.Loading,
                "Team-X" to UiState.Error(AppError.Network.NoConnectivity()),
            ),
        ),
        effects = kotlinx.coroutines.flow.emptyFlow(),
        onIntent = {},
        onNavigateToDetails = { },
        onClose = {},
    )
}

// endregion
