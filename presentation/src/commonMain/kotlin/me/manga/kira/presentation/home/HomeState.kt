package me.manga.kira.presentation.home

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.mvi.MviState

/**
 * Immutable Home screen state.
 *
 * Strict MVI: every property is `val`; the reducer in [HomeViewModel] is the only writer.
 *
 * **UiState shape (matches [me.manga.kira.presentation.details.DetailsState]).** The feed's
 * loading/error/data is modelled as three independent fields ([feed] + [isFeedLoading] +
 * [feedError]) rather than a single sealed envelope — the same split-flag posture `DetailsState`
 * adopted so the screen can keep rendering a stale feed while a refresh is in flight (flip
 * [isRefreshing] without discarding [feed]). The `:ui` reads `isInitialFeedLoading` for the
 * first-load spinner and `feedError` for the error pane. (Search's per-repo map, which can't use
 * parallel flags, uses the `UiState<T>` envelope instead — see `SearchState`.)
 *
 * Legacy parity (`MangaViewModel` + `RepoSettingsViewModel` + `HomeViewModel`):
 *  - [sourceTabs] / [activeTabIndex] mirror the enabled-source tab strip + the selected tab.
 *  - [siteState] gates the feed area (WORKING vs maintenance / stopped / adult) — legacy read it
 *    per-active-source from `getSiteStateFlow`.
 *  - [isGridView] is the grid↔list toggle.
 *  - [featured] is the popular carousel.
 *  - [isRefreshing] / [isLoadingNextPage] back pull-to-refresh + infinite-scroll guards.
 *  - [libraryKeys] holds the WHOLE library's keys, observed once via `ObserveLibraryUseCase`; the
 *    per-card heart state [savedKeys] is derived by intersecting it with the visible feed keys, so
 *    the heart stays in sync with cross-screen toggles without one Room flow per visible item —
 *    mirrors legacy `HomeViewModel.savedManga` (a single observed library list + contains check).
 *  - [savingKeys] holds the feed items whose save toggle is in flight (the toggle round-trip can
 *    fetch chapters before persisting). The per-card bookmark affordance shows an inline spinner
 *    while a key is in this set — legacy `MangaHomeItem` showed a `CircularProgressIndicator` until
 *    `isSaved` updated (GAP-HOME-24).
 *  - [isSearching] is Home's own search-overlay flag (legacy `MangaViewModel.isSearching`); the
 *    overlay-swap route reads it to show the Search surface over Home.
 */
data class HomeState(
    val sourceTabs: List<SourceTab> = emptyList(),
    val activeTabIndex: Int = 0,
    val siteState: SiteState = SiteState.WORKING,
    // Native default: LIST view (popular carousel + detailed rows). `HomeScreen.kt:110`
    // `var isGridView by remember { mutableStateOf(false) }` — false = list. The grid is the
    // user-toggled alternate, so first launch must start on the list layout for parity.
    val isGridView: Boolean = false,
    val feed: List<HomeFeedItem> = emptyList(),
    val isFeedLoading: Boolean = false,
    val feedError: AppError? = null,
    val featured: List<FeaturedManga> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val hasMorePages: Boolean = true,
    val page: Int = 1,
    /**
     * The WHOLE library's keys, projected once from `ObserveLibraryUseCase` for the VM lifetime.
     * [savedKeys] intersects this with the visible feed so the per-card heart reflects library
     * membership without a per-feed-item Room flow (the N+1 the previous heart-sync incurred).
     */
    val libraryKeys: Set<MangaKey> = emptySet(),
    val savingKeys: Set<MangaKey> = emptySet(),
    val isSearching: Boolean = false,
    /**
     * U2 (new-sources badge): true while the catalog holds sources the user hasn't reviewed —
     * the tab strip's edit action shows the "NEW" chip. Cleared on OnEditTabs (native parity).
     */
    val hasNewSources: Boolean = false,
) : MviState {

    /** Convenience: true on the very first feed fetch (no items yet) — drives the full-screen spinner. */
    val isInitialFeedLoading: Boolean get() = isFeedLoading && feed.isEmpty()

    /** The currently-selected source tab, or null if the tab strip hasn't loaded yet. */
    val activeTab: SourceTab? get() = sourceTabs.getOrNull(activeTabIndex)

    /**
     * Library membership of the *visible* feed items — the per-card heart state. Derived by
     * intersecting the whole-library [libraryKeys] with the keys of [feed], so it stays in sync as
     * either the library (cross-screen toggles) or the feed (refresh / pagination) changes, with no
     * per-item observation.
     */
    val savedKeys: Set<MangaKey>
        get() = if (libraryKeys.isEmpty()) {
            emptySet()
        } else {
            feed.asSequence()
                .map { MangaKey(api = it.api, language = it.language, title = it.title) }
                .filter { it in libraryKeys }
                .toSet()
        }
}
