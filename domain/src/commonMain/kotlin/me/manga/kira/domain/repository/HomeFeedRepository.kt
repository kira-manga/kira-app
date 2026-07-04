package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SearchFilters
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab

/**
 * Source of the Home screen's source tabs, popular carousel, and paginated feed.
 *
 * Strangler-fig boundary over the same legacy `SourcesRepository` + `BaseMangaRepository` feed
 * methods the Details slice already strangles (`getEnabledRepos` / `activeIndexFlow` /
 * `getSiteStateFlow` / `fetchPopularManga` / `fetchMangaHomeF` / `fetchMoreManga` / `sortTypes` /
 * `allGenres` / `activeRepo`). The `:data` impl owns source-routing, the `ManhastroDadosStore`
 * cache clear on tab switch (locked decision H-§77-(3)), and exception → [me.manga.kira.core
 * .error.AppError] mapping. Consumers depend on this interface, never on the impl (DIP §6).
 *
 * Observe (stateful, no [AppResult]) vs fetch (one-shot network, [AppResult]-wrapped):
 *  - The tab list, active-tab index, and per-source site state are app-held state surfaced as
 *    [Flow]s — they don't fail, so they aren't wrapped.
 *  - Home feed / featured fetches are one-shot network calls that can fail, so they return
 *    [AppResult] carrying a typed `AppError` on the failure branch.
 *  - [loadFilters] reads the active source's sort/genre lists, but active-source resolution can
 *    throw (e.g. a Room read error in the active-repo flow), so it returns [AppResult] carrying a
 *    typed `AppError` on failure rather than letting the throw escape raw at this rework boundary.
 */
interface HomeFeedRepository {

    /** Stream of the enabled-source tabs; re-emits when the user edits enabled sources. */
    fun observeSourceTabs(): Flow<List<SourceTab>>

    /** Stream of the active tab index within [observeSourceTabs]. */
    fun observeActiveTabIndex(): Flow<Int>

    /** Stream of the per-source [SiteState] for [api] (maintenance/stopped/adult gating). */
    fun observeSiteState(api: String): Flow<SiteState>

    /** Select the tab at [index]; the impl clears the source cache and resets pagination. */
    suspend fun selectTab(index: Int)

    /** Select a source by [api] directly (e.g. from a multi-repo result tap). */
    suspend fun selectSource(api: String)

    /**
     * Fetch the first page of the active source's home feed.
     *
     * @param reset true to discard any prior pagination state and start from page 1.
     */
    suspend fun fetchHome(reset: Boolean): AppResult<List<HomeFeedItem>>

    /** Fetch the given [page] of the active source's home feed for infinite scroll. */
    suspend fun fetchMore(page: Int): AppResult<List<HomeFeedItem>>

    /** Fetch the active source's popular carousel items. */
    suspend fun fetchFeatured(): AppResult<List<FeaturedManga>>

    /** Read the active source's available sort types + genres for the search filter sheet. */
    suspend fun loadFilters(): AppResult<SearchFilters>
}
