package me.manga.kira.work

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient

/** Models the facade's real return contracts, not a second implementation of refresh policy. */
internal class LibraryRefreshWorkTestFixtures : LibraryRefreshWorkPort {
    var libraryFlow: Flow<List<SavedMangaEntity>> = flowOf(listOf(refreshManga(1)))
    var chapterFlow: (Long) -> Flow<List<SavedChapterEntity>> = { flowOf(emptyList()) }
    var missingSource = false
    var fetch: suspend (Manga) -> AppResult<MangaDetails> = { AppResult.Success(refreshDetails(it)) }
    var cover: suspend () -> Unit = {}
    var write: suspend (List<SavedChapterEntity>) -> List<Long> = { rows -> rows.indices.map { it + 1L } }
    var lastSuccess = "old success"
    var stamps = 0
    var stamp: suspend () -> Unit = {
        lastSuccess = "new success"
        stamps++
    }
    val inserts = mutableListOf<List<SavedChapterEntity>>()
    val notifications = mutableListOf<List<SavedChapterEntity>>()

    override fun library() = libraryFlow

    override fun chapters(mangaId: Long) = chapterFlow(mangaId)

    override suspend fun updateCover(
        mangaId: Long,
        coverUrl: String,
    ) = cover()

    override suspend fun insert(chapters: List<SavedChapterEntity>): List<Long> {
        inserts += chapters
        return write(chapters)
    }

    override fun notify(
        manga: SavedMangaEntity,
        chapters: List<SavedChapterEntity>,
    ) {
        notifications += chapters
    }

    override suspend fun stampLastSuccess() = stamp()

    override fun source(api: String): MangaSourceClient? {
        val apiKey = api
        return if (missingSource) {
            null
        } else {
            object : MangaSourceClient {
                override val api = apiKey

                override suspend fun details(manga: Manga) = fetch(manga)

                override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = error("unused")

                override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = error("unused")

                override suspend fun search(
                    query: String,
                    page: Int,
                    filters: FilterSelections,
                ): AppResult<List<HomeFeedItem>> = error("unused")

                override fun pages(
                    manga: Manga,
                    chapter: Chapter,
                ): Flow<AppResult<List<Page>>> = error("unused")
            }
        }
    }
}

internal fun refreshManga(id: Long) =
    SavedMangaEntity(
        id = id,
        api = "test",
        language = "en",
        url = "m/$id",
        imageUrl = "old",
        title = "$id",
        description = "",
        status = "",
        rating = null,
        genres = emptyList(),
        savedTimestamp = 0,
        lastOpenTimestamp = 0,
    )

internal fun refreshDetails(
    manga: Manga,
    count: Int = 1,
) = MangaDetails(
    api = manga.api,
    language = manga.language,
    title = manga.title,
    url = manga.url,
    coverUrl = "new",
    description = "",
    author = "",
    rating = "",
    status = "",
    genres = emptyList(),
    chapters = (1..count).map { Chapter("$it", "$it", "${manga.url}/c/$it", null, false, false) },
)

internal fun cancellingRefreshWork(
    stage: Int,
    pause: suspend () -> Nothing,
) = LibraryRefreshWorkTestFixtures().apply {
    when (stage) {
        0 -> libraryFlow = flow { pause() }
        1 -> write = { pause() }
        2 ->
            stamp = {
                lastSuccess = "committed"
                pause()
            }
    }
}
