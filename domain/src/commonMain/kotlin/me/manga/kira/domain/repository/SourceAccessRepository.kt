package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.StateFlow
import me.manga.kira.domain.model.sources.SourceAccessState

/** Persists and observes the permanent source-management UI gate. */
interface SourceAccessRepository {
    val state: StateFlow<SourceAccessState>

    /** Returns true only for the first successful permanent activation write. */
    suspend fun activatePermanently(): Boolean
}
