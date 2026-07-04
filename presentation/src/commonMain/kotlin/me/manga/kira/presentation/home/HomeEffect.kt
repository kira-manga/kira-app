package me.manga.kira.presentation.home

import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [HomeViewModel] for the view to perform once and forget.
 *
 * Strict MVI: effects carry only the navigation target / typed error. Destinations are expressed
 * in *domain terms* (api + url + identity fields); the `:composeApp` route adapter is the only
 * layer that maps them onto `Screen.X` keys — `:presentation` + `:ui` stay route-agnostic, the
 * same guardrail `DetailsEffect` follows.
 */
sealed interface HomeEffect : MviEffect {

    /**
     * Navigate to the Details screen for a tapped feed item.
     *
     * Carries the FULL manga identity tuple the feed item already holds (api + language + title +
     * url + coverUrl + rating + genres), not just `(api, mangaUrl)`. The `:composeApp` adapter maps
     * this to the full-tuple rework Details route so the Details VM enters via `OnEnter(fullManga)`
     * — library membership, title and cover bind immediately instead of flashing empty until the
     * network fetch lands (regression fix: a saved manga opened from Home no longer "acts fresh").
     */
    data class NavigateToDetails(
        val api: String,
        val language: String,
        val title: String,
        val mangaUrl: String,
        val coverUrl: String,
        val rating: Int?,
        val genres: List<String>,
    ) : HomeEffect

    /**
     * Navigate to the Reader for a tapped recent-chapter chip.
     *
     * Carries the full legacy `ChapterImagesFragment` arg set the route adapter needs to build the
     * reader nav target (matches the legacy chapter-chip-tap path on the Home grid). The rework
     * reader's legacy-args adapter (`ChapterImagesByLegacyArgsReworkScreenRoute`, ADR-8) consumes
     * exactly this shape.
     */
    data class NavigateToReader(
        val api: String,
        val language: String,
        val title: String,
        val mangaUrl: String,
        val coverUrl: String,
        val chapterNumber: String,
        val chapterUrl: String,
        val isDownloaded: Boolean,
    ) : HomeEffect

    /** Navigate to the WebView for [url], scoped to source [api]. */
    data class NavigateToWebView(val url: String, val api: String) : HomeEffect

    /** Navigate to the Sources (edit-tabs) screen. */
    data object NavigateToSources : HomeEffect

    /** Show a non-blocking error toast / snackbar for a feed/featured fetch failure. */
    data class ShowError(val error: AppError) : HomeEffect

    /** Show the help dialog / video. */
    data object ShowHelp : HomeEffect

    // #25 (DEFERRED — do NOT add a `ScrollToTop` HomeEffect variant here): the Home effect
    // collector in the owner-WIP `HomeScreen.kt` is an exhaustive `when (effect)` with no `else`,
    // so introducing a new sealed variant forces a branch edit in that forbidden file (compile
    // error otherwise). The scroll-to-top reselect hook therefore stays fully deferred to the owner
    // — both the effect and its consumption land together when HomeScreen.kt can be edited. See
    // AUDIT_IMPLEMENTATION_PLAN B2 #25 prerequisites.
}
