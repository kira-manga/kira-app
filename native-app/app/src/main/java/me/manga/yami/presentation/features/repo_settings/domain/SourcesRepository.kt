package me.manga.yamiapk.presentation.features.repo_settings.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.HistoryDao
import me.manga.yamiapk.data.local.dao.MangaDao
import me.manga.yamiapk.data.local.dao.NotificationDao
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.local.entity.SourcesEntity
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.presentation.features.repo_settings.data.Source
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.EmptyMangaRepository
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourcesRepository @Inject constructor(
    private val sourcesDao: SourcesDao,
    val repos: @JvmSuppressWildcards Set<BaseMangaRepository> = emptySet(),
    private val prefs: SharedPrefsHelper,
    private val  applicationScope: CoroutineScope


    ) {

    private val repoMap: Map<String, BaseMangaRepository> by lazy {
        repos.associateBy { it.API }
    }

    init {

        applicationScope.launch {
            saveSources()

        }
    }

    fun getSiteStateFlow(sourceName: String): Flow<SourceState> =
        sourcesDao.getSiteStateByName(sourceName)
            .map {
                Log.e("asljkfalkfjsdfsadfsdfsadfasdf", "calll")


                it ?: SourceState.WORKING } // Default to WORKING if null
            .distinctUntilChanged() // Only emit when value actually changes

            .flowOn(Dispatchers.IO)

    suspend fun getSiteState(sourceName: String): SourceState {
        return try {
            sourcesDao.getSiteStateByNameSync(sourceName) ?: SourceState.WORKING
        } catch (e: Exception) {
            Log.e("SourcesRepository", "Failed to get site state for $sourceName: ${e.message}", e)
            SourceState.WORKING // Default fallback
        }
    }
    val disabledSourcesFlow = sourcesDao.getSourceNamesByState(SourceState.STOPPED)

    suspend fun getUrl(name: String) : String?{
      return  sourcesDao.getBaseUrlFor(name)
    }

    private val _activeIndex = MutableStateFlow(prefs.getInt("active_tab", 0))
    val activeIndexFlow: StateFlow<Int> = _activeIndex

    val allSources: Flow<List<SourcesEntity>> by lazy {  sourcesDao.getAllSources()}
    val enabledStates: Flow<Map<String, Boolean>> =
        allSources
            .map { entities ->
                entities.associate { it.name to it.isEnabled }
            }
            .flowOn(Dispatchers.IO)

    val activeRepo: Flow<BaseMangaRepository> =
        sourcesDao.getAllSources()
            .map { entities ->
                entities
                    .filter { it.isEnabled }
                    .sortedBy { it.priority }
                    // turn each entity into its repo (dropping any unknown names)
                    .mapNotNull { entity ->
                        repoMap[entity.name]
                    }.getOrElse(_activeIndex.value) { EmptyMangaRepository }
            }.flowOn(Dispatchers.IO)

    val allRepos: Flow<List<BaseMangaRepository>> =
        sourcesDao.getAllSources()
            .map { entities ->
                entities
                    // only keep the ones the user has “enabled”
                    .filter { it.isEnabled }
                    // order by the stored priority
                    .sortedBy { it.priority }
                    // turn each entity into its repo (dropping any unknown names)
                    .mapNotNull { entity ->
                        repoMap[entity.name]
                    }
            }.flowOn(Dispatchers.IO)


    val activeRepoFlow: Flow<BaseMangaRepository> =
        allRepos
            .combine(activeIndexFlow) { repos, idx ->
                repos.getOrNull(idx) ?: EmptyMangaRepository
            }
            .flowOn(Dispatchers.IO)

    suspend fun getActiveRepo(): BaseMangaRepository {
        return try {
            val repos = allRepos.first()
            val safeIndex = _activeIndex.value.coerceIn(0, maxOf(0, repos.size - 1))
            repos.getOrNull(safeIndex) ?: EmptyMangaRepository
        } catch (e: Exception) {
            Log.e("SourcesRepository", "Failed to get active repo: ${e.message}", e)
            EmptyMangaRepository
        }
    }
    fun getRepoByName(name: String): BaseMangaRepository {
        Log.i("asfjsdhgjkfhjdgdfgdsfgdfgd",name)
        return repoMap.getOrElse(name) { EmptyMangaRepository }
    }
    fun getOrRepoByName(name: String): BaseMangaRepository? {
        Log.i("asfjsdhgjkfhjdgdfgdsfgdfgd1",name)
        return repoMap.getOrElse(name) { null }
    }

    val repoTaps: List<BaseMangaRepository>
        get() = repos.sortedBy { it.PRIORITY }


    fun updateActiveIndex(idx: Int) {
        val safe = idx.coerceIn(0, repos.size)
        prefs.putInt("active_tab", safe)
        _activeIndex.value = safe
    }

    suspend fun updateActiveByApi(apiName: String) {
        // 1) Get the list of enabled repos, in priority order
        val enabled = getEnabledRepos()

        // 2) Find the index of the one matching our API name
        val idx = enabled.indexOfFirst { it.API == apiName }
            .takeIf { it >= 0 }
            ?: 0            // default to 0 if not found

        // 3) Reuse your existing logic to clamp, save prefs, emit
        updateActiveIndex(idx)
    }

    suspend fun enableDisAbleSource(name: String, enabled: Boolean) {
        try {
            val result = sourcesDao.setEnabledByName(name, enabled)
            if (result == 0) {
                Log.w("SourcesRepository", "No source found with name: $name")
            }
        } catch (e: Exception) {
            Log.e("SourcesRepository", "Failed to enable/disable source $name: ${e.message}", e)
        }
    }

    suspend fun getEnabledRepos(): List<BaseMangaRepository> {
        return try {
            sourcesDao.getAllSources()
                .first()
                .filter { it.isEnabled }
                .sortedBy { it.priority }
                .mapNotNull { repoMap[it.name] }
        } catch (e: Exception) {
            Log.e("SourcesRepository", "Failed to get enabled repos: ${e.message}", e)
            emptyList()
        }
    }
    private suspend fun saveSources() {
        try {
            if (repoMap.isEmpty()) {
                Log.w("SourcesRepository", "No repositories to save")
                return
            }

            repoMap.values.forEach { repo ->
                try {
                    sourcesDao.insert(
                        SourcesEntity(
                            name = repo.API,
                            priority = repo.PRIORITY,
                            isEnabled = false,
                            language = repo.LANGUAGE,
                            baseUrl = repo.BASE_URL,
                            baseVersion = repo.URL_VERSION,
                            imageBaseUrl = repo.imgBaseUrl,
                            imageUrlVersion = repo.imgUrlVersion,
                        )
                    )
                } catch (e: Exception) {
                    Log.e("SourcesRepository", "Failed to insert source ${repo.API}: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("SourcesRepository", "Failed to save sources: ${e.message}", e)
        }
    }





}