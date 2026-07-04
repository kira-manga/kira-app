package me.manga.yamiapk.sources_repositry.common

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.FormBody

abstract class NormalSites (
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository) {
    abstract override val mangaSource: MangaSource
    abstract val homeUrl : String
    abstract val popularUrl : String
    open var homeGet = true
    var searchGet = true

    abstract fun handelLoadMoreUrl(page: Int) : String

    abstract fun handelSearchUrl(searchType: SearchType) : String
    abstract override val sortTypes: Set<String>
    abstract override val allGenres: Set<String>
    abstract override val blackListGenres: Set<String>
    abstract override val defaultHeaders: Map<String, String>

     override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)

    abstract fun handelFormBody(page:Int = 0,popular: Boolean): FormBody?

     fun searchFormBody(searchType: SearchType): FormBody? =
        when (searchType) {
            is SearchType.Normal  -> normalSearchFormBody(searchType)
            is SearchType.GENRES  -> genresSearchFormBody(searchType)
            is SearchType.SORT    -> sortFormBody(searchType)
        }



    fun fetchMangaHome(url : String,page:Int = 0): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            Log.i("asdasdasdasdasdasfetchMangaHome1",url)


            if (homeGet){
                api.get(url, headers = defaultHeaders)
            } else{
                Log.i("asdasdasdasdasdasfetchMangaHome2",url)
                Log.i("asdasdasdasdasdasfetchMangaHome3",handelFormBody(page,false).toString())

                api.post(url, headers = defaultHeaders, body = handelFormBody(page,false))
            }


        }){  html -> extractHomeMangaItems(html)}



    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchDataWithHeaders({
            Log.i("asdasdasdasdasdasfetchPopularManga1",popularUrl)

            if (homeGet){
                Log.i("asdasdasdasdasdasfetchPopularManga2",popularUrl)

                api.get(popularUrl)
            } else{

                api.post(popularUrl, headers = defaultHeaders, body = handelFormBody(0,true))
            }

        }) { html -> extractMangaList(html) }


    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> = fetchDataWithHeaders({

        defaultHeaders.logHeaders()

        api.get(query, headers = defaultHeaders)


    })  { html ->  extractMangaInfo(html,query) }

    private fun Map<String, String>.logHeaders(
        tag: String = "manga-headers",
        bigTag: String = "manga-headers-big",
        bigThreshold: Int = 100,
        chunkSize: Int = 1500
    ) {
        forEach { (k, v) ->
            val msg = "$k: $v"
            val useTag = if (msg.length > bigThreshold) bigTag else tag
            msg.chunked(chunkSize).forEach { part ->
                Log.i(useTag, part)
            }
        }
    }



    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }) { html -> getChapterImages(html) }




    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Loading as State<List<MangaItem>>)
            val url = handelLoadMoreUrl(page)

            fetchMangaHome(url,page).collect { state ->


                when (state) {
                    is State.Success -> {
                        val newItems = state.toData() ?: emptyList()
                        val mergedList = (currentItems?.toMutableList() ?: mutableListOf()).apply {
                            addAll(newItems)
                        }
                        emit(
                            State.Success(
                                if (newItems.isEmpty()) (currentItems ?: emptyList()) else mergedList
                            )
                        )
                    }

                    is State.Error -> emit(state)
                    else -> Unit
                }
            }
        }.catch { e ->
            emit(State.Error(0,e.localizedMessage ?: "Unknown error occurred"))
        }



    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        Log.i("asfjlafksdfdsadfsdfsdfasdfsad3",url)

        return  if (searchGet) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }

    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>>  {
        val url = handelSearchUrl(searchType)
        return  if (searchGet) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }  }

    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return if (searchGet) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }

    abstract fun normalSearchFormBody(searchType: SearchType.Normal): FormBody?
    abstract fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody?
    abstract fun sortFormBody(searchType: SearchType.SORT): FormBody?



    abstract fun extractHomeMangaItems(string: String): MutableList<MangaItem>
    abstract fun extractMangaList(string: String): List<PopularManga>
    abstract suspend fun extractMangaInfo(string: String, baseUrl : String): MangaInfo
    abstract suspend fun getSearchResults(string: String): List<MangaItem>
    abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
    abstract fun getChapterImages(string: String): List<String>

}