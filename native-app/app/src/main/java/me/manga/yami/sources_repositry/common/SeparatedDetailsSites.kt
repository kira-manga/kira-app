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
import okhttp3.FormBody

abstract class  SeparatedDetailsSites(
    private val dataStore: DataStoreHelper,
    private val api: IMangaDataApiServices,
    sourcesRepository: SourcesDao,
) : BaseManga(sourcesRepository, ) {
        abstract override val mangaSource: MangaSource
        abstract val homeUrl : String
        abstract val popularUrl : String
        open var homeGet = true
    open var searchGet = true
        var fixedImgUrl = true
        var isChapterGet = true
        abstract fun handelLoadMoreUrl(page: Int) : String

        abstract fun handelSearchUrl(searchType: SearchType) : String
        abstract override val sortTypes: Set<String>
        abstract override val allGenres: Set<String>
        abstract override val blackListGenres: Set<String>
        abstract override val defaultHeaders: Map<String, String>

        override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = fetchMangaHome(homeUrl)

        abstract fun handelFormBody(page:Int = 0,popular: Boolean): FormBody?
        fun fetchMangaHome(url : String,page:Int = 0): Flow<State<MutableList<MangaItem>>> =
            fetchDataWithHeaders({

                Log.i("fslksadfasghfsdgdfgdfgfds",url.toString())
                Log.i("fslksadfasghfsdgdfgdfgfds",defaultHeaders.toString())

                if (homeGet){
                    api.get(url,defaultHeaders)
                } else{

                    api.post(url, body = handelFormBody(page,false))
                }


            }){  html -> extractHomeMangaItems(html)}



        override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> =
            fetchDataWithHeaders({
                Log.i("fslksadfasghfsdgdfgdfgfds1",baseUrl.toString())

                if (homeGet){
                    api.get(popularUrl,defaultHeaders)
                } else{

                    api.post(popularUrl, body = handelFormBody(0,true))
                }

            }) { html -> extractMangaList(html) }



        override suspend fun fetchMangaChaptersF(mangaId: String): Flow<State<MangaInfo>> {
            Log.i("saaksljdlkasdjfasdfdfasdf0",mangaId)

            val infoUrl = createInfoUrl(mangaId)
            val chaptersUrl =   createChaptersUrl(mangaId)

            Log.i("saaksljdlkasdjfasdfdfasdf1","infoUrl ======== $infoUrl   chaptersUrl========$chaptersUrl  ")


            // 1) Create a Flow<State<MangaInfo>> for the “info” endpoint:
            val infoFlow: Flow<State<MangaInfo?>> = fetchDataWithHeaders({ api.get(infoUrl,defaultHeaders) })  { html ->
                extractMangaInfo(html,infoUrl,mangaId)
            }


            // 2) Create a Flow<State<List<ChapterItem>>> for the “chapters” endpoint,
            //    and if it errors out, convert that error into a Success(emptyList()).
            val chaptersFlow: Flow<State<List<ChapterItem>>> =
                fetchDataWithHeaders({ if (isChapterGet) api.get(chaptersUrl,defaultHeaders)else api.post(chaptersUrl, body =   handelFormBody(0,true))}) { html ->
                    Log.i("saaksljdlkasdjfasdfdfasdf2",chaptersUrl)
                    Log.i("saaksljdlkasdjfasdfdfasdf3",html)

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





        abstract fun createInfoUrl(mangaId: String) : String
        abstract fun createChaptersUrl(mangaId: String) : String
        override fun fetchChapterDataF(url: String): Flow<State<List<String>>> =
            fetchDataWithHeaders({
                Log.i("sadasdasdasaxczxcxzcsdfsd0",url)
                val fullUrl = if (url.startsWith("http", ignoreCase = true)) {
                    url
                } else {
                    "${baseUrl.ifBlank { BASE_URL }}$url"
                }
                Log.i("sadasdasdasaxczxcxzcsdfsd1",fullUrl)

                api.get(fullUrl,defaultHeaders)

            }) { html ->
                Log.i("sadasdasdasaxczxcxzcsdfsd2",html)

                getChapterImages(html) }




        override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> =
            flow {
                emit(State.Loading as State<List<MangaItem>>)
                val url = handelLoadMoreUrl(page)
                Log.i("fslksadfasghfsdgdfgdfgfds2",url.toString())

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

    abstract fun handelSearchFormBody(page:Int = 0,searchType: SearchType.Normal): FormBody?



        override suspend fun normalSearch(searchType: SearchType.Normal): Flow<State<List<MangaItem>>> {
            val url = handelSearchUrl(searchType)
            Log.i("dsdgksfjglsdkfgfzxczxczcdgdfgdgsdfg1",url.toString())


            return  fetchDataWithHeaders({if (searchGet) api.get(url,defaultHeaders)  else api.post(url = url,handelSearchFormBody(0,searchType))}){  html -> getSearchResults(html)}
        }

        override suspend fun genresSearch(searchType: SearchType.GENRES): Flow<State<List<MangaItem>>>  {
            val url = handelSearchUrl(searchType)
            return  fetchDataWithHeaders({ api.get(url,defaultHeaders) }){  html -> getSearchResults(html)}
        }

        override suspend fun sortSearch(searchType: SearchType.SORT): Flow<State<List<MangaItem>>> {
            val url = handelSearchUrl(searchType)
            return  fetchDataWithHeaders({ api.get(url,defaultHeaders) }){  html -> getSearchResults(html)}
        }





        abstract fun parseChapters(html: String):List<ChapterItem>
        abstract fun extractHomeMangaItems(html: String): MutableList<MangaItem>
        abstract fun extractMangaList(html: String): List<PopularManga>
        abstract fun extractMangaInfo(html: String, baseUrl : String,combinUrl: String = ""): MangaInfo
        abstract fun getSearchResults(html: String): List<MangaItem>
        abstract override suspend fun refreshHeaders(newHeaders: Map<String, String>)
        abstract fun getChapterImages(html: String): List<String>

    }