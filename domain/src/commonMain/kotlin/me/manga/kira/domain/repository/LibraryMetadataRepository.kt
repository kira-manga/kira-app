package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator

/** Narrow cover-reconciliation port for legacy callers without a data-layer dependency. */
interface LibraryMetadataRepository {
    /** Revalidate both addresses and the retained ID inside the writer; blank covers are ignored. */
    suspend fun updateCoverIfChanged(
        owner: SavedWorkIdentity,
        fetched: WorkLocator,
        newCoverUrl: String,
    ): AppResult<Unit>
}
