package me.manga.yamiapk.presentation.features.details.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import javax.inject.Inject

@HiltViewModel
class MangaDerailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourcesRepository: SourcesRepository
) : ViewModel() {

    // StateFlow for manga details
    private val _mangaDetails = MutableStateFlow<State<MangaInfo>>(State.Loading)
    val mangaDetails: StateFlow<State<MangaInfo>> = _mangaDetails.asStateFlow()

    val args = savedStateHandle.toRoute<Screen.MangaDetails>()

    var currentUrl :String = args.mangaUrl
    init {


        fetchMangaChapters(args.mangaUrl,args.api)




    }

    fun fetchMangaChapters(mangaUrl: String,api: String) {
        viewModelScope.launch(Dispatchers.IO) {

            sourcesRepository.getRepoByName(api).fetchMangaChaptersF(mangaUrl)
                .collect { state ->
//
                    _mangaDetails.value = state
                }
        }
    }


    fun onRetry(mangaUrl: String,Api:String){
        fetchMangaChapters(mangaUrl,Api)
    }
    fun isPlus18(gens: List<String>, api: String): Boolean {
        // Fetch the blacklist (assumed to be a Set<String>)
        val blackListSet: Set<String> = sourcesRepository
            .getRepoByName(api)
            .blackListGenres


        // Return true if there is any overlap between gens and blackListSet
        return gens.any { it in blackListSet }
    }

    fun buildImageRequest (context : Context, url :String, api : String): ImageRequest {
        return sourcesRepository.getRepoByName(api).buildImageRequest(context,url,0)
    }

}