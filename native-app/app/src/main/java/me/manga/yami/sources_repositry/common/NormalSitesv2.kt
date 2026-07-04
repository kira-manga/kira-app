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

abstract class NormalSitesv2 (
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository,) {
    abstract override val mangaSource: MangaSource
    abstract val homeUrl : String
    abstract val popularUrl : String

    abstract override val defaultHeaders: Map<String, String>

    abstract fun handelLoadMoreUrl(page: Int) : String
    abstract fun handelSearchUrl(searchType: SearchType) : String

    open var customParseHome: Boolean = false

    open var useGetForHome: Boolean = true
    open var useGetForSearch: Boolean = true
    open var useGetForNormalSearch: Boolean = true
    open var useGetForGenresSearch: Boolean = true
    open var useGetForSortSearch: Boolean = true
    open var useGetForPopular: Boolean = true
    open var useGetForChapters: Boolean = true
    open var useGetForInfo: Boolean = true


    abstract override val sortTypes: Set<String>
    abstract override val allGenres: Set<String>
    abstract override val blackListGenres: Set<String>


    abstract fun handelFormBody(page:Int = 1,popular: Boolean): FormBody?

    open fun handelFormBodyPopular(page:Int = 1,popular: Boolean): FormBody? = handelFormBody(page,popular)
    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchDataWithHeaders({
            if (useGetForPopular){
                api.get(popularUrl,defaultHeaders)
            } else{

                api.post(popularUrl, headers = defaultHeaders, body = handelFormBodyPopular(0,true))
            }

        }) { html -> extractMangaList(html) }

    open fun handelFormBodyHome(page:Int = 1,popular: Boolean): FormBody? = handelFormBody(page,popular)

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)
    fun fetchMangaHome(url : String,page:Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
//            Log.i("sjghfdgdsfgsdfgdfggsdfgsdfgdfgsfd",url)
            if (useGetForHome){
                Log.i("csdfdsfsdfasdasfdsfsdsd1",url)

                api.get(url, headers = defaultHeaders)
            } else{

                api.post(url, headers = defaultHeaders, body = handelFormBodyHome(page,false))
            }


        }){  html ->


            extractHomeMangaItems(html)


        }
    fun fetchMangaHomeCustom(url : String,page:Int = 1): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            if (useGetForHome){
                Log.i("csdfdsfsdfasdasfdsfsdsd2",url)

                api.get(url, headers = defaultHeaders)
            } else{

                api.post(url, headers = defaultHeaders, body = handelFormBodyHome(page,false))
            }


        }){  html ->
                extractCustomHomeMangaItems(html)


        }


    fun searchFormBody(searchType: SearchType): FormBody? =
        when (searchType) {
            is SearchType.Normal  -> normalSearchFormBody(searchType)
            is SearchType.GENRES  -> genresSearchFormBody(searchType)
            is SearchType.SORT    -> sortFormBody(searchType)
        }

    abstract fun normalSearchFormBody(searchType: SearchType.Normal): FormBody?
    abstract fun genresSearchFormBody(searchType: SearchType.GENRES): FormBody?
    abstract fun sortFormBody(searchType: SearchType.SORT): FormBody?

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        Log.i("adflkdasadassjgklasfgfdgdfg",url.toString())
        return  if (useGetForNormalSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }
    override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>>  {
        val url = handelSearchUrl(searchType)

        return  if (useGetForGenresSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }  }
    override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)

        return if (useGetForSortSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({ api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html -> getSearchResults(html)}
        }
    }

    open fun handelFormBodyChapter(page:Int = 1,popular: Boolean): FormBody? = handelFormBody(page,popular)

   open override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({
            Log.i("asdasdakdlvlcxkvmcxcxvxc1",useGetForChapters.toString())
            Log.i("asdasdakdlvlcxkvmcxcxvxc3",defaultHeaders.toString())

            if (useGetForChapters){
                api.get(url, headers = defaultHeaders)}
            else{

                api.post(url, headers = defaultHeaders, body = handelFormBodyChapter(0,false))
            }
        }) { html -> getChapterImages(html) }


    open fun handelFormBodyMangaInfo(page:Int = 1,popular: Boolean): FormBody? = handelFormBody(page,popular)


    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> = fetchDataWithHeaders({

        if (useGetForInfo){ api.get(query, headers = defaultHeaders) }else { api.post(query, headers = defaultHeaders, body = handelFormBodyMangaInfo(0,false))} })  { html ->  extractMangaInfo(html,query) }

























































    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
        flow {
            emit(State.Loading as State<List<MangaItem>>)
            val url = handelLoadMoreUrl(page)

            Log.i("csdfdsfsdfasdasfdsfsdsd3",url)
            val fetcher = if (customParseHome) fetchMangaHomeCustom(url,page) else fetchMangaHome(url,page)
            fetcher.collect { state ->

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




    abstract fun extractCustomHomeMangaItems(string: String): MutableList<MangaItem>


    abstract fun extractHomeMangaItems(string: String): MutableList<MangaItem>
    abstract fun extractMangaList(string: String): List<PopularManga>
    abstract suspend fun extractMangaInfo(string: String, baseUrl : String): MangaInfo
    abstract suspend fun getSearchResults(string: String): List<MangaItem>
    abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
    abstract fun getChapterImages(string: String): List<String>
}