package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.HomeFeedItem

/**
 * Source of search results — single-source and multi-repo aggregated.
 *
 * Strangler-fig boundary over the legacy `BaseMangaRepository.fetchSearchDataF` (single-source,
 * dispatched on the legacy `SearchType`) and the legacy `HomeViewModel`'s multi-repo fan-out. The
 * `:data` impl routes config-backed sources through the generic engine and maps legacy sources'
 * selections onto the legacy `SearchType`; it owns the fan-out cancellation of the previous query
 * (locked decision H-§77-(4)).
 *
 * Results reuse [HomeFeedItem] so search rows render identically to home-feed rows.
 */
interface SearchRepository {

    /**
     * Search the active source with generic filter [selections] (config-driven filters, 2026-07).
     *
     * Routing invariant: a config-backed source ALWAYS runs through the generic engine — filtered
     * or not — and never falls back to legacy filter code (selections its config doesn't declare
     * simply don't exist). A legacy source has its `sort`/`genres` selections translated to the
     * legacy `SearchType` inside `:data`, keeping every legacy source functional unchanged.
     */
    suspend fun searchSource(
        query: String,
        selections: FilterSelections,
    ): AppResult<List<HomeFeedItem>>

    /**
     * Fan out [query] across all enabled repos and stream per-repo results as they arrive.
     *
     * The emitted map is keyed by source `api`; each value is that repo's own state:
     *  - `null` — that repo is still fetching (the consumer renders a per-source spinner). Every
     *    enabled repo is seeded `null` on the first emission so the UI shows a loading section per
     *    source immediately, instead of a misleading "no results" panel while the fan-out runs.
     *  - [AppResult] — that repo's terminal result; one repo failing doesn't sink the others.
     *
     * The impl cancels any in-flight previous query when a new one starts.
     */
    fun searchAllRepos(query: String): Flow<Map<String, AppResult<List<HomeFeedItem>>?>>
}
