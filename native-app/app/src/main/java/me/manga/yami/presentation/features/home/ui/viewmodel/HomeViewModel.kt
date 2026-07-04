package me.manga.yamiapk.presentation.features.home.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.states.State.Error.Companion.fromCode
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toSavedEntities
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toSavedEntity
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.repos.MangaRepository
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

private typealias SearchResultsMap = Map<String, State<List<MangaItem>>>

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mangaRepository: MangaRepository,
    private val settingsRepo: SettingsRepository,
    private val sourcesRepository: SourcesRepository,

    ) : ViewModel() {

    // in your HomeViewModel…

    private val _allSearchResults =
        MutableStateFlow<Map<String, State<List<MangaItem>>>>(emptyMap())
    val allSearchResults: StateFlow<Map<String, State<List<MangaItem>>>> =
        _allSearchResults

    fun fetchAllSearchResults(query: String) {
        // Prepare a MutableMap to accumulate each repo’s latest state
        val resultsMap = mutableMapOf<String, State<List<MangaItem>>>()
        viewModelScope.launch {
            val repos = withContext(Dispatchers.IO) {
                sourcesRepository.getEnabledRepos().onEach { it.getBaseUrl() }
            }.toList()
            // Turn each repo into a flow of Pair(API, State)

            val flows = repos.map { repo ->
                repo.initSite() // <-- suspend fun initSite() in repo
                repo.fetchSearchDataF(SearchType.Normal(query))
                    .flowOn(Dispatchers.IO)
                    .map { state -> repo.API to state }
            }

            // Merge them: this emits whichever repo-update comes in, as soon as it comes in
            merge(*flows.toTypedArray())
                .collect { (api, state) ->
                    // serial updates on the Main dispatcher
                    resultsMap[api] = state
                    // emit an immutable snapshot
                    _allSearchResults.value = resultsMap.toMap()
                }
        }
    }


    fun getSiteStatus(api: String): Flow<State<Boolean>> = flow {

        if (api != "Lekmanga") {
            return@flow
        } else {
            val client = OkHttpClient()

            emit(State.Loading)

            try {
                val repo = sourcesRepository.getRepoByName(api)
                // 1) build headers (including your cached ones + referer)
                val headers = Headers.Builder().apply {
                    repo.defaultHeaders.forEach { (name, value) -> add(name, value) }
                }.build()

                val url = sourcesRepository.getUrl(api)
                // 2) build & execute request
                val request = Request.Builder()
                    .url("${url}manga/")
                    .headers(headers)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        emit(State.Success(true))
                    } else {

                        emit(fromCode(response.code))
                    }
                }
            } catch (e: Exception) {

                emit(State.Error(0, e.localizedMessage ?: "Unknown error"))
            }

        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Companion.Lazily,
            initialValue = State.Loading
        )

    val savedMangaTitles: StateFlow<Set<ApiTitle>> =
        mangaRepository.savedMangaTitles
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Companion.Lazily,
                initialValue = emptySet()
            )
    val hasShownRemoveBookMarkFlow: StateFlow<Boolean> = settingsRepo.hasShownRemoveBookMarkFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, true)

    fun setShownRemoveBookMarkFlow(on: Boolean) =
        viewModelScope.launch { settingsRepo.setShownRemoveBookMark(on) }


    fun isMangaSaved(api: String, title: String): Boolean {
        val key = ApiTitle(api = api, title = title)
        return savedMangaTitles.value.contains(key)
    }

    /**
     * Toggle saving or deleting a manga. If already saved, remove it;
     * otherwise save along with provided chapters.
     */
    fun toggleManga(manga: MangaInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = ApiTitle(api = manga.api, title = manga.title)

            if (savedMangaTitles.value.contains(key)) {
                mangaRepository.removeManga(manga.title)
            } else {

                mangaRepository.save(
                    manga.toSavedEntity(),
                    manga.chapters.toList().toSavedEntities(1).reversed()
                )
            }
        }
    }


}