package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import me.manga.kira.core.states.State
import me.manga.kira.domain.model.ChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources_repositry.BaseMangaRepository

/**
 * A minimal in-memory [BaseMangaRepository] for testing the Stage-0 adapter/registry in isolation —
 * it emits fixed legacy models so the test can assert the legacy → domain mapping precisely. No
 * network, no platform deps (the image-request hooks keep their base defaults).
 */
open class FakeLegacyRepo(
    override val API: String,
    private val homeError: State.Error? = null,
    private val homeThrows: Throwable? = null,
) : BaseMangaRepository() {
    override val BASE_URL: String = "https://$API.test"
    override val URL_VERSION: Int = 1
    override var baseUrl: String = BASE_URL
    override var imgBaseUrl: String = "https://img.$API.test"
    override var imgUrlVersion: Int = 1
    override val LANGUAGE: String = "en"
    override val ICON: Int = 0
    override val PRIORITY: Int = 0
    override val blackListGenres: Set<String> = emptySet()
    override val sortTypes: Set<String> = emptySet()
    override val allGenres: Set<String> = emptySet()
    override val defaultHeaders: Map<String, String> = mapOf("Referer" to BASE_URL)

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> =
        flowOf(State.Loading, State.Success(listOf(mangaItem("Search Hit"))))

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = when {
        homeThrows != null -> flow { throw homeThrows }
        homeError != null -> flowOf(State.Loading, homeError)
        else -> flowOf(State.Loading, State.Success(mutableListOf(mangaItem("Home One"), mangaItem("Home Two"))))
    }

    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> =
        flowOf(State.Loading, State.Success(mangaInfo()))

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        flowOf(State.Loading, State.Success(listOf("https://img.$API.test/1.webp", "https://img.$API.test/2.webp")))

    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
        flowOf(State.Loading, State.Success(listOf(mangaItem("More p$page"))))

    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        flowOf(State.Loading, State.Success(listOf(popular("Popular One"))))

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) = Unit
    override suspend fun getBaseUrl(): String = baseUrl

    private fun mangaItem(title: String) = MangaItem(
        api = API, language = LANGUAGE, title = title, url = "$BASE_URL/manga/${title.hashCode()}",
        imageUrl = "https://img.$API.test/${title.hashCode()}.jpg", rating = 7, chapters = null, genres = listOf("Action"),
    )

    private fun popular(title: String) = PopularManga(
        api = API, language = LANGUAGE, title = title, url = "$BASE_URL/popular", imageUrl = "https://img.$API.test/pop.jpg",
    )

    private fun mangaInfo() = MangaInfo(
        api = API, language = LANGUAGE, url = "$BASE_URL/manga/op", title = "One Piece",
        imageUrl = "https://img.$API.test/op.jpg", rating = "9.2", description = "Pirates", author = "Oda",
        genres = listOf("Action", "Adventure"), status = "Ongoing",
        chapters = mutableListOf(
            ChapterItem(number = "1", name = "Romance Dawn", url = "$BASE_URL/op/1", date = LocalDate(2024, 1, 15)),
            ChapterItem(number = "2", name = "Buggy", url = "$BASE_URL/op/2", date = LocalDate(2024, 1, 22)),
        ),
    )
}
