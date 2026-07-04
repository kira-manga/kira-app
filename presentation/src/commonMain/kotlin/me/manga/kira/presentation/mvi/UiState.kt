package me.manga.kira.presentation.mvi

import me.manga.kira.core.error.AppError

/**
 * A small async-content envelope for screens whose loading/error/data has to travel as a *value*
 * — most notably inside a collection (e.g. Search's `Map<String, UiState<...>>` per-repo results),
 * where the split-flag shape that [me.manga.kira.presentation.details.DetailsState] uses
 * (separate `isLoading` / `data` / `error` fields) does not generalize.
 *
 * Strict MVI: instances are immutable. The screen renders a `when` over the three cases. Single-
 * value screens (Home's feed) keep the established split-flag shape from `DetailsState`; this
 * envelope exists for the map-of-states case only, so the two styles do not compete.
 */
sealed interface UiState<out T> {

    /** First load (or a reset) is in flight; no prior data to show. */
    data object Loading : UiState<Nothing>

    /** A successful fetch landed; [data] is the payload (may be empty — render the empty state). */
    data class Success<out T>(val data: T) : UiState<T>

    /** The fetch failed; [error] is the typed cause for the view to localize. */
    data class Error(val error: AppError) : UiState<Nothing>
}
