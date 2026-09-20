package me.manga.kira.domain.model.library

import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator

/** A fetched response and the lossless address requested before the fetch started. */
data class FetchedWorkDetails(
    val requested: WorkLocator,
    val details: MangaDetails,
)

/** Local details with the retained parent that owns every projected chapter. */
data class SavedWorkDetails(
    val owner: SavedWorkIdentity,
    val details: MangaDetails,
)

/** A refresh cannot substitute a newly saved row for the parent captured before fetching. */
data class LibraryRefreshRequest(
    val owner: SavedWorkIdentity,
    val fetched: FetchedWorkDetails,
)
