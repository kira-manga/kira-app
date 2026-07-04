package me.manga.kira.presentation.home

import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Home screen.
 *
 * Sealed so the reducer's `when` is exhaustive (OCP). Mirrors the legacy `MangaViewModel` +
 * `RepoSettingsViewModel` + `HomeViewModel` action surface.
 */
sealed interface HomeIntent : MviIntent {

    /** Screen becomes visible. Starts the tab/siteState/feed collectors + the first fetch. */
    data object OnEnter : HomeIntent

    /** Pull-to-refresh: re-fetches the feed from page 1 + the featured carousel. */
    data object OnRefresh : HomeIntent

    /** Infinite-scroll trigger: the list reached its end → fetch the next page (guarded). */
    data object OnEndReached : HomeIntent

    /** User tapped a source/language tab. Resets the feed and re-fetches for the new source. */
    data class OnTabSelected(val index: Int) : HomeIntent

    /** Grid ↔ list layout toggle. */
    data object OnToggleGridView : HomeIntent

    /** Open/close the search overlay (legacy `MangaViewModel.isSearching`). */
    data object OnToggleSearch : HomeIntent

    /** User tapped a feed item's cover/title → navigates to Details. */
    data class OnMangaClick(val item: HomeFeedItem) : HomeIntent

    /** User tapped a recent-chapter chip on a feed item → navigates to the Reader. */
    data class OnChapterClick(val item: HomeFeedItem, val chapterRef: HomeChapterRef) : HomeIntent

    /** User tapped the heart/save affordance on a feed item → toggles library membership. */
    data class OnSaveToggle(val item: HomeFeedItem) : HomeIntent

    /** User chose "open in WebView" for the active source. */
    data object OnOpenWebView : HomeIntent

    /** User tapped the edit-sources affordance → navigates to the Sources screen. */
    data object OnEditTabs : HomeIntent

    /** User tapped the help affordance → shows the help dialog/video. */
    data object OnHelp : HomeIntent

    // #25 (DEFERRED): the scroll-to-top reselect hook needs a `HomeEffect.ScrollToTop`, but adding
    // that sealed variant breaks the exhaustive `when` in the owner-WIP `HomeScreen.kt` effect
    // collector (forbidden to edit). The intent is shipped with its effect, so both are deferred to
    // the owner together. See AUDIT_IMPLEMENTATION_PLAN B2 #25 prerequisites.
}
