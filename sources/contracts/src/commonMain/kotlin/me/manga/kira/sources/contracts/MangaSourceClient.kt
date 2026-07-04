package me.manga.kira.sources.contracts

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page

/**
 * The single abstraction a "source" satisfies — regardless of whether it is backed by a
 * config-driven generic engine, the bundled config, a cached/remote config, or (temporarily) a
 * legacy Kotlin repository wrapped by an adapter. Callers (`:data`) obtain one from
 * [SourceRegistry] and never know the implementation type.
 *
 * All verbs return pure `:domain` models (never legacy `State`/`MangaInfo`); the legacy adapter
 * does the mapping once at the boundary. Errors are surfaced as [AppResult.Failure]; page loads
 * are a [Flow] because some sources stream pages incrementally.
 *
 * [home]/[search] return the rich [HomeFeedItem] (carrying `recentChapters`, the latest-chapter chips
 * the Home grid renders) so the home/search surfaces lose nothing vs. the legacy path; [featured]
 * returns the lighter [FeaturedManga] (cover + title only, the carousel). [details] takes the
 * [Manga] the user tapped (built from a [HomeFeedItem]).
 */
interface MangaSourceClient {
    /** The source's stable API key (== `MangaSource.API`). */
    val api: String

    suspend fun home(page: Int): AppResult<List<HomeFeedItem>>

    suspend fun featured(page: Int): AppResult<List<FeaturedManga>>

    suspend fun search(query: String, page: Int): AppResult<List<HomeFeedItem>>

    suspend fun details(manga: Manga): AppResult<MangaDetails>

    fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>>
}
