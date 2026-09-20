package me.manga.kira.presentation.library

import me.manga.kira.domain.model.identity.SavedWorkIdentity

/** Keep the row fence captured by the card; display-title/language changes never re-key selection. */
internal fun LibraryState.togglingSelection(owner: SavedWorkIdentity): LibraryState {
    val next = if (owner in selection) selection - owner else selection + owner
    return copy(selection = next, isInSelectionMode = next.isNotEmpty())
}
