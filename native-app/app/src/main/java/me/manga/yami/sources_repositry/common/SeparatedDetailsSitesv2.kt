package me.manga.yamiapk.sources_repositry.common

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.RequestBody

abstract class SeparatedDetailsSitesv2 (
    val dataStore: DataStoreHelper,
     val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository) {

    abstract override val mangaSource: MangaSource
    abstract val homeUrl : String
    abstract val popularUrl : String

    abstract override val defaultHeaders: Map<String, String>

    abstract fun handelLoadMoreUrl(page: Int) : String
    abstract fun handelSearchUrl(searchType: SearchType) : String


    open var useGetForHome: Boolean = true
    open var useGetForSearch: Boolean = true
    open var useGetForNormalSearch: Boolean = true
    open var useGetForGenresSearch: Boolean = true
    open var useGetForSortSearch: Boolean = true
    open var useGetForPopular: Boolean = true
    open var useGetForChapters: Boolean = true
    open  var useGetForInfo: Boolean = true


    abstract override val sortTypes: Set<String>
    abstract override val allGenres: Set<String>
    abstract override val blackListGenres: Set<String>


    abstract fun handelFormBody(page:Int = 0,popular: Boolean): RequestBody?

    fun handelFormBodyPopular(page:Int = 0,popular: Boolean): RequestBody? = handelFormBody(page,popular)
    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
        fetchDataWithHeaders({
            if (useGetForPopular){
                api.get(popularUrl,defaultHeaders)
            } else{

                api.post(popularUrl, headers = defaultHeaders, body = handelFormBodyPopular(0,true))
            }

        }) { html -> extractMangaList(html) }

    fun handelFormBodyHome(page:Int = 0,popular: Boolean): RequestBody? = handelFormBody(page,popular)

    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)
    fun fetchMangaHome(url : String,page:Int = 0): Flow<State<MutableList<MangaItem>>> =
        fetchDataWithHeaders({
            if (useGetForHome){
                api.get(url, headers = defaultHeaders)
            } else{

                api.post(url, headers = defaultHeaders, body = handelFormBodyHome(page,false))
            }


        }){  html -> extractHomeMangaItems(html)}


    fun searchFormBody(searchType: SearchType): RequestBody? =
        when (searchType) {
            is SearchType.Normal  -> normalSearchFormBody(searchType)
            is SearchType.GENRES  -> genresSearchFormBody(searchType)
            is SearchType.SORT    -> sortFormBody(searchType)
        }

    abstract fun normalSearchFormBody(searchType: SearchType.Normal): RequestBody?
    abstract fun genresSearchFormBody(searchType: SearchType.GENRES): RequestBody?
    abstract fun sortFormBody(searchType: SearchType.SORT): RequestBody?

    override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
        val url = handelSearchUrl(searchType)
        return  if (useGetForNormalSearch) fetchDataWithHeaders({ api.get(url, headers = defaultHeaders) }){  html -> getSearchResults(html)}else{
            fetchDataWithHeaders({
                Log.e("dsgjsdlkgsfgsfsgfgsf", "url : : ${url}")

                Log.e("dsgjsdlkgsfgsfsgfgsf", "body : : ${searchFormBody(searchType)}")
                api.post(url, body = searchFormBody(searchType),headers = defaultHeaders) }){  html ->
                Log.e("dsgjsdlkgsfgsfsgfgsf", "search : : ${html}")

                getSearchResults(html)}
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

    fun handelFormBodyChapter(page:Int = 0,popular: Boolean): RequestBody? = handelFormBody(page,popular)

    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
        fetchDataWithHeaders({
            Log.e("dsgjsdlkgsfgsfsgfgsf", "url : : ${url}")

            if (useGetForChapters){


                api.get(url, headers = defaultHeaders)}
            else{

                api.post(url, headers = defaultHeaders, body = handelFormBodyChapter(0,false))
            }
        }) { html -> getChapterImages(html) }


    fun handelFormBodyMangaInfo(page:Int = 0,popular: Boolean): RequestBody? = handelFormBody(page,popular)





    abstract fun createInfoUrl(mangaId: String) : String
    abstract fun createChaptersUrl(mangaId: String) : String


    override suspend fun fetchMangaChaptersF(mangaId: String): Flow<State<MangaInfo>> {

        val infoUrl = createInfoUrl(mangaId)
        val chaptersUrl = createChaptersUrl(mangaId)



        // 1) Create a Flow<State<MangaInfo>> for the “info” endpoint:
        val infoFlow: Flow<State<MangaInfo?>> = fetchDataWithHeaders({ api.get(infoUrl,defaultHeaders) })  { html ->
            extractMangaInfo(html,infoUrl)
        }

        // 2) Create a Flow<State<List<ChapterItem>>> for the “chapters” endpoint,
        //    and if it errors out, convert that error into a Success(emptyList()).
        val chaptersFlow: Flow<State<List<ChapterItem>>> =
            fetchDataWithHeaders({ if (useGetForInfo) api.get(chaptersUrl,defaultHeaders)else api.post(chaptersUrl, headers = defaultHeaders, body =   handelFormBodyMangaInfo(0,true))}) { html ->
                parseChapters(html)

            }
                // If fetchData(...) for chaptersUrl ever throws an exception internally,
                // catch it here and emit Success(emptyList()) instead.
                .catch { e ->
                    emit(State.Success(emptyList()))
                }
                // If the HTTP call itself succeeded but returned a State.Error, map that Error→Success(emptyList()):
                .map { state ->
                    when (state) {
                        is State.Success -> state
                        is State.Error -> {
                            State.Success(emptyList())
                        }
                        is State.Loading -> State.Loading
                    }
                }


        // 3) Combine both flows so that we can react whenever either one emits Loading/Success/Error.
        //    We immediately emit State.Loading, and then wait until both have emitted at least once.
        return flow {
            emit(State.Loading)

            infoFlow
                .combine(chaptersFlow) { infoState, chapState ->
                    Pair(infoState, chapState)
                }
                .collect { (infoState, chapState) ->
                    // 3a) If the “info” call is still Loading, we stay in Loading.
                    if (infoState is State.Loading || chapState is State.Loading) {
                        emit(State.Loading)
                        return@collect
                    }

                    // 3b) If “info” failed completely (i.e. State.Error), forward that error:
                    if (infoState is State.Error) {
                        emit(State.Error(0,infoState.message))
                        return@collect
                    }

                    // 3c) Otherwise, infoState is State.Success<MangaInfo?>. If the MangaInfo inside is null, treat as error:
                    val mangaInfo: MangaInfo? = (infoState as? State.Success)?.data
                    if (mangaInfo == null) {
                        emit(State.Error(0,"Failed to parse MangaInfo"))
                        return@collect
                    }

                    // 3d) chapState at this point is either Loading (handled above), or State.Success(empty or non‐empty list).
                    val chapterList: List<ChapterItem> = (chapState as? State.Success)?.data.orEmpty()

                    // 3e) Fill the MangaInfo’s .chapters field and emit Success:
                    mangaInfo.chapters.clear()
                    mangaInfo.chapters.addAll(chapterList)
                    emit(State.Success(mangaInfo))
                }
        }
    }







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





    abstract fun parseChapters(html: String):List<ChapterItem>
    abstract fun extractHomeMangaItems(string: String): MutableList<MangaItem>
    abstract fun extractMangaList(string: String): List<PopularManga>
    abstract suspend fun extractMangaInfo(string: String, baseUrl : String): MangaInfo
    abstract suspend fun getSearchResults(string: String): List<MangaItem>
    abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
    abstract fun getChapterImages(string: String): List<String>


}