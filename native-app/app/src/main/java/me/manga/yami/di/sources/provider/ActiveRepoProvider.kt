package me.manga.yamiapk.di.sources.provider

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import javax.inject.Inject
import javax.inject.Singleton


//
@Singleton
class ActiveRepoProvider @Inject constructor(
    // likewise suppress wildcard here
    val repos: @JvmSuppressWildcards Set<BaseMangaRepository>,
    private val prefs: SharedPrefsHelper
) {
    private val repoListFlow: Flow<List<BaseMangaRepository>> = snapshotFlow {
        repos
            .filter { repo -> prefs.getBoolean("repo_enabled_${repo.API}", true) }
            .sortedBy { it.PRIORITY }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private val ActiveRepoList: List<BaseMangaRepository> = repos
        .filter { prefs.getBoolean("repo_enabled_${it.API}", true) }
        .sortedBy { it.PRIORITY }

    private val repoListState: StateFlow<List<BaseMangaRepository>> =
        repoListFlow
            .stateIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                started = SharingStarted.Eagerly,
                initialValue = repos
                    .filter { prefs.getBoolean("repo_enabled_${it.API}", true) }
                    .sortedBy { it.PRIORITY }
            )

    val activeList: List<BaseMangaRepository>
        get() = repoListState.value

    private val _activeIndex = MutableStateFlow(prefs.getInt("active_tab", 0))
    val activeIndexFlow: StateFlow<Int> = _activeIndex

    private val repoList = repos
        .filter { prefs.getBoolean("repo_enabled_${it.API}", true) }
        .sortedBy { it.PRIORITY }


}