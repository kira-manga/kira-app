package me.manga.yamiapk.presentation.common.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.sources_repositry.pt.manhastro.ManhastroDadosStore
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class MangaViewModel @Inject constructor(
    private val sourcesRepository: SourcesRepository,
    private val sharedPrefsHelper: SharedPrefsHelper,
    private val dadosStore: ManhastroDadosStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {






    val currentApiFlow = sourcesRepository.activeRepoFlow.flowOn(Dispatchers.IO)
    private var homeFetchJob: Job? = null
    private var popularFetchJob: Job? = null


    suspend fun getCurrentBaseUrl(): String = withContext(Dispatchers.IO) {
        // first() is suspending, so this all runs off the main thread
        sourcesRepository.activeRepo
            .flowOn(Dispatchers.IO)   // optional: ensure upstream flow also runs on IO
            .first()
            .getBaseUrl()
    }

    val currentBaseUrlFlow: Flow<String> = sourcesRepository.activeRepoFlow
        .map { it.getBaseUrl() }
        .flowOn(Dispatchers.IO)


    suspend fun getCurrentApi(): String = withContext(Dispatchers.IO) {
        // first() is suspending, so this all runs off the main thread
        sourcesRepository.activeRepo
            .flowOn(Dispatchers.IO)   // optional: ensure upstream flow also runs on IO
            .first()
            .API
    }
    /** index LiveData for TabLayout binding */
    val activeTabIndex: LiveData<Int> =
        sourcesRepository.activeIndexFlow
            .asLiveData(context = viewModelScope.coroutineContext)



    // 1) Create a backing MutableStateFlow and initialize it in init { … }
    private val _sortTypesFlow = MutableStateFlow<Set<String>>(emptySet())
    val sortTypesFlow: StateFlow<Set<String>> = _sortTypesFlow
    private val _genresFlow = MutableStateFlow<Set<String>>(emptySet())
    val genresFlow: StateFlow<Set<String>> = _genresFlow
    private val _activeSortType = MutableStateFlow("")
    val activeSortType: StateFlow<String> = _activeSortType

    private val _activeGenres = MutableStateFlow("")
    val activeGenres: StateFlow<String> = _activeGenres

    fun onSortClick(type:String,query: String,genres : String){
        searchQuery.value = query

        viewModelScope.launch(Dispatchers.IO) {
        _activeSortType.emit( type)
        _activeGenres.emit(genres)
        startSearch(SearchType.SORT(query = query, sortType = type,genres))
        }
    }

    fun onGenreClicked(type:String){
        viewModelScope.launch(Dispatchers.IO) {

            _activeGenres.emit(type)
            startSearch(
                SearchType.GENRES(
                    genres = type,
                    query =  ""
                )
            )
        }

            }



    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private var currentPage = 1
    var LoadingNextPage = MutableLiveData(false)
    // UI state
    val mangaItems = MutableLiveData<State<MutableList<MangaItem>>>()

    val popularManga = MutableLiveData<State<List<PopularManga>>>()



    val mangaSearchItems = MutableLiveData<State<List<MangaItem>>>()
    var searchQuery = MutableLiveData<String>()

    private val _isSearching = MutableStateFlow(false)

    val isSearching: MutableStateFlow<Boolean> = _isSearching


    init {
            loadHome()
            getPopularManga()
            refreshSearchSetting()



    }
    fun closeSearch(){
        isSearching.value = !isSearching.value
    }

    private fun startHomeFetch(reset: Boolean = false) {
        // cancel previous
        homeFetchJob?.cancel()

        // optional: reset UI
        if (reset) {
            _isRefreshing.value = true
            mangaItems.postValue(State.Success(mutableListOf()))
        }

        homeFetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // ensure UI shows refresh only while we're preparing
                _isRefreshing.value = false
                currentPage = 1

                val repo = sourcesRepository.activeRepo.first()
                mangaItems.postValue(State.Loading)
                Log.i("sdlkfjsldfjsdfsdfsdfsdfsdfsdf","LOADING1 ${System.currentTimeMillis()}")
                // WAIT until repo initialization completes
                repo.initSite() // <-- suspend fun initSite() in repo
                Log.i("sdlkfjsldfjsdfsdfsdfsdfsdfsdf","LOADING2 ${System.currentTimeMillis()}")

                // now start collecting home flow
                repo.fetchMangaHomeF(repo.getBaseUrl()).collect { state ->
                    mangaItems.postValue(state)
                }
                Log.i("sdlkfjsldfjsdfsdfsdfsdfsdfsdf","LOADING3 ${System.currentTimeMillis()}")

            } catch (e: CancellationException) {
                // job cancelled — ok
                throw e
            } catch (t: Throwable) {
                // propagate error into UI if you want
                mangaItems.postValue(State.Error.fromException(t))
            } finally {
                // make sure refresh flag is cleared if you had set it earlier
                _isRefreshing.value = false
            }
        }
    }

    fun getMangaHome() = startHomeFetch(reset = true)
    fun loadHome()     = startHomeFetch(reset = false)


    fun refreshSearchSetting() {
        viewModelScope.launch(Dispatchers.IO) {
            val repo =sourcesRepository.activeRepo.first()

            _sortTypesFlow.emit(repo.sortTypes)
            _genresFlow.emit(repo.allGenres)
        }
    }

    fun getPopularManga() {
        // 1) cancel any previous popular‐manga request
        popularFetchJob?.cancel()

        // 2) reset UI state
        popularManga.postValue(State.Success(emptyList()))

        // 3) launch and keep the new job
        popularFetchJob = viewModelScope.launch(Dispatchers.IO) {
            val repo = sourcesRepository.activeRepo.first()

            repo.fetchPopularManga(repo.getBaseUrl()).collect { state ->


                popularManga.postValue(state)

            }
        }
    }


    fun getMoreManga(page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (LoadingNextPage.value == true) return@launch
            LoadingNextPage.postValue(true)
            val currentList = mangaItems.value?.toData() ?: mutableListOf()
            val repo =sourcesRepository.activeRepo.first()

            repo.fetchMoreManga(page, currentList).collect { result ->
                when(result) {
                    is State.Success -> {
                        mangaItems.postValue(State.Success(result.toData()?.toMutableList() ?: mutableListOf()))
                        LoadingNextPage.postValue(false)
                    }
                    is State.Error -> {
                        LoadingNextPage.postValue(false)
                    }
                    else -> { }
                }
            }
        }
    }
    fun startSearch(searchType: SearchType) {
        mangaSearchItems.postValue(State.Loading)
        viewModelScope.launch(Dispatchers.IO) {
            val repo =sourcesRepository.activeRepo.first()

            repo.fetchSearchDataF(searchType).collect { state ->
                mangaSearchItems.postValue(state)
            }
        }
    }

    suspend fun onRepoChange(api: String){
        sourcesRepository.updateActiveByApi(api)
    }

     fun onTabSelected(tab: Int) {

         sourcesRepository.updateActiveIndex(tab)
         dadosStore.clear()
        getMangaHome()
         getPopularManga()
         refreshSearchSetting()
    }
    fun changeTheActiveRepo(api:String){

        sourcesRepository.getRepoByName(api)
        getMangaHome()
        getPopularManga()
    }

    fun onLastItemVisible() {
        currentPage++
        getMoreManga(currentPage)
    }
    fun onSearchToggle(){
        _isSearching.value = !isSearching.value
    }


    fun buildImageRequest (context : Context, url :String, api : String): ImageRequest {
        return sourcesRepository.getRepoByName(api).buildImageRequest(context,url,0)
    }




}