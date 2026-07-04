package me.manga.yamiapk.presentation.features.repo_settings.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.storage.PrefsDelegate
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourceState
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import javax.inject.Inject


@HiltViewModel
class RepoSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourcesRepository: SourcesRepository,
    private val ds: DataStoreHelper,

    ) : ViewModel() {
    private val repoList = sourcesRepository.repoTaps.sortedBy { it.PRIORITY }

    private val _enabledStates = sourcesRepository.enabledStates.stateIn(
        viewModelScope,
                started = SharingStarted.Lazily,
        // initialValue: empty until DB emits
        initialValue = emptyMap()
    )
    val enabledStates: StateFlow<Map<String, Boolean>> = _enabledStates

    val newSources = ds.newSourcesFlow.stateIn(
        viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = false
    )
    private val allSourcesFlow = sourcesRepository.allSources

    val enabledRepositoriesFlow = allSourcesFlow
        .map { entities ->
            entities
                .filter { it.isEnabled }
                .mapNotNull { sourcesRepository.getOrRepoByName(it.name) }
        }
        .stateIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),

            started = SharingStarted.Eagerly,
            // initialValue: empty until DB emits
            initialValue = emptyList()
        )

    fun getSiteStateFlow(sourceName: String): Flow<SourceState> =
        sourcesRepository.getSiteStateFlow(sourceName)

    suspend fun getSiteState(sourceName: String): SourceState =
        sourcesRepository.getSiteState(sourceName)




    fun setRepoEnabled(api: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            sourcesRepository.enableDisAbleSource(api, enabled)
        }
    }
    fun setNewSources(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            ds.setNewSources(enabled)
        }
    }
    fun setLanguageEnabled(language: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            sourcesRepository
                .allSources
                .first()                  // one‐shot read of the table
                .filter { it.language == language }
                .forEach {
                    sourcesRepository.enableDisAbleSource(it.name, enabled)
                }
        }
    }

    fun setLanguageEnabledDefault(language: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val allSources = sourcesRepository.allSources.first()
            var languageSources = allSources.filter { it.language == language }

            // If no sources found for the requested language, fallback to English
            if (languageSources.isEmpty()) {
                languageSources = allSources.filter { it.language == "(EN)" }
            }

            languageSources.forEach {
                sourcesRepository.enableDisAbleSource(it.name, enabled)
            }
        }
    }

    // expose grouped repos by LANGUAGE
    fun groupedByLanguage(): Map<String, List<BaseMangaRepository>> =
        repoList.groupBy { it.LANGUAGE }

    /**
     * Returns the list of repositories whose "enabled" flag is true.
     */
    fun getEnabledRepositories(): List<BaseMangaRepository> {
        val currentStates = _enabledStates.value
        return repoList.filter { repo ->
            currentStates[repo.API] == true
        }
    }

}