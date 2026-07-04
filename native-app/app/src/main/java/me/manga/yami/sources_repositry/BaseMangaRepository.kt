package me.manga.yamiapk.sources_repositry


import android.content.Context
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType

abstract class BaseMangaRepository {

    abstract val BASE_URL : String
    abstract val URL_VERSION : Int
    abstract var baseUrl : String

    abstract var imgBaseUrl : String
    abstract var imgUrlVersion : Int

    abstract val API : String
    abstract val LANGUAGE : String
    abstract val ICON : Int
    abstract val PRIORITY : Int
    abstract val blackListGenres: Set<String>
    abstract val sortTypes: Set<String>
    abstract val allGenres: Set<String>
   abstract val defaultHeaders: Map<String, String>
    abstract suspend fun fetchSearchDataF(searchType : SearchType): Flow<State<List<MangaItem>>>
    abstract fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>>
    abstract suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>>
    abstract fun fetchChapterDataF(url: String): Flow<State<List<String>>>
    abstract fun fetchMoreManga(page: Int, currentItems: List<MangaItem>? = null): Flow<State<List<MangaItem>>>
    abstract suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>>
    abstract fun buildImageRequest( context: Context, url: String,screenWidthPx: Int, ):ImageRequest
    abstract suspend fun  refreshHeaders( newHeaders:  Map<String, String> )

    abstract fun buildItemsImageRequest( context: Context, url: String,screenWidthPx: Int, ):ImageRequest
    abstract suspend fun getBaseUrl(): String
    open suspend fun initSite(): Int {return 0}

}

fun String.dropTrailingSlash(): String =
    if (this.endsWith("/")) this.dropLast(1) else this