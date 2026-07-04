package me.manga.kira.presentation.search

import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [SearchViewModel].
 *
 * Strict MVI: destinations are expressed in domain terms (api + url); the `:composeApp` route
 * adapter maps them onto `Screen.X` keys, the same guardrail `DetailsEffect` / `HomeEffect` follow.
 */
sealed interface SearchEffect : MviEffect {

    /**
     * Navigate to the Details screen for a tapped search result.
     *
     * Carries the FULL manga identity tuple the result item already holds (api + language + title +
     * url + coverUrl + rating + genres), not just `(api, mangaUrl)`, so the Details VM enters via
     * `OnEnter(fullManga)` and binds library membership / title / cover immediately. Same
     * regression fix + rationale as [HomeEffect.NavigateToDetails].
     */
    data class NavigateToDetails(
        val api: String,
        val language: String,
        val title: String,
        val mangaUrl: String,
        val coverUrl: String,
        val rating: Int?,
        val genres: List<String>,
    ) : SearchEffect

    /** Close the search overlay (return to Home). */
    data object Close : SearchEffect

    /** Show a non-blocking error toast / snackbar for a search failure. */
    data class ShowError(val error: AppError) : SearchEffect
}
