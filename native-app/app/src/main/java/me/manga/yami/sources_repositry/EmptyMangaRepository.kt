package me.manga.yamiapk.sources_repositry

import android.content.Context
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType

object EmptyMangaRepository : BaseMangaRepository() {
    override val BASE_URL: String = ""
    override val URL_VERSION: Int
        get() = 0
    override var baseUrl: String = ""
    override var imgBaseUrl: String = " "
    override var imgUrlVersion: Int = 0
    override val API: String = ""
    override val LANGUAGE: String = ""
    override val ICON: Int = 0
    override val PRIORITY: Int = Int.MIN_VALUE
    override val blackListGenres: Set<String> = emptySet()
    override val sortTypes: Set<String> = emptySet()
    override val allGenres: Set<String> = emptySet()
    override val defaultHeaders: Map<String, String> = emptyMap()


    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Success(emptyList()))
        }

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> =
        flow {
            emit(State.Success(mutableListOf()))
        }

    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> =
        flow {
            // adjust defaults to your MangaInfo constructor
            emit(State.Success(

                MangaInfo(
                    api = "",
                    language = "",
                    url = "",
                    title = "",
                    imageUrl = "",
                    rating = "",
                    ratingCount = "",
                    description = "",
                    otherNames = "",
                    author = "",
                    artist = "",
                    genres =listOf(),
                    tags = listOf(),
                    yearOfProduction = "",
                    status = "",
                    favoritesCount = "",
                    chapters = mutableListOf()
                )


            ))
        }

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        flow {
            emit(State.Success(emptyList()))
        }

    override fun fetchMoreManga(
        page: Int,
        currentItems: List<MangaItem>?
    ): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Success(emptyList()))
        }

    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        flow {
            emit(State.Success(emptyList()))
        }

    override fun buildImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .build()

    override fun buildItemsImageRequest(
        context: Context,
        url: String,
        screenWidthPx: Int
    ): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .build()

    override suspend fun getBaseUrl(): String {
        return ""
    }

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) {
        // no-op
    }

}